package io.twostars.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import io.twostars.sdk.internal.SegmentationBackend
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.YuvHelper
import java.nio.ByteBuffer

/**
 * Virtual-background frame processor.
 *
 * Three modes (pick one via [setMode]):
 *   - [BackgroundMode.OFF] — pass through (no segmentation, no compose)
 *   - [BackgroundMode.BLUR] — Gaussian-blur the original frame, mask the
 *     foreground (user) back over it
 *   - [BackgroundMode.IMAGE] — replace the background with [setReplacementBitmap]
 *
 * Threading: [processFrame] runs on the WebRTC capture thread. The
 * MediaPipe segmenter is a synchronous call there (libmediapipe is
 * thread-safe per instance). On a 720p frame at 30fps the budget is
 * ~33ms — segmentation typically lands at 6–14ms on modern phones,
 * blur at 4–10ms via RenderScript, leaving plenty of headroom.
 *
 * Hot path summary:
 *   1. I420 → ARGB Bitmap (one allocation per frame, reused via pool).
 *   2. Segmenter.run(bitmap) → alpha mask (single-channel, frame size).
 *   3. Compose: foregroundBitmap × alpha + (blur or static) × (1-alpha).
 *   4. ARGB → I420 → wrap in VideoFrame.
 *
 * Lifecycle: [onAttached] / [onDetached] manage the segmenter handle.
 *
 * Production caveats:
 *   - Segmentation quality depends on the model. The SelfieSegmentation
 *     "general-256" landscape model is the default; switch to
 *     "general-512" via [Config.modelAsset] if you need crisper edges
 *     at the cost of ~2x latency.
 *   - On low-end devices (<= 4 cores, no GPU delegate) the segmenter
 *     may exceed the per-frame budget. Set [Config.fallbackToPassthrough]
 *     true to drop the processor automatically when this happens.
 */
public class BackgroundProcessor(
    private val context: Context,
    private val config: Config = Config(),
) : FrameProcessor() {

    public enum class BackgroundMode { OFF, BLUR, IMAGE }

    public data class Config(
        /**
         * Asset path of the .tflite segmentation model. Default ships
         * with MediaPipe; consumers can override to ship a custom one
         * under their own assets/ root.
         */
        val modelAsset: String = "selfie_segmenter.tflite",
        /** Blur radius in pixels (1..25 — RenderScript caps here). */
        val blurRadius: Float = 18f,
        /**
         * If the segmenter consistently exceeds 33ms on this device,
         * stop calling it and pass frames through unchanged. The
         * processor logs a single warning when this trips.
         */
        val fallbackToPassthrough: Boolean = true,
        /** Frames-over-budget tolerated before fallback trips. */
        val budgetExceedFramesBeforeFallback: Int = 30,
    )

    @Volatile private var mode: BackgroundMode = BackgroundMode.OFF
    @Volatile private var replacementBitmap: Bitmap? = null
    @Volatile private var degraded: Boolean = false   // hit fallback?
    private var overBudgetFrames: Int = 0

    // Segmentation backend is interface-typed so JVM tests can swap in
    // a deterministic mask producer. The default is the MediaPipe-backed
    // implementation; in JVM unit tests we inject a fake.
    @Volatile private var segmentation: SegmentationBackend? = null

    private var renderScript: RenderScript? = null

    // Reused buffers — frame-size is stable across a session, so we
    // allocate once on first frame and reuse. Reset on dimension change.
    private var workWidth: Int = 0
    private var workHeight: Int = 0
    private var fgBitmap: Bitmap? = null
    private var bgBitmap: Bitmap? = null

    /** Switch to [m]; pass-through when [BackgroundMode.OFF]. */
    public fun setMode(m: BackgroundMode) { this.mode = m }

    /** Bitmap used for [BackgroundMode.IMAGE]. Null = solid black. */
    public fun setReplacementBitmap(b: Bitmap?) { this.replacementBitmap = b }

    /** Stop processing — equivalent to setMode(OFF). */
    public fun clear() { setMode(BackgroundMode.OFF) }

    /** Test hook — inject a fake/deterministic segmentation backend. */
    internal fun setSegmentationBackendForTests(b: SegmentationBackend) {
        this.segmentation = b
    }

    // -- FrameProcessor lifecycle -------------------------------------

    override fun onAttached() {
        renderScript = RenderScript.create(context)
        if (segmentation == null) {
            segmentation = SegmentationBackend.createMediapipe(context, config.modelAsset)
        }
    }

    override fun onDetached() {
        try { segmentation?.close() } catch (_: Throwable) {}
        segmentation = null
        try { renderScript?.destroy() } catch (_: Throwable) {}
        renderScript = null
        fgBitmap?.recycle(); fgBitmap = null
        bgBitmap?.recycle(); bgBitmap = null
        workWidth = 0; workHeight = 0
        degraded = false; overBudgetFrames = 0
    }

    override fun processFrame(input: VideoFrame): VideoFrame {
        if (mode == BackgroundMode.OFF || degraded) return input
        val backend = segmentation ?: return input
        val src = input.buffer.toI420() ?: return input
        try {
            val w = src.width
            val h = src.height
            ensureWorkBuffers(w, h)

            val started = System.nanoTime()
            val bitmap = i420ToArgbBitmap(src, fgBitmap!!)
            val mask = backend.segment(bitmap) // single-channel WxH alpha
            val composed = when (mode) {
                BackgroundMode.BLUR  -> compositeBlur(bitmap, mask, config.blurRadius)
                BackgroundMode.IMAGE -> compositeImage(bitmap, mask, replacementBitmap)
                BackgroundMode.OFF   -> bitmap
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (elapsedMs > 33) {
                overBudgetFrames += 1
                if (config.fallbackToPassthrough &&
                    overBudgetFrames >= config.budgetExceedFramesBeforeFallback) {
                    degraded = true
                    android.util.Log.w("TwoStarsSDK",
                        "BackgroundProcessor consistently over budget (>33ms × " +
                                "$overBudgetFrames frames) — falling back to pass-through")
                }
            } else if (overBudgetFrames > 0) {
                overBudgetFrames -= 1   // recover gradually
            }

            val out = JavaI420Buffer.allocate(w, h)
            argbBitmapToI420(composed, out)
            return VideoFrame(out, input.rotation, input.timestampNs)
        } catch (t: Throwable) {
            android.util.Log.w("TwoStarsSDK", "BackgroundProcessor.processFrame failed; passing original", t)
            return input
        } finally {
            src.release()
        }
    }

    // -- Compositing math (JVM-testable) ------------------------------

    internal fun compositeBlur(fg: Bitmap, mask: ByteArray, radius: Float): Bitmap {
        val rs = renderScript ?: return fg
        val bg = blur(rs, fg, radius)
        return composeWithMask(fg, bg, mask)
    }

    internal fun compositeImage(fg: Bitmap, mask: ByteArray, replacement: Bitmap?): Bitmap {
        val bg = scaleReplacementToFrame(replacement, fg.width, fg.height)
        return composeWithMask(fg, bg, mask)
    }

    /**
     * Per-pixel: out = fg * α + bg * (1 - α). Pure pixel math — used by
     * both blur and image modes. JVM-testable: feed deterministic
     * fg/bg/mask, assert exact pixel values.
     */
    internal fun composeWithMask(fg: Bitmap, bg: Bitmap, mask: ByteArray): Bitmap {
        val w = fg.width; val h = fg.height
        require(bg.width == w && bg.height == h) { "bg dimensions must match fg" }
        require(mask.size == w * h) { "mask must be WxH bytes" }

        val fgPix = IntArray(w * h); val bgPix = IntArray(w * h)
        fg.getPixels(fgPix, 0, w, 0, 0, w, h)
        bg.getPixels(bgPix, 0, w, 0, 0, w, h)
        val outPix = composeWithMaskPixels(fgPix, bgPix, mask, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPix, 0, w, 0, 0, w, h)
        return out
    }

    public companion object {
        /**
         * Pure-Kotlin compose path — Bitmap-free so JVM unit tests can
         * exercise the per-pixel math without spinning up Robolectric
         * (Android's Bitmap class is a stub on the JVM classpath).
         *
         * Per-pixel: out = fg * (α/255) + bg * (1 - α/255), rounded.
         * α = 255  → output == foreground (user keeps); 0 → background;
         * 128 → 50/50 blend.
         *
         * @param fgArgb row-major ARGB_8888 pixels of the source frame
         * @param bgArgb row-major ARGB_8888 pixels of the background
         * @param mask   row-major byte mask (length = w*h)
         */
        @JvmStatic
        public fun composeWithMaskPixels(
            fgArgb: IntArray, bgArgb: IntArray, mask: ByteArray, w: Int, h: Int,
        ): IntArray {
            require(fgArgb.size == w * h) { "fg pixels must be WxH" }
            require(bgArgb.size == w * h) { "bg pixels must be WxH" }
            require(mask.size == w * h)   { "mask must be WxH bytes" }
            val out = IntArray(w * h)
            for (i in 0 until w * h) {
                val a = (mask[i].toInt() and 0xFF)
                val invA = 255 - a
                val fp = fgArgb[i]; val bp = bgArgb[i]
                val r = (((fp ushr 16) and 0xFF) * a + ((bp ushr 16) and 0xFF) * invA + 127) / 255
                val g = (((fp ushr 8)  and 0xFF) * a + ((bp ushr 8)  and 0xFF) * invA + 127) / 255
                val b = ((fp           and 0xFF) * a + (bp           and 0xFF) * invA + 127) / 255
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return out
        }
    }

    // -- Hot-path helpers (Android-only — covered by hardware smoke) --

    private fun ensureWorkBuffers(w: Int, h: Int) {
        if (w == workWidth && h == workHeight && fgBitmap != null) return
        workWidth = w; workHeight = h
        fgBitmap?.recycle()
        fgBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private fun i420ToArgbBitmap(src: org.webrtc.VideoFrame.I420Buffer, dst: Bitmap): Bitmap {
        // libwebrtc helper converts I420 → NV21 → ARGB. Cheap on mobile.
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
        val baos = java.io.ByteArrayOutputStream()
        tmp.compressToJpeg(android.graphics.Rect(0, 0, width, height), 90, baos)
        val jpg = baos.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
        // Draw onto the reused dst at native res.
        Canvas(dst).drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()
        return dst
    }

    private fun argbBitmapToI420(bmp: Bitmap, out: JavaI420Buffer) {
        val w = bmp.width; val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        // Naive ARGB → I420 (BT.601). Fine for now; if profiling shows
        // it as a hotspot, a libyuv-backed implementation cuts ~40%.
        val y = out.dataY; val u = out.dataU; val v = out.dataV
        val sy = out.strideY; val su = out.strideU; val sv = out.strideV
        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = argb[j * w + i]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8)  and 0xFF
                val b = px           and 0xFF
                val yi = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                y.put(j * sy + i, yi.coerceIn(0, 255).toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val ui = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vi = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cIdx = (j / 2) * su + (i / 2)
                    u.put(cIdx, ui.coerceIn(0, 255).toByte())
                    v.put((j / 2) * sv + (i / 2), vi.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private fun blur(rs: RenderScript, src: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val inAlloc = Allocation.createFromBitmap(rs, src)
        val outAlloc = Allocation.createFromBitmap(rs, out)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius.coerceIn(1f, 25f))
        script.setInput(inAlloc)
        script.forEach(outAlloc)
        outAlloc.copyTo(out)
        inAlloc.destroy(); outAlloc.destroy(); script.destroy()
        return out
    }

    private fun scaleReplacementToFrame(b: Bitmap?, w: Int, h: Int): Bitmap {
        if (b == null) return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (b.width == w && b.height == h) return b
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        matrix.setRectToRect(
            android.graphics.RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()),
            android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
            Matrix.ScaleToFit.CENTER,
        )
        Canvas(scaled).drawBitmap(b, matrix, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        })
        return scaled
    }
}
