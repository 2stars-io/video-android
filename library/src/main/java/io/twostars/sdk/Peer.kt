package io.twostars.sdk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

/**
 * A remote participant in the room.
 *
 * Identity ([participantId], [displayName], [joinedAtMs]) is set at
 * construction. The track flows ([videoTrack], [audioTrack],
 * [screenTrack]) start `null` and update reactively as the underlying
 * `RTCPeerConnection.onTrack` fires — a UI consumer can collect
 * [videoTrack] in a coroutine and `addSink` / `removeSink` to a
 * `SurfaceViewRenderer` as the value transitions.
 *
 * `screenTrack` stays null until A5 lands.
 */
public class Peer internal constructor(
    public val participantId: String,
    public val displayName: String?,
    /** When this peer's join was first observed, in ms since epoch. */
    public val joinedAtMs: Long,
) {

    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    /** Remote camera track from this peer. `null` while not yet received. */
    public val videoTrack: StateFlow<VideoTrack?> = _videoTrack.asStateFlow()

    private val _audioTrack = MutableStateFlow<AudioTrack?>(null)
    /** Remote mic track from this peer. `null` while not yet received. */
    public val audioTrack: StateFlow<AudioTrack?> = _audioTrack.asStateFlow()

    private val _screenTrack = MutableStateFlow<VideoTrack?>(null)
    /** Remote screen-share track. Stays `null` until A5. */
    public val screenTrack: StateFlow<VideoTrack?> = _screenTrack.asStateFlow()

    private val _videoEnabled = MutableStateFlow(true)
    /**
     * Whether this peer currently has their camera enabled, as reported
     * by the peer itself via `participant-state` signaling. Defaults to
     * `true` so consumers don't flash an avatar placeholder on first
     * render before the first state broadcast lands.
     *
     * Why this exists separately from [videoTrack]: when a peer toggles
     * their camera off via `track.enabled = false`, the underlying RTP
     * stream keeps producing black frames — the receive-side track stays
     * `live` and `unmuted`, so there's no observable WebRTC event that
     * tells us "the publisher chose to turn off their camera." This
     * flag is the platform's out-of-band signal so UIs can swap to an
     * avatar placeholder instead of rendering a black frame.
     */
    public val videoEnabled: StateFlow<Boolean> = _videoEnabled.asStateFlow()

    private val _audioEnabled = MutableStateFlow(true)
    /** Whether this peer currently has their microphone enabled. */
    public val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    internal fun setVideoTrack(track: VideoTrack?) {
        _videoTrack.value = track
    }

    internal fun setAudioTrack(track: AudioTrack?) {
        _audioTrack.value = track
    }

    internal fun setScreenTrack(track: VideoTrack?) {
        _screenTrack.value = track
    }

    internal fun setVideoEnabled(enabled: Boolean) {
        _videoEnabled.value = enabled
    }

    internal fun setAudioEnabled(enabled: Boolean) {
        _audioEnabled.value = enabled
    }

    /**
     * Drop all track refs (peer left, or PC closed). UI consumers that
     * hold a `SurfaceViewRenderer` should observe the flows transition
     * to null and call `removeSink` themselves.
     *
     * Also resets the camera/mic enabled flags back to their defaults
     * (`true`). Symmetry with the track flows: when the Peer is being
     * recycled or torn down, every reactive surface goes back to its
     * "fresh" state, so a UI that observes both `videoTrack` and
     * `videoEnabled` doesn't see stale state from a prior session.
     */
    internal fun clearTracks() {
        _videoTrack.value = null
        _audioTrack.value = null
        _screenTrack.value = null
        _videoEnabled.value = true
        _audioEnabled.value = true
    }

    override fun equals(other: Any?): Boolean =
        other is Peer && other.participantId == participantId

    override fun hashCode(): Int = participantId.hashCode()

    override fun toString(): String =
        "Peer($participantId, displayName=$displayName)"
}
