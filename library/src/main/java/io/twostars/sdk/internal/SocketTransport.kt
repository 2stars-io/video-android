package io.twostars.sdk.internal

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import io.twostars.sdk.SelfPresence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.util.Base64

/**
 * Thin wrapper around the Socket.IO Java client.
 *
 * - Lifecycles the connection (auth at `connect`, close idempotent).
 * - Translates between Socket.IO's `org.json.JSONObject` API and
 *   kotlinx-serialization [JsonObject] so the rest of the SDK runs on
 *   one JSON model.
 * - Multiplexes server events through typed callback registrations.
 *
 * Not part of the public API surface — `internal` so [io.twostars.sdk.Room]
 * and the WebRTC pool can talk to it without exposing org.json types.
 *
 * Events surfaced (by socket name) — match the JS SDK's wire vocabulary
 * since both clients hit the same backend:
 *
 *   incoming:  authenticated  auth_error  disconnect
 *              peer-joined    peer-left    room-mode-changed
 *              offer          answer       ice-candidate
 *
 *   outgoing:  join-room     leave-room
 *              offer          answer       ice-candidate
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
    private var onAuthenticated: ((SelfPresence, List<IceServerSpec>) -> Unit)? = null
    private var onAuthError:    ((String) -> Unit)? = null
    private var onConnectError: ((String) -> Unit)? = null
    private var onReconnected:  (() -> Unit)? = null
    // True after the FIRST `authenticated` event. Subsequent `authenticated`
    // events (after the socket.io client reconnects) fire onReconnected
    // instead of onAuthenticated so Room.kt can flip
    // ConnectionState RECONNECTING -> CONNECTED without trying to rebuild
    // its state from a SelfPresence it already has.
    @Volatile private var everAuthenticated = false
    private var onPeerJoined:    ((JsonObject) -> Unit)? = null
    private var onPeerLeft:      ((JsonObject) -> Unit)? = null
    private var onParticipantStateChanged: ((JsonObject) -> Unit)? = null
    private var onRoomModeChanged: ((JsonObject) -> Unit)? = null
    private var onDisconnected:  ((String?) -> Unit)? = null
    private var onOffer:         ((from: String, sdp: JsonObject) -> Unit)? = null
    private var onAnswer:        ((from: String, sdp: JsonObject) -> Unit)? = null
    private var onIceCandidate:  ((from: String, candidate: JsonObject) -> Unit)? = null
    private var onNewProducer:   ((JsonObject) -> Unit)? = null
    private var onProducerClosed: ((JsonObject) -> Unit)? = null
    private var onKeyBundle:     ((from: String, bundle: JsonObject) -> Unit)? = null
    private var onNewMessage:    ((JsonObject) -> Unit)? = null
    private var onActiveSpeaker: ((participantId: String?) -> Unit)? = null
    private var onActiveSpeakers: ((speakers: List<String>) -> Unit)? = null
    private var onE2eRecordingMode: ((enabled: Boolean, reason: String?, jobId: String?, kind: String?) -> Unit)? = null
    // E6 — moderation events from server.
    private var onKicked: ((reason: String?, by: String?) -> Unit)? = null
    private var onForceMuted: ((kind: String, by: String?) -> Unit)? = null
    private var onBanned: ((reason: String?, bannedUntil: String?, by: String?) -> Unit)? = null
    private var onRoomLocked: ((locked: Boolean, by: String?) -> Unit)? = null
    private var onModerationEvent: ((kind: String, by: String?, target: String?, extras: JSONObject) -> Unit)? = null
    private var onP2pMediatypeInfo: ((streamId: String, mediaType: String) -> Unit)? = null
    private var onTranscription: ((JsonObject) -> Unit)? = null
    private var onTranslation: ((JsonObject) -> Unit)? = null
    private var onTranslatedAudio: ((JsonObject) -> Unit)? = null
    private var onFactCheck: ((JsonObject) -> Unit)? = null
    private var onCorrectionSuggestion: ((JsonObject) -> Unit)? = null
    private var onHebbsResponse: ((JsonObject) -> Unit)? = null
    private var onHebbsMessage: ((JsonObject) -> Unit)? = null
    private var onAiParticipantJoined: ((JsonObject) -> Unit)? = null
    private var onAiParticipantUpdated: ((JsonObject) -> Unit)? = null
    private var onAiParticipantLeft: ((JsonObject) -> Unit)? = null
    private var onAiVoice: ((JsonObject) -> Unit)? = null
    private var onWhiteboardUpdate: ((JsonObject) -> Unit)? = null
    private var onWhiteboardAnnotation: ((JsonObject) -> Unit)? = null
    private var onControlRequestIncoming: ((JsonObject) -> Unit)? = null
    private var onControlGranted: ((JsonObject) -> Unit)? = null
    private var onControlDenied: ((JsonObject) -> Unit)? = null
    private var onControlRevoked: ((JsonObject) -> Unit)? = null
    private var onControlActive: ((JsonObject) -> Unit)? = null
    private var onControlInactive: ((JsonObject) -> Unit)? = null
    private var onRemoteInput: ((JsonObject) -> Unit)? = null

    fun onAuthenticated(cb: (SelfPresence, List<IceServerSpec>) -> Unit) { onAuthenticated = cb }
    fun onAuthError(cb: (String) -> Unit) { onAuthError = cb }
    fun onConnectError(cb: (String) -> Unit) { onConnectError = cb }
    fun onReconnected(cb: () -> Unit) { onReconnected = cb }
    fun onPeerJoined(cb: (JsonObject) -> Unit) { onPeerJoined = cb }
    fun onPeerLeft(cb: (JsonObject) -> Unit) { onPeerLeft = cb }
    fun onParticipantStateChanged(cb: (JsonObject) -> Unit) { onParticipantStateChanged = cb }
    fun onRoomModeChanged(cb: (JsonObject) -> Unit) { onRoomModeChanged = cb }
    fun onDisconnected(cb: (String?) -> Unit) { onDisconnected = cb }
    fun onOffer(cb: (from: String, sdp: JsonObject) -> Unit) { onOffer = cb }
    fun onAnswer(cb: (from: String, sdp: JsonObject) -> Unit) { onAnswer = cb }
    fun onIceCandidate(cb: (from: String, candidate: JsonObject) -> Unit) { onIceCandidate = cb }
    fun onNewProducer(cb: (JsonObject) -> Unit) { onNewProducer = cb }
    fun onProducerClosed(cb: (JsonObject) -> Unit) { onProducerClosed = cb }
    fun onKeyBundle(cb: (from: String, bundle: JsonObject) -> Unit) { onKeyBundle = cb }
    fun onNewMessage(cb: (JsonObject) -> Unit) { onNewMessage = cb }
    fun onActiveSpeaker(cb: (participantId: String?) -> Unit) { onActiveSpeaker = cb }
    fun onActiveSpeakers(cb: (speakers: List<String>) -> Unit) { onActiveSpeakers = cb }
    fun onE2eRecordingMode(cb: (enabled: Boolean, reason: String?, jobId: String?, kind: String?) -> Unit) {
        onE2eRecordingMode = cb
    }
    fun onKicked(cb: (reason: String?, by: String?) -> Unit) { onKicked = cb }
    fun onForceMuted(cb: (kind: String, by: String?) -> Unit) { onForceMuted = cb }
    fun onBanned(cb: (reason: String?, bannedUntil: String?, by: String?) -> Unit) { onBanned = cb }
    fun onRoomLocked(cb: (locked: Boolean, by: String?) -> Unit) { onRoomLocked = cb }
    fun onModerationEvent(cb: (kind: String, by: String?, target: String?, extras: JSONObject) -> Unit) {
        onModerationEvent = cb
    }
    fun onP2pMediatypeInfo(cb: (streamId: String, mediaType: String) -> Unit) { onP2pMediatypeInfo = cb }
    fun onTranscription(cb: (JsonObject) -> Unit) { onTranscription = cb }
    fun onTranslation(cb: (JsonObject) -> Unit) { onTranslation = cb }
    fun onTranslatedAudio(cb: (JsonObject) -> Unit) { onTranslatedAudio = cb }
    fun onFactCheck(cb: (JsonObject) -> Unit) { onFactCheck = cb }
    fun onCorrectionSuggestion(cb: (JsonObject) -> Unit) { onCorrectionSuggestion = cb }
    fun onHebbsResponse(cb: (JsonObject) -> Unit) { onHebbsResponse = cb }
    fun onHebbsMessage(cb: (JsonObject) -> Unit) { onHebbsMessage = cb }
    fun onAiParticipantJoined(cb: (JsonObject) -> Unit) { onAiParticipantJoined = cb }
    fun onAiParticipantUpdated(cb: (JsonObject) -> Unit) { onAiParticipantUpdated = cb }
    fun onAiParticipantLeft(cb: (JsonObject) -> Unit) { onAiParticipantLeft = cb }
    fun onAiVoice(cb: (JsonObject) -> Unit) { onAiVoice = cb }
    fun onWhiteboardUpdate(cb: (JsonObject) -> Unit) { onWhiteboardUpdate = cb }
    fun onWhiteboardAnnotation(cb: (JsonObject) -> Unit) { onWhiteboardAnnotation = cb }
    fun onControlRequestIncoming(cb: (JsonObject) -> Unit) { onControlRequestIncoming = cb }
    fun onControlGranted(cb: (JsonObject) -> Unit) { onControlGranted = cb }
    fun onControlDenied(cb: (JsonObject) -> Unit) { onControlDenied = cb }
    fun onControlRevoked(cb: (JsonObject) -> Unit) { onControlRevoked = cb }
    fun onControlActive(cb: (JsonObject) -> Unit) { onControlActive = cb }
    fun onControlInactive(cb: (JsonObject) -> Unit) { onControlInactive = cb }
    fun onRemoteInput(cb: (JsonObject) -> Unit) { onRemoteInput = cb }

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
            // The server only emits `authenticated` on success — auth
            // failures hit Socket.IO's `connect_error` path because
            // io.use() middleware rejects the handshake before this
            // event ever fires.
            //
            // Payload shape: { iceServers: [...], localRegion: "..." }.
            // Identity (participantId / roomCode / displayName) is NOT
            // in the payload — it lives in the JWT claims, which the
            // client decodes locally (no verification needed; the
            // server already verified the signature when it accepted
            // the handshake).
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: JsonObject(emptyMap())
            val ice = (obj["iceServers"] as? JsonArray)?.let { IceServerCodec.parseList(it) }.orEmpty()

            val presence = decodeJwtPresence(token)
            if (presence == null) {
                onAuthError?.invoke("malformed participant token")
                return@on
            }
            if (!everAuthenticated) {
                everAuthenticated = true
                onAuthenticated?.invoke(presence, ice)
            } else {
                // socket.io reconnected and the server re-authenticated us.
                // Room.kt only wants to know about the transition; the
                // SelfPresence + ICE list it already has are still valid.
                onReconnected?.invoke()
            }
        }

        s.on("peer-joined") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onPeerJoined?.invoke(obj)
        }
        s.on("peer-left") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onPeerLeft?.invoke(obj)
        }
        // E8 — server fans out remote camera/mic enabled-flag changes
        // (originated by another peer's setCameraEnabled/setMicEnabled
        // call). Payload: { participantId, videoEnabled?, audioEnabled? }.
        // Either flag may be omitted; the receiver should keep the prior
        // value when a flag is missing.
        s.on("participant-state-changed") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onParticipantStateChanged?.invoke(obj)
        }
        s.on("room-mode-changed") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onRoomModeChanged?.invoke(obj)
        }

        // P2P signaling — server forwards these between participants
        // verbatim. `from` arrives as a top-level field; `sdp` /
        // `candidate` carry the payload.
        s.on("offer") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return@on
            val sdp = obj["sdp"] as? JsonObject ?: return@on
            onOffer?.invoke(from, sdp)
        }
        s.on("answer") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return@on
            val sdp = obj["sdp"] as? JsonObject ?: return@on
            onAnswer?.invoke(from, sdp)
        }
        s.on("ice-candidate") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return@on
            val cand = obj["candidate"] as? JsonObject ?: return@on
            onIceCandidate?.invoke(from, cand)
        }

        // SFU — server announces a new mediasoup producer the local
        // recv-transport can consume, or that an existing producer
        // closed. The pending-producer queue inside SfuPipe deals with
        // events that arrive before our recv transport is set up.
        s.on("new-producer") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onNewProducer?.invoke(obj)
        }
        s.on("producer-closed") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onProducerClosed?.invoke(obj)
        }

        // E2E messaging events.
        // key-bundle relay: server forwards key-bundle envelopes between
        // peers (broadcast or directed). We unpack `from` + `bundle` and
        // hand them to the room's key manager.
        s.on("key-bundle") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return@on
            val bundle = obj["bundle"] as? JsonObject ?: return@on
            onKeyBundle?.invoke(from, bundle)
        }
        s.on("new-message") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onNewMessage?.invoke(obj)
        }

        // Server's mediasoup ActiveSpeakerObserver fires this when the
        // dominant speaker changes (interval ~300ms). Payload may be
        // either a bare string (legacy shape) or { participantId: ... }
        // — accept both for forward-compat. `null` participantId means
        // "no one is currently speaking" (silence).
        s.on("active-speaker") { args ->
            val raw = args.firstOrNull()
            val pid = when (raw) {
                is JSONObject -> raw.optString("participantId").takeIf { it.isNotEmpty() }
                is String -> raw.takeIf { it.isNotEmpty() }
                else -> null
            }
            onActiveSpeaker?.invoke(pid)
        }

        // D1 — rolling top-N speaker list. Set semantics — server only
        // fires when the membership of the top N changes. Use to manage
        // a "speaker grid" UX: subscribe to video for these IDs, drop
        // any video consumer for IDs that fell out of the list. Audio
        // stays universally subscribed (cheap).
        s.on("active-speakers") { args ->
            val obj = (args.firstOrNull() as? JSONObject) ?: return@on
            val arr = obj.optJSONArray("speakers") ?: return@on
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, null)
                if (!s.isNullOrEmpty()) out.add(s)
            }
            onActiveSpeakers?.invoke(out)
        }

        // E4.1 — server tells us a recording / broadcast is starting or
        // ending on this E2E room; we transparently flip SFrame off /
        // back on with disclosure. Payload shape:
        //   { enabled: bool, reason: string, jobId: string, kind: 'recording'|'broadcast' }
        s.on("e2e-recording-mode") { args ->
            val obj = (args.firstOrNull() as? JSONObject) ?: return@on
            val enabled = obj.optBoolean("enabled", false)
            val reason = obj.optString("reason", null)
            val jobId = obj.optString("jobId", null)
            val kind = obj.optString("kind", null)
            onE2eRecordingMode?.invoke(enabled, reason, jobId, kind)
        }

        // E6 — moderation events. Server emits these to:
        //   - the kicked / force-muted / banned target only
        //   - the room as a whole for room-locked + moderation-event
        s.on("kicked") { args ->
            val obj = args.firstOrNull() as? JSONObject
            onKicked?.invoke(obj?.optString("reason", null), obj?.optString("by", null))
        }
        s.on("force-muted") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val kind = obj.optString("kind", "audio") ?: "audio"
            onForceMuted?.invoke(kind, obj.optString("by", null))
        }
        s.on("banned") { args ->
            val obj = args.firstOrNull() as? JSONObject
            onBanned?.invoke(
                obj?.optString("reason", null),
                obj?.optString("bannedUntil", null),
                obj?.optString("by", null),
            )
        }
        s.on("room-locked") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            onRoomLocked?.invoke(obj.optBoolean("locked", false), obj.optString("by", null))
        }
        s.on("moderation-event") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val kind = obj.optString("kind", "") ?: ""
            if (kind.isEmpty()) return@on
            onModerationEvent?.invoke(
                kind,
                obj.optString("by", null),
                obj.optString("targetParticipantId", null),
                obj,
            )
        }

        // P2P out-of-band hint — sender emits this just before adding a
        // screen-share track to a PC, so the receiver knows the next
        // ontrack with the matching streamId is a screen, not a camera.
        // Server forwards to the named recipient verbatim. (P2P doesn't
        // have an appData channel like SFU's mediasoup; this is the JS
        // SDK's workaround and we mirror it on Android.)
        s.on("p2p-mediatype-info") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            val streamId = obj["streamId"]?.jsonPrimitive?.contentOrNull ?: return@on
            val mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull ?: return@on
            onP2pMediatypeInfo?.invoke(streamId, mediaType)
        }

        // AI — server fans out one `transcription` event per recognised
        // utterance, room-wide. Payload shape:
        //   { speaker, displayName, text, language, timestamp }
        s.on("transcription") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onTranscription?.invoke(obj)
        }
        // Text translations are fanned out per-listener: only sockets
        // that opted in via `set-language` AND whose target language
        // differs from the speaker's source language receive these.
        s.on("translation") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onTranslation?.invoke(obj)
        }
        // Voice translation — same listener gating as `translation`,
        // additionally requires the room's `voice-translation`
        // feature flag. Audio is base64'd inline.
        s.on("translated-audio") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onTranslatedAudio?.invoke(obj)
        }
        // AI — fact-check fires on every transcription that the LLM
        // disagrees with against the room's documents. Server emits
        // privately to the speaker (so always == this socket).
        s.on("fact-check") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onFactCheck?.invoke(obj)
        }
        // AI — text correction reply, fired only when the LLM has a
        // non-trivial suggestion. The ack on `request-correction`
        // also carries the same payload (or a `skipped` reason).
        s.on("correction-suggestion") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onCorrectionSuggestion?.invoke(obj)
        }

        // AI participant ("Hey Hebbs"). Response to a one-shot
        // hebbs-command goes private to the requester; chat-mode
        // hebbs-message goes either room-wide or private depending on
        // the request's `private:` flag.
        s.on("hebbs-response") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onHebbsResponse?.invoke(obj)
        }
        s.on("hebbs-message") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onHebbsMessage?.invoke(obj)
        }
        // Live AI participant lifecycle — when set-ai-assistant flips on,
        // the server spawns a virtual peer and broadcasts these.
        s.on("ai-participant-joined") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onAiParticipantJoined?.invoke(obj)
        }
        s.on("ai-participant-updated") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onAiParticipantUpdated?.invoke(obj)
        }
        s.on("ai-participant-left") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onAiParticipantLeft?.invoke(obj)
        }
        s.on("ai-voice") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onAiVoice?.invoke(obj)
        }
        // AI whiteboard — server fans the whole SVG out to every
        // participant on a regenerate; annotations are per-stroke-set
        // deltas appended by individual users.
        s.on("whiteboard-update") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onWhiteboardUpdate?.invoke(obj)
        }
        s.on("whiteboard-annotations") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onWhiteboardAnnotation?.invoke(obj)
        }

        // Remote desktop control. Two roles:
        //   - sharer: receives `control-request-incoming` + `remote-input`
        //   - controller: receives `control-granted` / `control-denied`
        // Both: `control-revoked` (per-side directed) and `control-active` /
        //       `control-inactive` (room-wide).
        s.on("control-request-incoming") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onControlRequestIncoming?.invoke(obj)
        }
        s.on("control-granted") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onControlGranted?.invoke(obj)
        }
        s.on("control-denied") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onControlDenied?.invoke(obj)
        }
        s.on("control-revoked") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onControlRevoked?.invoke(obj)
        }
        s.on("control-active") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onControlActive?.invoke(obj)
        }
        s.on("control-inactive") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onControlInactive?.invoke(obj)
        }
        s.on("remote-input") { args ->
            val obj = (args.firstOrNull() as? JSONObject)?.toKx() ?: return@on
            onRemoteInput?.invoke(obj)
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

    /**
     * Coroutine-flavoured [emitWithAck]. Suspends until the server
     * acks (or the underlying socket signals null). Convenience for
     * call-sites that want to await the ack without manually wrapping
     * a CompletableDeferred.
     */
    suspend fun emitWithAckSuspending(event: String, payload: JsonObject): JsonObject? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            emitWithAck(event, payload) { ack ->
                if (cont.isActive) cont.resume(ack) {}
            }
        }

    /**
     * Like [emitWithAckSuspending] but with a per-call timeout. Use
     * for events that may run server-side AI work (Gemini image-gen,
     * avatar generation, etc.) — those legitimately take 10–25 s and
     * blow past the default Socket.IO request lifetime, so callers
     * need an explicit bound. Returns `null` on timeout.
     *
     * Why this is separate from a default timeout on
     * [emitWithAckSuspending]: most signaling events (offer/answer/
     * produce/consume) resolve in a few hundred ms; an aggressive
     * default would mask transport bugs as "ack=null". Keep the
     * default un-bounded; opt in to a timeout per call site.
     */
    suspend fun emitWithAckSuspending(
        event: String,
        payload: JsonObject,
        timeoutMs: Long,
    ): JsonObject? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        emitWithAckSuspending(event, payload)
    }

    // ----- json bridge --------------------------------------------------------

    private fun JsonObject.toOrgJson(): JSONObject = JSONObject(this.toString())
    private fun JSONObject.toKx(): JsonObject =
        json.parseToJsonElement(this.toString()).jsonObject

    // ----- JWT identity decode -----------------------------------------------
    //
    // The server has already verified the signature when it accepted the
    // socket handshake; we just need the claims for identity.
    private fun decodeJwtPresence(token: String): SelfPresence? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = runCatching {
            // Base64URL with padding stripped — pad back to a multiple of 4.
            val raw = parts[1]
            val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
            val bytes = Base64.getUrlDecoder().decode(padded)
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return null

        val pid = payload["sub"]?.jsonPrimitive?.contentOrNull
            ?: payload["participantId"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val name = payload["displayName"]?.jsonPrimitive?.contentOrNull
        val code = payload["roomCode"]?.jsonPrimitive?.contentOrNull ?: ""
        return SelfPresence(pid, name, code)
    }

}

/**
 * Parses the server's `iceServers` field into a list of
 * transport-layer specs. Lifted out of [SocketTransport] so JVM
 * tests can drive it with synthesised JSON (no socket required).
 *
 * Accepts both shapes the platform has emitted historically:
 *   - `{ "urls": ["stun:..."], "username": "...", "credential": "..." }`
 *   - `{ "url":  "stun:...",   "username": "...", "credential": "..." }`
 */
internal object IceServerCodec {
    fun parseList(arr: JsonArray): List<IceServerSpec> = arr.mapNotNull(::parseOne)

    fun parseOne(el: kotlinx.serialization.json.JsonElement): IceServerSpec? {
        val obj = el as? JsonObject ?: return null
        val urls = when (val u = obj["urls"]) {
            is JsonArray -> u.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> obj["urls"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }
                ?: obj["url"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }
                ?: return null
        }
        if (urls.isEmpty()) return null
        return IceServerSpec(
            urls = urls,
            username = obj["username"]?.jsonPrimitive?.contentOrNull,
            credential = obj["credential"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

/**
 * Transport-layer representation of a server-supplied ICE server.
 * Decoupled from `org.webrtc.PeerConnection.IceServer` so the JSON
 * parsing layer doesn't pull in WebRTC types — the conversion happens
 * inside [PeerConnectionPool] where we actually need them.
 */
internal data class IceServerSpec(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)
