package io.twostars.sdk.internal

import io.twostars.sdk.TrackKind
import org.webrtc.MediaStreamTrack

/**
 * Per-participant FIFO of remote tracks that arrived via
 * `Room.onRemoteTrack` before the matching `peer-joined` event had
 * registered the [io.twostars.sdk.Peer] in `_peers`.
 *
 * Why this exists: socket.io's per-receiver fanout does not guarantee
 * `peer-joined` lands before `new-producer` for the same participantId,
 * and WebRTC's `onTrack` fires once per remote track. Dropping the
 * track ref on a peer-not-found used to leave the racing peer's tile
 * permanently empty until they republished (camera toggle, reconnect).
 *
 * Concurrency: thread-safe. `onRemoteTrack` can be invoked from
 * libwebrtc's native callback thread; the drain runs on whichever
 * thread dispatches the `peer-joined` event. Internal state is guarded
 * by a private intrinsic-lock object.
 *
 * Bounded: per-peer cap protects against memory growth when a
 * participantId never resolves (e.g. a server-side bug, or a peer
 * that joins and immediately drops without re-emitting `peer-joined`
 * for the same id). Under normal traffic each peer queues at most
 * three entries — audio, camera-video, screen-video.
 */
internal class PendingTrackQueue(
    private val maxPerPeer: Int = DEFAULT_MAX_PER_PEER,
    private val onOverflow: (participantId: String, kind: TrackKind) -> Unit = { _, _ -> },
) {
    /** One queued track ref awaiting a [io.twostars.sdk.Peer] binding. */
    internal data class PendingTrack(val kind: TrackKind, val track: MediaStreamTrack)

    private val queue: MutableMap<String, MutableList<PendingTrack>> = HashMap()
    private val lock = Any()

    /**
     * Add [track] to the queue for [participantId].
     *
     * @return true when the track was queued; false when the per-peer cap
     *   was already reached, in which case [onOverflow] was invoked and
     *   the caller MUST treat the track as dropped.
     */
    fun enqueue(participantId: String, kind: TrackKind, track: MediaStreamTrack): Boolean {
        synchronized(lock) {
            val list = queue.getOrPut(participantId) { mutableListOf() }
            if (list.size >= maxPerPeer) {
                // Notify outside the synchronized block to keep the
                // critical section tight, but the callback is rare
                // enough (overflow only) that we accept the lock
                // duration here.
                onOverflow(participantId, kind)
                return false
            }
            list.add(PendingTrack(kind, track))
            return true
        }
    }

    /**
     * Remove and return every queued track for [participantId], in
     * arrival order. Returns an empty list if nothing is queued.
     *
     * Idempotent — a second call for the same participantId returns
     * empty unless [enqueue] was called in between.
     */
    fun drain(participantId: String): List<PendingTrack> {
        synchronized(lock) {
            return queue.remove(participantId).orEmpty()
        }
    }

    /**
     * Drop any queued tracks for [participantId] without binding them.
     * Used when a peer leaves before their `peer-joined` race could be
     * resolved — the tracks would never be bindable anyway, and the
     * queue entry shouldn't leak across the peer's lifecycle.
     */
    fun forget(participantId: String) {
        synchronized(lock) { queue.remove(participantId) }
    }

    /**
     * Drop every queued track for every participant. Called from
     * `Room.leave` so MediaStreamTrack refs are released alongside
     * everything else on session teardown.
     */
    fun clear() {
        synchronized(lock) { queue.clear() }
    }

    /**
     * Number of tracks currently queued across all participants.
     * Exposed for tests + diagnostic logging only.
     */
    internal fun size(): Int {
        synchronized(lock) { return queue.values.sumOf { it.size } }
    }

    private companion object {
        // Soft cap chosen for the same reason as the in-line value it
        // replaced: under normal traffic each peer needs at most three
        // entries (audio, camera-video, screen-video). Anything beyond
        // ~10 indicates a server-side misbehavior or a participantId
        // that will never resolve — fire the overflow callback so the
        // condition is visible in logs, and drop.
        const val DEFAULT_MAX_PER_PEER = 10
    }
}
