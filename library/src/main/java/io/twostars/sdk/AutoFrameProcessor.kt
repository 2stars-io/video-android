package io.twostars.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import io.twostars.sdk.internal.FaceDetectionBackend
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-frame processor — face-tracking smart crop.
 *
 * Per-frame flow:
 *   1. ML Kit FaceDetection (or any [FaceDetectionBackend]) returns
 *      detected faces' normalized bounding boxes.
 *   2. Pick the LARGEST face as the primary speaker; switch with
 *      hysteresis so a sneeze-sized motion doesn't keep flipping.
 *   3. Compute the target crop window: a 4:3 (or input-aspect) box
 *      centered on the face, scaled so the face occupies ~30% of the
 *      shorter dimension.
 *   4. Smooth (cx, cy, scale) via exponential moving average so the
 *      camera doesn't jitter on micro-movements but still tracks
 *      deliberate motion.
 *   5. Crop + scale the frame to the original resolution and emit.
 *
 * Lost-face behavior: hold the last computed window for [Config.holdMs]
 * (default 2s); if no face appears in that window, gracefully revert
 * to the full frame at α = 0.05 per call so the swing is smooth.
 *
 * Multi-face: if detection returns N > 1 faces, pick the one with the
 * largest area. Hysteresis prevents thrashing when two faces are
 * similarly-sized: switching speakers requires the new candidate to
 * be at least 1.4× the area of the current pick for 3 consecutive
 * frames before the smoothing target updates.
 *
 * Testability: the deterministic math (EMA smoothing, crop window
 * computation, hysteresis) is split into pure functions on the
 * [Companion] so JVM unit tests can verify edge cases (no face,
 * face leaves frame, two faces of similar size) without Bitmap or
 * ML Kit on the classpath.
 */
public class AutoFrameProcessor(
    private val context: Context,
    private val config: Config = Config(),
) : FrameProcessor() {

    public data class Config(
        /** EMA smoothing α — higher = snappier. 0.18 ≈ ~3-frame settle. */
        val emaAlpha: Float = 0.18f,
        /** Hold last window for this long when face is lost (ms). */
        val holdMs: Long = 2000,
        /** Target face occupies this fraction of the shorter side. */
        val targetFaceFraction: Float = 0.30f,
        /** Detect at this fps cap; cheaper than every-frame for ML. */
        val detectionFps: Int = 5,
        /** Switching to a new "primary" face requires this size ratio. */
        val speakerSwitchAreaRatio: Float = 1.4f,
        /** ... for this many consecutive frames. Kills thrashing. */
        val speakerSwitchHysteresisFrames: Int = 3,
    )

    @Volatile private var enabled: Boolean = false

    @Volatile private var detector: FaceDetectionBackend? = null

    // EMA-smoothed crop window in normalized coords:
    //   cx, cy : centre of the crop in [0, 1]
    //   scale  : SHORTER-side size of the crop in [0, 1]
    //            (1.0 = full frame; 0.5 = half size)
    private var window = CropWindow(0.5f, 0.45f, 1.0f)
    private var target = window.copy()

    // Last-seen state for hysteresis + lost-face holding.
    private var lastFaceWallMs: Long = 0
    private var lastFaceArea: Float = 0f
    private var pendingSwitchFrames: Int = 0

    // Frame budget control.
    private var lastDetectMs: Long = 0
    private var lastDetectedBoxes: List<NormalizedBox> = emptyList()

    public fun setEnabled(on: Boolean) { this.enabled = on }
    public fun isEnabled(): Boolean = enabled

    /** Test hook — inject a fake detector so JVM tests work. */
    internal fun setFaceDetectorForTests(d: FaceDetectionBackend) {
        this.detector = d
    }

    override fun onAttached() {
        if (detector == null) {
            detector = FaceDetectionBackend.createMlkit(context)
        }
    }

    override fun onDetached() {
        try { detector?.close() } catch (_: Throwable) {}
        detector = null
        window = CropWindow(0.5f, 0.45f, 1.0f); target = window.copy()
        lastFaceWallMs = 0; lastFaceArea = 0f; pendingSwitchFrames = 0
        lastDetectedBoxes = emptyList(); lastDetectMs = 0
    }

    override fun processFrame(input: VideoFrame): VideoFrame {
        if (!enabled) return input
        val det = detector ?: return input
        val src = input.buffer.toI420() ?: return input
        try {
            val w = src.width; val h = src.height
            val nowMs = System.currentTimeMillis()

            // Detection is rate-capped — runs every (1000/fps) ms.
            val detectIntervalMs = 1000L / max(1, config.detectionFps)
            if (nowMs - lastDetectMs >= detectIntervalMs) {
                val bmp = i420ToArgbBitmap(src)
                lastDetectedBoxes = try { det.detect(bmp) } catch (_: Throwable) { emptyList() }
                bmp.recycle()
                lastDetectMs = nowMs
            }

            // Pick a target window based on the most-recent detection.
            val newTarget = pickTarget(
                boxes = lastDetectedBoxes,
                current = window,
                lastFaceWallMs = lastFaceWallMs,
                nowWallMs = nowMs,
                holdMs = config.holdMs,
                targetFaceFraction = config.targetFaceFraction,
                hysteresis = HysteresisState(
                    lastArea = lastFaceArea,
                    pendingFrames = pendingSwitchFrames,
                    requiredRatio = config.speakerSwitchAreaRatio,
                    requiredFrames = config.speakerSwitchHysteresisFrames,
                ),
            )
            target = newTarget.window
            lastFaceArea = newTarget.lastArea
            pendingSwitchFrames = newTarget.pendingFrames
            if (newTarget.faceSeen) lastFaceWallMs = nowMs

            // EMA smooth toward the target.
            window = ema(window, target, config.emaAlpha)

            // Crop + scale.
            val out = JavaI420Buffer.allocate(w, h)
            cropAndScale(src, out, window)
            return VideoFrame(out, input.rotation, input.timestampNs)
        } catch (t: Throwable) {
            android.util.Log.w("TwoStarsSDK",
                "AutoFrameProcessor.processFrame failed; passing original", t)
            return input
        } finally {
            src.release()
        }
    }

    // -- Hot-path helpers ---------------------------------------------

    private fun i420ToArgbBitmap(src: VideoFrame.I420Buffer): Bitmap {
        val width = src.width; val height = src.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val nv21Buf = ByteBuffer.wrap(nv21)
        YuvHelper.I420ToNV12(
            src.dataY, src.strideY,
            src.dataU, src.strideU,
            src.dataV, src.strideV,
            nv21Buf, width, height,
        )
        val tmp = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val baos = ByteArrayOutputStream()
        tmp.compressToJpeg(android.graphics.Rect(0, 0, width, height), 75, baos)
        val jpg = baos.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
    }

    private fun cropAndScale(src: VideoFrame.I420Buffer, dst: JavaI420Buffer, win: CropWindow) {
        val w = src.width; val h = src.height
        val sw = (win.scale * min(w, h)).toInt().coerceAtLeast(64)
        val sh = sw                                          // 1:1 crop window; scale fills output
        val sx = ((win.cx * w) - sw / 2f).toInt().coerceIn(0, w - sw)
        val sy = ((win.cy * h) - sh / 2f).toInt().coerceIn(0, h - sh)

        // Naive nearest-neighbour up-sample of the crop into the dst
        // plane. Production-grade would use libyuv's ScaleI420 — TODO
        // as a follow-up if profiling shows this as a hotspot.
        for (j in 0 until h) {
            val sj = sy + (j * sh / h)
            for (i in 0 until w) {
                val si = sx + (i * sw / w)
                dst.dataY.put(j * dst.strideY + i, src.dataY.get(sj * src.strideY + si))
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val ui = src.dataU.get((sj / 2) * src.strideU + (si / 2))
                    val vi = src.dataV.get((sj / 2) * src.strideV + (si / 2))
                    dst.dataU.put((j / 2) * dst.strideU + (i / 2), ui)
                    dst.dataV.put((j / 2) * dst.strideV + (i / 2), vi)
                }
            }
        }
    }

    // -- Pure math (JVM-testable) -------------------------------------

    public data class CropWindow(val cx: Float, val cy: Float, val scale: Float)
    public data class NormalizedBox(val cx: Float, val cy: Float, val w: Float, val h: Float) {
        val area: Float get() = w * h
    }
    internal data class HysteresisState(
        val lastArea: Float,
        val pendingFrames: Int,
        val requiredRatio: Float,
        val requiredFrames: Int,
    )
    internal data class PickResult(
        val window: CropWindow,
        val faceSeen: Boolean,
        val lastArea: Float,
        val pendingFrames: Int,
    )

    public companion object {

        /** Exponential moving average toward [target]. */
        @JvmStatic
        public fun ema(current: CropWindow, target: CropWindow, alpha: Float): CropWindow =
            CropWindow(
                cx    = current.cx    + (target.cx    - current.cx)    * alpha,
                cy    = current.cy    + (target.cy    - current.cy)    * alpha,
                scale = current.scale + (target.scale - current.scale) * alpha,
            )

        /**
         * Pick the next target crop window from the latest detection
         * results. Encapsulates: largest-face selection, hysteresis,
         * lost-face hold + revert-to-full, target-face-fraction sizing.
         *
         * Pure: no side effects, no Android imports — JVM-testable.
         */
        @JvmStatic
        internal fun pickTarget(
            boxes: List<NormalizedBox>,
            current: CropWindow,
            lastFaceWallMs: Long,
            nowWallMs: Long,
            holdMs: Long,
            targetFaceFraction: Float,
            hysteresis: HysteresisState,
        ): PickResult {
            // No faces this tick.
            if (boxes.isEmpty()) {
                val sinceFace = nowWallMs - lastFaceWallMs
                if (lastFaceWallMs == 0L || sinceFace > holdMs) {
                    // Slow revert to full-frame — α=0.05 per tick.
                    val reverted = ema(current, CropWindow(0.5f, 0.5f, 1.0f), 0.05f)
                    return PickResult(reverted, faceSeen = false, lastArea = 0f, pendingFrames = 0)
                }
                // Within hold window — keep current target.
                return PickResult(current, faceSeen = false, lastArea = hysteresis.lastArea, pendingFrames = 0)
            }

            // Largest face wins.
            val biggest = boxes.maxByOrNull { it.area }!!
            val biggestArea = biggest.area

            // Hysteresis: switching speakers requires N consecutive
            // frames where biggest is requiredRatio × current's area.
            var pending = hysteresis.pendingFrames
            val isSwitch = hysteresis.lastArea > 0 &&
                biggestArea >= hysteresis.lastArea * hysteresis.requiredRatio
            pending = if (isSwitch) pending + 1 else 0
            val acceptSwitch = pending >= hysteresis.requiredFrames

            // First face ever, or accepted switch: snap target.
            val useThis = hysteresis.lastArea == 0f || acceptSwitch || !isSwitch

            return if (useThis) {
                val faceShorter = min(biggest.w, biggest.h)
                val targetScale = (faceShorter / targetFaceFraction).coerceIn(0.2f, 1.0f)
                PickResult(
                    window = CropWindow(biggest.cx, biggest.cy, targetScale),
                    faceSeen = true,
                    lastArea = biggestArea,
                    pendingFrames = if (acceptSwitch) 0 else pending,
                )
            } else {
                // Mid-hysteresis — hold the previous target.
                PickResult(current, faceSeen = true, lastArea = hysteresis.lastArea, pendingFrames = pending)
            }
        }
    }
}
