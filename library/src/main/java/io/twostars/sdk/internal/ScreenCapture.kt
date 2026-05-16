package io.twostars.sdk.internal

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns the screen-capture pipeline for a single
 * [io.twostars.sdk.Room.startScreenShare] call.
 *
 * Built on top of WebRTC's [ScreenCapturerAndroid], which under the
 * hood wraps Android's `MediaProjection` API. The `consentIntent`
 * passed in must come from a successful `MediaProjectionManager
 * .createScreenCaptureIntent()` consent prompt — the sample app's
 * `requestScreenShareConsent()` shows the canonical pattern.
 *
 * The `videoSource` is initialised with `isScreencast = true` which
 * tells the WebRTC encoder to optimise for the screen-share content
 * profile (typically slower frame rate, higher resolution, less
 * temporal compression than camera video — better for text legibility
 * across the wire).
 *
 * Stop via [stop] (idempotent) or by closing the underlying
 * `MediaProjection` from the system "Stop sharing" notification —
 * the [MediaProjection.Callback.onStop] callback will fire either way.
 */
internal class ScreenCapture private constructor(
    private val capturer: ScreenCapturerAndroid,
    private val helper: SurfaceTextureHelper,
    private val source: VideoSource,
    val track: VideoTrack,
) {

    fun stop() {
        try { capturer.stopCapture() } catch (_: Throwable) {}
        capturer.dispose()
        source.dispose()
        helper.dispose()
    }

    companion object {

        fun create(
            context: Context,
            factory: WebRTCFactory,
            consentIntent: Intent,
            onProjectionStopped: () -> Unit,
        ): ScreenCapture {
            val capturer = ScreenCapturerAndroid(consentIntent, object : MediaProjection.Callback() {
                override fun onStop() {
                    // System-initiated stop (user tapped "Stop sharing" in
                    // the persistent notification, or the OS revoked the
                    // projection). Fire the SDK-level callback so Room
                    // can drop the producer + flip its isSharingScreen
                    // flag.
                    onProjectionStopped()
                }
            })

            val helper = SurfaceTextureHelper.create(
                "TwoStarsScreenCaptureThread",
                factory.eglBase.eglBaseContext,
            )
            val source = factory.factory.createVideoSource(/* isScreencast = */ true)
            capturer.initialize(helper, context.applicationContext, source.capturerObserver)

            // Cap at 1080p / 15fps regardless of actual screen size — full
            // 4K screens at 30fps would saturate residential uplinks. The
            // consumer can render the cap-resolution frame fine; UI tiles
            // are typically 240-720p anyway.
            val (w, h) = clampedScreenSize(context, maxLongEdge = 1920)
            capturer.startCapture(w, h, SCREEN_SHARE_FPS)

            val track = factory.factory.createVideoTrack(SCREEN_TRACK_ID, source)
            track.setEnabled(true)
            return ScreenCapture(capturer, helper, source, track)
        }

        private fun clampedScreenSize(context: Context, maxLongEdge: Int): Pair<Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val long = maxOf(w, h)
            if (long <= maxLongEdge) return w to h
            val scale = maxLongEdge.toDouble() / long
            return (w * scale).toInt() to (h * scale).toInt()
        }

        const val SCREEN_TRACK_ID = "twostars-local-screen"
        private const val SCREEN_SHARE_FPS = 15
    }
}
