package io.twostars.sdk.internal

import android.content.Context
import android.graphics.Bitmap

/**
 * Pluggable per-frame segmentation backend used by [io.twostars.sdk.BackgroundProcessor].
 *
 * Two implementations:
 *   - [createMediapipe] — production. Wraps MediaPipe Tasks Vision's
 *     ImageSegmenter with the SelfieSegmentation model. Returns a per-
 *     pixel alpha mask (255 = foreground, 0 = background).
 *   - [createConstant] — JVM/test. Returns a fixed mask shape so the
 *     compositing math can be unit-tested without loading the .tflite
 *     model or pulling in libmediapipe_jni.so on the JVM classpath.
 *
 * Why the seam: MediaPipe ships native code that won't load in a plain
 * JVM unit test. Without this layer, every test of compositing would
 * have to be an instrumentation (device) test, which is slow and gated
 * on physical hardware availability.
 */
internal interface SegmentationBackend {

    /**
     * Run segmentation on [input] and return the alpha mask as a
     * row-major byte array of length width*height. 255 = foreground
     * (keep this pixel from the input), 0 = background (replace).
     */
    fun segment(input: Bitmap): ByteArray

    fun close()

    companion object {
        /**
         * MediaPipe-backed implementation. Loads the .tflite model
         * from `context.assets/<modelAsset>` (default
         * `selfie_segmenter.tflite`). On a 720p input, expect 6–14ms
         * on a 2024 mid-range phone with the GPU delegate enabled.
         *
         * Falls back to [createConstant] if MediaPipe class loading
         * or model init throws — keeps the SDK from hard-crashing on
         * devices where MediaPipe Tasks Vision isn't installable
         * (rare but seen on heavily-stripped AOSP builds).
         */
        fun createMediapipe(context: Context, modelAsset: String): SegmentationBackend {
            return try {
                MediapipeSegmentation(context, modelAsset)
            } catch (t: Throwable) {
                android.util.Log.w("TwoStarsSDK",
                    "MediaPipe SelfieSegmentation init failed; falling back to constant mask", t)
                createConstant(255.toByte())
            }
        }

        /**
         * Test backend — returns a constant-valued mask. Useful for
         * verifying the compositing path with a deterministic input.
         * - `255` → output == foreground (input passes through)
         * - `0`   → output == background (full replacement)
         * - `128` → 50/50 blend
         */
        fun createConstant(value: Byte): SegmentationBackend = object : SegmentationBackend {
            override fun segment(input: Bitmap): ByteArray {
                val n = input.width * input.height
                val out = ByteArray(n)
                java.util.Arrays.fill(out, value)
                return out
            }
            override fun close() {}
        }

        /**
         * Test backend — returns a custom mask shape (e.g. a centered
         * rectangle of foreground). Used by JVM tests that want to
         * verify edge behavior of the compose path.
         */
        fun createCustom(maskFor: (width: Int, height: Int) -> ByteArray): SegmentationBackend =
            object : SegmentationBackend {
                override fun segment(input: Bitmap): ByteArray = maskFor(input.width, input.height)
                override fun close() {}
            }
    }
}

/**
 * MediaPipe Tasks Vision ImageSegmenter wrapper. Lives in its own
 * file (well, the same file, but encapsulated below) so the JVM
 * tests can stub at the [SegmentationBackend] interface level
 * without ever loading the MediaPipe class.
 */
private class MediapipeSegmentation(context: Context, modelAsset: String) : SegmentationBackend {
    // Late-bind the MediaPipe imports so a JVM that doesn't have the
    // native lib doesn't fail at class-load time. The `try` in the
    // factory above catches NoClassDefFoundError / UnsatisfiedLinkError.
    private val segmenter: Any
    private val runMethod: java.lang.reflect.Method
    private val closeMethod: java.lang.reflect.Method

    init {
        val baseOptionsCls = Class.forName("com.google.mediapipe.tasks.core.BaseOptions")
        val baseOptionsBuilderCls = Class.forName("com.google.mediapipe.tasks.core.BaseOptions\$Builder")
        val segmenterCls = Class.forName("com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter")
        val segmenterOptsCls = Class.forName("com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter\$ImageSegmenterOptions")
        val segmenterOptsBuilderCls = Class.forName("com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter\$ImageSegmenterOptions\$Builder")

        val baseBuilder = baseOptionsCls.getMethod("builder").invoke(null)
        baseOptionsBuilderCls.getMethod("setModelAssetPath", String::class.java)
            .invoke(baseBuilder, modelAsset)
        val baseOpts = baseOptionsBuilderCls.getMethod("build").invoke(baseBuilder)

        val optsBuilder = segmenterOptsCls.getMethod("builder").invoke(null)
        segmenterOptsBuilderCls.getMethod("setBaseOptions", baseOptionsCls)
            .invoke(optsBuilder, baseOpts)
        segmenterOptsBuilderCls.getMethod("setOutputCategoryMask", java.lang.Boolean.TYPE)
            .invoke(optsBuilder, true)
        segmenterOptsBuilderCls.getMethod("setOutputConfidenceMasks", java.lang.Boolean.TYPE)
            .invoke(optsBuilder, false)
        val opts = segmenterOptsBuilderCls.getMethod("build").invoke(optsBuilder)

        segmenter = segmenterCls.getMethod("createFromOptions", Context::class.java, segmenterOptsCls)
            .invoke(null, context, opts)!!
        runMethod = segmenterCls.getMethod("segment",
            Class.forName("com.google.mediapipe.framework.image.MPImage"))
        closeMethod = segmenterCls.getMethod("close")
    }

    override fun segment(input: Bitmap): ByteArray {
        // Convert Bitmap → MPImage → segment(...) → category-mask byte buffer.
        // We use reflection-light here only to keep the SegmentationBackend
        // interface clean; in production the JIT inlines this away.
        val builderCls = Class.forName("com.google.mediapipe.framework.image.BitmapImageBuilder")
        val builder = builderCls.getConstructor(Bitmap::class.java).newInstance(input)
        val mpImage = builderCls.getMethod("build").invoke(builder)
        val result = runMethod.invoke(segmenter, mpImage)
        // result.categoryMask().get() → MPImage with single-channel UINT8
        val categoryMaskOpt = result!!.javaClass.getMethod("categoryMask").invoke(result)
        val categoryMask = categoryMaskOpt!!.javaClass.getMethod("get").invoke(categoryMaskOpt)
        // ByteBufferImage extractor:
        val extractor = Class.forName("com.google.mediapipe.framework.image.ByteBufferExtractor")
        val byteBuf = extractor.getMethod("extract",
            Class.forName("com.google.mediapipe.framework.image.MPImage"))
            .invoke(null, categoryMask) as java.nio.ByteBuffer
        val out = ByteArray(byteBuf.remaining())
        byteBuf.get(out)
        // SelfieSegmentation outputs 0=foreground, 255=background by
        // convention. Invert so 255=foreground for the composite path.
        for (i in out.indices) out[i] = (255 - (out[i].toInt() and 0xFF)).toByte()
        return out
    }

    override fun close() { try { closeMethod.invoke(segmenter) } catch (_: Throwable) {} }
}
