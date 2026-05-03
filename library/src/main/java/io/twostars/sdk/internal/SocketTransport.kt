package io.twostars.sdk.internal

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import io.twostars.sdk.SelfPresence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject

/**
 * Thin wrapper around the Socket.IO Java client.
 *
 * - Lifecycles the connection (auth at `connect`, close idempotent).
 * - Translates between Socket.IO's `org.json.JSONObject` API and
 *   kotlinx-serialization `JsonObject` so the rest of the SDK can use
 *   one JSON model.
 * - Multiplexes server events through typed callback registrations.
 *
 * Not part of the public API surface — the only reason it's `internal`
 * (rather than `private`) is so it can be referenced by [io.twostars.sdk.Room].
 */
internal class SocketTransport(
    private val baseUrl: String,
    private val token: String,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var socket: Socket? = null
    private var closed = false

    // ----- callback registries ------------------------------------------------
    private var onAuthenticated: ((SelfPresence) -> Unit)? = null
    private var onAuthError:    ((String) -> Unit)? = null
    private var onConnectError: ((String) -> Unit)? = null
    private var onPeerJoined:    ((JsonObject) -> Unit)? = null
    private var onPeerLeft:      ((JsonObject) -> Unit)? = null
    private var onRoomModeChanged: ((JsonObject) -> Unit)? = null
    private var onDisconnected:  ((String?) -> Unit)? = null

    fun onAuthenticated(cb: (SelfPresence) -> Unit) { onAuthenticated = cb }
    fun onAuthError(cb: (String) -> Unit) { onAuthError = cb }
    fun onConnectError(cb: (String) -> Unit) { onConnectError = cb }
    fun onPeerJoined(cb: (JsonObject) -> Unit) { onPeerJoined = cb }
    fun onPeerLeft(cb: (JsonObject) -> Unit) { onPeerLeft = cb }
    fun onRoomModeChanged(cb: (JsonObject) -> Unit) { onRoomModeChanged = cb }
    fun onDisconnected(cb: (String?) -> Unit) { onDisconnected = cb }

    // ----- lifecycle ----------------------------------------------------------

    fun connect() {
        val opts = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionAttempts(5)
            .build()
        val s = IO.socket(baseUrl, opts)
        socket = s

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = args.firstOrNull()?.toString() ?: "connect_error"
            onConnectError?.invoke(msg)
        }
        s.on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString()
            onDisconnected?.invoke(reason)
        }

        s.on("authenticated") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            // Server emits { ok: true, participantId, displayName, roomCode } on success;
            // { ok: false, error } on failure.
            val ok = obj["ok"]?.jsonPrimitive?.contentOrNull == "true" ||
                obj["ok"]?.jsonPrimitive?.contentOrNull == "1" ||
                obj["ok"]?.jsonPrimitive?.toString() == "true"
            if (!ok) {
                val err = obj["error"]?.jsonPrimitive?.contentOrNull ?: "unauthorized"
                onAuthError?.invoke(err)
                return@on
            }
            val pid = obj["participantId"]?.jsonPrimitive?.contentOrNull
                ?: obj["sub"]?.jsonPrimitive?.contentOrNull ?: ""
            val name = obj["displayName"]?.jsonPrimitive?.contentOrNull
            val code = obj["roomCode"]?.jsonPrimitive?.contentOrNull ?: ""
            onAuthenticated?.invoke(SelfPresence(pid, name, code))
        }

        s.on("auth_error") { args ->
            val msg = (args.firstOrNull() as? JSONObject)?.optString("message")
                ?: args.firstOrNull()?.toString()
                ?: "auth_error"
            onAuthError?.invoke(msg)
        }

        s.on("peer-joined") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onPeerJoined?.invoke(obj)
        }
        s.on("peer-left") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onPeerLeft?.invoke(obj)
        }
        s.on("room-mode-changed") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onRoomModeChanged?.invoke(obj)
        }

        s.connect()
    }

    fun close() {
        if (closed) return
        closed = true
        socket?.disconnect()
        socket?.close()
        socket = null
    }

    // ----- emit ---------------------------------------------------------------

    fun emit(event: String, payload: JsonObject) {
        socket?.emit(event, payload.toOrgJson())
    }

    /**
     * Emit + wait for ack. The Socket.IO Java client signals ack via a
     * trailing callback variadic — we wrap it here so the public API
     * works on `(JsonObject?) -> Unit`.
     */
    fun emitWithAck(event: String, payload: JsonObject, ack: (JsonObject?) -> Unit) {
        val s = socket
        if (s == null) {
            ack(null); return
        }
        s.emit(event, arrayOf<Any?>(payload.toOrgJson()), io.socket.client.Ack { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx()
            ack(obj)
        })
    }

    // ----- json bridge --------------------------------------------------------

    private fun JsonObject.toOrgJson(): JSONObject = JSONObject(this.toString())
    private fun JSONObject.toKx(): JsonObject =
        json.parseToJsonElement(this.toString()).jsonObject
}
