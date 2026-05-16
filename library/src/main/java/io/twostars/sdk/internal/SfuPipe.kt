package io.twostars.sdk.internal

import io.github.crow_misia.mediasoup.Consumer
import io.github.crow_misia.mediasoup.Device
import io.github.crow_misia.mediasoup.Producer
import io.github.crow_misia.mediasoup.RecvTransport
import io.github.crow_misia.mediasoup.SendTransport
import io.github.crow_misia.mediasoup.Transport
import io.github.crow_misia.mediasoup.createDevice
import io.twostars.sdk.TrackKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.webrtc.AudioTrack
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The SFU equivalent of [PeerConnectionPool]. Owns the mediasoup
 * [Device] + send/recv [Transport]s + the producer/consumer registries
 * for the lifetime of an SFU-mode room.
 *
 * Entered via [enter] when the server flips us to SFU (either at join
 * or via `room-mode-changed`). Leaves cleanly on [release].
 *
 * Thread-safety: state mutators are guarded by [setupMutex]. The
 * mediasoup native callbacks fire on internal worker threads; we
 * marshal long-running work onto [scope] but avoid blocking the
 * native callback thread.
 *
 * Key bug ported from JS SDK: the **`pendingProducers` queue**.
 *
 *   When a `room-mode-changed → sfu` event triggers [enter] across
 *   every existing peer concurrently, server-emitted `new-producer`
 *   events can arrive in the window between us setting `mode = sfu`
 *   and our recv transport being negotiated. Without the queue those
 *   events are silently dropped — and `list-producers` is only a
 *   one-shot snapshot, so a producer announced *during* the bootstrap
 *   is lost forever and the corresponding peer renders as a black
 *   tile until the next mode change.
 *
 *   Fix: queue `new-producer` info during the negotiation window,
 *   then drain the queue twice in [enter] — once after the recv
 *   transport is up (so we catch events that arrived during transport
 *   negotiation) and again after `list-producers` (so we catch events
 *   that arrived during the snapshot fetch). [consumeProducer] is
 *   idempotent so a producer that shows up in both [list-producers]
 *   AND `new-producer` is safe to encounter twice.
 */
internal class SfuPipe(
    private val factory: WebRTCFactory,
    private val transport: SocketTransport,
    private val scope: CoroutineScope,
    private val onTrack: TrackArrivedCallback,
    private val onProducerClosed: ProducerClosedCallback,
) {

    fun interface TrackArrivedCallback {
        fun invoke(remoteId: String, kind: TrackKind, track: MediaStreamTrack)
    }

    fun interface ProducerClosedCallback {
        fun invoke(producerId: String, kind: TrackKind, remoteId: String)
    }

    @Volatile private var device: Device? = null
    @Volatile private var sendTransport: SendTransport? = null
    @Volatile private var recvTransport: RecvTransport? = null

    /** producer key (e.g. "camera-audio", "camera-video") -> Producer. */
    private val producers = ConcurrentHashMap<String, Producer>()

    /** producerId -> ConsumerRecord. Per JS SDK's `_consumersByProducer`. */
    private val consumers = ConcurrentHashMap<String, ConsumerRecord>()

    /**
     * `new-producer` events that arrived while [recvTransport] was
     * still being negotiated. Drained twice in [enter].
     */
    private val pendingProducers = mutableListOf<NewProducerInfo>()
    private val pendingMutex = Mutex()

    /** Guards the entire [enter]/[release] state-machine transition. */
    private val setupMutex = Mutex()

    /**
     * Serialises [consumeProducer] calls. mediasoup-client-android's
     * `RecvTransport.consume` is *not* concurrency-safe — internally
     * it drives the PC's SDP state machine (`setRemoteDescription`,
     * `createAnswer`, `setLocalDescription`). Two concurrent consumes
     * race on that state machine and crash with "Failed to set local
     * answer sdp: Called in wrong state: stable" when the second one
     * finds the PC already in mid-cycle.
     *
     * Consumes are usually fast (~10ms each) so a per-consume lock is
     * cheap; the alternative — a thread-pool-of-one for the recv
     * transport — would also work but uses more machinery.
     */
    private val consumeMutex = Mutex()

    /** True between [enter] start and [release] — used by [enqueueIfPending]. */
    @Volatile private var inSfuMode: Boolean = false

    /**
     * D1.1 — auto-managed video subscriptions.
     *
     *   "all"   (default) — consume every producer.
     *   "top-n"           — only consume video/screen for participants
     *                       in the current active-speakers set; audio
     *                       always passes through.
     *
     * The producer-info cache lets us consume a deferred video producer
     * the moment its owner enters the top-N set, without re-asking the
     * server. Cache entries are cleared on producer-close + on
     * [release].
     */
    @Volatile internal var subscriptionMode: String = "all"
    private val currentTopN: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val videoProducerInfo = ConcurrentHashMap<String, NewProducerInfo>()

    /**
     * E4 — end-to-end media encryption. Off by default; flipped on by
     * Room.setMediaEncryption(true) once the room key is ready. When
     * on, every produced track gets an [SFrameEncryptor] using our own
     * per-sender key, and every consumed track gets an [SFrameDecryptor]
     * using the producer's per-sender key.
     */
    @Volatile internal var mediaEncryptionEnabled: Boolean = false
    @Volatile internal var ourSenderKey: javax.crypto.SecretKey? = null
    // Map<participantId, SecretKey> — derived once per remote sender,
    // cached. Cleared on release().
    private val remoteSenderKeys = ConcurrentHashMap<String, javax.crypto.SecretKey>()

    /** RTC config seeded into mediasoup transports (carries iceServers). */
    @Volatile private var rtcConfig: PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(emptyList())

    fun isInSfuMode(): Boolean = inSfuMode

    fun setIceServers(servers: List<PeerConnection.IceServer>) {
        rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
    }

    /**
     * Bootstrap SFU mode. Loads the [Device] with the server's router
     * RTP capabilities, opens send + recv transports, drains any
     * pending `new-producer` events, then publishes any local tracks
     * + consumes the existing producer set.
     */
    suspend fun enter(routerRtpCapabilitiesJson: String, localTracks: List<MediaStreamTrack>) =
        setupMutex.withLock {
            inSfuMode = true
            Log.i(TAG, "enter: building device + transports (localTracks=${localTracks.size})")
            // 1. Build + load device.
            val dev = factory.factory.createDevice()
            if (!dev.loaded) {
                dev.load(routerRtpCapabilitiesJson, rtcConfig)
            }
            device = dev

            // 2. Build send + recv transports.
            sendTransport = createSendTransport(dev)
            recvTransport = createRecvTransport(dev)
            Log.i(TAG, "enter: transports up (queued=${pendingProducers.size})")

            // 3. First drain — catches events that landed during the two
            //    transport-create round-trips above.
            drainPending()

            // 4. Publish our local tracks (if any).
            for (track in localTracks) produceTrack(track)
            Log.i(TAG, "enter: published ${producers.size} producer(s)")

            // 5. Consume the snapshot of existing producers.
            val list = listProducersAck()
            Log.i(TAG, "enter: list-producers returned ${list.size}")
            for (p in list) consumeProducer(p)

            // 6. Second drain — catches events that landed *during* steps
            //    4 and 5 (i.e. our own producers triggered new-producer
            //    fan-out at the server, plus another peer might have just
            //    completed their own enter() call).
            drainPending()
            Log.i(TAG, "enter: done — total consumers=${consumers.size}")
        }

    /**
     * Add a single local track now that we're already in SFU mode
     * (mid-call publish). Idempotent on producer-key collision.
     *
     * `mediaType` is the appData hint that tells the server's SFU how
     * to route the resulting consumer (camera tracks land on
     * `Peer.videoTrack`/`audioTrack`, screen tracks land on
     * `Peer.screenTrack`). Use `"camera"` for getUserMedia tracks,
     * `"screen"` for MediaProjection tracks.
     */
    suspend fun produceTrack(track: MediaStreamTrack, mediaType: String = "camera") {
        val st = sendTransport ?: return
        val kind = when (track) {
            is AudioTrack -> "audio"
            is VideoTrack -> "video"
            else -> return
        }
        val key = "$mediaType-$kind"
        if (producers.containsKey(key)) return

        val appData = """{"mediaType":"$mediaType"}"""

        // Per-content-type encoding profile. Camera defaults are fine
        // (mediasoup picks ~1 Mbps), but screen content is text-heavy
        // and gets fuzzy below ~2 Mbps. We bump the cap explicitly for
        // mediaType=screen so shared code looks crisp on receivers.
        val encodings: List<org.webrtc.RtpParameters.Encoding> =
            if (mediaType == "screen" && kind == "video") {
                listOf(
                    org.webrtc.RtpParameters.Encoding(/* rid = */ null, /* active = */ true, /* scaleResolutionDownBy = */ null).apply {
                        maxBitrateBps = 2_500_000   // 2.5 Mbps cap
                        minBitrateBps = 500_000      // don't drop below 500k or text becomes unreadable
                        maxFramerate = 15            // matches ScreenCapture.SCREEN_SHARE_FPS
                    }
                )
            } else emptyList()

        // Force the produce call onto the IO dispatcher — the mediasoup
        // produce() callback (`onProduce` listener) uses a blocking ack
        // round-trip via SocketTransport.emitWithAck, which itself
        // blocks the calling thread until ack lands. Running on a
        // single-threaded coroutine dispatcher would deadlock.
        val producer = withContext(Dispatchers.IO) {
            st.produce(
                listener = makeProducerListener(key),
                track = track,
                encodings = encodings,
                codecOptions = null,
                codec = null,
                appData = appData,
            )
        }
        // E4 — wire SFrame encryption if enabled. ourSenderKey is set
        // by Room.setMediaEncryption() before any produce calls go out.
        // Uses libwebrtc's FrameEncryptor hook on the underlying RTC
        // sender — payloads are opaque to the SFU from this point.
        ourSenderKey?.let { k ->
            if (mediaEncryptionEnabled) {
                runCatching { producer.setFrameEncryptor(SFrameEncryptor(k)) }
                    .onFailure { Log.w(TAG, "produceTrack: setFrameEncryptor failed", it) }
            }
        }
        producers[key] = producer
    }

    /**
     * Close a single producer by key (e.g. `"screen-video"`) and tell
     * the server. Used by screen-share stop — closing all producers
     * via [unproduceAll] would also kill the camera/mic.
     */
    suspend fun unproduceByKey(key: String) {
        val p = producers.remove(key) ?: return
        val id = p.id
        runCatching { p.close() }
        try {
            transport.emitWithAckSuspending("close-producer", buildJsonObject {
                put("producerId", JsonPrimitive(id))
            })
        } catch (_: Throwable) { /* best-effort */ }
    }

    /**
     * Stop publishing every track. mediasoup-client's `producer.close()`
     * is **local-only** — it doesn't tell the server. Without an
     * explicit `close-producer` round-trip the server keeps forwarding
     * the (now-dead) track to every consumer; remote peers see the
     * last received frame frozen on screen until the producer is
     * eventually GC'd by the disconnect path.
     *
     * Best-effort: if the ack fails (server gone, network blip), the
     * disconnect path still cleans up server-side; the only cost of a
     * miss is a few seconds of staleness on remote tiles.
     */
    suspend fun unproduceAll() {
        val ids = producers.values.map { it.id }
        for ((_, p) in producers) runCatching { p.close() }
        producers.clear()
        for (id in ids) {
            try {
                transport.emitWithAckSuspending("close-producer", buildJsonObject {
                    put("producerId", JsonPrimitive(id))
                })
            } catch (_: Throwable) { /* best-effort */ }
        }
    }

    /**
     * Consume one server-announced producer. **Idempotent** — if the
     * producer is already in [consumers] (e.g. because it appeared in
     * both `list-producers` and a `new-producer` event), this is a no-op.
     */
    suspend fun consumeProducer(info: NewProducerInfo): Unit = consumeMutex.withLock {
        val rt = recvTransport ?: run {
            // Caller raced us — queue + bail. Drains pick up later.
            pendingMutex.withLock { pendingProducers.add(info) }
            return@withLock
        }
        val dev = device ?: return@withLock
        if (consumers.containsKey(info.producerId)) return@withLock

        // D1.1 — cache info for every video/screen producer so applyTopN
        // can promote a deferred producer the moment its owner enters
        // the speaker set. Audio always passes through (cheap, and we
        // want every voice).
        val isVideoLike = info.kind == "video" || info.mediaType == "screen"
        if (isVideoLike) {
            videoProducerInfo[info.producerId] = info
        }
        if (subscriptionMode == "top-n"
            && isVideoLike
            && !currentTopN.contains(info.participantId)) {
            return@withLock
        }

        val ack = consumeAck(rt.id, info.producerId, dev.rtpCapabilities) ?: return@withLock
        // dev.rtpCapabilities is String JSON in libmediasoup-android — passed straight through.
        val consumer = withContext(Dispatchers.IO) {
            rt.consume(
                listener = makeConsumerListener(info.producerId),
                id = ack.id,
                producerId = ack.producerId,
                kind = ack.kind,
                rtpParameters = ack.rtpParameters,
                appData = null,
            )
        } ?: return@withLock

        // E4 — derive (or reuse) the producer's per-sender key and
        // attach a FrameDecryptor. The producer's participantId is
        // carried in the new-producer info; both sides derive the
        // same key from (roomKey, participantId) via HKDF.
        if (mediaEncryptionEnabled) {
            val devKey = remoteSenderKeys[info.participantId]
            if (devKey != null) {
                runCatching { consumer.setFrameDecryptor(SFrameDecryptor(devKey)) }
                    .onFailure { Log.w(TAG, "consumeProducer: setFrameDecryptor failed", it) }
            } else {
                Log.w(TAG, "consumeProducer: no sender key cached for ${info.participantId} — frames will fail decrypt")
            }
        }

        val mediaType = info.mediaType ?: ack.mediaType ?: "camera"
        val track = consumer.track
        val trackKind = when {
            mediaType == "screen" -> TrackKind.SCREEN
            track is VideoTrack -> TrackKind.VIDEO
            track is AudioTrack -> TrackKind.AUDIO
            else -> return@withLock
        }
        consumers[info.producerId] = ConsumerRecord(
            producerId = info.producerId,
            participantId = info.participantId,
            consumer = consumer,
            kind = trackKind,
        )
        onTrack.invoke(info.participantId, trackKind, track)
    }

    /**
     * Called from the inbound `new-producer` socket handler. If our
     * recv transport isn't up yet (either because we haven't entered
     * SFU mode yet OR we're mid-bootstrap), enqueue for later. The
     * queue is drained twice inside [enter] so events that land
     * during the setup window aren't lost.
     *
     * Crucially: do NOT gate on `inSfuMode` here. The JS SDK can
     * because Node is single-threaded and `this.mode = 'sfu'` runs
     * synchronously between `room-mode-changed` and the next event.
     * On Android, both events `scope.launch` independently — so a
     * `new-producer` from a remote peer who's already in SFU can run
     * BEFORE our own `enterSfuMode` coroutine flips inSfuMode to true.
     * That race used to silently drop those events and leave the peer
     * invisible until the next mode change. Queueing instead makes
     * the order irrelevant.
     */
    suspend fun onNewProducer(info: NewProducerInfo) {
        if (recvTransport == null) {
            pendingMutex.withLock { pendingProducers.add(info) }
            Log.d(TAG, "onNewProducer queued (no recvTransport yet): pid=${info.producerId} from=${info.participantId}")
            return
        }
        Log.d(TAG, "onNewProducer immediate: pid=${info.producerId} from=${info.participantId}")
        consumeProducer(info)
    }

    fun onProducerClosedRemote(producerId: String) {
        videoProducerInfo.remove(producerId)
        val rec = consumers.remove(producerId) ?: return
        // Don't close the consumer ourselves — mediasoup closes it on
        // upstream producer close. The kind we recorded at consume-time
        // tells the caller which Peer flow to null out (audio vs video
        // vs screen), so a peer that turns off only their mic doesn't
        // also lose its video tile.
        onProducerClosed.invoke(producerId, rec.kind, rec.participantId)
    }

    /**
     * E4 — enable end-to-end media encryption for this room. Caller
     * (Room) supplies the local sender key (derived from roomKey + our
     * own participantId) + a map of remote sender keys by participantId.
     * After this, every NEW producer/consumer is wired with the SFrame
     * encryptor/decryptor. Already-active producers are NOT retrofitted
     * — libwebrtc doesn't support live FrameEncryptor swap; callers
     * needing a hard cut should leave + rejoin.
     */
    fun enableMediaEncryption(
        ourKey: javax.crypto.SecretKey,
        remoteKeys: Map<String, javax.crypto.SecretKey>,
    ) {
        ourSenderKey = ourKey
        remoteSenderKeys.clear()
        remoteSenderKeys.putAll(remoteKeys)
        mediaEncryptionEnabled = true
    }

    /**
     * Add or update a remote sender's key — called by Room when a new
     * peer joins (so subsequent consumers for that sender can decrypt).
     */
    fun setRemoteSenderKey(participantId: String, key: javax.crypto.SecretKey) {
        remoteSenderKeys[participantId] = key
    }

    /**
     * E4.1 — close the camera-audio + camera-video producers (NOT
     * screen) so Room can re-call produceTrack with the current
     * mediaEncryptionEnabled state. libwebrtc doesn't allow detaching
     * an in-flight FrameEncryptor; the republish creates fresh
     * producers that pick up the current encryption flag at produce
     * time. Brief media gap (~500ms-1s) is acceptable per the
     * director-mode tradeoff already in use.
     *
     * Called from Room.republishLocalTracks() which owns the original
     * MediaStreamTrack references.
     */
    suspend fun closeLocalCameraProducers() {
        for (k in listOf("camera-audio", "camera-video")) {
            val p = producers.remove(k) ?: continue
            val id = p.id
            runCatching { p.close() }
            // Release the server-side mediasoup producer too so the
            // remote consumers tear down + the next produce gets a
            // fresh producerId.
            runCatching {
                transport.emitWithAckSuspending("close-producer", buildJsonObject {
                    put("producerId", JsonPrimitive(id))
                })
            }
        }
    }

    /**
     * D1.1 — switch between consume-everything and top-N modes. Mirrors
     * the JS SDK's `Room.setVideoSubscriptionMode`. Re-evaluates existing
     * subscriptions immediately on transition.
     */
    suspend fun setSubscriptionMode(mode: String) {
        require(mode == "all" || mode == "top-n") { "unknown subscription mode \"$mode\"" }
        if (mode == subscriptionMode) return
        subscriptionMode = mode
        if (mode == "all") consumeAllDeferred() else closeOutOfTopN()
    }

    /**
     * Seed the top-N set from a join-room ack snapshot. Doesn't trigger
     * consume/close — the consumeProducer loop in [enter] inspects this
     * set on each producer and admits/defers based on it.
     */
    fun seedTopN(speakers: List<String>) {
        currentTopN.clear()
        currentTopN.addAll(speakers)
    }

    /**
     * Apply a fresh top-N from a server `active-speakers` broadcast.
     * Diffs against the current set: consumes deferred video for new
     * entrants, closes consumers for departures. Audio is never touched.
     * No-op when [subscriptionMode] is "all".
     */
    suspend fun applyTopN(speakers: List<String>) {
        currentTopN.clear()
        currentTopN.addAll(speakers)
        if (subscriptionMode != "top-n") return
        for ((producerId, info) in videoProducerInfo) {
            val inSet = currentTopN.contains(info.participantId)
            val hasConsumer = consumers.containsKey(producerId)
            if (inSet && !hasConsumer) {
                consumeProducer(info)
            } else if (!inSet && hasConsumer) {
                closeConsumerLocal(producerId)
            }
        }
    }

    private suspend fun consumeAllDeferred() {
        for ((producerId, info) in videoProducerInfo) {
            if (!consumers.containsKey(producerId)) consumeProducer(info)
        }
    }

    private fun closeOutOfTopN() {
        for ((producerId, info) in videoProducerInfo) {
            if (!currentTopN.contains(info.participantId) && consumers.containsKey(producerId)) {
                closeConsumerLocal(producerId)
            }
        }
    }

    // Close a consumer locally + tell the server (`close-consumer`) so
    // the SFU stops sending RTP for it (bandwidth-saving half of D1.1)
    // + notify Room so the corresponding Peer track is detached. The
    // producer keeps running for the room's other consumers.
    // videoProducerInfo retains the entry so a future top-N entry for
    // this participant can re-consume from scratch.
    private fun closeConsumerLocal(producerId: String) {
        val rec = consumers.remove(producerId) ?: return
        val consumerId = runCatching { rec.consumer.id }.getOrNull()
        runCatching { rec.consumer.close() }
        if (consumerId != null) {
            transport.emit("close-consumer", buildJsonObject {
                put("consumerId", JsonPrimitive(consumerId))
            })
        }
        onProducerClosed.invoke(producerId, rec.kind, rec.participantId)
    }

    suspend fun release(): Unit = setupMutex.withLock {
        inSfuMode = false
        unproduceAll()
        for ((_, rec) in consumers) {
            runCatching { rec.consumer.close() }
        }
        consumers.clear()
        videoProducerInfo.clear()
        currentTopN.clear()
        pendingMutex.withLock { pendingProducers.clear() }
        runCatching { sendTransport?.close() }
        runCatching { recvTransport?.close() }
        sendTransport = null
        recvTransport = null
        runCatching { device?.dispose() }
        device = null
    }

    // --- internals -----------------------------------------------------------

    private suspend fun drainPending() {
        val toProcess = pendingMutex.withLock {
            val copy = pendingProducers.toList()
            pendingProducers.clear()
            copy
        }
        for (info in toProcess) consumeProducer(info)
    }

    private suspend fun createSendTransport(dev: Device): SendTransport {
        val ack = createTransportAck("send")
        return dev.createSendTransport(
            listener = sendListener,
            id = ack.id,
            iceParameters = ack.iceParameters,
            iceCandidates = ack.iceCandidates,
            dtlsParameters = ack.dtlsParameters,
            sctpParameters = null,
            appData = null,
            rtcConfig = rtcConfig,
        )
    }

    private suspend fun createRecvTransport(dev: Device): RecvTransport {
        val ack = createTransportAck("recv")
        return dev.createRecvTransport(
            listener = recvListener,
            id = ack.id,
            iceParameters = ack.iceParameters,
            iceCandidates = ack.iceCandidates,
            dtlsParameters = ack.dtlsParameters,
            sctpParameters = null,
            rtcConfig = rtcConfig,
        )
    }

    // ---- Listeners ----------------------------------------------------------

    private val sendListener: SendTransport.Listener = object : SendTransport.Listener {
        override fun onConnect(transport: Transport, dtlsParameters: String) {
            // mediasoup hands us dtlsParameters as a JSON string; relay to
            // the server and ignore the ack body — the server signals
            // success by the absence of an error.
            scope.launch {
                this@SfuPipe.transport.emit("connect-transport", buildJsonObject {
                    put("transportId", JsonPrimitive(transport.id))
                    put("dtlsParameters", parseJsonObject(dtlsParameters))
                })
            }
        }

        override fun onConnectionStateChange(transport: Transport, newState: String) { /* no-op */ }

        override fun onProduce(
            transport: Transport,
            kind: String,
            rtpParameters: String,
            appData: String?,
        ): String {
            // mediasoup expects a synchronous return of the server-assigned
            // producer id. Our SocketTransport.emitWithAck is callback-
            // based, so we build a CompletableDeferred + wait. The native
            // mediasoup thread can block here — that's exactly what the
            // API contract says.
            val deferred = CompletableDeferred<String>()
            this@SfuPipe.transport.emitWithAck(
                "produce",
                buildJsonObject {
                    put("transportId", JsonPrimitive(transport.id))
                    put("kind", JsonPrimitive(kind))
                    put("rtpParameters", parseJsonObject(rtpParameters))
                    appData?.let { put("appData", parseJsonObject(it)) }
                },
            ) { ack ->
                val id = ack?.get("id")?.jsonPrimitive?.contentOrNull
                if (id != null) deferred.complete(id)
                else deferred.completeExceptionally(
                    IllegalStateException("produce ack missing id: $ack")
                )
            }
            return try { runBlocking { deferred.await() } } catch (_: Throwable) { "" }
        }

        override fun onProduceData(
            transport: Transport,
            sctpStreamParameters: String,
            label: String,
            protocol: String,
            appData: String?,
        ): String = "" // data channels not used
    }

    private val recvListener: RecvTransport.Listener = object : RecvTransport.Listener {
        override fun onConnect(transport: Transport, dtlsParameters: String) {
            scope.launch {
                this@SfuPipe.transport.emit("connect-transport", buildJsonObject {
                    put("transportId", JsonPrimitive(transport.id))
                    put("dtlsParameters", parseJsonObject(dtlsParameters))
                })
            }
        }
        override fun onConnectionStateChange(transport: Transport, newState: String) { /* no-op */ }
    }

    private fun makeProducerListener(key: String): Producer.Listener =
        object : Producer.Listener {
            override fun onTransportClose(producer: Producer) {
                producers.remove(key)
            }
        }

    private fun makeConsumerListener(producerId: String): Consumer.Listener =
        object : Consumer.Listener {
            override fun onTransportClose(consumer: Consumer) {
                consumers.remove(producerId)
            }
        }

    // ---- Server round-trips -------------------------------------------------

    private suspend fun createTransportAck(direction: String): CreateTransportAck =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("create-transport", buildJsonObject {
                put("direction", JsonPrimitive(direction))
            }) { ack ->
                if (ack == null) {
                    cont.resumeWithException(IllegalStateException("create-transport: no ack"))
                    return@emitWithAck
                }
                cont.resume(
                    CreateTransportAck(
                        id = ack["id"]?.jsonPrimitive?.contentOrNull
                            ?: return@emitWithAck cont.resumeWithException(
                                IllegalStateException("create-transport: missing id")
                            ),
                        iceParameters = (ack["iceParameters"] as? JsonObject)?.toString().orEmpty(),
                        iceCandidates = (ack["iceCandidates"] as? JsonArray)?.toString().orEmpty(),
                        dtlsParameters = (ack["dtlsParameters"] as? JsonObject)?.toString().orEmpty(),
                    )
                )
            }
        }

    private suspend fun consumeAck(
        recvTransportId: String,
        producerId: String,
        rtpCapabilitiesJson: String,
    ): ConsumeAck? = suspendCancellableCoroutine { cont ->
        transport.emitWithAck("consume", buildJsonObject {
            put("transportId", JsonPrimitive(recvTransportId))
            put("producerId", JsonPrimitive(producerId))
            put("rtpCapabilities", parseJsonObject(rtpCapabilitiesJson))
        }) { ack ->
            if (ack == null) {
                cont.resume(null); return@emitWithAck
            }
            cont.resume(
                ConsumeAck(
                    id = ack["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    producerId = ack["producerId"]?.jsonPrimitive?.contentOrNull ?: producerId,
                    kind = ack["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                    rtpParameters = (ack["rtpParameters"] as? JsonObject)?.toString().orEmpty(),
                    mediaType = ack["mediaType"]?.jsonPrimitive?.contentOrNull,
                )
            )
        }
    }

    private suspend fun listProducersAck(): List<NewProducerInfo> =
        suspendCancellableCoroutine { cont ->
            transport.emitWithAck("list-producers", buildJsonObject { /* no body */ }) { ack ->
                val arr = ack?.get("producers") as? JsonArray
                if (arr == null) {
                    cont.resume(emptyList()); return@emitWithAck
                }
                cont.resume(arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    NewProducerInfo(
                        producerId = o["producerId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        participantId = o["participantId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        kind = o["kind"]?.jsonPrimitive?.contentOrNull,
                        mediaType = o["mediaType"]?.jsonPrimitive?.contentOrNull,
                    )
                })
            }
        }

    // ---- helpers ------------------------------------------------------------

    private fun parseJsonObject(raw: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject

    companion object {
        private const val TAG = "TwoStarsSfu"

        // appData payload sent up with `produce` so the server can route
        // routing decisions correctly (e.g. screen vs. camera).
        private val APP_DATA_CAMERA: String = """{"mediaType":"camera"}"""
    }

    // ---- Plain-data helpers -------------------------------------------------

    internal data class NewProducerInfo(
        val producerId: String,
        val participantId: String,
        val kind: String?,
        val mediaType: String?,
    )

    internal data class ConsumerRecord(
        val producerId: String,
        val participantId: String,
        val consumer: Consumer,
        val kind: TrackKind,
    )

    private data class CreateTransportAck(
        val id: String,
        val iceParameters: String,
        val iceCandidates: String,
        val dtlsParameters: String,
    )

    private data class ConsumeAck(
        val id: String,
        val producerId: String,
        val kind: String,
        val rtpParameters: String,
        val mediaType: String?,
    )
}
