package io.twostars.sample

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.twostars.sample.databinding.ActivityMainBinding
import io.twostars.sdk.CallForegroundService
import io.twostars.sdk.ControlSession
import io.twostars.sdk.FrameProcessor
import io.twostars.sdk.LocalMedia
import io.twostars.sdk.Message
import io.twostars.sdk.Peer
import io.twostars.sdk.Room
import io.twostars.sdk.RoomEvent
import io.twostars.sdk.ScreenShareSession
import io.twostars.sdk.StarsClient
import io.twostars.sdk.TintFrameProcessor
import io.twostars.sdk.WakeWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * A2 sample. Tap **Quick demo** to mint a fresh room + token via the
 * platform's demo endpoints — no copy-pasting JWTs from your
 * laptop. Then **Connect** + **Publish** lights up the camera and
 * pushes you into the room.
 *
 * For multi-peer testing, open the React playground in a desktop
 * browser, paste the same `roomCode` shown after Quick demo, and mint
 * a second token from the same `/demo/tokens` endpoint.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var room: Room? = null
    private var localMedia: LocalMedia? = null

    /**
     * One [PeerTileBinding] per peer currently in the room. Keyed by
     * `participantId`. Built/torn down dynamically in [collectPeers]
     * as the `room.peers` flow updates.
     */
    private val peerTiles = mutableMapOf<String, PeerTileBinding>()

    /** Single AI-assistant tile, alive while [Room.assistantParticipant] is non-null. */
    private var hebbsTile: HebbsTileBinding? = null

    /**
     * Per-peer collector job, so a peer leaving cleans up its
     * `peer.videoTrack.collect` coroutine instead of leaking it.
     */
    private val peerJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Camera + mic are hard requirements; notification denial only
            // hides the foreground-service notification (the service still
            // keeps the app from being Doze'd).
            val cam = results[Manifest.permission.CAMERA] != false
            val mic = results[Manifest.permission.RECORD_AUDIO] != false
            if (cam && mic) startPublish()
            else toast("camera + mic permissions are required to publish")
        }

    /**
     * Launches the system MediaProjection consent dialog. On success
     * we hand the resulting `data` Intent to [Room.startScreenShare].
     */
    private val screenShareLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode != android.app.Activity.RESULT_OK || data == null) {
                toast("screen-share consent denied")
                return@registerForActivityResult
            }
            val r2 = room ?: return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    r2.startScreenShare(data)
                    log("screen share started")
                } catch (t: Throwable) {
                    toast("screen share failed: ${t.message}")
                    log("screen share failed: ${t.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Keep the screen on while the activity is in the foreground.
        // Standard pattern for video-call apps — without it the screen
        // dims/locks mid-call and the local self-view goes black.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Self renderer MUST use the SDK's shared EGL context — see the
        // KDoc on StarsClient.sharedEglBaseContext for why. Initializing
        // with EglBase.create() instead would render black tiles for
        // hardware-backed frames (the default path on modern WebRTC).
        // Peer tiles are created dynamically and use the same context.
        val egl = StarsClient.sharedEglBaseContext(this)
        b.selfRenderer.init(egl, null)
        // mirror=false on self-view so direction is consistent with
        // what remote peers see of you (camera-natural orientation).
        // Set true if you prefer the conventional "selfie mirror" UX.
        b.selfRenderer.setMirror(false)
        b.selfRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        b.quickDemoBtn.setOnClickListener { onQuickDemoTapped() }
        b.connectBtn.setOnClickListener   { connect() }
        b.leaveBtn.setOnClickListener     { leave() }
        b.publishBtn.setOnClickListener   { onPublishTapped() }
        b.muteBtn.setOnClickListener      { onMuteTapped() }
        b.videoBtn.setOnClickListener     { onVideoTapped() }
        b.switchCamBtn.setOnClickListener { onSwitchCamTapped() }
        b.fxBtn.setOnClickListener        { onFxTapped() }
        b.bgBlurBtn.setOnClickListener    { onBgBlurTapped() }
        b.bgImageBtn.setOnClickListener   { onBgImageTapped() }
        b.autoFrameBtn.setOnClickListener { onAutoFrameTapped() }
        b.bgAiBtn.setOnClickListener      { onBgAiTapped() }
        b.inviteABtn.setOnClickListener   { onInviteTapped("browser-a", "Desktop A") }
        b.inviteBBtn.setOnClickListener   { onInviteTapped("browser-b", "Desktop B") }
        b.chatSendBtn.setOnClickListener     { onSendChatTapped() }
        b.chatCorrectBtn.setOnClickListener  { onCorrectChatTapped() }
        b.askHebbsBtn.setOnClickListener     { onAskHebbsTapped() }
        b.whiteboardBtn.setOnClickListener   { onWhiteboardTapped() }
        b.wakeWordBtn.setOnClickListener     { onWakeWordTapped() }
        b.reqControlBtn.setOnClickListener   { onReqControlTapped() }
        b.revokeControlBtn.setOnClickListener { onRevokeControlTapped() }
        b.hebbsToggleBtn.setOnClickListener   { onHebbsToggleTapped() }
        b.screenShareBtn.setOnClickListener { onScreenShareTapped() }
        b.transcribeBtn.setOnClickListener  { onTranscribeTapped() }
        b.translateBtn.setOnClickListener   { onTranslateTapped() }
        b.sttLangBtn.setOnClickListener     { onSttLangTapped() }

        // The screen-share tile renderer must use the SDK's shared EGL
        // context too — same reason as the self/peer renderers.
        val egl2 = StarsClient.sharedEglBaseContext(this)
        b.screenShareRenderer.init(egl2, null)
        b.screenShareRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        b.leaveBtn.isEnabled = false
    }

    override fun onPause() {
        super.onPause()
        // Don't pause capture if we have a foreground service running —
        // the service's CAMERA/MICROPHONE foregroundServiceType keeps
        // camera + mic frames flowing across the app switch (which is
        // exactly what the invite share sheet flow needs). Pausing
        // would actively break the call for any peer watching.
        // We DO pause if there's no service (e.g. user is in the lobby
        // before publish, just previewing).
        if (localMedia != null && !isCallServiceLikelyRunning) {
            runCatching { localMedia?.pauseCapture() }
        }
    }

    /**
     * Heuristic: we start the service when publish() succeeds and stop
     * it on unpublish/leave. Tracking the same flag locally avoids
     * cross-process service-state queries (which Android makes
     * surprisingly painful).
     */
    private val isCallServiceLikelyRunning: Boolean
        get() = localMedia != null

    override fun onResume() {
        super.onResume()
        runCatching { localMedia?.resumeCapture() }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAllPeerTiles()
        unbindScreenTile()
        runCatching { b.selfRenderer.release() }
        runCatching { b.screenShareRenderer.release() }
        // The SDK's EglBase is a process singleton — don't release it on
        // activity destroy; the next activity to use the SDK still needs it.
    }

    // ---- Quick demo ---------------------------------------------------------

    /**
     * Mint a fresh room + token via the API's public demo endpoints
     * (no API key required — the server uses a synthetic
     * developer + per-IP rate limit). Auto-fills the inputs and
     * triggers Connect + Publish so you go from cold to live in one
     * tap.
     *
     * **Don't ship this pattern.** Real apps mint participant tokens
     * server-side using their own API key.
     */
    private fun onQuickDemoTapped() {
        val baseUrl = b.baseUrlInput.text.toString().trim().trimEnd('/').ifEmpty {
            toast("base URL required"); return
        }
        val displayName = b.displayNameInput.text.toString().trim().ifEmpty { "Phone" }
        val participantId = "phone-" + Random.Default.nextInt(0, 1_000_000).toString(16)

        b.quickDemoBtn.isEnabled = false
        b.statusText.text = "Minting demo room…"

        lifecycleScope.launch {
            try {
                val (roomCode, token) = withContext(Dispatchers.IO) {
                    mintDemoRoomAndToken(baseUrl, participantId, displayName)
                }
                b.roomCodeInput.setText(roomCode)
                b.tokenInput.setText(token)
                b.statusText.text = "Got room $roomCode — connecting…"
                log("quick demo → room=$roomCode pid=$participantId")
                connect()
            } catch (t: Throwable) {
                b.statusText.text = "Quick demo failed: ${t.message}"
                toast("Quick demo failed: ${t.message}")
            } finally {
                b.quickDemoBtn.isEnabled = true
            }
        }
    }

    /**
     * Two POSTs: `/api/v1/demo/rooms` then `/api/v1/demo/tokens`. Uses
     * `HttpURLConnection` (Java SE) so we don't drag in a third-party
     * HTTP client — this sample is meant to demonstrate the SDK, not
     * teach OkHttp.
     */
    private fun mintDemoRoomAndToken(
        baseUrl: String,
        participantId: String,
        displayName: String,
    ): Pair<String, String> {
        val roomBody = postJson("$baseUrl/api/v1/demo/rooms", "{}")
        val roomCode = JSONObject(roomBody).optString("roomCode").ifEmpty {
            throw IllegalStateException("/demo/rooms returned no roomCode: $roomBody")
        }
        val tokenPayload = JSONObject().apply {
            put("roomCode", roomCode)
            put("participantId", participantId)
            put("displayName", displayName)
        }.toString()
        val tokenBody = postJson("$baseUrl/api/v1/demo/tokens", tokenPayload)
        val token = JSONObject(tokenBody).optString("token").ifEmpty {
            throw IllegalStateException("/demo/tokens returned no token: $tokenBody")
        }
        return roomCode to token
    }

    private fun postJson(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ---- Connect / leave ----------------------------------------------------

    private fun connect() {
        val baseUrl = b.baseUrlInput.text.toString().trim().trimEnd('/').ifEmpty {
            "http://34.142.79.105"
        }
        val token = b.tokenInput.text.toString().trim()
        val roomCode = b.roomCodeInput.text.toString().trim()
        if (token.isEmpty() || roomCode.isEmpty()) {
            toast("token + roomCode required (tap Quick demo)")
            return
        }

        // If a previous room handle is hanging around (e.g. the user
        // backgrounded the app and the socket silently died), tear it
        // down before starting a fresh connect — otherwise we'd leak
        // the old socket + camera and the UI state stays out of sync.
        if (room != null || localMedia != null) {
            lifecycleScope.launch {
                runCatching { room?.leave() }
                room = null
                localMedia = null
                connectAfterReset(baseUrl, token, roomCode)
            }
        } else {
            connectAfterReset(baseUrl, token, roomCode)
        }
    }

    private fun connectAfterReset(baseUrl: String, token: String, roomCode: String) {
        b.connectBtn.isEnabled = false
        b.statusText.text = "Connecting…"
        b.peersList.text = ""

        lifecycleScope.launch {
            try {
                val client = StarsClient(this@MainActivity, baseUrl)
                val r = client.connect(token).also { room = it }
                b.statusText.text = "Authed as ${r.self.participantId}"

                val join = r.join(roomCode)
                b.statusText.text = "Joined ${join.roomCode} mode=${join.mode}"
                b.leaveBtn.isEnabled = true
                b.mediaControls.visibility = View.VISIBLE
                b.inviteControls.visibility = View.VISIBLE
                // Transcription is independent of WebRTC publish — opens
                // its own AudioRecord — so we enable the button as soon
                // as the room is joined.
                b.transcribeBtn.isEnabled = true
                b.translateBtn.isEnabled = true
                b.whiteboardBtn.isEnabled = true
                b.reqControlBtn.isEnabled = true
                b.hebbsToggleBtn.isEnabled = true

                launch { collectPeers(r) }
                launch { collectEvents(r) }
                launch { collectMessages(r) }
                launch { collectE2eState(r) }
                launch { collectScreenShare(r) }
                launch { collectTranscripts(r) }
                launch { collectTranslations(r) }
                launch { collectTranslatedAudio(r) }
                launch { collectFactChecks(r) }
                launch { collectCorrections(r) }
                launch { collectAssistant(r) }
                launch { collectWhiteboard(r) }
                launch { collectControl(r) }

                // Auto-publish once we've joined — saves a second tap. Permission
                // request will trigger here on first run.
                onPublishTapped()
            } catch (t: Throwable) {
                b.statusText.text = "Error: ${t.message}"
                b.leaveBtn.isEnabled = false
                room = null
            } finally {
                // Always re-enable Connect — even on success it's harmless
                // (the user can tap Leave first, then Connect again).
                // Without this `finally`, an early throw would leave the
                // button stuck disabled forever.
                b.connectBtn.isEnabled = true
            }
        }
    }

    private fun leave() {
        val r = room ?: return
        // Tear down sample-side helpers that hold their own scopes
        // before the Room itself goes away.
        wakeWord?.release(); wakeWord = null
        b.wakeWordBtn.text = "Wake"
        lifecycleScope.launch {
            try { r.leave() } catch (_: Throwable) {}
            clearAllPeerTiles()
            localMedia = null
            room = null
            CallForegroundService.stop(this@MainActivity)
            chatLog.clear()
            b.chatLog.text = ""
            b.statusText.text = "Left room"
            b.connectBtn.isEnabled = true
            b.leaveBtn.isEnabled = false
            b.mediaControls.visibility = View.GONE
            b.inviteControls.visibility = View.GONE
            b.publishBtn.text = "Publish"
            b.muteBtn.isEnabled = false
            b.videoBtn.isEnabled = false
            b.switchCamBtn.isEnabled = false
            b.transcribeBtn.isEnabled = false
            b.transcribeBtn.text = "Transcribe"
            transcribing = false
            // Wipe the local self-view's last painted frame. SurfaceViewRenderer
            // retains the most recent buffer until clearImage() is called —
            // the unpublish-button path already does this, leave() didn't,
            // so leaving while publishing left a frozen self-view behind.
            b.selfRenderer.clearImage()
            // Same story for the dedicated screen-share tile if it had one
            // bound (own preview or a remote peer's screen).
            unbindScreenTile()
            b.whiteboardTile.visibility = View.GONE
            b.whiteboardWebView.loadUrl("about:blank")
            b.whiteboardCanvas.clearAll()
            whiteboardWired = false
            // Remove the AI tile so it doesn't outlive the room.
            detachHebbsTile()
            hebbsOn = false
            b.hebbsToggleBtn.text = "Hebbs"
        }
    }

    // ---- Publish / unpublish ------------------------------------------------

    private fun onPublishTapped() {
        if (localMedia != null) {
            val r = room ?: return
            lifecycleScope.launch {
                if (transcribing) {
                    runCatching { r.stopTranscription() }
                    transcribing = false
                    b.transcribeBtn.text = "Transcribe"
                }
                runCatching { r.unpublish() }
                localMedia = null
                CallForegroundService.stop(this@MainActivity)
                b.publishBtn.text = "Publish"
                b.muteBtn.isEnabled = false
                b.videoBtn.isEnabled = false
                b.switchCamBtn.isEnabled = false
                // Transcription button stays enabled — AudioRecord is
                // independent of publish, so the user can still toggle
                // it on without re-publishing. We just auto-stopped any
                // in-flight session above.
                b.selfRenderer.clearImage()
            }
            return
        }

        // Camera + mic always; notifications too on API 33+ so the
        // foreground-service notification can actually display
        // (without it the service still runs but the user can't see
        // it in the shade).
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = wanted
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (needed.isEmpty()) startPublish() else permissionLauncher.launch(needed)
    }

    private fun startPublish() {
        val r = room ?: return
        b.publishBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val media = r.publish()
                localMedia = media
                media.videoTrack?.addSink(b.selfRenderer)

                // Boot the foreground service NOW that we have an active
                // call. Without it the OS will Doze + suspend the socket
                // + camera the moment the user switches apps (e.g. the
                // invite share sheet → WhatsApp round-trip).
                CallForegroundService.start(
                    this@MainActivity,
                    CallForegroundService.Config(
                        title = "2Stars sample call",
                        text = "Tap to return to the call",
                        launchActivity = MainActivity::class.java,
                    ),
                )

                b.publishBtn.text = "Stop publish"
                b.muteBtn.isEnabled = true
                b.videoBtn.isEnabled = true
                b.switchCamBtn.isEnabled = true
                b.screenShareBtn.isEnabled = true
                b.transcribeBtn.isEnabled = true
                b.fxBtn.isEnabled = true
                b.bgBlurBtn.isEnabled = true
                b.bgImageBtn.isEnabled = true
                b.autoFrameBtn.isEnabled = true
                b.bgAiBtn.isEnabled = true
                b.muteBtn.text = "Mute"
                b.videoBtn.text = "Video off"
                log("publish() ok")
            } catch (t: Throwable) {
                toast("publish failed: ${t.message}")
                log("publish failed: ${t.message}")
            } finally {
                b.publishBtn.isEnabled = true
            }
        }
    }

    private fun onMuteTapped() {
        // Route through Room.setMicEnabled (rather than
        // LocalMedia.toggleMute directly) so the change is broadcast
        // to every peer via the `participant-state` socket event. That
        // lets remote tiles render a "mic muted" indicator instead of
        // having to wait for silence to figure it out.
        val r = room ?: return
        val newEnabled = !r.isMicEnabled()
        r.setMicEnabled(newEnabled)
        b.muteBtn.text = if (newEnabled) "Mute" else "Unmute"
    }

    private fun onVideoTapped() {
        // Route through Room.setCameraEnabled so peers swap their
        // tile to an avatar placeholder instead of staring at black
        // frames (which is what `track.enabled = false` produces over
        // the wire — the receiver can't tell the camera is off).
        val r = room ?: return
        val newEnabled = !r.isCameraEnabled()
        r.setCameraEnabled(newEnabled)
        b.videoBtn.text = if (newEnabled) "Video off" else "Video on"
    }

    // ---- Browser invites -----------------------------------------------------

    /**
     * Mint a fresh participant token for the room we're already in,
     * compose a playground auto-fill URL, and pop Android's share
     * sheet so the operator can fire it off to a desktop browser via
     * any messaging app (WhatsApp / Telegram / Slack / email — all of
     * which sync to the desktop). Click the URL on desktop → playground
     * auto-connects to the same room as a second participant.
     *
     * Two buttons mint with different `participantId`/`displayName` so
     * server-side presence has unique IDs (the demo's `name_in_use`
     * guard would 409 otherwise).
     */
    private fun onInviteTapped(pid: String, name: String) {
        val r = room ?: run { toast("connect first"); return }
        val baseUrl = b.baseUrlInput.text.toString().trim().trimEnd('/').ifEmpty {
            "http://34.142.79.105"
        }
        val roomCode = r.self.roomCode
        b.inviteABtn.isEnabled = false
        b.inviteBBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    val payload = JSONObject().apply {
                        put("roomCode", roomCode)
                        put("participantId", "$pid-${Random.Default.nextInt(0, 0xFFFF).toString(16)}")
                        put("displayName", name)
                    }.toString()
                    val resp = postJson("$baseUrl/api/v1/demo/tokens", payload)
                    JSONObject(resp).optString("token").ifEmpty {
                        throw IllegalStateException("/demo/tokens returned no token")
                    }
                }

                // Build the playground auto-fill URL. The React playground
                // (App.tsx) reads url+room+token+name from window.location.hash
                // and auto-connects. Defaulting the playground host to
                // localhost:5173 — fine for local dev; real-prospect demo
                // would point at a hosted playground URL.
                val playground = "http://localhost:5173/"
                val invite = playground + "#" +
                    "url=" + Uri.encode(baseUrl) +
                    "&room=" + Uri.encode(roomCode) +
                    "&token=" + Uri.encode(token) +
                    "&name=" + Uri.encode(name)

                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Join my 2Stars demo room ($roomCode)")
                    putExtra(Intent.EXTRA_TEXT, invite)
                }
                startActivity(Intent.createChooser(send, "Send invite to…"))
                // Echo the URL into the events log so the operator can
                // sanity-check the room code if joining from desktop seems
                // off ("did the URL really point at MY phone's room?").
                log("invite for $name → room=$roomCode")
                log("URL: $invite")
            } catch (t: Throwable) {
                toast("invite failed: ${t.message}")
                log("invite failed: ${t.message}")
            } finally {
                b.inviteABtn.isEnabled = true
                b.inviteBBtn.isEnabled = true
            }
        }
    }

    private fun onScreenShareTapped() {
        val r = room ?: return
        if (r.screenShare.value != null) {
            // Already sharing — stop.
            lifecycleScope.launch {
                runCatching { r.stopScreenShare() }
                b.screenShareBtn.text = "Share screen"
                log("screen share stopped")
            }
            return
        }
        // Launch system consent dialog. The launcher's callback hands
        // the result Intent to room.startScreenShare().
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenShareLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun onSwitchCamTapped() {
        val media = localMedia ?: return
        // The sample doesn't track which camera is currently in use; just flip.
        val target = if ((media as Any).hashCode() and 1 == 0)
            io.twostars.sdk.CameraFacing.BACK
        else
            io.twostars.sdk.CameraFacing.FRONT
        runCatching { media.switchCamera(target) }
            .onFailure { toast("switch failed: ${it.message}") }
    }

    // ---- Peer + event observers --------------------------------------------

    private suspend fun collectPeers(r: Room) {
        r.peers.collectLatest { peers ->
            b.peersList.text = peers.values.joinToString("\n") {
                "• ${it.displayName ?: it.participantId} (${it.participantId})"
            }
            syncPeerTiles(peers)
        }
    }

    private suspend fun collectEvents(r: Room) {
        r.events.collectLatest { ev ->
            when (ev) {
                is RoomEvent.PeerJoined  -> log("+ ${ev.peer.displayName ?: ev.peer.participantId}")
                is RoomEvent.PeerLeft    -> log("- ${ev.participantId}")
                is RoomEvent.ModeChanged -> log("mode → ${ev.mode}")
                is RoomEvent.Disconnected -> log("disconnected: ${ev.reason}")
                is RoomEvent.PeerTrack -> {
                    log("track ${ev.kind} from ${ev.peer.displayName ?: ev.peer.participantId}")
                }
                is RoomEvent.ScreenShareEnded -> {
                    log("screen share ended: ${ev.reason}")
                    // Show a toast on system-driven termination so the
                    // operator knows their share dropped (vs them tapping
                    // the in-app button themselves).
                    if (ev.reason != io.twostars.sdk.ScreenShareEndReason.USER_REQUESTED) {
                        toast("screen share ended (${ev.reason})")
                    }
                }
                is RoomEvent.ConnectionStateChanged -> log("connection-state -> ${ev.state}")
                is RoomEvent.LocalTrackEnded     -> log("local track ${ev.kind} ended: ${ev.reason}")
                is RoomEvent.LocalTrackRecovered -> log("local track ${ev.kind} recovered")
                is RoomEvent.LocalTrackFailed    -> {
                    log("local track ${ev.kind} failed: ${ev.reason}")
                    toast("camera unavailable - tap retry")
                }
                is RoomEvent.BackgroundReady     -> log("background ready (${ev.mode})")
                is RoomEvent.BackgroundError     -> log("background error: ${ev.reason}")
                is RoomEvent.BackgroundCleared   -> log("background cleared")
                is RoomEvent.AutoFrameEnabled    -> log("auto-frame on")
                is RoomEvent.AutoFrameDisabled   -> log("auto-frame off")
                is RoomEvent.ScreenShareStarted  -> log("screen share started (${ev.streamId})")
                // Existing pre-parity events the sample doesn't render
                // anywhere (moderation, lifecycle, etc.) — keep them as
                // silent no-ops so the when stays exhaustive when the
                // sealed class grows again.
                is RoomEvent.PeerStateChanged,
                is RoomEvent.E2eRecordingMode,
                is RoomEvent.Kicked,
                is RoomEvent.ForceMuted,
                is RoomEvent.Banned,
                is RoomEvent.RoomLocked,
                is RoomEvent.ModerationEvent,
                is RoomEvent.VisibilityChanged -> Unit
            }
        }
    }

    // ---- Screen share ---------------------------------------------------

    /** Track currently bound to the screen-share tile (own OR remote). */
    private var boundScreenTrack: org.webrtc.VideoTrack? = null

    private suspend fun collectScreenShare(r: Room) {
        // Drive the screen tile from BOTH sources at once:
        //   - our own session (if we're the one sharing) — bind self-preview
        //   - any peer's screenTrack (if they're sharing) — bind remote screen
        // Whichever appears first wins; if both happen we prefer the remote
        // so the operator can see what's being shared TO them.
        kotlinx.coroutines.coroutineScope {
            launch {
                r.screenShare.collectLatest { session: ScreenShareSession? ->
                    if (session != null && boundScreenTrack == null) {
                        bindScreenTile(session.videoTrack, "Your screen")
                        b.screenShareBtn.text = "Stop sharing"
                    } else if (session == null) {
                        // Local stopped — fall back to any remote share if present.
                        val remote = r.peers.value.values
                            .firstOrNull { it.screenTrack.value != null }
                        if (remote != null) {
                            bindScreenTile(remote.screenTrack.value!!,
                                remote.displayName ?: remote.participantId)
                        } else {
                            unbindScreenTile()
                        }
                        b.screenShareBtn.text = "Share screen"
                    }
                }
            }
            launch {
                // Watch every peer's screenTrack flow. Aggregating with
                // collectLatest on r.peers re-launches an inner watch
                // each time the peer set changes.
                r.peers.collectLatest { peers ->
                    kotlinx.coroutines.coroutineScope {
                        for (p in peers.values) {
                            launch {
                                p.screenTrack.collectLatest { t ->
                                    if (t != null && r.screenShare.value == null) {
                                        bindScreenTile(t, p.displayName ?: p.participantId)
                                    } else if (t == null && boundScreenTrack != null) {
                                        // Check if any other peer is still sharing.
                                        val other = r.peers.value.values
                                            .firstOrNull { it != p && it.screenTrack.value != null }
                                        if (other != null) {
                                            bindScreenTile(other.screenTrack.value!!,
                                                other.displayName ?: other.participantId)
                                        } else if (r.screenShare.value == null) {
                                            unbindScreenTile()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bindScreenTile(track: org.webrtc.VideoTrack, label: String) {
        if (boundScreenTrack === track) {
            b.screenShareLabel.text = label
            return
        }
        unbindScreenTile()
        boundScreenTrack = track
        track.addSink(b.screenShareRenderer)
        b.screenShareLabel.text = label
        b.screenShareTile.visibility = View.VISIBLE
    }

    private fun unbindScreenTile() {
        boundScreenTrack?.let { runCatching { it.removeSink(b.screenShareRenderer) } }
        boundScreenTrack = null
        // Wipe the last painted buffer before hiding — otherwise a future
        // re-bind would briefly flash the previous share's last frame.
        b.screenShareRenderer.clearImage()
        b.screenShareTile.visibility = View.GONE
    }

    // ---- Transcription --------------------------------------------------

    private var transcribing: Boolean = false

    /**
     * STT source-language hint. The Gemini STT path sometimes biases
     * toward English when given `"auto"`, so the operator can override
     * via the [sttLangBtn] button.
     */
    private var sttLanguage: String = "auto"
    private val sttLanguages = listOf("auto", "he", "en", "es", "fr")

    private fun onSttLangTapped() {
        val r = room
        val idx = sttLanguages.indexOf(sttLanguage).let { if (it < 0) 0 else it }
        sttLanguage = sttLanguages[(idx + 1) % sttLanguages.size]
        b.sttLangBtn.text = "STT:$sttLanguage"
        log("STT source language → $sttLanguage")
        // If transcription is currently running, restart it so the new
        // language hint is sent to the server (the JS SDK does the same:
        // the per-socket language is captured at enable-transcription time).
        if (transcribing && r != null) {
            lifecycleScope.launch {
                runCatching { r.stopTranscription() }
                runCatching { r.startTranscription(language = sttLanguage) }
                log("transcription restarted with lang=$sttLanguage")
            }
        }
    }

    private fun onTranscribeTapped() {
        val r = room ?: return
        if (!transcribing) {
            // Transcription opens its own AudioRecord, so it doesn't
            // require publish() — but we still need RECORD_AUDIO. If
            // the user hasn't tapped Publish first, request the
            // permission on the spot.
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                toast("grant mic permission, then tap Transcribe again")
                return
            }
            b.transcribeBtn.isEnabled = false
            lifecycleScope.launch {
                try {
                    r.startTranscription(language = sttLanguage)
                    transcribing = true
                    b.transcribeBtn.text = "Stop transcribe"
                    log("transcription started (lang=$sttLanguage)")
                } catch (t: Throwable) {
                    toast("transcription start failed: ${t.message}")
                    log("transcription start failed: ${t.message}")
                } finally {
                    b.transcribeBtn.isEnabled = true
                }
            }
        } else {
            r.stopTranscription()
            transcribing = false
            b.transcribeBtn.text = "Transcribe"
            log("transcription stopped")
        }
    }

    private suspend fun collectTranscripts(r: Room) {
        r.transcripts.collect { t ->
            // Mirror the chat-style line so the operator can verify:
            //   <speaker>: <text>  (lang=xx)
            val who = t.speakerDisplayName ?: t.speakerId
            log("📝 $who: ${t.text} (lang=${t.language})")
        }
    }

    // ---- Translation ----------------------------------------------------

    private var translationTarget: String? = null
    // Cycle order matches the STT picker so the operator can demo every
    // pair without recompiling. `null` (first / wraparound) = off.
    private val translationTargets = listOf<String?>(null, "en", "he", "es", "fr")

    private fun onTranslateTapped() {
        val r = room ?: return
        val idx = translationTargets.indexOf(translationTarget).let { if (it < 0) 0 else it }
        val next = translationTargets[(idx + 1) % translationTargets.size]
        b.translateBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                // Pass includeSelf=true so the operator can validate the
                // translation pipeline solo. In a real app this would be
                // false (or wired off a UI preference).
                val acked = r.setTranslationLanguage(next, includeSelf = true)
                translationTarget = acked ?: next
                b.translateBtn.text = translationTarget?.let { "→ ${it.uppercase()}" } ?: "→ off"
                log("translation target → ${translationTarget ?: "off"}")
            } catch (t: Throwable) {
                toast("set-language failed: ${t.message}")
                log("set-language failed: ${t.message}")
            } finally {
                b.translateBtn.isEnabled = true
            }
        }
    }

    private suspend fun collectTranslations(r: Room) {
        r.translations.collect { t ->
            val who = t.speakerDisplayName ?: t.speakerId
            log("🌐 $who [${t.originalLanguage}→${t.targetLanguage}] ${t.translatedText}")
        }
    }

    private suspend fun collectTranslatedAudio(r: Room) {
        r.translatedAudio.collect { ta ->
            // For the smoke we just confirm receipt — playing the bytes
            // requires plumbing them through MediaPlayer or AudioTrack
            // (out of scope for the protocol smoke).
            log("🔊 [${ta.originalLanguage}→${ta.targetLanguage}] ${ta.translatedText} (audio ${ta.audio.length} b64 bytes)")
        }
    }

    // ---- Fact-check + correction ----------------------------------------

    private suspend fun collectFactChecks(r: Room) {
        r.factChecks.collect { f ->
            log("🔍 fact: \"${f.claim}\" → ${f.correction} (conf=${"%.2f".format(f.confidence)} from ${f.source})")
        }
    }

    private suspend fun collectCorrections(r: Room) {
        r.corrections.collect { c ->
            log("✏️  \"${c.original}\" → \"${c.suggestion}\"")
        }
    }

    private fun onCorrectChatTapped() {
        val r = room ?: return
        val draft = b.chatInput.text.toString().trim()
        if (draft.isEmpty()) {
            toast("type a draft first, then tap Correct")
            return
        }
        b.chatCorrectBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = r.requestCorrection(draft)
                if (result == null) {
                    log("✏️  no suggestion (skipped — too short / throttled / disabled)")
                } else {
                    // Replace the draft inline so the operator can tap Send right away.
                    b.chatInput.setText(result.suggestion)
                    b.chatInput.setSelection(result.suggestion.length)
                    log("✏️  applied: \"${result.original}\" → \"${result.suggestion}\"")
                }
            } catch (t: Throwable) {
                toast("correction failed: ${t.message}")
                log("correction failed: ${t.message}")
            } finally {
                b.chatCorrectBtn.isEnabled = true
            }
        }
    }

    // ---- AI assistant ("Hey Hebbs") -------------------------------------

    private fun onAskHebbsTapped() {
        val r = room ?: return
        val q = b.chatInput.text.toString().trim()
        if (q.isEmpty()) {
            toast("type a question first")
            return
        }
        b.askHebbsBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = r.askAssistant(q)
                if (resp == null) {
                    log("🤖 (no classification)")
                } else {
                    log("🤖 [${resp.type}] ${resp.content}")
                    b.chatInput.setText("")
                }
            } catch (t: Throwable) {
                toast("askHebbs failed: ${t.message}")
                log("askHebbs failed: ${t.message}")
            } finally {
                b.askHebbsBtn.isEnabled = true
            }
        }
    }

    private suspend fun collectAssistant(r: Room) {
        // 4 streams to surface — collect each on its own coroutine so a
        // slow consumer doesn't back-pressure the others.
        kotlinx.coroutines.coroutineScope {
            launch {
                r.assistantResponses.collect { resp ->
                    // The askAssistant suspending call already logs the
                    // result; this collector exists for parallel inflight
                    // queries that don't await synchronously.
                    log("🤖 hebbs-response [${resp.type}] ${resp.content}")
                }
            }
            launch {
                r.assistantMessages.collect { msg ->
                    val who = msg.senderDisplayName ?: msg.senderId
                    val tag = if (msg.isPrivate) "private " else ""
                    log("🤖 $who: $tag${msg.text}")
                }
            }
            launch {
                // StateFlow always re-plays its current value on collect,
                // so a fresh subscription would print "assistant left" on
                // connect even though nothing actually changed. Drop the
                // initial snapshot and only log real transitions.
                r.assistantParticipant.drop(1).collect { ai ->
                    if (ai != null) {
                        log("🤖 assistant joined: ${ai.displayName ?: ai.participantId}")
                        attachHebbsTile(ai)
                    } else {
                        log("🤖 assistant left")
                        detachHebbsTile()
                    }
                }
            }
            launch {
                r.assistantVoice.collect { v ->
                    log("🤖 voice: ${v.text ?: "(no text)"} (audio ${v.audio.length} b64 bytes)")
                }
            }
        }
    }

    // ---- FX (virtual background / auto-frame) ---------------------------

    /**
     * Cycle of FX modes the sample supports today. The pipeline
     * (FrameProcessor → VideoSource) is in place; only Tint is wired
     * for the smoke. Real virtual background (MediaPipe segmentation)
     * + auto-frame land in A8.2/A8.3 — we'll extend this enum then.
     */
    private enum class FxMode(val label: String) {
        OFF("FX:off"),
        TINT("FX:tint"),
    }
    private var fxMode: FxMode = FxMode.OFF

    private fun onFxTapped() {
        val media = localMedia
        if (media == null) {
            toast("publish camera + mic first — FX needs a video track to process")
            return
        }
        val next = when (fxMode) {
            FxMode.OFF  -> FxMode.TINT
            FxMode.TINT -> FxMode.OFF
        }
        val processor: FrameProcessor? = when (next) {
            FxMode.OFF  -> null
            FxMode.TINT -> TintFrameProcessor()
        }
        media.setVideoProcessor(processor)
        fxMode = next
        b.fxBtn.text = next.label
        log("🎨 fx → ${next.label}")
    }

    // A8.2 — virtual background. Two button states each (off / on).
    // Mutually exclusive with FxMode.TINT and with each other —
    // tapping one mode resets the others.

    private var bgBlurOn: Boolean = false
    private var bgImageOn: Boolean = false
    private var autoFrameOn: Boolean = false

    private fun onBgBlurTapped() {
        val r = room
        if (r == null) { toast("connect first"); return }
        bgBlurOn = !bgBlurOn
        if (bgBlurOn) {
            bgImageOn = false; b.bgImageBtn.text = "BG image:off"
            bgAiOn = false; b.bgAiBtn.text = "BG AI:off"
            r.setVirtualBackground(io.twostars.sdk.BackgroundProcessor.BackgroundMode.BLUR)
        } else {
            r.clearVirtualBackground()
        }
        b.bgBlurBtn.text = if (bgBlurOn) "BG blur:on" else "BG blur:off"
        log("🌫️ bg blur → $bgBlurOn")
    }

    private fun onBgImageTapped() {
        val r = room
        if (r == null) { toast("connect first"); return }
        bgImageOn = !bgImageOn
        if (bgImageOn) {
            bgBlurOn = false; b.bgBlurBtn.text = "BG blur:off"
            bgAiOn = false; b.bgAiBtn.text = "BG AI:off"
            // Use a simple solid-colour bitmap so the smoke doesn't
            // need a bundled asset. Production apps load any bitmap.
            val bg = android.graphics.Bitmap.createBitmap(1280, 720,
                android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bg).drawColor(android.graphics.Color.parseColor("#0a4d6e"))
            r.setVirtualBackground(io.twostars.sdk.BackgroundProcessor.BackgroundMode.IMAGE, bg)
        } else {
            r.clearVirtualBackground()
        }
        b.bgImageBtn.text = if (bgImageOn) "BG image:on" else "BG image:off"
        log("🖼️  bg image → $bgImageOn")
    }

    private fun onAutoFrameTapped() {
        val r = room
        if (r == null) { toast("connect first"); return }
        autoFrameOn = !autoFrameOn
        r.setAutoFrame(autoFrameOn)
        b.autoFrameBtn.text = if (autoFrameOn) "Auto-frame:on" else "Auto-frame:off"
        log("🎯 auto-frame → $autoFrameOn")
    }

    // E8 — high-level AI background generation. Demonstrates
    // [Room.setBackground] which round-trips a prompt through the
    // server's image-gen plugin (Gemini by default), decodes the
    // returned data-URL into a Bitmap, and composites it behind the
    // local camera via the same BackgroundProcessor used by
    // setVirtualBackground above. Two states: off / on; tapping toggles.
    private var bgAiOn: Boolean = false

    private fun onBgAiTapped() {
        val r = room
        if (r == null) { toast("connect first"); return }
        if (bgAiOn) {
            r.clearBackground()
            bgAiOn = false
            // Reset the other two button states' tracker since
            // clearBackground() also wipes whatever they had set.
            bgBlurOn = false; b.bgBlurBtn.text = "BG blur:off"
            bgImageOn = false; b.bgImageBtn.text = "BG image:off"
            b.bgAiBtn.text = "BG AI:off"
            log("🪄 bg AI → off")
            return
        }
        // Pick a fixed prompt — production apps prompt the user. The
        // setBackground() call is suspending and has its own 30 s
        // timeout, so the UI button is briefly disabled while the
        // server's Gemini call runs (typically 5–15 s).
        b.bgAiBtn.text = "BG AI:…"
        b.bgAiBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                r.setBackground("calm forest at dawn, soft golden light")
                bgAiOn = true
                bgBlurOn = false; b.bgBlurBtn.text = "BG blur:off"
                bgImageOn = false; b.bgImageBtn.text = "BG image:off"
                b.bgAiBtn.text = "BG AI:on"
                log("🪄 bg AI → on")
            } catch (e: Throwable) {
                b.bgAiBtn.text = "BG AI:off"
                log("🪄 bg AI failed: ${e.message}")
                toast("BG AI failed: ${e.message}")
            } finally {
                b.bgAiBtn.isEnabled = true
            }
        }
    }

    // ---- Hebbs AI participant ------------------------------------------

    private var hebbsOn: Boolean = false

    private var hebbsTileForId: String? = null

    private fun attachHebbsTile(ai: io.twostars.sdk.AssistantParticipant) {
        val existing = hebbsTile
        if (existing != null && hebbsTileForId == ai.participantId) {
            // Same Hebbs, new avatar (ai-participant-updated swap).
            // Keep the tile in place; just refresh the image so the
            // operator doesn't see a flicker.
            existing.label.text = "🤖 ${ai.displayName ?: "Hebbs"}"
            existing.bindAvatarUrl(ai.avatarUrl, lifecycleScope)
            return
        }
        detachHebbsTile()
        val tile = HebbsTileBinding.create(this, ai.displayName ?: "Hebbs")
        hebbsTile = tile
        hebbsTileForId = ai.participantId
        b.peerTilesRow.addView(tile.container)
        tile.bindAvatarUrl(ai.avatarUrl, lifecycleScope)
    }

    private fun detachHebbsTile() {
        hebbsTile?.let { b.peerTilesRow.removeView(it.container) }
        hebbsTile = null
        hebbsTileForId = null
    }

    private fun onHebbsToggleTapped() {
        val r = room ?: return
        val target = !hebbsOn
        b.hebbsToggleBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val ai = r.setAssistantEnabled(target)
                hebbsOn = target
                b.hebbsToggleBtn.text = if (target) "Hebbs (on)" else "Hebbs"
                if (target) {
                    val name = ai?.displayName ?: ai?.participantId ?: "Hebbs"
                    val avatar = ai?.avatarUrl ?: "(no avatar)"
                    log("🤖 Hebbs joined as \"$name\" — avatar: $avatar")
                    toast("Hebbs joined as $name")
                } else {
                    log("🤖 Hebbs disabled")
                }
            } catch (t: Throwable) {
                toast("Hebbs toggle failed: ${t.message}")
                log("Hebbs toggle failed: ${t.message}")
            } finally {
                b.hebbsToggleBtn.isEnabled = true
            }
        }
    }

    // ---- Whiteboard -----------------------------------------------------

    /** Set once the canvas is wired up — guards against double-binding. */
    private var whiteboardWired: Boolean = false

    private fun onWhiteboardTapped() {
        val r = room ?: return
        wireWhiteboardCanvasOnce(r)
        // Show the tile empty. AI generation is opt-in via the AI
        // button inside the tile; finger strokes broadcast immediately
        // via the canvas's onStrokeFinished callback.
        b.whiteboardTile.visibility = View.VISIBLE
        b.whiteboardLabel.text = "Whiteboard — draw with your finger"
    }

    private fun wireWhiteboardCanvasOnce(r: Room) {
        if (whiteboardWired) return
        whiteboardWired = true

        // Stroke-end callback → broadcast as a one-shape annotation.
        b.whiteboardCanvas.onStrokeFinished = { shape ->
            lifecycleScope.launch {
                runCatching {
                    r.annotateWhiteboard(kotlinx.serialization.json.buildJsonArray { add(shape) })
                }.onFailure { log("annotate failed: ${it.message}") }
            }
        }
        // AI gen button — pops a small prompt dialog so the operator
        // can actually describe what they want drawn (the chat input
        // is for chat; conflating the two was confusing).
        b.whiteboardAiBtn.setOnClickListener { promptForAiWhiteboard(r) }
        // Clear wipes our local view AND broadcasts a clear-shape so
        // peers wipe too. The JS SDK uses a `_deleted: true` tombstone
        // per shape; for the smoke we just clear locally + log — full
        // tombstone-broadcast is left as a TODO for parity.
        b.whiteboardClearBtn.setOnClickListener {
            b.whiteboardCanvas.clearAll()
            b.whiteboardWebView.loadUrl("about:blank")
            b.whiteboardLabel.text = "Whiteboard — cleared (local only)"
            log("📋 whiteboard cleared (local — peers still see their copy)")
        }
        // Close hides the tile but keeps state — re-tapping Whiteboard
        // shows it again with everything intact.
        b.whiteboardCloseBtn.setOnClickListener {
            b.whiteboardTile.visibility = View.GONE
        }
    }

    /**
     * Prompt the user for a free-form description of what to draw, then
     * fire generateWhiteboard. Pre-fills with the most recent prompt
     * so re-rolling a slight variation doesn't require retyping.
     */
    private fun promptForAiWhiteboard(r: Room) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. flowchart of how DNS works"
            setText(lastWhiteboardPrompt)
            setSelection(text?.length ?: 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("AI whiteboard prompt")
            .setView(input)
            .setPositiveButton("Generate") { _, _ ->
                val prompt = input.text.toString().trim()
                if (prompt.isEmpty()) { toast("prompt is empty"); return@setPositiveButton }
                lastWhiteboardPrompt = prompt
                runWhiteboardAi(r, prompt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var lastWhiteboardPrompt: String = ""

    private fun runWhiteboardAi(r: Room, prompt: String) {
        b.whiteboardAiBtn.isEnabled = false
        log("📋 AI gen: \"${prompt.take(60)}\" (5-10s)…")
        lifecycleScope.launch {
            try {
                val u = r.generateWhiteboard(prompt)
                log("📋 whiteboard v${u.version} (${u.svg.length} chars SVG)")
            } catch (t: Throwable) {
                toast("whiteboard AI failed: ${t.message}")
                log("whiteboard AI failed: ${t.message}")
            } finally {
                b.whiteboardAiBtn.isEnabled = true
            }
        }
    }

    private suspend fun collectWhiteboard(r: Room) {
        kotlinx.coroutines.coroutineScope {
            launch {
                r.whiteboardUpdates.collect { u ->
                    val by = u.authorDisplayName ?: u.author ?: "?"
                    log("📋 whiteboard update from $by — v${u.version} (\"${u.prompt.take(40)}\")")
                    // Auto-show the tile if it wasn't already up so
                    // peers' AI generations don't go invisible.
                    if (b.whiteboardTile.visibility != View.VISIBLE) {
                        wireWhiteboardCanvasOnce(r)
                        b.whiteboardTile.visibility = View.VISIBLE
                    }
                    renderWhiteboardSvg(u.svg, "${u.prompt.take(40)} · v${u.version} · by $by")
                }
            }
            launch {
                r.whiteboardAnnotations.collect { a ->
                    // Skip own-stroke echoes BEFORE we touch the events
                    // log — every log line auto-scrolls the page to the
                    // bottom, which feels like the screen is jumping
                    // out from under your finger after every drawn stroke.
                    if (a.authorId == r.self.participantId) return@collect
                    log("✍️  ${a.authorDisplayName ?: a.authorId} drew ${a.strokes.size} stroke(s)")
                    // Auto-show the tile so a remote drawing on an
                    // unopened whiteboard still shows up immediately.
                    if (b.whiteboardTile.visibility != View.VISIBLE) {
                        wireWhiteboardCanvasOnce(r)
                        b.whiteboardTile.visibility = View.VISIBLE
                    }
                    for (s in a.strokes) {
                        (s as? kotlinx.serialization.json.JsonObject)?.let {
                            b.whiteboardCanvas.addRemoteStroke(it)
                        }
                    }
                }
            }
        }
    }

    /**
     * Drop the SVG into the WebView. We wrap with a tiny HTML shell so
     * the SVG fills the available space regardless of its inherent
     * viewBox / dimensions; the meta-viewport line keeps it
     * device-pixel-aware so the strokes don't pixelate.
     */
    private fun renderWhiteboardSvg(svg: String, label: String) {
        val html = """
            <!DOCTYPE html>
            <html><head>
              <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=yes">
              <style>
                html,body{margin:0;padding:0;height:100%;width:100%;background:#fff;}
                svg{width:100%;height:100%;display:block;}
              </style>
            </head><body>$svg</body></html>
        """.trimIndent()
        b.whiteboardWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        b.whiteboardLabel.text = label
    }

    // ---- Wake word ------------------------------------------------------

    private var wakeWord: WakeWord? = null

    private fun onWakeWordTapped() {
        val r = room ?: return
        val ww = wakeWord
        if (ww == null) {
            // Spin up an AUTO-mode listener that pipes through the room's
            // transcript stream. Each match auto-asks Hebbs in chat (so
            // the response shows up in the events log).
            val newWw = WakeWord(r.transcripts, mode = WakeWord.Mode.AUTO) { match ->
                log("🎯 wake fired prefix=${match.prefix} token=${match.matchedToken} cmd=${match.command}")
                lifecycleScope.launch {
                    runCatching { r.askAssistant(match.command) }
                        .onFailure { log("askAssistant from wake failed: ${it.message}") }
                }
            }
            newWw.start()
            wakeWord = newWw
            b.wakeWordBtn.text = "Wake (on)"
            log("wake word listening — needs Transcribe on too for AUTO mode to receive any text")
        } else {
            ww.release()
            wakeWord = null
            b.wakeWordBtn.text = "Wake"
            log("wake word stopped")
        }
    }

    // ---- Remote control -------------------------------------------------

    /** Most recent grant we received as the controller. Tracks the active session. */
    private var asController: ControlSession? = null
    /** Most recent request we received as the sharer (waiting for grant/deny). */
    private var pendingSharerRequestId: String? = null

    private fun onReqControlTapped() {
        val r = room ?: return
        // Request control of the first remote peer present. With a real
        // UI you'd present a peer picker — sample takes the easy path.
        val target = r.peers.value.values.firstOrNull()
        if (target == null) {
            toast("no remote peers in the room to request control of")
            return
        }
        b.reqControlBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val rid = r.requestControl(target.participantId)
                log("🖱️  requested control of ${target.displayName ?: target.participantId} (req=$rid)")
            } catch (t: Throwable) {
                toast("request failed: ${t.message}")
                log("request failed: ${t.message}")
            } finally {
                b.reqControlBtn.isEnabled = true
            }
        }
    }

    private fun onRevokeControlTapped() {
        val r = room ?: return
        b.revokeControlBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                r.revokeControl(reason = "user-stopped")
                log("🖱️  revoked control")
            } catch (t: Throwable) {
                toast("revoke failed: ${t.message}")
                log("revoke failed: ${t.message}")
            }
            // Re-enable based on whether a session is still showing
            // active per controlActive — collector will refresh state.
            b.revokeControlBtn.isEnabled = (asController != null)
        }
    }

    private suspend fun collectControl(r: Room) {
        kotlinx.coroutines.coroutineScope {
            // Sharer side — incoming control requests. Sample auto-grants
            // for the smoke; a real app would prompt the user.
            launch {
                r.controlRequests.collect { req ->
                    pendingSharerRequestId = req.requestId
                    log("🖱️  ${req.fromDisplayName ?: req.fromParticipantId} wants to control you (auto-granting in 1s)")
                    kotlinx.coroutines.delay(1000)
                    runCatching { r.grantControl(req.requestId) }
                        .onSuccess { log("🖱️  granted control to ${it.controllerDisplayName ?: it.controllerParticipantId}") }
                        .onFailure { log("grant failed: ${it.message}") }
                    pendingSharerRequestId = null
                }
            }
            launch {
                r.controlGranted.collect { sess ->
                    asController = sess
                    log("🖱️  GOT control of ${sess.sharerParticipantId}")
                    b.revokeControlBtn.isEnabled = true
                }
            }
            launch {
                r.controlDenied.collect { d ->
                    log("🖱️  control denied by ${d.deniedByParticipantId}: ${d.reason ?: "(no reason)"}")
                }
            }
            launch {
                r.controlRevoked.collect { rv ->
                    log("🖱️  control revoked (sharer=${rv.sharerParticipantId}): ${rv.reason ?: "(no reason)"}")
                    asController = null
                    b.revokeControlBtn.isEnabled = false
                }
            }
            launch {
                r.controlActive.collect { active ->
                    if (active != null) {
                        log("🖱️  active: ${active.controllerDisplayName ?: active.controllerParticipantId} → ${active.sharerParticipantId}")
                    }
                }
            }
            launch {
                r.remoteInput.collect { ev ->
                    // Sample doesn't inject anything (Android can't without
                    // an AccessibilityService) — just log so the operator
                    // can verify the wire works.
                    log("🖱️  input ${ev.type} nx=${ev.nx} ny=${ev.ny} key=${ev.key}")
                }
            }
        }
    }

    // ---- Chat -----------------------------------------------------------

    /**
     * Local message log, kept in a Map<id, Message> so we can dedupe
     * across the local-send echo + the inbound socket echo. Re-rendered
     * sorted by server timestamp on every change, so the on-screen
     * order matches what every other client sees regardless of who
     * rendered their own send optimistically.
     */
    private val chatLog = linkedMapOf<String, Message>()

    private suspend fun collectE2eState(r: Room) {
        r.isRoomKeyReady.collectLatest { ready ->
            if (ready) {
                b.e2eStatus.text = "E2E key: ready"
                b.e2eStatus.setBackgroundColor(android.graphics.Color.parseColor("#dcfce7"))
                b.e2eStatus.setTextColor(android.graphics.Color.parseColor("#166534"))
                b.chatInput.isEnabled = true
                b.chatSendBtn.isEnabled = true
                b.chatCorrectBtn.isEnabled = true
                b.askHebbsBtn.isEnabled = true
                // First time the key flips ready, pull recent history so the
                // chat panel isn't empty on rejoin. Idempotent — fetch
                // returns [] if the key changed since.
                lifecycleScope.launch {
                    runCatching { r.fetchHistory(50) }
                        .getOrNull()
                        ?.forEach { addChatMessage(it) }
                }
            } else {
                b.e2eStatus.text = "E2E key: pending"
                b.e2eStatus.setBackgroundColor(android.graphics.Color.parseColor("#fee2e2"))
                b.e2eStatus.setTextColor(android.graphics.Color.parseColor("#991b1b"))
                b.chatInput.isEnabled = false
                b.chatSendBtn.isEnabled = false
                b.chatCorrectBtn.isEnabled = false
                b.askHebbsBtn.isEnabled = false
            }
        }
    }

    private suspend fun collectMessages(r: Room) {
        r.messages.collect { msg -> addChatMessage(msg) }
    }

    private fun onSendChatTapped() {
        val text = b.chatInput.text.toString().trim()
        if (text.isEmpty()) return
        val r = room ?: return
        b.chatSendBtn.isEnabled = false
        b.chatInput.setText("")
        lifecycleScope.launch {
            try {
                val sent = r.sendMessage(text)
                addChatMessage(sent)
            } catch (t: Throwable) {
                toast("send failed: ${t.message}")
            } finally {
                // Re-enable iff the room key is still ready (it should be).
                b.chatSendBtn.isEnabled = r.isRoomKeyReady.value
            }
        }
    }

    private fun addChatMessage(msg: Message) {
        chatLog[msg.id] = msg
        renderChatLog()
    }

    private fun renderChatLog() {
        val selfId = room?.self?.participantId
        val sorted = chatLog.values.sortedWith(
            compareBy<Message> { it.timestampMs }.thenBy { it.id }
        )
        val text = buildString {
            for (m in sorted) {
                val sender = if (m.senderId == selfId) "You"
                             else (m.senderDisplayName ?: m.senderId)
                append(sender); append(": "); append(m.text); append('\n')
            }
        }
        b.chatLog.text = text
        b.chatScroll.post { b.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- Peer tile lifecycle ----------------------------------------------

    /**
     * Reconcile the on-screen peer tiles with the current peers map.
     * Adds tiles for peers that don't have one yet, removes tiles for
     * peers that left. Each new tile starts a coroutine that observes
     * the peer's `videoTrack` flow and binds/unbinds the renderer
     * across track replacements (e.g. p2p→sfu transition).
     */
    private fun syncPeerTiles(peers: Map<String, Peer>) {
        // Remove tiles for peers that left.
        val gone = peerTiles.keys - peers.keys
        for (id in gone) {
            removePeerTile(id)
            log("tile removed for $id")
        }
        // Add tiles for new peers.
        for ((id, peer) in peers) {
            if (peerTiles.containsKey(id)) continue
            addPeerTile(peer)
            log("tile added for ${peer.displayName ?: id}")
        }
    }

    private fun addPeerTile(peer: Peer) {
        val tile = PeerTileBinding.create(this, peer.displayName ?: peer.participantId)
        b.peerTilesRow.addView(tile.container)
        tile.renderer.init(StarsClient.sharedEglBaseContext(this), null)
        tile.renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        peerTiles[peer.participantId] = tile

        // Combine the track flow with the new videoEnabled flag so the
        // renderer only shows when BOTH a track is bound AND the peer
        // has confirmed their camera is on. When the peer toggles off
        // (via Room.setCameraEnabled), we hide the renderer — the
        // tile's dark background shows through as a "video off"
        // placeholder. Without this we'd render black frames the peer
        // is still emitting after `track.enabled = false`.
        peerJobs[peer.participantId] = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                peer.videoTrack,
                peer.videoEnabled,
            ) { track, enabled -> track to enabled }.collectLatest { (track, enabled) ->
                if (enabled && track != null) {
                    tile.renderer.visibility = android.view.View.VISIBLE
                    tile.bind(track)
                } else {
                    tile.bind(null)
                    tile.renderer.visibility = android.view.View.INVISIBLE
                }
            }
        }
    }

    private fun removePeerTile(participantId: String) {
        peerJobs.remove(participantId)?.cancel()
        peerTiles.remove(participantId)?.let { tile ->
            tile.bind(null)
            runCatching { tile.renderer.release() }
            b.peerTilesRow.removeView(tile.container)
        }
    }

    private fun clearAllPeerTiles() {
        for (id in peerTiles.keys.toList()) removePeerTile(id)
    }

    /**
     * Self-contained mutable tile — owns the FrameLayout, the
     * SurfaceViewRenderer, and tracks which VideoTrack is currently
     * bound so [bind] can swap cleanly when the producer changes
     * (which happens at every p2p→sfu transition since SFU consumers
     * are fresh tracks even though they represent the same logical
     * peer).
     */
    /**
     * Tile for the AI assistant participant. Same shape as the regular
     * peer tile but renders an ImageView instead of a video renderer
     * (the assistant has no media stream — just a generated avatar).
     */
    private class HebbsTileBinding(
        val container: FrameLayout,
        val image: android.widget.ImageView,
        val label: TextView,
    ) {
        fun bindAvatarUrl(url: String?, scope: kotlinx.coroutines.CoroutineScope) {
            if (url.isNullOrBlank()) return
            scope.launch {
                val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { decodeAvatar(url) }.getOrNull()
                }
                if (bmp != null) image.setImageBitmap(bmp)
                else android.util.Log.w("TwoStarsSample",
                    "avatar decode failed (len=${url.length}, prefix=${url.take(40)})")
            }
        }

        /**
         * The server returns the AI avatar as a `data:image/...;base64,…`
         * URL — Gemini synthesises the PNG and we get it inline rather
         * than hosted at a URL. `URL(...).openConnection()` doesn't
         * grok the `data:` scheme, so we parse + base64-decode by hand.
         * Falls back to HTTP for hand-set URLs (avatar override).
         */
        private fun decodeAvatar(url: String): android.graphics.Bitmap? {
            return if (url.startsWith("data:", ignoreCase = true)) {
                val comma = url.indexOf(',')
                if (comma < 0) return null
                val payload = url.substring(comma + 1)
                val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
        }

        companion object {
            fun create(context: Context, displayName: String): HebbsTileBinding {
                val dp = { v: Float -> TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
                ).toInt() }
                val container = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(160f), dp(120f)).apply {
                        marginEnd = dp(6f)
                    }
                    setBackgroundColor(Color.parseColor("#1f2937")) // slate so the avatar pops
                }
                val image = android.widget.ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    // Generic robot glyph as a placeholder until the real avatar lands.
                    setImageResource(android.R.drawable.sym_def_app_icon)
                }
                val label = TextView(context).apply {
                    text = "🤖 $displayName"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setBackgroundColor(Color.parseColor("#88000000"))
                    setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.START
                        setMargins(dp(6f), 0, 0, dp(6f))
                    }
                }
                container.addView(image)
                container.addView(label)
                return HebbsTileBinding(container, image, label)
            }
        }
    }

    private class PeerTileBinding(
        val container: FrameLayout,
        val renderer: SurfaceViewRenderer,
        val label: TextView,
    ) {
        private var bound: VideoTrack? = null

        fun bind(track: VideoTrack?) {
            if (bound === track) return
            bound?.let { runCatching { it.removeSink(renderer) } }
            bound = track
            track?.addSink(renderer)
        }

        companion object {
            fun create(context: Context, displayName: String): PeerTileBinding {
                val dp = { v: Float -> TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
                ).toInt() }

                val container = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(160f), dp(120f)).apply {
                        marginEnd = dp(6f)
                    }
                    setBackgroundColor(Color.parseColor("#111111"))
                }
                val renderer = SurfaceViewRenderer(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }
                val label = TextView(context).apply {
                    text = displayName
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setBackgroundColor(Color.parseColor("#88000000"))
                    setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.START
                        setMargins(dp(6f), 0, 0, dp(6f))
                    }
                }
                container.addView(renderer)
                container.addView(label)
                return PeerTileBinding(container, renderer, label)
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun log(line: String) {
        b.eventsLog.append(line)
        b.eventsLog.append("\n")
        b.eventsScroll.post { b.eventsScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
