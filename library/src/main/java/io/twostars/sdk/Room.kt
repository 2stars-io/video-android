package io.twostars.sdk

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.twostars.sdk.internal.AudioRecordCapture
import io.twostars.sdk.internal.Crypto
import io.twostars.sdk.internal.IceServerSpec
import io.twostars.sdk.internal.LocalCamera
import io.twostars.sdk.internal.PeerConnectionPool
import io.twostars.sdk.internal.ScreenCapture
import io.twostars.sdk.internal.SfuPipe
import io.twostars.sdk.internal.SignalingCodec
import io.twostars.sdk.internal.SocketTransport
import io.twostars.sdk.internal.VadEndpointer
import io.twostars.sdk.internal.WavEncoder
import io.twostars.sdk.internal.WebRTCFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handle for a single participant's connection to a 2Stars room.
 *
 * Obtain one via [StarsClient.connect]. Then call [join] with a room
 * code; observe [peers] for membership and [events] for finer-grained
 * lifecycle signals; call [publish] to start sending camera + mic to
 * the rest of the room.
 *
 * **A2 status — P2P media plane.** This stage handles up to 4
 * participants (the server's auto-promote threshold). Past that the
 * server will emit `room-mode-changed → sfu` and the SFU upgrade work
 * (Stage A3) takes over.
 */
public class Room internal constructor(
    private val context: Context,
    private val transport: SocketTransport,
    private val factory: WebRTCFactory,
    /**
     * Authenticated identity returned by the server at connect time.
     * Carries this peer's participantId, displayName, and roomCode (the
     * room baked into the participant token).
     */
    public val self: SelfPresence,
    /**
     * ICE servers the server returned with auth — STUN + (eventually)
     * TURN. Empty list ⇒ STUN-only fallback inside the pool.
     */
    private val initialIceServers: List<IceServerSpec>,
) {

    public enum class Mode { P2P, SFU }

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    /** Live map of peers in the room, keyed by participantId. */
    public val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _mode = MutableStateFlow(Mode.P2P)
    /** Current room media mode (P2P up to 4 participants, then SFU in A3). */
    public val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _events = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 64)
    /** Lifecycle event stream — finer-grained than [peers] / [mode]. */
    public val events: SharedFlow<RoomEvent> = _events.asSharedFlow()

    private val _localMedia = MutableStateFlow<LocalMedia?>(null)
    /** Currently-published local camera + mic, or `null` when not publishing. */
    public val localMedia: StateFlow<LocalMedia?> = _localMedia.asStateFlow()

    // Local camera/mic intent flags. These mirror what
    // [setCameraEnabled] / [setMicEnabled] were last asked to do,
    // independent of whether [publish] has run yet. The pair gets
    // broadcast to peers via the `participant-state` socket event so
    // they can render an avatar placeholder when our camera is
    // disabled (a `track.enabled = false` sender keeps emitting black
    // frames — peers can't tell from the media alone). Also re-
    // broadcast whenever a new peer joins, so the newcomer doesn't
    // miss the current state.
    private val _videoEnabled = MutableStateFlow(true)
    /** Reactive view of [setCameraEnabled]. Default `true`. */
    public val videoEnabled: StateFlow<Boolean> = _videoEnabled.asStateFlow()
    private val _audioEnabled = MutableStateFlow(true)
    /** Reactive view of [setMicEnabled]. Default `true`. */
    public val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    private val _screenShare = MutableStateFlow<ScreenShareSession?>(null)
    /**
     * Currently-published local screen share, or `null` when not
     * sharing. Bound by [startScreenShare] / [stopScreenShare].
     * Other peers' screen shares come in on `Peer.screenTrack`.
     */
    public val screenShare: StateFlow<ScreenShareSession?> = _screenShare.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    /**
     * Decrypted chat messages from other peers. Subscribe to render
     * the chat tile. Sender's own [sendMessage] return value is the
     * authoritative record for outbound messages — the server
     * doesn't echo them back to the sender.
     */
    public val messages: SharedFlow<Message> = _messages.asSharedFlow()

    private val _isRoomKeyReady = MutableStateFlow(false)
    /**
     * True once this client either minted the room E2E key (joined
     * an empty room) or unwrapped one received from a peer. Until
     * this flips true, [sendMessage] throws and inbound `new-message`
     * events can't be decrypted.
     */
    public val isRoomKeyReady: StateFlow<Boolean> = _isRoomKeyReady.asStateFlow()

    private val _transcripts = MutableSharedFlow<Transcript>(extraBufferCapacity = 64)
    /**
     * Server-side STT transcripts for every participant in the room
     * (including this one). Subscribe to render a live captions panel.
     * Empty until [startTranscription] is invoked by *some* participant
     * — only one client per room needs to enable transcription for the
     * server to start fanning events out to everyone.
     */
    public val transcripts: SharedFlow<Transcript> = _transcripts.asSharedFlow()

    private val _translations = MutableSharedFlow<Translation>(extraBufferCapacity = 64)
    /**
     * Translated transcripts for the language this client opted into
     * via [setTranslationLanguage]. Empty until you've both:
     *
     *  - called [setTranslationLanguage] with a non-null target, and
     *  - someone in the room is producing transcriptions in a *different*
     *    language than your target (the server skips no-op same-language
     *    "translations").
     */
    public val translations: SharedFlow<Translation> = _translations.asSharedFlow()

    private val _factChecks = MutableSharedFlow<FactCheck>(extraBufferCapacity = 32)
    /**
     * Fact-check findings from the room's LLM, comparing this peer's
     * transcribed claims against the room's attached documents. Server
     * emits these privately to the speaker (== this client), so the
     * UI typically shows them as a discreet self-overlay rather than
     * broadcasting to the room.
     */
    public val factChecks: SharedFlow<FactCheck> = _factChecks.asSharedFlow()

    private val _assistantResponses = MutableSharedFlow<AssistantResponse>(extraBufferCapacity = 16)
    /**
     * One-shot AI assistant responses fired in reply to [askAssistant].
     * Same payload is also returned from `askAssistant`'s suspending
     * result — the flow is here for consumers tracking multiple
     * inflight queries (or that want a single subscription point).
     */
    public val assistantResponses: SharedFlow<AssistantResponse> = _assistantResponses.asSharedFlow()

    private val _assistantMessages = MutableSharedFlow<AssistantMessage>(extraBufferCapacity = 64)
    /**
     * Chat-mode AI assistant messages — broadcast to the room (or
     * delivered privately to the requester) when someone sends an
     * `@Hebbs ...` command via [askAssistantInChat].
     */
    public val assistantMessages: SharedFlow<AssistantMessage> = _assistantMessages.asSharedFlow()

    private val _assistantParticipant = MutableStateFlow<AssistantParticipant?>(null)
    /**
     * Live AI participant tracker. Non-null when the room operator has
     * called [setAssistantEnabled]`(true)` and the server has finished
     * spawning the virtual peer; back to null after `setAssistantEnabled`
     * (false) or `ai-participant-left`. The display name + avatar URL
     * fields update reactively when the server fires `ai-participant-updated`.
     */
    public val assistantParticipant: StateFlow<AssistantParticipant?> = _assistantParticipant.asStateFlow()

    private val _whiteboardUpdates = MutableSharedFlow<WhiteboardUpdate>(extraBufferCapacity = 16)
    /**
     * Server-broadcast whiteboard regenerations from any participant's
     * [generateWhiteboard] call. [WhiteboardUpdate.svg] is the full
     * fresh SVG; render into a `WebView` to refresh the whiteboard tile.
     */
    public val whiteboardUpdates: SharedFlow<WhiteboardUpdate> = _whiteboardUpdates.asSharedFlow()

    private val _controlRequests = MutableSharedFlow<ControlRequest>(extraBufferCapacity = 16)
    /**
     * Incoming control requests when this peer is the prospective
     * sharer. Surface a UI prompt; respond via [grantControl] or
     * [denyControl] referencing the request's `requestId`.
     */
    public val controlRequests: SharedFlow<ControlRequest> = _controlRequests.asSharedFlow()

    private val _controlGranted = MutableSharedFlow<ControlSession>(extraBufferCapacity = 16)
    /**
     * Fired on the controller side when their [requestControl] is
     * granted. Same payload is also returned by [grantControl] from
     * the sharer side — flow is here for the controller-side path.
     */
    public val controlGranted: SharedFlow<ControlSession> = _controlGranted.asSharedFlow()

    private val _controlDenied = MutableSharedFlow<ControlDenial>(extraBufferCapacity = 16)
    /** Controller-side: their request was refused. */
    public val controlDenied: SharedFlow<ControlDenial> = _controlDenied.asSharedFlow()

    private val _controlRevoked = MutableSharedFlow<ControlRevocation>(extraBufferCapacity = 16)
    /** Both sides: an active session ended. */
    public val controlRevoked: SharedFlow<ControlRevocation> = _controlRevoked.asSharedFlow()

    private val _controlActive = MutableStateFlow<ControlSession?>(null)
    /**
     * Room-wide visibility of an active control session. Non-null
     * when *some* sharer-controller pair is currently active in the
     * room (one at a time per sharer in current server impl). Use
     * for "X is controlling Y" status badges.
     */
    public val controlActive: StateFlow<ControlSession?> = _controlActive.asStateFlow()

    private val _remoteInput = MutableSharedFlow<RemoteInputEvent>(extraBufferCapacity = 256)
    /**
     * Incoming remote-input events when this peer is the active
     * sharer. The SDK does not auto-inject — translate to whatever
     * the host platform supports (AccessibilityService on Android,
     * `Robot` on Java desktop, etc.).
     */
    public val remoteInput: SharedFlow<RemoteInputEvent> = _remoteInput.asSharedFlow()

    private val _whiteboardAnnotations = MutableSharedFlow<WhiteboardAnnotation>(extraBufferCapacity = 64)
    /**
     * Per-stroke-set freehand annotations from [annotateWhiteboard].
     * Append each [WhiteboardAnnotation] to your in-memory overlay
     * list and re-render. Late joiners can hydrate via [fetchWhiteboard].
     */
    public val whiteboardAnnotations: SharedFlow<WhiteboardAnnotation> = _whiteboardAnnotations.asSharedFlow()

    private val _assistantVoice = MutableSharedFlow<AssistantVoice>(extraBufferCapacity = 32)
    /**
     * TTS-rendered voice clips from the live AI participant. Same
     * playback contract as [translatedAudio] — SDK gives you the
     * bytes, host app decides how to mix into the call's audio
     * routing.
     */
    public val assistantVoice: SharedFlow<AssistantVoice> = _assistantVoice.asSharedFlow()

    private val _corrections = MutableSharedFlow<Correction>(extraBufferCapacity = 16)
    /**
     * Text-correction suggestions, fired in reply to [requestCorrection].
     * Same payload is also returned from [requestCorrection]'s
     * suspending result — the flow is here for consumers that prefer
     * an event-stream model (e.g. multiple inflight drafts).
     */
    public val corrections: SharedFlow<Correction> = _corrections.asSharedFlow()

    private val _translatedAudio = MutableSharedFlow<TranslatedAudio>(extraBufferCapacity = 16)
    /**
     * TTS'd voice-translated audio for the listener's chosen language.
     * Same opt-in as [translations] (via [setTranslationLanguage])
     * but additionally requires the room's `voice-translation`
     * feature to be enabled by the operator. The SDK does not
     * auto-play these — see [TranslatedAudio] for the consumption
     * contract.
     */
    public val translatedAudio: SharedFlow<TranslatedAudio> = _translatedAudio.asSharedFlow()

    private val _activeSpeakers = MutableStateFlow<List<String>>(emptyList())
    /**
     * Rolling top-N most-recent dominant speakers in the room
     * (server-tracked, default N=9 — set by server env). Updates
     * only when the SET changes (re-ordering the same N is filtered
     * out so consumers don't churn).
     *
     * Use this for "speaker grid" UX in interactive rooms with
     * 50–500 peers: subscribe to video tracks for participants in
     * this list, tear down consumers for anyone who fell out.
     * Audio for everyone stays universally subscribed (cheap).
     * Without this filter, each peer would have to decode every
     * other peer's video — physically impossible past ~100 peers
     * on a phone.
     */
    public val activeSpeakers: StateFlow<List<String>> = _activeSpeakers.asStateFlow()

    private val _activeSpeakerId = MutableStateFlow<String?>(null)
    /**
     * The `participantId` of whoever is currently the dominant speaker
     * in the room (server-tracked via mediasoup's ActiveSpeakerObserver,
     * fired ~3× per second). `null` when the room is silent.
     *
     * Useful for highlighting the active speaker's tile, snapping a
     * presentation-mode panel to them, etc. The local participant's
     * own id can show up here too — the server doesn't filter out the
     * local speaker.
     */
    public val activeSpeakerId: StateFlow<String?> = _activeSpeakerId.asStateFlow()

    /** Internal scope cancelled on [leave]. Default dispatcher — never the main thread. */
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    // E2E state. Created on init, replaced never. The keypair is generated
    // synchronously so it's ready before the first key-bundle event arrives.
    private val ecdh = Crypto.generateEcdhKeypair()
    private var roomKey: javax.crypto.SecretKey? = null

    /**
     * E4.1 — server-declared "this room must be E2E media-encrypted".
     * Populated from the join-room ack. When true, the SDK auto-enables
     * SFrame on join (after the room key is ready) and the recording-
     * mode handler flips encryption off for the duration of any
     * recording / broadcast.
     */
    @Volatile public var e2eRequired: Boolean = false
        private set
    private val peerPubKeys = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    // AI / transcription. Both pieces are allocated lazily on
    // startTranscription and torn down on stopTranscription.
    @Volatile private var transcriber: VadEndpointer? = null
    @Volatile private var transcriptionCapture: AudioRecordCapture? = null

    private val rtcIceServers = initialIceServers.toRtcIceServers()

    private val pool = PeerConnectionPool(
        factory = factory,
        transport = transport,
        scope = scope,
        onTrack = ::onRemoteTrack,
        onTracksPruned = ::onP2pTracksPruned,
    ).also { it.setIceServers(rtcIceServers) }

    private val sfu = SfuPipe(
        factory = factory,
        transport = transport,
        scope = scope,
        onTrack = ::onRemoteTrack,
        onProducerClosed = ::onSfuProducerClosed,
    ).also { it.setIceServers(rtcIceServers) }

    init {
        transport.onPeerJoined { obj ->
            val peer = obj.toPeer() ?: return@onPeerJoined
            _peers.update { it + (peer.participantId to peer) }
            _events.tryEmit(RoomEvent.PeerJoined(peer))
            // E4 — E2E *media* encryption is planned for v0.6.0 on Android
            // (needs the native FrameEncryptor JNI bridge). E2E messaging
            // (Message.kt) is fully implemented and works today. Until the
            // native bridge lands, sfu.mediaEncryptionEnabled is always
            // false and there's no per-sender key derivation to do here.
            // E8 — re-announce our camera/mic state so the new peer
            // renders our tile correctly. Defaults are `true` on both
            // sides, so this only matters when we've previously
            // toggled off, but we always send to keep the protocol
            // simple.
            broadcastParticipantState()
            // Newcomer pattern: we don't initiate to them. They'll see us in
            // their join-room ack and offer first; our handleOffer below
            // attaches our local tracks (if we have any) and answers.
        }
        transport.onPeerLeft { obj ->
            val pid = obj["participantId"]?.jsonPrimitive?.contentOrNull ?: return@onPeerLeft
            pool.closePeer(pid)
            _peers.value[pid]?.clearTracks()
            _peers.update { it - pid }
            _events.tryEmit(RoomEvent.PeerLeft(pid))
        }
        // E8 — server told us a peer's camera/mic state. Update the
        // matching [Peer]'s flags and emit [RoomEvent.PeerStateChanged].
        // We always emit, even when neither value differs from the
        // current (defaulted) state, so apps know "I've heard from
        // this peer at least once" rather than relying on defaults.
        transport.onParticipantStateChanged { obj ->
            val pid = obj["participantId"]?.jsonPrimitive?.contentOrNull ?: return@onParticipantStateChanged
            val peer = _peers.value[pid] ?: return@onParticipantStateChanged
            obj["videoEnabled"]?.jsonPrimitive?.booleanOrNull?.let { peer.setVideoEnabled(it) }
            obj["audioEnabled"]?.jsonPrimitive?.booleanOrNull?.let { peer.setAudioEnabled(it) }
            _events.tryEmit(RoomEvent.PeerStateChanged(peer))
        }
        transport.onRoomModeChanged { obj ->
            val raw = obj["mode"]?.jsonPrimitive?.contentOrNull ?: return@onRoomModeChanged
            val newMode = if (raw.equals("sfu", ignoreCase = true)) Mode.SFU else Mode.P2P
            val rtpCaps = (obj["rtpCapabilities"] as? JsonObject)?.toString()
            _mode.value = newMode
            _events.tryEmit(RoomEvent.ModeChanged(newMode))
            if (newMode == Mode.SFU && rtpCaps != null) {
                scope.launch { enterSfuMode(rtpCaps) }
            }
        }
        transport.onDisconnected { reason ->
            _events.tryEmit(RoomEvent.Disconnected(reason))
        }

        // P2P signaling. Each handler is wrapped because a thrown
        // exception inside a coroutine launched on `scope` propagates to
        // the JVM's default uncaught-exception handler — which crashes
        // the host app. Signaling errors are recoverable: log + drop the
        // event and let the next offer/answer round-trip catch us up.
        transport.onOffer { from, sdp ->
            scope.launch {
                try {
                    val parsed = SignalingCodec.parseSdp(sdp) ?: return@launch
                    pool.handleOffer(from, parsed)
                } catch (t: Throwable) {
                    android.util.Log.w("TwoStarsSDK", "handleOffer($from) failed", t)
                }
            }
        }
        transport.onAnswer { from, sdp ->
            scope.launch {
                try {
                    val parsed = SignalingCodec.parseSdp(sdp) ?: return@launch
                    pool.handleAnswer(from, parsed)
                } catch (t: Throwable) {
                    android.util.Log.w("TwoStarsSDK", "handleAnswer($from) failed", t)
                }
            }
        }
        transport.onIceCandidate { from, cand ->
            val parsed = SignalingCodec.parseIce(cand) ?: return@onIceCandidate
            pool.handleIceCandidate(from, parsed)
        }

        // Active speaker — server's mediasoup observer broadcasts the
        // dominant speaker's participantId on each change.
        transport.onActiveSpeakers { speakers ->
            safe("active-speakers") {
                _activeSpeakers.value = speakers
                // D1.1 — drives consume/close for video tracks when in
                // 'top-n' mode. No-op when mode is 'all'.
                scope.launch { runCatching { sfu.applyTopN(speakers) } }
            }
        }

        // E4.1 — server tells us a recording / broadcast is starting
        // (enabled=false) or ending (enabled=true) on an E2E room.
        // Transparently flip SFrame + republish local producers so
        // the recorder captures plaintext for the duration. Re-emits
        // as a public RoomEvent so consumer apps can show a "E2E
        // paused for recording" disclosure banner.
        transport.onE2eRecordingMode { enabled, reason, jobId, kind ->
            safe("e2e-recording-mode") {
                _events.tryEmit(RoomEvent.E2eRecordingMode(enabled, reason, jobId, kind))
                scope.launch {
                    runCatching {
                        // Flip the encryption flag FIRST so the
                        // republish picks up the new state when
                        // creating fresh producers.
                        if (enabled) setMediaEncryption(true) else setMediaEncryption(false)
                        republishLocalTracks()
                    }
                }
            }
        }

        // E6 — moderation events. Re-emit as RoomEvents for consumer
        // app UI; for force-muted ALSO mutate the local track so the
        // camera/mic actually stops emitting (with the user notified
        // via the public event).
        transport.onKicked { reason, by ->
            safe("kicked") { _events.tryEmit(RoomEvent.Kicked(reason, by)) }
            // Server force-disconnects ~150ms later; the existing
            // onDisconnected handler will fire RoomEvent.Disconnected.
        }
        transport.onForceMuted { kind, by ->
            safe("force-muted") {
                _localMedia.value?.let { media ->
                    when (kind) {
                        "audio" -> media.audioTrack?.setEnabled(false)
                        "video" -> media.videoTrack?.setEnabled(false)
                    }
                }
                _events.tryEmit(RoomEvent.ForceMuted(kind, by))
            }
        }
        transport.onBanned { reason, bannedUntil, by ->
            safe("banned") { _events.tryEmit(RoomEvent.Banned(reason, bannedUntil, by)) }
        }
        transport.onRoomLocked { locked, by ->
            safe("room-locked") { _events.tryEmit(RoomEvent.RoomLocked(locked, by)) }
        }
        transport.onModerationEvent { kind, by, target, extras ->
            safe("moderation-event") {
                // Convert JSONObject extras into a Map<String, Any?>
                // so consumer apps can read them without org.json deps.
                val extrasMap = mutableMapOf<String, Any?>()
                val keys = extras.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "kind" && k != "by" && k != "targetParticipantId") {
                        extrasMap[k] = extras.opt(k)
                    }
                }
                _events.tryEmit(RoomEvent.ModerationEvent(kind, by, target, extrasMap))
            }
        }
        transport.onActiveSpeaker { pid ->
            _activeSpeakerId.value = pid
        }

        // E2E messaging — key bundle exchange + inbound message decrypt.
        transport.onKeyBundle { from, bundle ->
            scope.launch {
                try { handleKeyBundle(from, bundle) }
                catch (t: Throwable) { android.util.Log.w("TwoStarsSDK", "key-bundle from $from failed", t) }
            }
        }
        transport.onNewMessage { obj ->
            scope.launch {
                try { handleInboundMessage(obj) }
                catch (t: Throwable) { android.util.Log.w("TwoStarsSDK", "new-message decrypt failed", t) }
            }
        }

        // AI — server fans out one `transcription` event per VAD-endpointed
        // utterance to every participant in the room (including the speaker).
        transport.onTranscription { obj ->
            safe("transcription") { parseTranscript(obj)?.let { _transcripts.tryEmit(it) } }
        }
        transport.onTranslation { obj ->
            safe("translation") { parseTranslation(obj)?.let { _translations.tryEmit(it) } }
        }
        transport.onTranslatedAudio { obj ->
            safe("translated-audio") { parseTranslatedAudio(obj)?.let { _translatedAudio.tryEmit(it) } }
        }
        transport.onFactCheck { obj ->
            safe("fact-check") { parseFactCheck(obj)?.let { _factChecks.tryEmit(it) } }
        }
        transport.onCorrectionSuggestion { obj ->
            safe("correction-suggestion") { parseCorrection(obj)?.let { _corrections.tryEmit(it) } }
        }
        transport.onHebbsResponse { obj ->
            safe("hebbs-response") { parseAssistantResponse(obj)?.let { _assistantResponses.tryEmit(it) } }
        }
        transport.onHebbsMessage { obj ->
            safe("hebbs-message") { parseAssistantMessage(obj)?.let { _assistantMessages.tryEmit(it) } }
        }
        transport.onAiParticipantJoined { obj ->
            safe("ai-participant-joined") { _assistantParticipant.value = parseAssistantParticipant(obj) }
        }
        transport.onAiParticipantUpdated { obj ->
            safe("ai-participant-updated") { _assistantParticipant.value = parseAssistantParticipant(obj) }
        }
        transport.onAiParticipantLeft { _ ->
            safe("ai-participant-left") { _assistantParticipant.value = null }
        }
        transport.onAiVoice { obj ->
            safe("ai-voice") { parseAssistantVoice(obj)?.let { _assistantVoice.tryEmit(it) } }
        }
        transport.onWhiteboardUpdate { obj ->
            safe("whiteboard-update") { parseWhiteboardUpdate(obj)?.let { _whiteboardUpdates.tryEmit(it) } }
        }
        transport.onWhiteboardAnnotation { obj ->
            safe("whiteboard-annotations") { parseWhiteboardAnnotation(obj)?.let { _whiteboardAnnotations.tryEmit(it) } }
        }
        transport.onControlRequestIncoming { obj ->
            safe("control-request-incoming") { parseControlRequest(obj)?.let { _controlRequests.tryEmit(it) } }
        }
        transport.onControlGranted { obj ->
            safe("control-granted") { parseControlSession(obj)?.let { _controlGranted.tryEmit(it) } }
        }
        transport.onControlDenied { obj ->
            safe("control-denied") { parseControlDenial(obj)?.let { _controlDenied.tryEmit(it) } }
        }
        transport.onControlRevoked { obj ->
            safe("control-revoked") { parseControlRevocation(obj)?.let { _controlRevoked.tryEmit(it) } }
        }
        transport.onControlActive { obj ->
            safe("control-active") { _controlActive.value = parseControlSession(obj) }
        }
        transport.onControlInactive { _ ->
            safe("control-inactive") { _controlActive.value = null }
        }
        transport.onRemoteInput { obj ->
            safe("remote-input") { parseRemoteInput(obj)?.let { _remoteInput.tryEmit(it) } }
        }

        // P2P out-of-band metadata: when a remote announces an upcoming
        // screen-share track via this hint, store the stream-id mapping
        // so the pool's onAddTrack routes that track to Peer.screenTrack.
        transport.onP2pMediatypeInfo { streamId, mediaType ->
            pool.setStreamMediaType(streamId, mediaType)
        }

        // SFU.
        transport.onNewProducer { obj ->
            val info = SfuPipe.NewProducerInfo(
                producerId    = obj["producerId"]?.jsonPrimitive?.contentOrNull ?: return@onNewProducer,
                participantId = obj["participantId"]?.jsonPrimitive?.contentOrNull ?: return@onNewProducer,
                kind          = obj["kind"]?.jsonPrimitive?.contentOrNull,
                mediaType     = obj["mediaType"]?.jsonPrimitive?.contentOrNull,
            )
            scope.launch { sfu.onNewProducer(info) }
        }
        transport.onProducerClosed { obj ->
            val pid = obj["producerId"]?.jsonPrimitive?.contentOrNull ?: return@onProducerClosed
            sfu.onProducerClosedRemote(pid)
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
            transport.emitWithAck("join-room", buildJsonObject { put("roomCode", JsonPrimitive(roomCode)) }) { ack ->
                if (ack == null) {
                    if (cont.isActive) cont.resumeWithException(JoinException("server did not ack join-room"))
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

                val initialPeers = (ack["peers"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.toPeer() }
                    .orEmpty()
                if (initialPeers.isNotEmpty()) {
                    _peers.update { existing ->
                        existing + initialPeers.associateBy { it.participantId }
                    }
                }

                // D1.1 — seed top-N from join ack so a 'top-n' subscriber's
                // first list-producers pass admits the right video tracks
                // without waiting for the next dominantspeaker change
                // (which a quiet room may never produce).
                val recent = (ack["recentSpeakers"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                if (recent.isNotEmpty()) {
                    _activeSpeakers.value = recent
                    sfu.seedTopN(recent)
                }

                // E2E key bootstrap. Two paths:
                //   (a) Joined an empty room → mint room key locally + set
                //       isRoomKeyReady=true. We become the key oracle; future
                //       joiners' pubkeys get wrapped + sent back via
                //       handleKeyBundle.
                //   (b) Joined a non-empty room → broadcast our pubkey via
                //       key-bundle. Whichever existing peer holds the key will
                //       reply with a wrapped room-key envelope, and our
                //       handleKeyBundle path will unwrap + flip isRoomKeyReady.
                if (initialPeers.isEmpty()) {
                    roomKey = Crypto.generateRoomKey()
                    _isRoomKeyReady.value = true
                } else {
                    transport.emit("key-bundle", buildJsonObject {
                        put("bundle", buildJsonObject {
                            put("type", JsonPrimitive("pubkey"))
                            put("pubKey", ecdh.pubJwk)
                        })
                    })
                }

                // E6 — token-declared role. 'moderator' unlocks the
                // kick/mute/ban/lock methods; default 'participant'.
                role = ack["role"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it == "moderator" } ?: "participant"

                // E4.1 — server-declared "this room must be E2E media-encrypted".
                // When true the SDK auto-enables SFrame as soon as the room key
                // is ready. The recording-mode handler later flips it back off
                // for the duration of any recording (and back on when stopped).
                e2eRequired = ack["e2eRequired"]?.jsonPrimitive?.boolean == true
                if (e2eRequired) {
                    scope.launch {
                        // Suspend until isRoomKeyReady flips to true (returns
                        // immediately if already true), then enable. `first`
                        // unsubscribes once its predicate matches — won't
                        // leak the collector for the room's lifetime.
                        _isRoomKeyReady.first { it }
                        runCatching { setMediaEncryption(true) }
                    }
                }

                // If the room is already in SFU when we join (3+ peers
                // already there), the join-room ack carries the router's
                // RTP capabilities. Bootstrap the SFU pipe in the
                // background so the caller's await on join() returns
                // immediately — track flows fill in as `new-producer`
                // events arrive.
                if (newMode == Mode.SFU) {
                    val rtpCaps = (ack["rtpCapabilities"] as? JsonObject)?.toString()
                    if (rtpCaps != null) scope.launch { enterSfuMode(rtpCaps) }
                }
                // E7 — start observing process lifecycle so we know
                // when the host app backgrounds. No-op in unit tests
                // where ProcessLifecycleOwner isn't initialized.
                attachLifecycleObserver()

                if (cont.isActive) cont.resume(JoinResult(roomCode, newMode, initialPeers))
            }
        }

    // --- Subscription policy (D1.1) -----------------------------------------

    /** Subscription mode for inbound video tracks. */
    public enum class VideoSubscriptionMode {
        /**
         * Consume every video track the server announces. Right for
         * small rooms (≤ ~50 peers) where every face is on screen.
         */
        ALL,

        /**
         * Only consume video / screen for participants currently in the
         * server-tracked active-speakers set (audio is always consumed
         * for everyone). Receiver bandwidth and decoder load scale with
         * the configured top-N (server: `SFU_ACTIVE_TOP_N`, default 9)
         * instead of room size — required for rooms ≥ 100 peers.
         *
         * The set is seeded from the join-room ack and updated by
         * `active-speakers` broadcasts; switching mode re-evaluates
         * existing subscriptions immediately.
         */
        TOP_N,
    }

    /**
     * Choose how the SDK manages remote video subscriptions. See
     * [VideoSubscriptionMode] for semantics.
     *
     * Switching ALL → TOP_N closes video consumers for participants
     * outside the current top-N. Switching TOP_N → ALL re-consumes any
     * video producers that were deferred. Audio is never affected.
     */
    public suspend fun setVideoSubscriptionMode(mode: VideoSubscriptionMode) {
        sfu.setSubscriptionMode(if (mode == VideoSubscriptionMode.TOP_N) "top-n" else "all")
    }

    // --- E4 — End-to-end media encryption ----------------------------------

    /**
     * Toggle SFrame end-to-end media encryption. When on, every
     * outgoing audio/video frame is encrypted with AES-256-GCM under
     * a per-sender key derived from the room's E2E key (the same key
     * chat already uses). The SFU sees only opaque ciphertext.
     *
     * Requirements:
     *   - The room key must be ready (await `isRoomKeyReady`).
     *   - Encryption applies to NEW producers/consumers — already-
     *     active media keeps its plaintext path because libwebrtc
     *     doesn't allow swapping a FrameEncryptor mid-stream. Toggle
     *     before publishing or call leave/rejoin for a hard cut.
     *
     * Throws if the room key isn't ready yet.
     */
    public suspend fun setMediaEncryption(enabled: Boolean) {
        // E2E *media* encryption (SFrame) is planned for v0.6.0 on Android.
        // The native libwebrtc FrameEncryptor interface is JNI-only —
        // a pure-Kotlin implementation isn't reachable without an
        // accompanying native bridge, which the SDK doesn't yet ship.
        //
        // The JS / Web SDK has the full implementation today; cross-
        // platform interop for the media plane will land when this
        // Android-side bridge is in.
        //
        // E2E *messaging* (Message.kt — AES-GCM payload encryption) works
        // today and is independent of this.
        if (enabled) {
            throw UnsupportedOperationException(
                "E2E media encryption is not yet supported on Android (planned for v0.6.0). " +
                "End-to-end-encrypted MESSAGES still work via Message.kt."
            )
        }
        // Disable is a no-op — nothing to unhook because nothing was hooked.
    }

    /**
     * Toggle the local camera and broadcast the change to peers.
     *
     * Why this exists: setting `track.enabled = false` on the local
     * video track keeps the SFU/peer-connection alive and just sends
     * black frames over the wire. From the receive side that looks
     * identical to "the user is staring at a dark room" — there is no
     * observable WebRTC event that says "the publisher chose to turn
     * off their camera." Apps that want to render an avatar
     * placeholder on the peer's tile need this state out-of-band.
     * Going through the SDK keeps the flag-flip and the broadcast in
     * one place, so consumers can't accidentally desync the two.
     *
     * Idempotent: calling with the current value is a no-op (no track
     * mutation, no socket emit). Safe to call before [publish] — the
     * intent is recorded and applied to tracks when they exist.
     *
     * Mirrors `LocalMedia.setVideoEnabled` for the in-process flip,
     * but additionally tells peers about it. Use this method
     * (preferred) instead of `LocalMedia.setVideoEnabled` directly
     * unless you specifically don't want peers to know.
     */
    public fun setCameraEnabled(enabled: Boolean) {
        if (_videoEnabled.value == enabled) return
        _videoEnabled.value = enabled
        _localMedia.value?.setVideoEnabled(enabled)
        broadcastParticipantState(videoEnabled = enabled)
    }

    /** As [setCameraEnabled], but for the microphone. */
    public fun setMicEnabled(enabled: Boolean) {
        if (_audioEnabled.value == enabled) return
        _audioEnabled.value = enabled
        // LocalMedia models mic state as `isMuted` (inverse of enabled).
        _localMedia.value?.setMuted(!enabled)
        broadcastParticipantState(audioEnabled = enabled)
    }

    /** Last value passed to [setCameraEnabled] (default: `true`). */
    public fun isCameraEnabled(): Boolean = _videoEnabled.value

    /** Last value passed to [setMicEnabled] (default: `true`). */
    public fun isMicEnabled(): Boolean = _audioEnabled.value

    /**
     * Tell peers about our current camera/mic state. Server fans this
     * out as `participant-state-changed` to other sockets in the room.
     * No ack — fire-and-forget; the server doesn't persist it. If
     * either flag is `null`, the peer keeps the prior value for that
     * field; with both `null` we send our cached pair (used on
     * peer-joined re-broadcast).
     */
    private fun broadcastParticipantState(
        videoEnabled: Boolean? = null,
        audioEnabled: Boolean? = null,
    ) {
        val payload = buildJsonObject {
            val v = videoEnabled ?: _videoEnabled.value
            val a = audioEnabled ?: _audioEnabled.value
            put("videoEnabled", JsonPrimitive(v))
            put("audioEnabled", JsonPrimitive(a))
        }
        runCatching { transport.emit("participant-state", payload) }
    }

    /** True if E2E media encryption is enabled. */
    public val isMediaEncryptionEnabled: Boolean get() = sfu.mediaEncryptionEnabled

    // --- E6 Moderation -----------------------------------------------------

    /**
     * E6 — participant role from JWT claim (server-enforced; we cache
     * it client-side for UX gating). 'moderator' unlocks the kick/mute/
     * ban/lock methods. Default 'participant'. Set when join-room ack
     * arrives.
     */
    @Volatile public var role: String = "participant"
        private set

    /** True iff this participant joined as a moderator. */
    public val isModerator: Boolean get() = role == "moderator"

    /**
     * Force a peer out of the room. Server emits 'kicked' to them
     * + disconnects their socket. Throws IllegalStateException when
     * called by a non-moderator (server would also reject).
     */
    public suspend fun kickPeer(participantId: String, reason: String? = null): JsonObject? {
        requireModerator("kickPeer")
        return transport.emitWithAckSuspending("mod-kick", buildJsonObject {
            put("targetParticipantId", JsonPrimitive(participantId))
            if (reason != null) put("reason", JsonPrimitive(reason))
        })
    }

    /**
     * Force a peer's mic ('audio') or camera ('video') off. Server
     * emits 'force-muted' to them + the SDK on their side flips
     * track.enabled=false locally so the camera light goes off.
     */
    public suspend fun mutePeer(participantId: String, kind: String): JsonObject? {
        requireModerator("mutePeer")
        require(kind == "audio" || kind == "video") { "kind must be 'audio' or 'video'" }
        return transport.emitWithAckSuspending("mod-mute", buildJsonObject {
            put("targetParticipantId", JsonPrimitive(participantId))
            put("kind", JsonPrimitive(kind))
        })
    }

    /**
     * Ban a peer + force-disconnect. [durationMs] = null → permanent
     * (until manual unban). Re-join attempts with the same
     * participantId are refused server-side until the ban expires.
     */
    public suspend fun banPeer(
        participantId: String,
        durationMs: Long? = null,
        reason: String? = null,
    ): JsonObject? {
        requireModerator("banPeer")
        return transport.emitWithAckSuspending("mod-ban", buildJsonObject {
            put("targetParticipantId", JsonPrimitive(participantId))
            if (durationMs != null) put("durationMs", JsonPrimitive(durationMs))
            if (reason != null) put("reason", JsonPrimitive(reason))
        })
    }

    /** Remove an active ban. */
    public suspend fun unbanPeer(participantId: String): JsonObject? {
        requireModerator("unbanPeer")
        return transport.emitWithAckSuspending("mod-unban", buildJsonObject {
            put("targetParticipantId", JsonPrimitive(participantId))
        })
    }

    /**
     * Lock / unlock the room. Locked rooms refuse new joiners
     * (moderators bypass the lock so the host can recover from
     * disconnects). Existing participants stay.
     */
    public suspend fun lockRoom(locked: Boolean = true): JsonObject? {
        requireModerator("lockRoom")
        return transport.emitWithAckSuspending("mod-lock-room", buildJsonObject {
            put("locked", JsonPrimitive(locked))
        })
    }

    private fun requireModerator(method: String) {
        check(role == "moderator") { "$method: not a moderator (role=$role)" }
    }

    /**
     * E8 — diagnostic snapshot of the local WebRTC video encoder
     * backend ("hardware" / "software"), set at first connect time
     * via [StarsClient.configureWebRTC]. Useful for "battery may
     * drain faster — tap to switch to lower quality" UX hints.
     */
    public val encoderInfo: io.twostars.sdk.EncoderInfo
        get() = factory.encoderInfo

    // --- E7 Background-tab / lifecycle resilience -------------------------

    /**
     * True while the host app is in the background (after onStop /
     * before onStart on ProcessLifecycleOwner). The matching
     * [RoomEvent.VisibilityChanged] event fires on every transition.
     *
     * The Android equivalent of the JS Page Visibility API integration.
     * Doze + background-task throttling can stall the socket heartbeat;
     * this lets consumer apps surface "Reconnecting..." UI promptly,
     * and the SDK uses it to optionally pause local video to save
     * bandwidth + battery (off by default — see [configurePresence]).
     *
     * For active calls that must survive backgrounding (the user
     * expects the call to keep running even while they jump to
     * another app), the host activity should bind a foreground
     * service — see the sample app's `CallService`.
     */
    @Volatile public var isBackgrounded: Boolean = false
        private set

    @Volatile private var pauseVideoOnBackground: Boolean = false
    @Volatile private var pausedVideoOnBackground: Boolean = false
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    /**
     * E7 — opt into "pause local video while backgrounded" behaviour.
     * Audio is never auto-muted (the user expects to keep hearing the
     * meeting and being heard); video resumes automatically on
     * foreground. Tracks the user explicitly disabled stay disabled
     * — we only un-pause what we paused.
     */
    public fun configurePresence(pauseVideoOnBackground: Boolean) {
        this.pauseVideoOnBackground = pauseVideoOnBackground
    }

    // Called from join() once we're connected; observes the global
    // process lifecycle (not a specific Activity) so backgrounding via
    // either the back button OR home button OR app switcher all fire.
    private fun attachLifecycleObserver() {
        if (lifecycleObserver != null) return
        val obs = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { onBackgrounded() }
            override fun onStart(owner: LifecycleOwner) { onForegrounded() }
        }
        lifecycleObserver = obs
        // Must observe on main thread.
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
            }
        } catch (_: Throwable) { /* no lifecycle owner in tests */ }
    }

    private fun detachLifecycleObserver() {
        val obs = lifecycleObserver ?: return
        lifecycleObserver = null
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(obs)
            }
        } catch (_: Throwable) {}
    }

    private fun onBackgrounded() {
        if (isBackgrounded) return
        isBackgrounded = true
        if (pauseVideoOnBackground) {
            _localMedia.value?.let { media ->
                media.videoTrack?.let { vt ->
                    if (vt.enabled()) {
                        vt.setEnabled(false)
                        pausedVideoOnBackground = true
                    }
                }
            }
        }
        runCatching {
            transport.emit("visibility", buildJsonObject { put("visible", JsonPrimitive(false)) })
        }
        _events.tryEmit(RoomEvent.VisibilityChanged(visible = false))
    }

    private fun onForegrounded() {
        if (!isBackgrounded) return
        isBackgrounded = false
        if (pausedVideoOnBackground) {
            _localMedia.value?.videoTrack?.setEnabled(true)
            pausedVideoOnBackground = false
        }
        runCatching {
            transport.emit("visibility", buildJsonObject { put("visible", JsonPrimitive(true)) })
        }
        _events.tryEmit(RoomEvent.VisibilityChanged(visible = true))
    }

    // --- A8.2 / A8.3 — virtual background + auto-frame --------------------

    @Volatile private var _backgroundProcessor: BackgroundProcessor? = null

    /**
     * A8.2 — install a virtual background.
     *   - [BackgroundProcessor.BackgroundMode.BLUR] gaussian-blurs the
     *     original frame and masks the user back over it.
     *   - [BackgroundProcessor.BackgroundMode.IMAGE] composites the
     *     supplied [bitmap] behind the user. Pass `null` for a black bg.
     *   - [BackgroundProcessor.BackgroundMode.OFF] = pass-through (use
     *     [clearVirtualBackground] for clarity).
     *
     * The processor is installed on the local camera's frame pipeline
     * and runs on the WebRTC capture thread (~33ms budget at 30fps).
     * If the segmentation model consistently misses the budget on the
     * device, the processor self-disables and falls back to pass-
     * through — see [BackgroundProcessor.Config].
     *
     * Mirrors the JS SDK's `room.setVirtualBackground(...)` API.
     *
     * @param mode    blur, image-replace, or off
     * @param bitmap  required when [mode] = IMAGE; ignored otherwise
     */
    public fun setVirtualBackground(
        mode: BackgroundProcessor.BackgroundMode,
        bitmap: android.graphics.Bitmap? = null,
    ) {
        val media = _localMedia.value ?: return
        val ctx = context.applicationContext
        if (mode == BackgroundProcessor.BackgroundMode.OFF) {
            clearVirtualBackground()
            return
        }
        val proc = _backgroundProcessor ?: BackgroundProcessor(ctx).also {
            _backgroundProcessor = it
            media.setVideoProcessor(it)
        }
        proc.setReplacementBitmap(bitmap)
        proc.setMode(mode)
    }

    /**
     * High-level background generation: ask the server's image-gen
     * plugin (Gemini by default) to render a virtual background from
     * a natural-language [prompt], then composite it behind the local
     * camera via a [BackgroundProcessor]. Mirrors the JS SDK's
     * `room.setBackground(prompt)`.
     *
     * Server contract: emits `generate-background` with `{prompt}`,
     * expects `{ok: true, imageUrl: "data:image/...;base64,..."}`. The
     * server-side AbortController bounds the upstream Gemini call at
     * 25 s; this method's [AI_ACK_TIMEOUT_MS] caps the round-trip at
     * 30 s. Throws on timeout, server error, malformed response, or
     * undecodable image.
     *
     * Requires [publish] to have been called first (we need a video
     * track to wrap). Throws [IllegalStateException] otherwise.
     */
    public suspend fun setBackground(prompt: String): Unit =
        kotlinx.coroutines.withTimeout(AI_ACK_TIMEOUT_MS) {
            val media = _localMedia.value
                ?: throw IllegalStateException("setBackground: call publish() first")
            val ack = transport.emitWithAckSuspending("generate-background", buildJsonObject {
                put("prompt", JsonPrimitive(prompt))
            }) ?: throw IllegalStateException("generate-background: no ack")
            if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "generate-background failed"
                throw IllegalStateException(err)
            }
            val imageUrl = ack["imageUrl"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("generate-background: missing imageUrl")
            val bitmap = decodeDataUrlToBitmap(imageUrl)
                ?: throw IllegalStateException("generate-background: could not decode image")
            val ctx = context.applicationContext
            val proc = _backgroundProcessor ?: BackgroundProcessor(ctx).also {
                _backgroundProcessor = it
                media.setVideoProcessor(it)
            }
            proc.setReplacementBitmap(bitmap)
            proc.setMode(BackgroundProcessor.BackgroundMode.IMAGE)
        }

    /**
     * Decode an `imageUrl` of the form `data:image/<type>;base64,<...>`
     * into a [android.graphics.Bitmap]. Returns `null` on a malformed
     * URL or undecodable bytes. Server-side image generation always
     * returns this format; we don't fetch HTTP URLs to avoid an
     * accidental network leg.
     */
    private fun decodeDataUrlToBitmap(dataUrl: String): android.graphics.Bitmap? {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return null
        val payload = dataUrl.substring(comma + 1)
        return runCatching {
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** Remove the virtual background and revert to the raw camera frame. */
    public fun clearVirtualBackground() {
        val proc = _backgroundProcessor
        _backgroundProcessor = null
        proc?.setMode(BackgroundProcessor.BackgroundMode.OFF)
        // If auto-frame is also active, keep it attached; otherwise
        // detach from the camera entirely.
        if (_autoFrameProcessor == null) _localMedia.value?.setVideoProcessor(null)
    }

    /**
     * Alias for [clearVirtualBackground] that mirrors the JS SDK's
     * `room.clearBackground()` naming, kept consistent with the
     * high-level [setBackground] above.
     */
    public fun clearBackground() {
        clearVirtualBackground()
    }

    @Volatile private var _autoFrameProcessor: AutoFrameProcessor? = null

    /**
     * A8.3 — toggle face-tracking smart crop ("auto-frame"). When [on],
     * the local camera is wrapped in a processor that:
     *   - runs ML Kit FaceDetection at ~5fps
     *   - picks the largest face as the primary speaker (with
     *     hysteresis so a sneeze doesn't change the framing)
     *   - smoothly crops + scales the frame so the face occupies ~30%
     *     of the shorter dimension
     *
     * Mirrors the JS SDK's `videoProcessor` auto-frame mode. Edge
     * cases handled by [AutoFrameProcessor]:
     *   - no face seen for 2s → smoothly revert to full frame
     *   - multiple faces → largest, with switch hysteresis
     *
     * NOTE: only one of [setAutoFrame] / [setVirtualBackground] can be
     * active at a time — the second installs over the first. Combining
     * them in one processor is a follow-up (compose the segmentation
     * mask through the auto-frame crop), tracked separately.
     */
    public fun setAutoFrame(on: Boolean) {
        val media = _localMedia.value ?: return
        val ctx = context.applicationContext
        if (!on) {
            val proc = _autoFrameProcessor
            _autoFrameProcessor = null
            proc?.setEnabled(false)
            if (_backgroundProcessor == null) media.setVideoProcessor(null)
            return
        }
        val proc = _autoFrameProcessor ?: AutoFrameProcessor(ctx).also {
            _autoFrameProcessor = it
            media.setVideoProcessor(it)
        }
        proc.setEnabled(true)
    }

    // E4.1 — close existing camera-audio/video producers and republish
    // them so they pick up the current mediaEncryptionEnabled state.
    // Used by the recording-mode disable/enable round-trip — libwebrtc
    // doesn't allow detaching a FrameEncryptor mid-stream so we have
    // to close + recreate.
    internal suspend fun republishLocalTracks() {
        if (!sfu.isInSfuMode()) return
        val media = _localMedia.value ?: return
        sfu.closeLocalCameraProducers()
        val tracks = listOfNotNull(media.audioTrack, media.videoTrack)
        for (track in tracks) runCatching { sfu.produceTrack(track) }
    }

    // --- Screen share API ----------------------------------------------------

    /**
     * Begin sharing the device screen with the room. Requires:
     *  - the caller to have already shown the system MediaProjection
     *    consent prompt and gotten `Activity.RESULT_OK` — pass the
     *    resulting `data` Intent here as [consentIntent];
     *  - the host activity to have a foreground service running
     *    declaring `foregroundServiceType` includes `mediaProjection`
     *    (Android 14+ requirement). The sample's `CallService` does this.
     *
     * Returns a [ScreenShareSession] handle. Bind its `videoTrack` to
     * a `SurfaceViewRenderer` for a local self-preview. The track is
     * automatically published to all peers as a screen-typed producer
     * so receivers can route it onto `Peer.screenTrack`.
     *
     * **A5 limitation:** screen share publishes via the SFU, so the
     * room must already be in SFU mode (3+ peers) for it to reach
     * remote peers. In a pure 2-peer P2P call, [startScreenShare]
     * succeeds locally but no peer receives it until a 3rd participant
     * promotes the room. Adding P2P screen-share publishing is queued
     * for a follow-up — most production demos already have ≥3 peers
     * by the time anyone needs to share a screen.
     *
     * Throws [IllegalStateException] if already sharing.
     */
    public suspend fun startScreenShare(consentIntent: Intent): ScreenShareSession {
        check(_screenShare.value == null) { "already sharing screen — call stopScreenShare() first" }

        // Android 14+ requires the foreground service hosting the call
        // to declare type=mediaProjection BEFORE MediaProjection.createVirtualDisplay
        // is invoked. The service can't carry that bit at idle (no consent
        // → SecurityException), so we bump it now while the consent is
        // fresh. No-op if the host runs its own foreground service.
        CallForegroundService.enableMediaProjection(context)

        val capture = try {
            ScreenCapture.create(
                context = context,
                factory = factory,
                consentIntent = consentIntent,
                onProjectionStopped = {
                    // System-initiated stop (notification "Stop sharing",
                    // incoming-call interrupt, OS revoke). Tear down on
                    // the room scope so we don't block the WebRTC
                    // callback thread.
                    scope.launch { stopScreenShare(ScreenShareEndReason.SYSTEM_STOPPED) }
                },
            )
        } catch (t: Throwable) {
            // Capture failed to start (consent invalidated, no display,
            // OOM on the encoder…). Drop the projection type bit we
            // just added so the FGS state stays consistent, then surface
            // a CAPTURE_FAILED event for observers that subscribe
            // before the throw lands at the caller's coroutine, and
            // rethrow so the suspending caller sees the failure inline.
            CallForegroundService.disableMediaProjection(context)
            _events.tryEmit(RoomEvent.ScreenShareEnded(ScreenShareEndReason.CAPTURE_FAILED))
            throw t
        }
        val session = ScreenShareSession(capture, streamId = "screen-${java.util.UUID.randomUUID()}")
        _screenShare.value = session

        if (sfu.isInSfuMode()) {
            sfu.produceTrack(capture.track, mediaType = "screen")
        } else {
            // P2P: announce the stream-id → 'screen' mapping to every
            // remote BEFORE addTrack so their pool routes the next
            // inbound track onto Peer.screenTrack. Then add + renegotiate.
            for (peerId in _peers.value.keys) {
                transport.emit("p2p-mediatype-info", buildJsonObject {
                    put("to", JsonPrimitive(peerId))
                    put("streamId", JsonPrimitive(session.streamId))
                    put("mediaType", JsonPrimitive("screen"))
                })
            }
            pool.addScreenTrackAndRenegotiate(capture.track, session.streamId)
        }
        return session
    }

    /**
     * Stop the active screen share. Idempotent.
     *
     * Emits a [RoomEvent.ScreenShareEnded] with the supplied [reason].
     * Public callers should leave [reason] at its default —
     * [ScreenShareEndReason.SYSTEM_STOPPED] is reserved for the SDK's
     * internal projection callback.
     */
    @JvmOverloads
    public suspend fun stopScreenShare(
        reason: ScreenShareEndReason = ScreenShareEndReason.USER_REQUESTED,
    ) {
        val session = _screenShare.value ?: return
        _screenShare.value = null
        if (sfu.isInSfuMode()) {
            sfu.unproduceByKey("screen-video")
        } else {
            // P2P: drop just the screen sender (camera/mic stay).
            // Renegotiation fires `removetrack` on remote peer.stream,
            // their VideoTile clears via the srcObject=null fix.
            pool.removeTrackAndRenegotiate(session.videoTrack)
        }
        session.stop()
        // Drop the MEDIA_PROJECTION type bit from the foreground service
        // — the call continues with camera|microphone only.
        CallForegroundService.disableMediaProjection(context)
        _events.tryEmit(RoomEvent.ScreenShareEnded(reason))
    }

    // --- Transcription API ---------------------------------------------------

    /**
     * Begin streaming local mic audio to the server's STT pipeline.
     *
     * Wire-level behaviour mirrors the JS SDK exactly: a one-shot
     * `enable-transcription` ack handshake, then a stream of
     * VAD-endpointed [audio-chunk] events (one per recognised
     * utterance, encoded as 16 kHz mono WAV, base64'd). Server-side
     * STT runs per chunk and broadcasts a `transcription` event to
     * every participant in the room — including this one — which
     * lands on [transcripts].
     *
     * Side effects:
     *  - Installs an ADM mic sink. Until [stopTranscription] runs,
     *    the same mic frames WebRTC is publishing are also fed into
     *    the VAD endpointer. No second AudioRecord is opened.
     *  - Requires [publish] to have been called first — without an
     *    active mic capture, the ADM doesn't fire SamplesReadyCallback.
     *
     * Idempotent: calling while already running is a no-op.
     *
     * @param language BCP-47 hint (e.g. `"en"`, `"he"`, `"auto"`). Server
     *   uses it as a recognition prior; passing `"auto"` lets the model
     *   detect from the audio.
     */
    public suspend fun startTranscription(language: String = "auto") {
        if (transcriber != null) return
        // 1) Server-side gate — explicit handshake so the backend knows
        //    to expect chunks (and to fan out `transcription` events to
        //    everyone in the room).
        val ack = suspendCancellableCoroutine<JsonObject?> { cont ->
            transport.emitWithAck("enable-transcription", buildJsonObject {
                put("language", JsonPrimitive(language))
            }) { obj -> cont.resume(obj) }
        }
        if (ack == null || ack["ok"]?.jsonPrimitive?.boolean != true) {
            val err = ack?.get("error")?.jsonPrimitive?.contentOrNull ?: "enable-transcription failed"
            throw IllegalStateException(err)
        }

        // 2) Allocate the VAD endpointer. The closure captures
        //    `language` so each emitted chunk reports the same hint
        //    the server is expecting.
        val endpointer = VadEndpointer(
            onUtterance = { samples, sampleRate ->
                val wav = WavEncoder.encode(samples, sampleRate)
                val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
                transport.emit("audio-chunk", buildJsonObject {
                    put("audio", JsonPrimitive(b64))
                    put("mimeType", JsonPrimitive("audio/wav"))
                    put("language", JsonPrimitive(language))
                })
            },
        )
        endpointer.enabled = true

        // 3) Open a dedicated AudioRecord and feed the endpointer.
        //    Independent of WebRTC — works whether or not [publish] has
        //    been called and whether or not the room has any peers.
        val capture = try {
            AudioRecordCapture(
                onSamples = { samples, count, rate ->
                    endpointer.feed(samples, count, rate)
                },
            ).also { it.start() }
        } catch (t: Throwable) {
            try { transport.emit("disable-transcription", buildJsonObject { /* no body */ }) }
            catch (_: Throwable) { /* drop */ }
            throw t
        }

        transcriber = endpointer
        transcriptionCapture = capture
    }

    /**
     * Stop streaming mic audio. Tells the server to drop the
     * per-room transcription state for this socket; remaining chunks
     * from other participants in the room continue to land on
     * [transcripts] (server enables/disables per-socket, not
     * per-room). Idempotent.
     */
    public fun stopTranscription() {
        val endpointer = transcriber ?: return
        endpointer.enabled = false
        transcriber = null
        transcriptionCapture?.stop()
        transcriptionCapture = null
        try { transport.emit("disable-transcription", buildJsonObject { /* no body */ }) }
        catch (_: Throwable) { /* drop */ }
    }

    private fun parseTranscript(obj: JsonObject): Transcript? {
        val speakerId = obj["speaker"]?.jsonPrimitive?.contentOrNull ?: return null
        val text      = obj["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
        val language    = obj["language"]?.jsonPrimitive?.contentOrNull ?: "auto"
        val ts          = parseTimestampMs(obj["timestamp"])
        return Transcript(
            speakerId = speakerId,
            speakerDisplayName = displayName,
            text = text,
            language = language,
            timestampMs = ts,
        )
    }

    /**
     * Opt this client into receiving server-side translations of every
     * room transcription into [language]. Pass `null` (or `"auto"`) to
     * opt out — the server stops sending [translations] / [translatedAudio]
     * to this socket.
     *
     * Setting per-listener; takes effect for the *next* transcription
     * the speaker(s) emit (already-broadcast transcripts are not
     * back-translated).
     *
     * Speaker self-filtering: by default, the server *won't* send a
     * translation of your own speech back to you (it's noise — you
     * already know what you said). Pass [includeSelf] = true to opt
     * out of that filter — useful for accessibility overlays, solo
     * smoke testing, or self-captioning.
     *
     * The ack confirms the language the server stored — useful in
     * case the server canonicalised it (e.g. trimmed length, lowercased).
     */
    @JvmOverloads
    public suspend fun setTranslationLanguage(
        language: String?,
        includeSelf: Boolean = false,
    ): String? =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("set-language", buildJsonObject {
                if (language != null) put("language", JsonPrimitive(language))
                if (includeSelf) put("includeSelf", JsonPrimitive(true))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("set-language: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "set-language failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                cont.resume(ack["language"]?.jsonPrimitive?.contentOrNull)
            }
        }

    private fun parseTranslation(obj: JsonObject): Translation? {
        val speaker = obj["speaker"]?.jsonPrimitive?.contentOrNull ?: return null
        val originalText = obj["originalText"]?.jsonPrimitive?.contentOrNull ?: return null
        val translatedText = obj["translatedText"]?.jsonPrimitive?.contentOrNull ?: return null
        val targetLang = obj["targetLang"]?.jsonPrimitive?.contentOrNull ?: return null
        return Translation(
            speakerId = speaker,
            speakerDisplayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            originalText = originalText,
            originalLanguage = obj["originalLang"]?.jsonPrimitive?.contentOrNull ?: "auto",
            translatedText = translatedText,
            targetLanguage = targetLang,
            timestampMs = parseTimestampMs(obj["timestamp"]),
        )
    }

    /**
     * Ask the room's LLM to clean up [text] — typically a chat draft —
     * for tone, grammar, and clarity. Returns the [Correction] if the
     * LLM had a non-trivial suggestion, or `null` if the request was
     * skipped (rate-limited / empty / too-long / feature-disabled —
     * the reason is logged but not surfaced; callers usually just
     * treat null as "leave the draft alone").
     *
     * The same payload also lands on [corrections] for consumers that
     * subscribe via the flow — useful when multiple draft requests
     * are inflight in parallel.
     */
    @JvmOverloads
    public suspend fun requestCorrection(
        text: String,
        language: String? = null,
        tone: String? = null,
    ): Correction? = suspendCancellableCoroutine { cont ->
        transport.emitWithAck("request-correction", buildJsonObject {
            put("text", JsonPrimitive(text))
            if (language != null) put("language", JsonPrimitive(language))
            if (tone != null) put("tone", JsonPrimitive(tone))
        }) { ack ->
            if (ack == null) {
                cont.resumeWithException(IllegalStateException("request-correction: no ack"))
                return@emitWithAck
            }
            if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "request-correction failed"
                cont.resumeWithException(IllegalStateException(err))
                return@emitWithAck
            }
            // Skipped result has `skipped` set; no event will fire.
            if (ack["skipped"] != null) {
                cont.resume(null)
                return@emitWithAck
            }
            cont.resume(parseCorrection(ack))
        }
    }

    /**
     * Fire a one-shot "Hey Hebbs" command and wait for the structured
     * response. Same payload also lands on [assistantResponses].
     *
     * Throws on transport / server errors; returns null only if the
     * server explicitly refused to classify (rare — normally throws).
     */
    public suspend fun askAssistant(text: String): AssistantResponse? =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("hebbs-command", buildJsonObject {
                put("text", JsonPrimitive(text))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("hebbs-command: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "hebbs-command failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                cont.resume(parseAssistantResponse(ack))
            }
        }

    /**
     * Send an `@Hebbs ...` chat command. The server's reply lands on
     * [assistantMessages] (broadcast room-wide unless [private] = true).
     * The ack response carries the same message for in-flight callers.
     */
    @JvmOverloads
    public suspend fun askAssistantInChat(text: String, private: Boolean = false): AssistantMessage? =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("hebbs-chat-command", buildJsonObject {
                put("text", JsonPrimitive(text))
                if (private) put("private", JsonPrimitive(true))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("hebbs-chat-command: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "hebbs-chat-command failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                val msg = ack["message"] as? JsonObject
                cont.resume(msg?.let { parseAssistantMessage(it) })
            }
        }

    /**
     * Toggle the live AI participant on/off. When enabled, the server
     * spawns a virtual peer the rest of the room sees as a real
     * participant — observe [assistantParticipant] to track its
     * identity, and [assistantVoice] for the TTS audio it emits.
     */
    public suspend fun setAssistantEnabled(enabled: Boolean): AssistantParticipant? =
        // 30 s timeout: spawning the AI participant runs server-side
        // Gemini avatar generation which legitimately takes 10–25 s.
        // Without an upper bound the call would block indefinitely on
        // the rare hung Gemini request.
        kotlinx.coroutines.withTimeout(AI_ACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                transport.emitWithAck("set-ai-assistant", buildJsonObject {
                    put("enabled", JsonPrimitive(enabled))
                }) { ack ->
                    if (ack == null) {
                        cont.resumeWithException(IllegalStateException("set-ai-assistant: no ack"))
                        return@emitWithAck
                    }
                    if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                        val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "set-ai-assistant failed"
                        cont.resumeWithException(IllegalStateException(err))
                        return@emitWithAck
                    }
                    val ai = ack["ai"] as? JsonObject
                    cont.resume(ai?.let { parseAssistantParticipant(it) })
                }
            }
        }

    /**
     * Ask the AI to (re)generate the room's whiteboard SVG from a prompt
     * like `"flowchart of CPU cache"`. The fresh SVG is broadcast to
     * every participant on [whiteboardUpdates]; the caller's own copy
     * is also returned synchronously here.
     *
     * Throws [IllegalStateException] if the room's `whiteboard` feature
     * flag is off, the LLM call fails, or generation returns empty.
     */
    public suspend fun generateWhiteboard(prompt: String): WhiteboardUpdate =
        // 30 s timeout: server-side Gemini SVG generation can take
        // 10–25 s for complex prompts. Same rationale as
        // [setAssistantEnabled] above.
        kotlinx.coroutines.withTimeout(AI_ACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                transport.emitWithAck("whiteboard-command", buildJsonObject {
                    put("text", JsonPrimitive(prompt))
                }) { ack ->
                    if (ack == null) {
                        cont.resumeWithException(IllegalStateException("whiteboard-command: no ack"))
                        return@emitWithAck
                    }
                    if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                        val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "whiteboard-command failed"
                        cont.resumeWithException(IllegalStateException(err))
                        return@emitWithAck
                    }
                    val update = parseWhiteboardUpdate(ack)
                    if (update == null) cont.resumeWithException(IllegalStateException("whiteboard-command: malformed result"))
                    else cont.resume(update)
                }
            }
        }

    /**
     * Push a freehand annotation onto the shared whiteboard. [strokes]
     * is opaque to the SDK — pass whatever JSON shape the rest of the
     * room agrees on (typical: `[ [x,y,x,y,…], … ]` per stroke).
     * Returns the server-assigned annotation id.
     */
    public suspend fun annotateWhiteboard(strokes: JsonArray): String =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("whiteboard-annotate", buildJsonObject {
                put("strokes", strokes)
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("whiteboard-annotate: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "whiteboard-annotate failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                val id = ack["id"]?.jsonPrimitive?.contentOrNull
                if (id == null) cont.resumeWithException(IllegalStateException("whiteboard-annotate: no id in ack"))
                else cont.resume(id)
            }
        }

    /**
     * Fetch the current whiteboard state — useful for late joiners who
     * need to hydrate their canvas before subscribing to the live
     * delta flows.
     */
    public suspend fun fetchWhiteboard(): WhiteboardSnapshot =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("get-whiteboard", buildJsonObject { /* no body */ }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("get-whiteboard: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "get-whiteboard failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                val anns = (ack["annotations"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::parseWhiteboardAnnotation) }
                    .orEmpty()
                cont.resume(WhiteboardSnapshot(
                    svg = ack["svg"]?.jsonPrimitive?.contentOrNull,
                    version = ack["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    prompt = ack["prompt"]?.jsonPrimitive?.contentOrNull,
                    generatedAtMs = ack["generatedAt"]?.let { parseTimestampMs(it) },
                    annotations = anns,
                ))
            }
        }

    /**
     * Ask [targetParticipantId] for permission to remote-control them.
     * Returns the server-issued `requestId`; the target sees a
     * [ControlRequest] on [controlRequests] and replies via
     * [grantControl] / [denyControl]. The grant lands on
     * [controlGranted] for this client.
     *
     * Throws if [targetParticipantId] isn't currently in the room or
     * is this client's own id.
     */
    public suspend fun requestControl(targetParticipantId: String): String =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("request-control", buildJsonObject {
                put("target", JsonPrimitive(targetParticipantId))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("request-control: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "request-control failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                val rid = ack["requestId"]?.jsonPrimitive?.contentOrNull
                if (rid == null) cont.resumeWithException(IllegalStateException("request-control: no requestId in ack"))
                else cont.resume(rid)
            }
        }

    /**
     * Grant a pending control request (sharer side). Returns the
     * established [ControlSession]; the room also gets a
     * [controlActive] update for status badging.
     */
    public suspend fun grantControl(requestId: String): ControlSession =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("grant-control", buildJsonObject {
                put("requestId", JsonPrimitive(requestId))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("grant-control: no ack"))
                    return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "grant-control failed"
                    cont.resumeWithException(IllegalStateException(err))
                    return@emitWithAck
                }
                val sess = parseControlSession(ack)
                if (sess == null) cont.resumeWithException(IllegalStateException("grant-control: malformed session"))
                else cont.resume(sess)
            }
        }

    /** Deny a pending control request. [reason] is shown to the requester. */
    @JvmOverloads
    public suspend fun denyControl(requestId: String, reason: String? = null) {
        suspendCancellableCoroutine<Unit> { cont ->
            transport.emitWithAck("deny-control", buildJsonObject {
                put("requestId", JsonPrimitive(requestId))
                if (reason != null) put("reason", JsonPrimitive(reason))
            }) { ack ->
                if (ack == null) cont.resumeWithException(IllegalStateException("deny-control: no ack"))
                else if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "deny-control failed"
                    cont.resumeWithException(IllegalStateException(err))
                } else cont.resume(Unit)
            }
        }
    }

    /**
     * End the active control session — works from either side. Sharer
     * side: kicks the controller. Controller side: releases their own
     * session. Both sides get a [controlRevoked] event.
     */
    @JvmOverloads
    public suspend fun revokeControl(reason: String? = null) {
        suspendCancellableCoroutine<Unit> { cont ->
            transport.emitWithAck("revoke-control", buildJsonObject {
                if (reason != null) put("reason", JsonPrimitive(reason))
            }) { ack ->
                if (ack == null) cont.resumeWithException(IllegalStateException("revoke-control: no ack"))
                else if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "revoke-control failed"
                    cont.resumeWithException(IllegalStateException(err))
                } else cont.resume(Unit)
            }
        }
    }

    /**
     * Forward a single input event to the active sharer (controller
     * side). Fire-and-forget — input is high-frequency, no ack. The
     * server validates the type + bounds and silently drops malformed
     * events.
     */
    public fun sendRemoteInput(input: RemoteInputEvent) {
        transport.emit("remote-input", buildJsonObject {
            put("type", JsonPrimitive(input.type))
            input.nx?.let     { put("nx", JsonPrimitive(it)) }
            input.ny?.let     { put("ny", JsonPrimitive(it)) }
            input.button?.let { put("button", JsonPrimitive(it)) }
            input.deltaX?.let { put("deltaX", JsonPrimitive(it)) }
            input.deltaY?.let { put("deltaY", JsonPrimitive(it)) }
            input.key?.let    { put("key", JsonPrimitive(it)) }
            input.code?.let   { put("code", JsonPrimitive(it)) }
            input.ctrl?.let   { put("ctrl", JsonPrimitive(it)) }
            input.meta?.let   { put("meta", JsonPrimitive(it)) }
            input.alt?.let    { put("alt", JsonPrimitive(it)) }
            input.shift?.let  { put("shift", JsonPrimitive(it)) }
        })
    }

    private fun parseControlRequest(obj: JsonObject): ControlRequest? {
        val rid = obj["requestId"]?.jsonPrimitive?.contentOrNull ?: return null
        val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return null
        return ControlRequest(
            requestId = rid,
            fromParticipantId = from,
            fromDisplayName = obj["fromDisplayName"]?.jsonPrimitive?.contentOrNull,
            requestedAtMs = parseTimestampMs(obj["requestedAt"]),
        )
    }

    private fun parseControlSession(obj: JsonObject): ControlSession? {
        val sharer = obj["sharerParticipantId"]?.jsonPrimitive?.contentOrNull
            ?: obj["sharer"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val controller = obj["controllerParticipantId"]?.jsonPrimitive?.contentOrNull
            ?: obj["controller"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return ControlSession(
            sharerParticipantId = sharer,
            controllerParticipantId = controller,
            controllerDisplayName = obj["controllerDisplayName"]?.jsonPrimitive?.contentOrNull,
            grantedAtMs = parseTimestampMs(obj["grantedAt"]),
        )
    }

    private fun parseControlDenial(obj: JsonObject): ControlDenial? {
        val rid = obj["requestId"]?.jsonPrimitive?.contentOrNull ?: return null
        val by = obj["by"]?.jsonPrimitive?.contentOrNull ?: return null
        return ControlDenial(
            requestId = rid,
            deniedByParticipantId = by,
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseControlRevocation(obj: JsonObject): ControlRevocation? {
        val sharer = obj["sharer"]?.jsonPrimitive?.contentOrNull ?: return null
        return ControlRevocation(
            sharerParticipantId = sharer,
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseRemoteInput(obj: JsonObject): RemoteInputEvent? {
        val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return null
        val ev = obj["event"] as? JsonObject ?: return null
        val type = ev["type"]?.jsonPrimitive?.contentOrNull ?: return null
        fun num(k: String) = ev[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        fun bool(k: String) = ev[k]?.jsonPrimitive?.contentOrNull?.let {
            when (it) { "true" -> true; "false" -> false; else -> null }
        }
        fun str(k: String) = ev[k]?.jsonPrimitive?.contentOrNull
        return RemoteInputEvent(
            fromParticipantId = from,
            type = type,
            nx = num("nx"),
            ny = num("ny"),
            button = num("button")?.toInt(),
            deltaX = num("deltaX"),
            deltaY = num("deltaY"),
            key = str("key"),
            code = str("code"),
            ctrl = bool("ctrl"),
            meta = bool("meta"),
            alt = bool("alt"),
            shift = bool("shift"),
        )
    }

    private fun parseWhiteboardUpdate(obj: JsonObject): WhiteboardUpdate? {
        val svg = obj["svg"]?.jsonPrimitive?.contentOrNull ?: return null
        return WhiteboardUpdate(
            svg = svg,
            version = obj["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull ?: "",
            author = obj["author"]?.jsonPrimitive?.contentOrNull,
            authorDisplayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            generatedAtMs = parseTimestampMs(obj["generatedAt"]),
        )
    }

    private fun parseWhiteboardAnnotation(obj: JsonObject): WhiteboardAnnotation? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val author = obj["author"]?.jsonPrimitive?.contentOrNull ?: return null
        val strokes = obj["strokes"] as? JsonArray ?: return null
        return WhiteboardAnnotation(
            id = id,
            authorId = author,
            authorDisplayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            strokes = strokes,
            addedAtMs = parseTimestampMs(obj["addedAt"]),
        )
    }

    private fun parseAssistantResponse(obj: JsonObject): AssistantResponse? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        val metadata = (obj["metadata"] as? JsonObject)?.toString()
        return AssistantResponse(type = type, content = content, metadata = metadata)
    }

    private fun parseAssistantMessage(obj: JsonObject): AssistantMessage? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val sender = obj["senderId"]?.jsonPrimitive?.contentOrNull ?: return null
        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return null
        return AssistantMessage(
            id = id,
            senderId = sender,
            senderDisplayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            text = text,
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "chat",
            metadata = (obj["metadata"] as? JsonObject)?.toString(),
            replyToParticipantId = obj["replyTo"]?.jsonPrimitive?.contentOrNull,
            isPrivate = obj["private"]?.jsonPrimitive?.boolean == true,
            timestampMs = parseTimestampMs(obj["timestamp"]),
        )
    }

    private fun parseAssistantParticipant(obj: JsonObject): AssistantParticipant? {
        val pid = obj["participantId"]?.jsonPrimitive?.contentOrNull
            ?: obj["id"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return AssistantParticipant(
            participantId = pid,
            displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull,
            avatarUrl = obj["avatarUrl"]?.jsonPrimitive?.contentOrNull
                ?: obj["avatar"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseAssistantVoice(obj: JsonObject): AssistantVoice? {
        val audio = obj["audio"]?.jsonPrimitive?.contentOrNull ?: return null
        return AssistantVoice(
            audio = audio,
            mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "audio/mpeg",
            text = obj["text"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseFactCheck(obj: JsonObject): FactCheck? {
        val claim = obj["claim"]?.jsonPrimitive?.contentOrNull ?: return null
        val correction = obj["correction"]?.jsonPrimitive?.contentOrNull ?: return null
        return FactCheck(
            claim = claim,
            speakerId = obj["speaker"]?.jsonPrimitive?.contentOrNull ?: self.participantId,
            correction = correction,
            source = obj["source"]?.jsonPrimitive?.contentOrNull ?: "room-documents",
            confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.5,
            originalTranscript = obj["original"]?.jsonPrimitive?.contentOrNull ?: "",
            timestampMs = parseTimestampMs(obj["at"]),
        )
    }

    private fun parseCorrection(obj: JsonObject): Correction? {
        val original = obj["original"]?.jsonPrimitive?.contentOrNull ?: return null
        val suggestion = obj["suggestion"]?.jsonPrimitive?.contentOrNull
            ?: obj["corrected"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val changes = (obj["changes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        return Correction(
            original = original,
            suggestion = suggestion,
            changes = changes,
            tone = obj["tone"]?.jsonPrimitive?.contentOrNull,
            language = obj["language"]?.jsonPrimitive?.contentOrNull,
            requestedAtMs = parseTimestampMs(obj["requestedAt"]),
        )
    }

    private fun parseTranslatedAudio(obj: JsonObject): TranslatedAudio? {
        val audio = obj["audio"]?.jsonPrimitive?.contentOrNull ?: return null
        val mime = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null
        return TranslatedAudio(
            audio = audio,
            mimeType = mime,
            originalText = obj["originalText"]?.jsonPrimitive?.contentOrNull ?: "",
            translatedText = obj["translatedText"]?.jsonPrimitive?.contentOrNull ?: "",
            originalLanguage = obj["originalLang"]?.jsonPrimitive?.contentOrNull ?: "auto",
            targetLanguage = obj["targetLang"]?.jsonPrimitive?.contentOrNull ?: "auto",
        )
    }

    // --- E2E messaging API ---------------------------------------------------

    /**
     * Encrypt [text] with the room's shared E2E key and broadcast it
     * to other participants. Returns the durable [Message] record
     * (with server-assigned id + timestamp) so callers can render
     * their own message immediately without waiting for the server's
     * `new-message` echo (the server intentionally doesn't echo back
     * to the sender).
     *
     * Throws [IllegalStateException] if the room E2E key isn't
     * established yet — observe [isRoomKeyReady] before showing
     * a "Send" button to the user.
     */
    public suspend fun sendMessage(text: String): Message {
        val key = roomKey ?: throw IllegalStateException(
            "Room E2E key not yet established — wait for isRoomKeyReady=true"
        )
        val pid = self.participantId
        val displayName = self.displayName
        val (ciphertextB64, ivB64) = Crypto.encryptText(key, text).let { it.encrypted to it.iv }
        return suspendCancellableCoroutine { cont ->
            transport.emitWithAck("send-message", buildJsonObject {
                put("roomCode", JsonPrimitive(self.roomCode))
                put("encrypted", JsonPrimitive(ciphertextB64))
                put("iv", JsonPrimitive(ivB64))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("send-message: no ack")); return@emitWithAck
                }
                if (ack["ok"]?.jsonPrimitive?.boolean != true) {
                    val err = ack["error"]?.jsonPrimitive?.contentOrNull ?: "send_failed"
                    cont.resumeWithException(IllegalStateException("send-message: $err")); return@emitWithAck
                }
                val id = ack["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val ts = parseTimestampMs(ack["timestamp"])
                cont.resume(Message(
                    id = id,
                    senderId = pid,
                    senderDisplayName = displayName,
                    text = text,
                    timestampMs = ts,
                    encryptedB64 = ciphertextB64,
                    ivB64 = ivB64,
                ))
            }
        }
    }

    /**
     * Fetch the most recent [limit] messages from server-side history,
     * decrypt each with the current room key, return oldest-first.
     *
     * Useful on join to populate a chat panel with backlog. Requires
     * [isRoomKeyReady] — older messages encrypted with a *different*
     * room key (e.g. before the most recent rotation) won't decrypt
     * and will be dropped from the result.
     */
    public suspend fun fetchHistory(limit: Int = 100): List<Message> {
        val key = roomKey ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            transport.emitWithAck("fetch-history", buildJsonObject {
                put("limit", JsonPrimitive(limit))
            }) { ack ->
                if (ack == null || ack["ok"]?.jsonPrimitive?.boolean != true) {
                    cont.resume(emptyList()); return@emitWithAck
                }
                val arr = ack["messages"] as? JsonArray ?: run {
                    cont.resume(emptyList()); return@emitWithAck
                }
                val out = arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    decryptInbound(o, key)
                }
                cont.resume(out)
            }
        }
    }

    // --- E2E internals -------------------------------------------------------

    /**
     * Server forwards `key-bundle` envelopes between peers. Two flavours:
     *   - `pubkey` from a newcomer → if we hold the room key, wrap it for
     *     them and send back as `room-key` directed to that peer.
     *   - `room-key` from an existing peer → if we don't already have the
     *     room key, unwrap with our private key + flip [isRoomKeyReady].
     */
    private suspend fun handleKeyBundle(from: String, bundle: JsonObject) {
        if (from == self.participantId) return
        when (bundle["type"]?.jsonPrimitive?.contentOrNull) {
            "pubkey" -> {
                val pub = bundle["pubKey"] as? JsonObject ?: return
                peerPubKeys[from] = pub
                val key = roomKey ?: return
                val wrap = Crypto.wrapRoomKeyForPeer(key, ecdh.privateKey, pub)
                transport.emit("key-bundle", buildJsonObject {
                    put("to", JsonPrimitive(from))
                    put("bundle", buildJsonObject {
                        put("type", JsonPrimitive("room-key"))
                        put("wrapped", JsonPrimitive(wrap.wrapped))
                        put("iv", JsonPrimitive(wrap.iv))
                        put("senderPubKey", ecdh.pubJwk)
                    })
                })
            }
            "room-key" -> {
                if (roomKey != null) return // already have one — ignore late arrivals
                val wrapped = bundle["wrapped"]?.jsonPrimitive?.contentOrNull ?: return
                val iv = bundle["iv"]?.jsonPrimitive?.contentOrNull ?: return
                val sender = bundle["senderPubKey"] as? JsonObject ?: return
                roomKey = Crypto.unwrapRoomKey(wrapped, iv, ecdh.privateKey, sender)
                _isRoomKeyReady.value = true
            }
        }
    }

    private fun handleInboundMessage(obj: JsonObject) {
        val key = roomKey ?: return // can't decrypt yet — drop (rare race)
        decryptInbound(obj, key)?.let { _messages.tryEmit(it) }
    }

    private fun decryptInbound(obj: JsonObject, key: javax.crypto.SecretKey): Message? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val senderId = obj["senderId"]?.jsonPrimitive?.contentOrNull ?: return null
        val senderName = obj["senderDisplayName"]?.jsonPrimitive?.contentOrNull
        val encB64 = obj["encrypted"]?.jsonPrimitive?.contentOrNull ?: return null
        val ivB64 = obj["iv"]?.jsonPrimitive?.contentOrNull ?: return null
        val ts = parseTimestampMs(obj["timestamp"])
        val text = runCatching { Crypto.decryptText(key, encB64, ivB64) }.getOrNull() ?: return null
        return Message(
            id = id,
            senderId = senderId,
            senderDisplayName = senderName,
            text = text,
            timestampMs = ts,
            encryptedB64 = encB64,
            ivB64 = ivB64,
        )
    }

    private fun parseTimestampMs(el: kotlinx.serialization.json.JsonElement?): Long {
        if (el == null) return System.currentTimeMillis()
        // Defensive cast — `el.jsonPrimitive` would throw if the server
        // unexpectedly sent an object/array here, taking down the
        // socket dispatch thread and crashing the host app.
        val s = (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: return System.currentTimeMillis()
        // Server returns either an ISO-8601 string or ms-since-epoch number.
        return runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { s.toLong() }.getOrNull()
            ?: System.currentTimeMillis()
    }

    /**
     * Wrap any handler body so a parser bug or unexpected payload
     * shape gets logged + dropped instead of propagating up the
     * socket.io callback thread, where an uncaught exception would
     * crash the host app. Used pervasively across the
     * `transport.onXxx { ... }` registrations below.
     */
    private inline fun safe(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            android.util.Log.w("TwoStarsSDK", "$name handler threw — payload dropped", t)
        }
    }

    /**
     * Acquire the local camera + mic per [constraints] and start
     * publishing into the room.
     *
     * Side-effects:
     *   - Allocates a [LocalCamera] (camera + mic capture pipeline).
     *   - Adds the resulting tracks to every existing peer's PC and
     *     either opens a new PC + offer (if no PC yet — typically when
     *     we're the newcomer) or renegotiates an existing one (if the
     *     remote already initiated to us).
     *   - Returns a [LocalMedia] handle for mute / video toggle / self-
     *     view rendering. The same handle is also published on
     *     [localMedia].
     *
     * Throws [IllegalStateException] if already publishing — call
     * [unpublish] first if you want to change constraints.
     */
    public suspend fun publish(constraints: PublishConstraints = PublishConstraints.DEFAULT): LocalMedia {
        check(_localMedia.value == null) { "already publishing — call unpublish() first" }

        val camera = LocalCamera.create(context, factory, constraints)
        val media = LocalMedia(camera)
        _localMedia.value = media

        val tracks: List<MediaStreamTrack> = listOfNotNull(camera.videoTrack, camera.audioTrack)

        if (sfu.isInSfuMode()) {
            // SFU mode — produce each track via the send transport. The
            // server fans new-producer to remote peers automatically.
            for (track in tracks) sfu.produceTrack(track)
        } else {
            // P2P mode — attach to any PCs the inbound offer side already
            // built, then offer fresh to anyone we haven't talked to yet.
            pool.setLocalTracks(tracks)
            pool.addLocalTrackToAll()
            for (peerId in _peers.value.keys) pool.offerTo(peerId)
        }
        return media
    }

    /**
     * Stop publishing the local camera + mic. Releases capture
     * resources and tears down every outbound P2P sender; remote peers
     * will see their inbound video go dark within ~1 RTT.
     *
     * Idempotent — calling when nothing is published is a no-op.
     */
    public suspend fun unpublish() {
        val media = _localMedia.value ?: return
        _localMedia.value = null
        if (sfu.isInSfuMode()) {
            sfu.unproduceAll()
        } else {
            // P2P: don't close the PC — instead remove our senders +
            // renegotiate, so the remote browser's peer.stream sees the
            // tracks naturally `removetrack` away. Closing the PC
            // outright leaves a stale dead track on the remote side
            // and the tile freezes on the last received frame.
            pool.setLocalTracks(emptyList())
            pool.removeLocalTracksAndRenegotiate()
        }
        media.stop()
    }

    /** Leave the room and close the underlying connection. Idempotent. */
    public suspend fun leave() {
        // E7 — stop observing the process lifecycle before tearing
        // down so the observer doesn't keep us referenced after
        // close.
        detachLifecycleObserver()
        try {
            stopTranscription()
            unpublish()
        } finally {
            try {
                transport.emit("leave-room", buildJsonObject { /* no body */ })
            } catch (_: Throwable) { /* drop */ }
            pool.closeAll()
            sfu.release()
            transport.close()
            job.cancel()
        }
    }

    // --- SFU bootstrap -------------------------------------------------------

    /**
     * Tear down the P2P state and bring up the mediasoup [SfuPipe].
     * Triggered by `room-mode-changed → sfu` (the server promotes a
     * room to SFU mode at the 4-participant threshold by default).
     *
     * If we were already publishing in P2P, the local tracks are kept
     * around (camera capture isn't restarted) and re-published via the
     * SFU send transport.
     */
    private suspend fun enterSfuMode(rtpCapabilitiesJson: String) {
        // Close P2P PCs and drop the now-stale remote tracks. SFU
        // consumers rebuild the per-peer track flows from scratch via
        // list-producers + new-producer events inside SfuPipe.enter().
        pool.closeAll()
        for (peer in _peers.value.values) peer.clearTracks()

        val media = _localMedia.value
        val localTracks: List<MediaStreamTrack> = if (media != null) {
            listOfNotNull(media.audioTrack, media.videoTrack)
        } else emptyList()

        sfu.enter(rtpCapabilitiesJson, localTracks)
    }

    private fun onSfuProducerClosed(producerId: String, kind: TrackKind, remoteId: String) {
        val peer = _peers.value[remoteId] ?: return
        when (kind) {
            TrackKind.AUDIO  -> peer.setAudioTrack(null)
            TrackKind.VIDEO  -> peer.setVideoTrack(null)
            TrackKind.SCREEN -> peer.setScreenTrack(null)
        }
    }

    // --- internals -----------------------------------------------------------

    /**
     * Called by [PeerConnectionPool] after an inbound P2P renegotiation:
     * [liveIds] is the set of receiver-track ids the post-negotiation
     * transceivers still expect to receive on. Anything we've already
     * routed onto a [Peer] track flow that's NOT in that set was just
     * removed by the remote — clear it so the bound renderer drops the
     * frozen last frame.
     */
    private fun onP2pTracksPruned(remoteId: String, liveIds: Set<String>) {
        val peer = _peers.value[remoteId] ?: return
        peer.videoTrack.value?.let { if (it.id() !in liveIds) peer.setVideoTrack(null) }
        peer.audioTrack.value?.let { if (it.id() !in liveIds) peer.setAudioTrack(null) }
        peer.screenTrack.value?.let { if (it.id() !in liveIds) peer.setScreenTrack(null) }
    }

    private fun onRemoteTrack(remoteId: String, kind: TrackKind, track: MediaStreamTrack) {
        val peer = _peers.value[remoteId]
        if (peer == null) {
            // Track arrived for a peer we don't know about yet — most likely
            // the peer-joined event just hasn't been processed. We could
            // lazy-create the Peer here, but for A2 we drop the track ref.
            // peer-joined will arrive within microseconds, after which a
            // re-fired ontrack (or the next renegotiation) catches up.
            return
        }
        when (kind) {
            TrackKind.VIDEO  -> peer.setVideoTrack(track as VideoTrack)
            TrackKind.AUDIO  -> peer.setAudioTrack(track as AudioTrack)
            TrackKind.SCREEN -> peer.setScreenTrack(track as VideoTrack)
        }
        _events.tryEmit(RoomEvent.PeerTrack(peer, kind, track))
    }

    public data class JoinResult(
        val roomCode: String,
        val mode: Mode,
        val initialPeers: List<Peer>,
    )

    public class JoinException(message: String) : Exception(message)

    private companion object {
        // Per-event ack timeout for socket events that round-trip
        // through Gemini for image / SVG / TTS generation. The server
        // wraps the upstream Gemini call in a 25 s AbortController
        // (see plugins/default/gemini.js); we give ourselves a 30 s
        // ceiling so the server's bounded failure ack wins over the
        // client-side timeout. Most signaling events finish in
        // hundreds of ms — they don't need this.
        private const val AI_ACK_TIMEOUT_MS = 30_000L
    }
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
        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
    } ?: System.currentTimeMillis()
    return Peer(participantId = pid, displayName = name, joinedAtMs = joinedAtMs)
}

internal fun List<IceServerSpec>.toRtcIceServers(): List<PeerConnection.IceServer> = map { spec ->
    val builder = PeerConnection.IceServer.builder(spec.urls)
    if (spec.username != null) builder.setUsername(spec.username)
    if (spec.credential != null) builder.setPassword(spec.credential)
    builder.createIceServer()
}
