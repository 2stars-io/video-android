package io.twostars.sdk

import io.twostars.sdk.internal.ScreenCapture
import org.webrtc.VideoTrack

/**
 * Caller-facing handle to an active screen-share. Returned by
 * [Room.startScreenShare] and exposed on [Room.screenShare].
 *
 * Use [videoTrack] to render a self-preview of what's being shared.
 * Call [Room.stopScreenShare] (or this handle's [stop]) to end the
 * share — that closes the producer, broadcasts `producer-closed` to
 * peers, and releases the underlying `MediaProjection`.
 */
public class ScreenShareSession internal constructor(
    private val capture: ScreenCapture,
    /**
     * Unique MediaStream id this share publishes under. Used by the
     * P2P path to coordinate the receiver-side
     * `p2p-mediatype-info` → `Peer.screenTrack` routing. Receivers
     * never see this directly — it's only meaningful inside the
     * sender's PeerConnection state machine.
     */
    internal val streamId: String,
) {
    /** Self-view track of the screen content we're sharing. */
    public val videoTrack: VideoTrack get() = capture.track

    internal fun stop() = capture.stop()
}
