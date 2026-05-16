package io.twostars.sdk.internal

import io.twostars.sdk.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the set of `RTCPeerConnection`s the local participant has open
 * to remote peers in P2P mode (one PC per remote).
 *
 * Responsibilities:
 *
 *   1. Build a PC per remote on demand. Wire its `Observer` callbacks
 *      so:
 *        - local ICE candidates flow back over the socket
 *        - inbound tracks fan out via [TrackArrivedCallback] to update
 *          the relevant [io.twostars.sdk.Peer]'s state flows
 *   2. Coordinate the offer/answer dance:
 *        - [offerTo] — we initiate (newcomer pattern; we're the new
 *          one and offer to existing peers)
 *        - [handleOffer] — we received an offer; build PC if needed,
 *          attach our local tracks, answer
 *        - [handleAnswer] — apply the remote SDP to a PC we created
 *        - [handleIceCandidate] — feed remote ICE in
 *   3. Lifecycle: [closePeer] for a single departure, [closeAll] when
 *      the room tears down or the SFU upgrade kicks in (A3).
 *
 * Thread-safety: [pcs] is a ConcurrentHashMap. WebRTC observer
 * callbacks fire on WebRTC's signalling thread; we marshal long-running
 * work onto [scope] but observe the flows from there.
 */
internal class PeerConnectionPool(
    private val factory: WebRTCFactory,
    private val transport: SocketTransport,
    private val scope: CoroutineScope,
    private val onTrack: TrackArrivedCallback,
    private val onTracksPruned: TracksPrunedCallback = TracksPrunedCallback { _, _ -> },
) {

    /**
     * Invoked whenever a new remote track arrives on any PC.
     */
    fun interface TrackArrivedCallback {
        fun invoke(remoteId: String, kind: TrackKind, track: MediaStreamTrack)
    }

    /**
     * Invoked after an inbound renegotiation finishes, with the set of
     * receiver-track ids that are STILL receiving on this PC. Anything
     * a [io.twostars.sdk.Peer] still references that's NOT in the set
     * was removed by the remote (typically a peer stopping its P2P
     * screen share or unpublishing its camera) and should be cleared
     * to drive the receiver-side UI cleanup.
     */
    fun interface TracksPrunedCallback {
        fun invoke(remoteId: String, liveReceiverTrackIds: Set<String>)
    }

    private val pcs = ConcurrentHashMap<String, PeerConnection>()
    @Volatile private var iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE_SERVERS
    @Volatile private var localTracks: List<MediaStreamTrack> = emptyList()

    /**
     * `streamId → mediaType` mapping fed by `p2p-mediatype-info` socket
     * events. P2P doesn't have a structured appData channel like SFU
     * does, so the sender announces "the next track on this stream is
     * a screen, not a camera" via an out-of-band socket hint sent
     * right before `pc.addTrack(track, [streamId])`.
     *
     * Read in [makeObserver]'s `onAddTrack` to route screen tracks
     * onto [Peer.screenTrack] instead of [Peer.videoTrack]. Without
     * this, screen-share would clobber the peer's camera tile.
     */
    private val streamMediaTypes = ConcurrentHashMap<String, String>()

    fun setStreamMediaType(streamId: String, mediaType: String) {
        streamMediaTypes[streamId] = mediaType
    }

    /** Replace the ICE server set (call once after `authenticated`). */
    fun setIceServers(servers: List<PeerConnection.IceServer>) {
        iceServers = if (servers.isEmpty()) DEFAULT_ICE_SERVERS else servers
    }

    /**
     * Hand the pool the local audio + video tracks. Subsequent PC
     * builds (whether we initiate or receive an offer) attach these
     * automatically. Existing PCs get the new track via [addLocalTrackToAll].
     */
    fun setLocalTracks(tracks: List<MediaStreamTrack>) {
        localTracks = tracks.toList()
    }

    /**
     * Push every track in [localTracks] onto every existing PC's sender
     * list, then renegotiate. Called after a late `room.publish()` when
     * peer connections were already open (e.g. the existing peer who
     * received a join from a no-tracks newcomer).
     */
    suspend fun addLocalTrackToAll() {
        for ((remoteId, pc) in pcs) {
            val existingSenderTracks = pc.senders.mapNotNull { it.track() }.toSet()
            var changed = false
            for (track in localTracks) {
                if (track !in existingSenderTracks) {
                    pc.addTrack(track, listOf(STREAM_LABEL))
                    changed = true
                }
            }
            if (changed) renegotiate(remoteId, pc)
        }
    }

    suspend fun offerTo(remoteId: String) {
        val pc = pcs[remoteId] ?: buildPc(remoteId)
        renegotiate(remoteId, pc)
    }

    /**
     * Add a single screen-share track to every PC's senders + renegotiate.
     * Returns the unique streamId we attached it under; the caller must
     * have *already* announced this same streamId via the
     * `p2p-mediatype-info` socket event so the receiver routes the
     * inbound track to its `Peer.screenTrack` flow rather than clobbering
     * `videoTrack`. See [io.twostars.sdk.Room.startScreenShare]'s P2P
     * branch for the full sequence.
     */
    suspend fun addScreenTrackAndRenegotiate(track: VideoTrack, streamId: String) {
        for ((remoteId, pc) in pcs) {
            pc.addTrack(track, listOf(streamId))
            renegotiate(remoteId, pc)
        }
    }

    /**
     * Remove a single sender (by underlying track reference) from every
     * PC + renegotiate. Used by [io.twostars.sdk.Room.stopScreenShare]
     * in P2P mode so the screen track goes away while the camera/mic
     * senders stay attached.
     */
    suspend fun removeTrackAndRenegotiate(track: MediaStreamTrack) {
        val targetId = track.id()
        for ((remoteId, pc) in pcs) {
            var changed = false
            // Compare by track id rather than reference — libwebrtc-android
            // returns a fresh wrapper from transceiver.sender on every call,
            // so === would never match even when the sender was originally
            // created from this exact track.
            for (transceiver in pc.transceivers) {
                val sender = transceiver.sender
                val senderTrackId = sender.track()?.id()
                if (senderTrackId == targetId) {
                    runCatching { pc.removeTrack(sender) }
                    flipDirectionAfterRemoveTrack(transceiver)
                    changed = true
                }
            }
            if (changed) renegotiate(remoteId, pc)
        }
    }

    /**
     * libwebrtc-android's `pc.removeTrack(sender)` differs from the W3C
     * spec implemented by Chrome: it nulls the sender's track but does
     * NOT automatically flip the transceiver's direction. Without the
     * flip, the next createOffer still emits `a=sendonly` for that
     * m-line, the remote receiver's `currentDirection` stays at
     * `recvonly`, and the prune-on-renegotiation logic on the receiver
     * leaves the now-dead track in `peer.screenStream` / `peer.stream`
     * — the bound `<video>` keeps painting its last frame.
     *
     * Mirror Chrome's spec behaviour here so cross-platform receivers
     * see the m-line transition the way they do for a Chrome sender.
     */
    private fun flipDirectionAfterRemoveTrack(transceiver: RtpTransceiver) {
        val current = transceiver.currentDirection ?: transceiver.direction
        val next = when (current) {
            RtpTransceiver.RtpTransceiverDirection.SEND_RECV ->
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY ->
                RtpTransceiver.RtpTransceiverDirection.INACTIVE
            else -> return // already recvonly/inactive; nothing to flip
        }
        runCatching { transceiver.setDirection(next) }
    }

    /**
     * Strip every local sender from every PC and renegotiate. Used by
     * [io.twostars.sdk.Room.unpublish] in P2P mode. Renegotiating with
     * removed senders fires `removetrack` on the *remote* side's
     * `peer.stream` automatically, which lets their UI clear the video
     * tile cleanly. Slamming the PC closed (the previous behaviour)
     * skipped that signal — the remote tile would freeze on the last
     * frame because the dead track stayed bound to the `<video>` srcObject.
     *
     * Doesn't tear down the PC itself — keeping it alive lets a
     * subsequent [io.twostars.sdk.Room.publish] reuse the same channel
     * (and the existing ICE pair) instead of paying a fresh connect cost.
     */
    suspend fun removeLocalTracksAndRenegotiate() {
        for ((remoteId, pc) in pcs) {
            var changed = false
            for (transceiver in pc.transceivers) {
                val sender = transceiver.sender
                if (sender.track() != null) {
                    runCatching { pc.removeTrack(sender) }
                    flipDirectionAfterRemoveTrack(transceiver)
                    changed = true
                }
            }
            if (changed) renegotiate(remoteId, pc)
        }
    }

    suspend fun handleOffer(from: String, sdp: SessionDescription) {
        val pc = pcs[from] ?: buildPc(from)
        // Glare protection. If we already sent our own offer to this
        // peer (HAVE_LOCAL_OFFER) and now they've also offered, calling
        // setRemoteDescription with an OFFER would crash WebRTC with
        // "Called in wrong state: have-local-offer". Drop the inbound
        // offer — our outbound offer is in flight and the remote will
        // answer it. (Standard impolite-peer behaviour; works as long
        // as one side is consistent about who ignores.)
        if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        setRemoteDescriptionSuspending(pc, sdp)
        val answer = createAnswerSuspending(pc)
        setLocalDescriptionSuspending(pc, answer)
        transport.emit("answer", buildJsonObject {
            put("to", JsonPrimitive(from))
            put("sdp", buildJsonObject {
                put("type", JsonPrimitive(answer.type.canonicalForm()))
                put("sdp", JsonPrimitive(answer.description))
            })
        })
        // Inbound renegotiation may have removed senders on the remote
        // side (e.g. browser stopping a P2P screen share). WebRTC
        // doesn't auto-clear receiver tracks on renegotiation — they
        // stay around permanently muted, which leaves Peer.screenTrack
        // / Peer.videoTrack pointing at a dead track and any bound
        // SurfaceViewRenderer painting its last frame. Reconcile from
        // the post-renegotiation transceiver directions.
        pruneRemovedReceiverTracks(from, pc)
    }

    private fun pruneRemovedReceiverTracks(remoteId: String, pc: PeerConnection) {
        val liveIds = HashSet<String>()
        for (transceiver in pc.transceivers) {
            val dir = transceiver.currentDirection ?: transceiver.direction
            if (dir == RtpTransceiver.RtpTransceiverDirection.SEND_RECV ||
                dir == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY) {
                transceiver.receiver?.track()?.id()?.let { liveIds.add(it) }
            }
        }
        onTracksPruned.invoke(remoteId, liveIds)
    }

    suspend fun handleAnswer(from: String, sdp: SessionDescription) {
        val pc = pcs[from] ?: return
        setRemoteDescriptionSuspending(pc, sdp)
    }

    fun handleIceCandidate(from: String, ice: IceCandidate) {
        val pc = pcs[from] ?: return
        pc.addIceCandidate(ice)
    }

    fun closePeer(remoteId: String) {
        pcs.remove(remoteId)?.let {
            runCatching { it.close() }
            runCatching { it.dispose() }
        }
    }

    fun closeAll() {
        for ((_, pc) in pcs) {
            runCatching { pc.close() }
            runCatching { pc.dispose() }
        }
        pcs.clear()
    }

    // --- internals -----------------------------------------------------------

    private fun buildPc(remoteId: String): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Pre-enable TCP/UDP host candidates; turn relays light up via the
            // iceServers list once authed brings them in.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val pc = factory.factory.createPeerConnection(
            rtcConfig,
            buildObserver(remoteId),
        ) ?: throw IllegalStateException("createPeerConnection returned null")
        // Attach whatever local tracks we already have; if publish() runs
        // later, addLocalTrackToAll() catches up.
        for (track in localTracks) {
            pc.addTrack(track, listOf(STREAM_LABEL))
        }
        pcs[remoteId] = pc
        return pc
    }

    private fun buildObserver(remoteId: String): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    transport.emit("ice-candidate", buildJsonObject {
                        put("to", JsonPrimitive(remoteId))
                        put("candidate", buildJsonObject {
                            put("candidate", JsonPrimitive(candidate.sdp))
                            put("sdpMid",     JsonPrimitive(candidate.sdpMid))
                            put("sdpMLineIndex", JsonPrimitive(candidate.sdpMLineIndex))
                        })
                    })
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver?.track() ?: return
                val kind = when (track) {
                    is VideoTrack -> TrackKind.VIDEO
                    is AudioTrack -> TrackKind.AUDIO
                    else -> return
                }
                onTrack.invoke(remoteId, kind, track)
            }
            // Older WebRTC paths still fire onAddTrack — forward the same way.
            // Use this path (not onTrack above) when we need stream-id
            // routing because mediaStreams is reliably populated here in
            // both unified-plan and plan-b. Look up the streamMediaTypes
            // hint to decide whether the inbound track is a screen share.
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track() ?: return
                val streamId = mediaStreams.firstOrNull()?.id
                val mediaType = streamId?.let { streamMediaTypes[it] }
                val kind = when {
                    mediaType == "screen" && track is VideoTrack -> TrackKind.SCREEN
                    track is VideoTrack -> TrackKind.VIDEO
                    track is AudioTrack -> TrackKind.AUDIO
                    else -> return
                }
                onTrack.invoke(remoteId, kind, track)
            }
            // The rest are required by the interface but we don't use them
            // in A2. State changes get logged at TRACE level only — wiring
            // them into a public RoomEvent waits for the connection-state
            // hook (mirrors React SDK's useConnectionState).
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
        }

    private suspend fun renegotiate(remoteId: String, pc: PeerConnection) {
        val offer = createOfferSuspending(pc)
        setLocalDescriptionSuspending(pc, offer)
        transport.emit("offer", buildJsonObject {
            put("to", JsonPrimitive(remoteId))
            put("sdp", buildJsonObject {
                put("type", JsonPrimitive(offer.type.canonicalForm()))
                put("sdp", JsonPrimitive(offer.description))
            })
        })
    }

    // ---- SDP suspending helpers ---------------------------------------------
    // The Java WebRTC API uses old-style SdpObserver callbacks that fire on
    // an internal thread. Wrap each in a coroutine so the orchestration above
    // reads top-down.

    private suspend fun createOfferSuspending(pc: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { cont ->
            pc.createOffer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (cont.isActive) cont.resume(sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {
                    if (cont.isActive) cont.resumeWithException(SignalingException("createOffer: $err"))
                }
                override fun onSetFailure(err: String?) {}
            }, MediaConstraints())
        }

    private suspend fun createAnswerSuspending(pc: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { cont ->
            pc.createAnswer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (cont.isActive) cont.resume(sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {
                    if (cont.isActive) cont.resumeWithException(SignalingException("createAnswer: $err"))
                }
                override fun onSetFailure(err: String?) {}
            }, MediaConstraints())
        }

    private suspend fun setLocalDescriptionSuspending(pc: PeerConnection, sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setLocalDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onCreateFailure(err: String?) {}
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onSetFailure(err: String?) {
                    if (cont.isActive) cont.resumeWithException(SignalingException("setLocalDescription: $err"))
                }
            }, sdp)
        }

    private suspend fun setRemoteDescriptionSuspending(pc: PeerConnection, sdp: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            pc.setRemoteDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onCreateFailure(err: String?) {}
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onSetFailure(err: String?) {
                    if (cont.isActive) cont.resumeWithException(SignalingException("setRemoteDescription: $err"))
                }
            }, sdp)
        }

    companion object {
        // Stream label used when adding our local tracks to a PC. Matches
        // the JS SDK's per-peer stream pattern so cross-platform sender
        // behaviour stays predictable.
        private const val STREAM_LABEL = "twostars-local"

        private val DEFAULT_ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
    }
}

/** Thrown by the suspending SDP wrappers when WebRTC reports failure. */
public class SignalingException(message: String) : Exception(message)

/**
 * Helpers for translating between the over-the-wire `{type, sdp}`
 * shape and WebRTC's [SessionDescription] / [IceCandidate] objects.
 * Kept here rather than in Room.kt so the call sites read cleanly.
 */
internal object SignalingCodec {
    fun parseSdp(obj: JsonObject?): SessionDescription? {
        if (obj == null) return null
        val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val sdp = obj["sdp"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = when (typeStr.lowercase()) {
            "offer"    -> SessionDescription.Type.OFFER
            "answer"   -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            "rollback" -> SessionDescription.Type.ROLLBACK
            else -> return null
        }
        return SessionDescription(type, sdp)
    }

    fun parseIce(obj: JsonObject?): IceCandidate? {
        if (obj == null) return null
        val candidate = obj["candidate"]?.jsonPrimitive?.contentOrNull ?: return null
        val sdpMid = obj["sdpMid"]?.jsonPrimitive?.contentOrNull
        val mLineIndex = obj["sdpMLineIndex"]?.jsonPrimitive?.let {
            runCatching { it.content.toInt() }.getOrNull()
        } ?: 0
        return IceCandidate(sdpMid ?: "0", mLineIndex, candidate)
    }
}
