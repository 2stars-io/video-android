package io.twostars.sdk

import org.webrtc.MediaStreamTrack

/**
 * Lifecycle events emitted on [Room.events]. Use the [Room.peers] /
 * [Room.mode] state flows for current state; use this stream when the
 * *transition* itself matters (e.g. "play a sound on peer-joined",
 * "log a metric when the SFU upgrade fires").
 */
public sealed class RoomEvent {
    public data class PeerJoined(val peer: Peer) : RoomEvent()
    public data class PeerLeft(val participantId: String) : RoomEvent()
    public data class ModeChanged(val mode: Room.Mode) : RoomEvent()
    public data class Disconnected(val reason: String?) : RoomEvent()

    /**
     * A new track from [peer] just landed. Fires once per (peer,kind)
     * pair after WebRTC's `onTrack` callback. Useful for animating a
     * tile's transition from "still loading" → "video live".
     *
     * The same information is exposed reactively on
     * [Peer.videoTrack] / [Peer.audioTrack] — pick the API that fits
     * the consumer.
     */
    public data class PeerTrack(
        val peer: Peer,
        val kind: TrackKind,
        val track: MediaStreamTrack,
    ) : RoomEvent()

    /**
     * The local screen share has ended. Always fires once per
     * successful [Room.startScreenShare] — including when the user
     * called [Room.stopScreenShare] themselves. Use [reason] to tell
     * the deliberate case apart from a system-driven termination, so
     * the UI can (for instance) re-prompt for consent on
     * [ScreenShareEndReason.SYSTEM_STOPPED] or surface an error toast
     * on [ScreenShareEndReason.CAPTURE_FAILED].
     *
     * After this event, [Room.screenShare] is back to `null` and a
     * fresh [Room.startScreenShare] is required to begin sharing again.
     */
    public data class ScreenShareEnded(val reason: ScreenShareEndReason) : RoomEvent()

    /**
     * A peer's camera or microphone enabled flag changed. Read the
     * current values on [Peer.videoEnabled] / [Peer.audioEnabled].
     * Fires on every server-broadcast `participant-state-changed`,
     * including the first one a peer sends after joining (so apps
     * receive a confirmation even when both sides defaulted to `true`).
     *
     * The same data is reactively available on the [Peer]'s state
     * flows; this event is for transition-driven work (animate a tile
     * fade, log analytics) where the *fact of the change* matters.
     */
    public data class PeerStateChanged(val peer: Peer) : RoomEvent()

    /**
     * E4.1 — server told us a recording or broadcast just started or
     * ended on this E2E room. The SDK has already (un)flipped SFrame
     * + republished local producers transparently; this event is for
     * the consumer app to render a disclosure banner ("Recording —
     * E2E paused"). [enabled] is the NEW state of E2E media encryption
     * (false while a recording / broadcast is active, true otherwise).
     */
    public data class E2eRecordingMode(
        val enabled: Boolean,
        val reason: String?,
        val jobId: String?,
        val kind: String?,    // "recording" | "broadcast"
    ) : RoomEvent()

    /**
     * E6 — a moderator removed this participant from the room. Server
     * disconnects the socket ~150ms after this fires; consumer apps
     * should use the brief window to render a toast with [reason]
     * before the connection-lost UI takes over.
     */
    public data class Kicked(val reason: String?, val by: String?) : RoomEvent()

    /**
     * E6 — a moderator forced this participant's mic / camera off.
     * The SDK has already flipped track.enabled=false on the local
     * stream so the camera light goes off; this event lets the UI
     * render "muted by host" disclosure instead of looking like the
     * user did it themselves.
     */
    public data class ForceMuted(val kind: String, val by: String?) : RoomEvent()

    /**
     * E6 — this participant was banned. Same shape as [Kicked] plus
     * [bannedUntil] (ISO-8601 string, null = permanent until manual
     * unban). Server disconnects the socket shortly after.
     */
    public data class Banned(
        val reason: String?,
        val bannedUntil: String?,
        val by: String?,
    ) : RoomEvent()

    /**
     * E6 — room locked / unlocked by a moderator. [locked] is the new
     * state. UI should render a banner; new joiners get refused server-
     * side until unlocked.
     */
    public data class RoomLocked(val locked: Boolean, val by: String?) : RoomEvent()

    /**
     * E6 — generic moderation audit event. Fires for every moderation
     * action visible to the room (kick, mute, ban, lock). Useful for
     * showing "{by} kicked {target}" toasts on bystanders' screens.
     * [extras] carries per-kind details (e.g. muteKind, bannedUntil).
     */
    public data class ModerationEvent(
        val kind: String,
        val by: String?,
        val targetParticipantId: String?,
        val extras: Map<String, Any?> = emptyMap(),
    ) : RoomEvent()

    /**
     * E7 — process visibility / lifecycle transition. Fires when the
     * app moves to background ([visible] = false) or foreground
     * ([visible] = true). Consumer apps use this to render a
     * "Reconnecting..." UI and to pause non-essential work.
     */
    public data class VisibilityChanged(val visible: Boolean) : RoomEvent()

    // --- parity with JS SDK (v0.4.4) -------------------------------------

    /**
     * Connection state transition. Mirrors `room.on('connection-state')`
     * on the JS SDK. The same value is observable on [Room.connectionState];
     * this event fires on each transition for apps that want to drive
     * disclosure UI (banner, toast, retry button) off the change itself.
     */
    public data class ConnectionStateChanged(val state: ConnectionState) : RoomEvent()

    /**
     * A *local* camera or mic track ended unexpectedly (sensor revoked,
     * USB camera unplugged, OS rotated the camera to another app, etc.).
     *
     * Fires once per (kind) when the track transitions to ENDED / stops
     * producing frames. The SDK will attempt automatic recovery; apps
     * can short-circuit by calling [Room.retryLocalTrack] from a
     * "Try again" button. After successful recovery,
     * [LocalTrackRecovered] fires; after the SDKs internal retry budget
     * is exhausted it gives up and emits [LocalTrackFailed].
     *
     * Mirrors the JS SDKs `local-track-ended` event.
     */
    public data class LocalTrackEnded(val kind: TrackKind, val reason: String?) : RoomEvent()

    /**
     * A previously-[LocalTrackEnded] track is live again. Fires after
     * the SDK or [Room.retryLocalTrack] successfully re-acquired the
     * device. Mirrors the JS SDKs `local-track-recovered` event.
     */
    public data class LocalTrackRecovered(val kind: TrackKind) : RoomEvent()

    /**
     * The SDK gave up trying to recover a local track. Apps should
     * surface a "Camera unavailable" / "Microphone unavailable" UI and
     * offer manual retry via [Room.retryLocalTrack].
     *
     * Mirrors the JS SDKs `local-track-failed` event.
     */
    public data class LocalTrackFailed(val kind: TrackKind, val reason: String?) : RoomEvent()

    /** A virtual background was successfully applied. Mirrors `background-ready`. */
    public data class BackgroundReady(val mode: String, val imageUrl: String?, val prompt: String?) : RoomEvent()

    /** Background application failed (image load error, GL init, etc.). Mirrors `background-error`. */
    public data class BackgroundError(val reason: String) : RoomEvent()

    /** Virtual background was cleared / disabled. Mirrors `background-cleared`. */
    public object BackgroundCleared : RoomEvent()

    /** Auto-frame mode was turned on. Mirrors `auto-frame-enabled`. */
    public object AutoFrameEnabled : RoomEvent()

    /** Auto-frame mode was turned off. Mirrors `auto-frame-disabled`. */
    public object AutoFrameDisabled : RoomEvent()

    /**
     * The local screen share has started successfully. Mirrors
     * `screen-share-started` on the JS SDK. Pairs with [ScreenShareEnded]
     * - every successful start eventually fires one of each.
     *
     * [streamId] is the WebRTC stream identifier the SDK negotiated with
     * the SFU. It's exposed mainly for analytics; UIs typically just
     * bind to [Room.screenShare].
     */
    public data class ScreenShareStarted(val streamId: String) : RoomEvent()
}

/**
 * Coarse transport-level state of the room's Socket.IO connection.
 *
 *   CONNECTING   - initial state, awaiting `authenticated`.
 *   CONNECTED    - authenticated; signaling traffic flows.
 *   RECONNECTING - transport dropped; underlying socket.io client
 *                  is auto-retrying (5 attempts by default).
 *   DISCONNECTED - permanent: either retries exhausted or [Room.leave].
 */
public enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
}

public enum class TrackKind { AUDIO, VIDEO, SCREEN }

/**
 * Why a screen share ended. Reported on [RoomEvent.ScreenShareEnded].
 */
public enum class ScreenShareEndReason {
    /** Caller invoked [Room.stopScreenShare]. The expected, deliberate path. */
    USER_REQUESTED,

    /**
     * The OS or the user-via-system-UI ended the projection — typically
     * the persistent "Stop sharing" notification, an inbound phone call
     * interrupting `MediaProjection`, OS resource pressure, or the user
     * revoking the projection consent. The SDK has already torn down
     * cleanly; the host app may want to re-prompt for consent.
     */
    SYSTEM_STOPPED,

    /**
     * The capture pipeline failed mid-stream (sensor/encoder error or
     * an exception inside the WebRTC capturer callback). Rare; treat
     * as recoverable by re-prompting consent.
     */
    CAPTURE_FAILED,
}
