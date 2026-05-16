package io.twostars.sdk.internal

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.crow_misia.mediasoup.MediasoupClient
import io.github.crow_misia.webrtc.log.LogHandler
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

// Lifted from a nested companion data class to a top-level public type so
// the public API surface (StarsClient.configureWebRTC, Room.encoderInfo)
// can reference it without the visibility cap of `internal` parents.
import io.twostars.sdk.WebRTCConfig as Config
import io.twostars.sdk.EncoderInfo

/**
 * Process-singleton wrapper around the heavy WebRTC plumbing every
 * [io.twostars.sdk.Room] depends on:
 *
 *  - [PeerConnectionFactory] — creates [org.webrtc.PeerConnection]s,
 *    tracks, and sources. Internally holds a thread pool + native
 *    libraries; expensive to construct (~100ms).
 *  - [EglBase] — shared GL context; required so the local capture's
 *    GPU buffers and the remote renderer's `SurfaceViewRenderer` agree
 *    on a single display thread. Sharing it avoids GPU-to-GPU copies.
 *  - [AudioDeviceModule] — owns the mic + speaker. Configured with
 *    hardware AEC + NS so the device's DSP does the heavy lifting
 *    instead of running an extra software pass per frame.
 *
 * Initialised lazily on first [obtain]; survives `Room.leave()` so a
 * subsequent connect doesn't pay the init cost again. Released only
 * if a caller explicitly invokes [releaseProcessSingleton] (typically
 * never — process death does it for free).
 *
 * Thread-safety: [obtain] is double-checked-locked. The factory + EGL
 * + ADM internal APIs are themselves thread-safe per the WebRTC spec.
 */
internal class WebRTCFactory private constructor(
    val eglBase: EglBase,
    val factory: PeerConnectionFactory,
    val audioDeviceModule: AudioDeviceModule,
    /**
     * E8 — info about which video encoder backend WebRTC selected at
     * factory init. Useful for battery-aware UX ("you're on the
     * software fallback — your battery may drain faster") and ops
     * triage on devices with broken HW codecs.
     */
    val encoderInfo: EncoderInfo,
) {

    companion object {
        @Volatile private var instance: WebRTCFactory? = null
        @Volatile private var pendingConfig: Config = Config()
        private val lock = Any()

        /**
         * E8 — configuration knob for the encoder factory. Must be
         * set BEFORE the first [obtain] call (PeerConnectionFactory
         * is process-singleton + can't be reconfigured after init).
         * Calling after init is a no-op + logs a warning.
         */
        @JvmStatic
        fun configure(config: Config) {
            synchronized(lock) {
                if (instance != null) {
                    Log.w("TwoStarsSDK",
                        "WebRTCFactory.configure called AFTER init — ignored. " +
                        "Set config before first Room.connect().")
                    return
                }
                pendingConfig = config
            }
        }

        /**
         * E8 — factory configuration.
         *
         * @property hardwareEncoderPreferred when true (default), use
         *   the OS's hardware video encoders where available (saves
         *   ~3× battery + reduces device heat); software fallback for
         *   codecs/profiles the chip doesn't support. Set false to
         *   force software-only (useful for testing on devices with
         *   buggy vendor encoders, or to verify behaviour parity).
         * @property enableIntelVp8Encoder forwarded to libwebrtc;
         *   enables the Intel-shipped VP8 hardware encoder on x86
         *   Android devices (Chromebooks, emulators). Harmless on ARM.
         * @property enableH264HighProfile forwarded to libwebrtc; lets
         *   negotiate the higher-quality H.264 profile when both ends
         *   support it. Lower bitrate at the same visual quality;
         *   slightly higher CPU on encode. Default true.
         */
        // Config + EncoderInfo are now top-level public data classes in
        // package io.twostars.sdk — see WebRTCConfig.kt. They were lifted
        // out of this companion because an `internal class`'s public
        // members are visibility-capped to internal, which made
        // StarsClient.configureWebRTC / Room.encoderInfo unreachable.
        //
        // The names "Config" and "EncoderInfo" still resolve here via
        // the `import ... as Config` alias at the top of this file, so
        // the call sites below (line 56, 66, 232, etc.) compile without
        // rewriting.

        /**
         * The currently-installed mic sink, fed by the ADM's
         * SamplesReadyCallback. Process-singleton because Android only
         * has one mic; the active [io.twostars.sdk.Room] owns
         * installation/removal via [installAudioSink].
         */
        @Volatile
        private var audioSamplesSink: AudioSamplesSink? = null

        /**
         * Install [sink] as the receiver for mic frames captured by the
         * WebRTC ADM. Pass `null` to detach. No-op if WebRTC hasn't
         * been initialised yet (the next [obtain] will pick up the
         * installed sink).
         */
        @JvmStatic
        internal fun installAudioSink(sink: AudioSamplesSink?) {
            audioSamplesSink = sink
        }

        fun obtain(context: Context): WebRTCFactory {
            instance?.let { return it }
            return synchronized(lock) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Bridge from libwebrtc-ktx's log surface to android.util.Log
         * under a stable tag. Quiet by default — only WARNING + above
         * are forwarded (the loggableSeverity below is the gate).
         */
        private val LOG_HANDLER = object : LogHandler {
            override fun log(
                priority: Int,
                tag: String?,
                t: Throwable?,
                message: String?,
                vararg args: Any?,
            ) {
                val msg = message?.let { if (args.isNotEmpty()) it.format(*args) else it } ?: ""
                Log.println(priority, tag ?: "TwoStarsSDK", t?.let { "$msg\n$it" } ?: msg)
            }
        }

        /**
         * Tear down the process-wide WebRTC state. Useful in tests; in a
         * real app the OS reclaims everything when the process exits.
         * After calling this, the next [obtain] re-initialises from scratch.
         */
        fun releaseProcessSingleton() {
            synchronized(lock) {
                instance?.let {
                    runCatching { it.factory.dispose() }
                    runCatching { it.audioDeviceModule.release() }
                    runCatching { it.eglBase.release() }
                }
                instance = null
            }
        }

        private fun build(appContext: Context): WebRTCFactory {
            // MediasoupClient.initialize loads the bundled native lib,
            // calls PeerConnectionFactory.initialize for us, and sets up
            // the libwebrtc-ktx extension surface. Calling
            // PeerConnectionFactory.initialize directly would crash with
            // "libjingle_peerconnection_so.so not found" — that .so isn't
            // shipped as a separate file; libmediasoup statically linked
            // libwebrtc's symbols into libmediasoupclient_so.so.
            //
            // Requires the application Context (not just any Context) —
            // the underlying native code calls into Activity Manager
            // services that are application-scoped.
            MediasoupClient.initialize(
                context = appContext as Application,
                logHandler = LOG_HANDLER,
                loggableSeverity = Logging.Severity.LS_WARNING,
            )
            val egl = EglBase.create()
            val adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                // Forward every mic frame WebRTC captures to whichever
                // [AudioCaptureSink] is currently installed (one per
                // process — set by [Room.startTranscription]). Tapping
                // the ADM avoids opening a parallel AudioRecord that
                // would compete with WebRTC for the mic.
                .setSamplesReadyCallback { samples ->
                    audioSamplesSink?.onSamples(samples)
                }
                .createAudioDeviceModule()
            // E8 — encoder factory selection.
            //   hardwareEncoderPreferred=true (default): DefaultVideoEncoderFactory
            //     internally tries hardware first + falls back to software per-
            //     codec. ~3× battery savings on most ARM SoCs that have hw H264.
            //   hardwareEncoderPreferred=false: SoftwareVideoEncoderFactory only
            //     — useful for devices with buggy vendor encoders OR for
            //     verifying behaviour parity in QA.
            val cfg = pendingConfig
            val encoderFactory: VideoEncoderFactory = if (cfg.hardwareEncoderPreferred) {
                DefaultVideoEncoderFactory(
                    egl.eglBaseContext,
                    /* enableIntelVp8Encoder = */ cfg.enableIntelVp8Encoder,
                    /* enableH264HighProfile = */ cfg.enableH264HighProfile,
                )
            } else {
                SoftwareVideoEncoderFactory()
            }
            val supported = runCatching {
                encoderFactory.supportedCodecs.map { it.name }
            }.getOrDefault(emptyList())
            val backend = if (cfg.hardwareEncoderPreferred) {
                // DefaultVideoEncoderFactory wraps a HardwareVideoEncoderFactory
                // + a SoftwareVideoEncoderFactory; if the device has at least
                // one HW codec, we'll use it for negotiated codecs that match.
                val hw = HardwareVideoEncoderFactory(
                    egl.eglBaseContext, cfg.enableIntelVp8Encoder, cfg.enableH264HighProfile)
                if (runCatching { hw.supportedCodecs.isNotEmpty() }.getOrDefault(false)) "hardware"
                else "software"
            } else "software"
            Log.i("TwoStarsSDK", "WebRTCFactory encoder backend=$backend codecs=$supported")

            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
            val info = EncoderInfo(
                backend = backend,
                hardwareEncoderPreferred = cfg.hardwareEncoderPreferred,
                supportedCodecs = supported,
            )
            return WebRTCFactory(egl, factory, adm, info)
        }
    }
}
