package io.twostars.sdk

import io.twostars.sdk.internal.SocketTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handle for a single participant's connection to a 2Stars room.
 *
 * Obtain one via [StarsClient.connect]. Then call [join] with a room
 * code; observe [peers] for membership and [events] for finer-grained
 * lifecycle signals.
 *
 * In Stage A1 a Room is presence-only — [peers] reflects who's joined,
 * but no media flows. Media-plane behaviour lands in A2+.
 */
public class Room internal constructor(
    private val transport: SocketTransport,
    /**
     * Authenticated identity returned by the server at connect time.
     * Carries this peer's participantId, displayName, and roomCode (the
     * room baked into the participant token).
     */
    public val self: SelfPresence,
) {

    public enum class Mode { P2P, SFU }

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    /** Live map of peers in the room, keyed by participantId. */
    public val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _mode = MutableStateFlow(Mode.P2P)
    /** Current room media mode (P2P up to 4 participants, then SFU). */
    public val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _events = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 16)
    /** Lifecycle event stream — finer-grained than [peers] / [mode]. */
    public val events: SharedFlow<RoomEvent> = _events.asSharedFlow()

    init {
        transport.onPeerJoined { obj ->
            val peer = obj.toPeer() ?: return@onPeerJoined
            _peers.update { it + (peer.participantId to peer) }
            _events.tryEmit(RoomEvent.PeerJoined(peer))
        }
        transport.onPeerLeft { obj ->
            val pid = obj["participantId"]?.jsonPrimitive?.contentOrNull
                ?: return@onPeerLeft
            _peers.update { it - pid }
            _events.tryEmit(RoomEvent.PeerLeft(pid))
        }
        transport.onRoomModeChanged { obj ->
            val raw = obj["mode"]?.jsonPrimitive?.contentOrNull ?: return@onRoomModeChanged
            val newMode = if (raw.equals("sfu", ignoreCase = true)) Mode.SFU else Mode.P2P
            _mode.value = newMode
            _events.tryEmit(RoomEvent.ModeChanged(newMode))
        }
        transport.onDisconnected { reason ->
            _events.tryEmit(RoomEvent.Disconnected(reason))
        }
    }

    /**
     * Join the room identified by [roomCode]. Server validates that
     * the participant token's roomCode claim matches.
     *
     * Returns the [JoinResult] the server emitted in its ack — most
     * importantly the initial [Mode], plus the list of peers already
     * in the room (which are also reflected immediately in [peers]).
     */
    public suspend fun join(roomCode: String): JoinResult =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("join-room", buildJsonObject { "roomCode" to roomCode }) { ack ->
                if (ack == null) {
                    if (cont.isActive) cont.resumeWithException(
                        JoinException("server did not ack join-room")
                    )
                    return@emitWithAck
                }
                val ok = ack["ok"]?.jsonPrimitive?.boolean == true
                if (!ok) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    if (cont.isActive) cont.resumeWithException(JoinException(err))
                    return@emitWithAck
                }
                val modeRaw = ack["mode"]?.jsonPrimitive?.contentOrNull
                val newMode = if (modeRaw == "sfu") Mode.SFU else Mode.P2P
                _mode.value = newMode

                val initialPeers = ack["peers"]?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { (it as? JsonObject)?.toPeer() }
                    .orEmpty()
                if (initialPeers.isNotEmpty()) {
                    _peers.update { existing ->
                        existing + initialPeers.associateBy { it.participantId }
                    }
                }
                if (cont.isActive) cont.resume(JoinResult(roomCode, newMode, initialPeers))
            }
        }

    /** Leave the room and close the underlying connection. Idempotent. */
    public suspend fun leave() {
        try {
            transport.emit("leave-room", buildJsonObject { /* no body */ })
        } finally {
            transport.close()
        }
    }

    public data class JoinResult(
        val roomCode: String,
        val mode: Mode,
        val initialPeers: List<Peer>,
    )

    public class JoinException(message: String) : Exception(message)
}

/**
 * Authenticated self identity — what the server tells us about ourselves
 * at connect time. Comes from the participant token's claims.
 */
public data class SelfPresence(
    val participantId: String,
    val displayName: String?,
    val roomCode: String,
)

internal fun JsonObject.toPeer(): Peer? {
    val pid = this["participantId"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = this["displayName"]?.jsonPrimitive?.contentOrNull
    val joinedAtMs = this["joinedAt"]?.jsonPrimitive?.contentOrNull?.let {
        // ISO timestamp from server; we store millis-since-epoch for callers,
        // but only if parseable. Drop on failure rather than crash.
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: System.currentTimeMillis()
    return Peer(participantId = pid, displayName = name, joinedAtMs = joinedAtMs)
}

private inline fun buildJsonObject(builder: JsonObjectBuilder.() -> Unit): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        val b = JsonObjectBuilder(this)
        b.builder()
    }

internal class JsonObjectBuilder(private val real: kotlinx.serialization.json.JsonObjectBuilder) {
    infix fun String.to(value: String) {
        real.put(this, kotlinx.serialization.json.JsonPrimitive(value))
    }
    infix fun String.to(value: Number) {
        real.put(this, kotlinx.serialization.json.JsonPrimitive(value))
    }
    infix fun String.to(value: Boolean) {
        real.put(this, kotlinx.serialization.json.JsonPrimitive(value))
    }
}
