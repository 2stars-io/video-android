package io.twostars.sdk

import android.content.Context
import io.twostars.sdk.internal.SocketTransport
import io.twostars.sdk.internal.WebRTCFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.EglBase
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Top-level entry point for the 2Stars Android SDK.
 *
 * Construct one with an Android [Context] (any context — the SDK only
 * holds the application context internally) and the API base URL of
 * your 2Stars deployment, then call [connect] with a participant token
 * to obtain a [Room] handle.
 *
 * ```kotlin
 * val client = StarsClient(context = applicationContext, baseUrl = "https://video-api.2stars.io")
 * val room = client.connect(participantJwt)
 * room.join("abc-defg-hij")
 * val media = room.publish()                 // starts camera + mic
 * media.videoTrack?.addSink(selfRenderer)    // self-view
 * ```
 *
 * Participant tokens are minted by your backend via
 * `POST /api/v1/tokens` against the 2Stars API. **Never** embed your
 * 2Stars API key in the mobile app.
 *
 * Lifecycle:
 *   - [StarsClient] is cheap to allocate; you can keep one per process
 *     or one per call screen — your choice.
 *   - The first call to [connect] across the process initialises the
 *     WebRTC plumbing (`PeerConnectionFactory`, EGL, audio device
 *     module). That's the ~100ms one-off cost; subsequent connects
 *     reuse it.
 */
public class StarsClient(
    context: Context,
    private val baseUrl: String,
) {

    // Hold onto the application context only. Activity contexts would
    // leak; the WebRTC bits genuinely need an application-scoped one.
    private val appContext: Context = context.applicationContext

    // Companion is declared once at the bottom of this class (line ~219)
    // to host all the static-style helpers (configureWebRTC,
    // forceSoftwareEncoder, sharedEglBaseContext). Kotlin only allows
    // one companion per class — adding two here was a v0.5.0 regression
    // that broke the JitPack build.

    /**
     * Open a Socket.IO connection to the 2Stars API and authenticate
     * with [participantToken]. Suspends until the server confirms auth
     * (`authenticated` event) or returns an [AuthException] on failure.
     *
     * Cancelling the calling coroutine cancels the connection.
     *
     * **Region routing.** Pass [region] to scope the socket to a
     * specific signaling region:
     *   - `null` (default) → connect to [baseUrl] verbatim (legacy /
     *     dev path; load balancer handles regional routing).
     *   - `"<region-id>"` → fetch the catalog from [baseUrl], pick the
     *     matching region, connect to its `signalingHost`.
     *   - `"auto"` → fetch the catalog, probe every region's
     *     `probeUrl`, connect to the lowest-RTT one.
     */
    @JvmOverloads
    public suspend fun connect(participantToken: String, region: String? = null): Room {
        val targetUrl = if (region == null) baseUrl else resolveRegionUrl(region)
        return suspendCancellableCoroutine { cont ->
            val transport = SocketTransport(targetUrl, participantToken)
            val factory = WebRTCFactory.obtain(appContext)

            transport.onAuthenticated { presence, iceServers ->
                if (cont.isActive) cont.resume(Room(appContext, transport, factory, presence, iceServers))
            }
            transport.onAuthError { msg ->
                if (cont.isActive) cont.resumeWithException(AuthException(msg))
            }
            transport.onConnectError { msg ->
                if (cont.isActive) cont.resumeWithException(ConnectException(msg))
            }

            cont.invokeOnCancellation { transport.close() }
            transport.connect()
        }
    }

    /**
     * Look up a region's signaling URL. Pass an explicit region id or
     * `"auto"` to probe-and-pick. Throws if the discovery URL doesn't
     * respond or the requested id isn't in the catalog.
     */
    public suspend fun resolveRegionUrl(region: String): String {
        val catalog = discoverRegions(baseUrl)
        val regions = (catalog["regions"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?: throw ConnectException("malformed region catalog from $baseUrl")
        val chosen = if (region == "auto") {
            pickFastestRegion(regions)
                ?: throw ConnectException("auto-region: no region responded to probes")
        } else {
            regions.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == region }
                ?: throw ConnectException("unknown region: $region")
        }
        val host = chosen["signalingHost"]?.jsonPrimitive?.contentOrNull
            ?: throw ConnectException("region ${chosen["id"]} has no signalingHost")
        // Local hosts (dev) use http; everything else assumes prod TLS.
        val isLocal = Regex("^(localhost|127\\.0\\.0\\.1)(:\\d+)?$").matches(host)
        return (if (isLocal) "http://" else "https://") + host
    }

    /**
     * Probe each region's `probeUrl` in parallel; return the fastest
     * responder, or `null` if none answered inside the 3s budget.
     */
    private suspend fun pickFastestRegion(regions: List<JsonObject>): JsonObject? = coroutineScope {
        val deferred = regions.map { r ->
            async(Dispatchers.IO) {
                val url = r["probeUrl"]?.jsonPrimitive?.contentOrNull ?: return@async null
                val t0 = System.nanoTime()
                val ok = withTimeoutOrNull(3000) {
                    runCatching {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.requestMethod = "GET"
                        conn.responseCode in 200..299
                    }.getOrDefault(false)
                } ?: false
                if (!ok) null else r to (System.nanoTime() - t0)
            }
        }
        deferred.mapNotNull { it.await() }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Fetch the region catalog from a discovery URL. Public so callers
     * can render a region picker UI without instantiating a full
     * StarsClient + connecting.
     */
    public suspend fun discoverRegions(discoveryUrl: String): JsonObject = withContext(Dispatchers.IO) {
        val url = discoveryUrl.trimEnd('/') + "/api/v1/regions"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                throw ConnectException("discover-regions: HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(body).jsonObject
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * The SDK's shared EGL context, exposed for `SurfaceViewRenderer`
     * initialisation.
     *
     * **Why this matters:** the SDK's local capture pipeline and its
     * peer-connection encoder/decoder both run inside one OpenGL
     * context (held by the process-singleton `PeerConnectionFactory`).
     * `SurfaceViewRenderer.init()` takes an `EglBase.Context` of its
     * own — and unless that context is the SDK's, hardware-backed
     * video frames (the default path on modern Android WebRTC) can't
     * cross between them and the tile renders **black** even though
     * the underlying track is live.
     *
     * Always initialise renderers like this:
     *
     * ```kotlin
     * surfaceViewRenderer.init(StarsClient.sharedEglBaseContext(context), null)
     * ```
     *
     * Idempotent — first call lazily builds the singleton (~100ms one-
     * off cost); subsequent calls return the cached context.
     */
    public companion object {
        /**
         * E8 — configure WebRTC encoder behaviour BEFORE any [connect]
         * call. Defaults are sensible (hardware encoder preferred,
         * H.264 high profile + Intel VP8 enabled) so most apps don't
         * need to touch this. Calling AFTER the first connect is a
         * no-op and logs a warning — PeerConnectionFactory is process-
         * singleton + can't be reconfigured live.
         *
         * Use [forceSoftwareEncoder] for QA on devices with broken
         * vendor codecs; the call is a one-liner alias for the common
         * tweak.
         */
        @JvmStatic
        public fun configureWebRTC(config: io.twostars.sdk.WebRTCConfig) {
            io.twostars.sdk.internal.WebRTCFactory.configure(config)
        }

        /** Convenience: turn off the hardware encoder path entirely. */
        @JvmStatic
        public fun forceSoftwareEncoder() {
            configureWebRTC(io.twostars.sdk.WebRTCConfig(
                hardwareEncoderPreferred = false))
        }

        /**
         * Return the shared [EglBase.Context] used by the SDK's
         * WebRTC plumbing. A consumer renderer (e.g. an external
         * `SurfaceViewRenderer`) MUST initialise with this context
         * so its GL pipeline shares textures with the SDK's
         * capture/render path — otherwise frames render black.
         *
         * Idempotent — first call lazily builds the singleton (~100ms
         * one-off cost); subsequent calls return the cached context.
         */
        @JvmStatic
        public fun sharedEglBaseContext(context: Context): EglBase.Context =
            WebRTCFactory.obtain(context.applicationContext).eglBase.eglBaseContext
    }
}

/** Server rejected the participant token. */
public class AuthException(message: String) : Exception(message)

/** Network / transport-level failure before auth could complete. */
public class ConnectException(message: String) : Exception(message)
