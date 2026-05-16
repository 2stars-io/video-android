package io.twostars.sdk

/**
 * Incoming request from another peer who wants to remote-control
 * *this* participant. Surface a UI prompt; if the user accepts, call
 * [Room.grantControl] with [requestId]. To refuse, call
 * [Room.denyControl] (optionally with a [reason]).
 *
 * Requests automatically expire after a server-side TTL (~30s) if
 * neither granted nor denied.
 */
public data class ControlRequest(
    public val requestId: String,
    public val fromParticipantId: String,
    public val fromDisplayName: String?,
    public val requestedAtMs: Long,
)

/**
 * An active remote-control grant. Emitted on both sides:
 *   - to the controller via [Room.controlGranted] / as the
 *     [Room.grantControl] return value when granting succeeds, and
 *   - room-wide via [Room.controlActive] so other peers can show a
 *     "X is controlling Y" badge.
 */
public data class ControlSession(
    public val sharerParticipantId: String,
    public val controllerParticipantId: String,
    public val controllerDisplayName: String?,
    public val grantedAtMs: Long,
)

/**
 * A control request was refused by the would-be sharer. Surface to
 * the requesting user so they know why.
 */
public data class ControlDenial(
    public val requestId: String,
    public val deniedByParticipantId: String,
    public val reason: String?,
)

/**
 * A previously-active control session ended — either side revoked
 * it, the controller disconnected, or the sharer kicked. Both
 * controller and sharer receive this directly; the room also gets a
 * [controlInactive] broadcast for UI cleanup.
 */
public data class ControlRevocation(
    public val sharerParticipantId: String,
    public val reason: String?,
)

/**
 * Remote input event — the controller's pointer / keyboard activity,
 * fanned out to the sharer. Coordinates ([nx], [ny]) are normalised
 * 0..1 so the sharer can map onto its own viewport regardless of
 * relative size.
 *
 * The SDK does NOT inject these into the host app's UI — that's
 * platform-specific (Android needs an [android.accessibilityservice.AccessibilityService]
 * or root). Subscribe to [Room.remoteInput] and translate as needed.
 */
public data class RemoteInputEvent(
    public val fromParticipantId: String,
    public val type: String, // pointermove / pointerdown / pointerup / click / dblclick / wheel / keydown / keyup
    public val nx: Double? = null,
    public val ny: Double? = null,
    public val button: Int? = null,
    public val deltaX: Double? = null,
    public val deltaY: Double? = null,
    public val key: String? = null,
    public val code: String? = null,
    public val ctrl: Boolean? = null,
    public val meta: Boolean? = null,
    public val alt: Boolean? = null,
    public val shift: Boolean? = null,
)
