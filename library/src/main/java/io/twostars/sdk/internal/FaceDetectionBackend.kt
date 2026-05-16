package io.twostars.sdk.internal

import android.content.Context
import android.graphics.Bitmap
import io.twostars.sdk.AutoFrameProcessor.NormalizedBox

/**
 * Pluggable face-detection backend used by [io.twostars.sdk.AutoFrameProcessor].
 *
 * Production: ML Kit FaceDetection (Play-Services-shipped, no model
 * bundle required for most devices). Tests: deterministic in-memory
 * detector returning hand-crafted box layouts.
 *
 * Why ML Kit over MediaPipe FaceDetection:
 *   - Most Android devices already have Play Services, so the model
 *     ships with the device — no extra download.
 *   - Inference latency comparable (5-12ms on 720p mid-range).
 *   - Smaller binary footprint added to the SDK (~150KB vs ~2MB).
 */
internal interface FaceDetectionBackend {

    /**
     * Detect faces in [input] and return their bounding boxes in
     * NORMALIZED coordinates (cx, cy, w, h all in [0,1] relative to
     * the input frame). Empty list = no faces detected.
     */
    fun detect(input: Bitmap): List<NormalizedBox>

    fun close()

    companion object {

        fun createMlkit(context: Context): FaceDetectionBackend = try {
            MlkitFaceDetection(context)
        } catch (t: Throwable) {
            android.util.Log.w("TwoStarsSDK",
                "ML Kit FaceDetection init failed; auto-frame will be a no-op", t)
            createNoFaces()
        }

        /** Test backend — always returns no faces (revert-to-full-frame path). */
        fun createNoFaces(): FaceDetectionBackend = object : FaceDetectionBackend {
            override fun detect(input: Bitmap): List<NormalizedBox> = emptyList()
            override fun close() {}
        }

        /** Test backend — returns the supplied list every call. */
        fun createConstant(boxes: List<NormalizedBox>): FaceDetectionBackend =
            object : FaceDetectionBackend {
                override fun detect(input: Bitmap): List<NormalizedBox> = boxes
                override fun close() {}
            }
    }
}

/**
 * ML Kit FaceDetection wrapper. Reflection-loaded so a pure-JVM
 * unit-test environment without Play Services on the classpath
 * can compile against [FaceDetectionBackend] without touching
 * com.google.mlkit.* at class-load time.
 */
private class MlkitFaceDetection(@Suppress("UNUSED_PARAMETER") context: Context) : FaceDetectionBackend {
    private val detector: Any
    private val processMethod: java.lang.reflect.Method
    private val closeMethod: java.lang.reflect.Method

    init {
        val optsCls = Class.forName("com.google.mlkit.vision.face.FaceDetectorOptions")
        val optsBuilderCls = Class.forName("com.google.mlkit.vision.face.FaceDetectorOptions\$Builder")
        // PERFORMANCE_MODE_FAST = 1 (lookup constant rather than relying
        // on the public field name to dodge a future minor-version rename).
        val opts = optsBuilderCls.newInstance().let { b ->
            optsBuilderCls.getMethod("setPerformanceMode", java.lang.Integer.TYPE).invoke(b, 1)
            optsBuilderCls.getMethod("setLandmarkMode",   java.lang.Integer.TYPE).invoke(b, 1)
            optsBuilderCls.getMethod("setMinFaceSize",    java.lang.Float.TYPE).invoke(b, 0.10f)
            optsBuilderCls.getMethod("build").invoke(b)
        }

        val faceDetectionCls = Class.forName("com.google.mlkit.vision.face.FaceDetection")
        detector = faceDetectionCls.getMethod("getClient", optsCls).invoke(null, opts)!!
        val faceDetectorCls = Class.forName("com.google.mlkit.vision.face.FaceDetector")
        processMethod = faceDetectorCls.getMethod("process",
            Class.forName("com.google.mlkit.vision.common.InputImage"))
        closeMethod = faceDetectorCls.getMethod("close")
    }

    override fun detect(input: Bitmap): List<NormalizedBox> {
        val inputImageCls = Class.forName("com.google.mlkit.vision.common.InputImage")
        val inputImage = inputImageCls.getMethod("fromBitmap", Bitmap::class.java, java.lang.Integer.TYPE)
            .invoke(null, input, 0)

        val task = processMethod.invoke(detector, inputImage)
        val tasksCls = Class.forName("com.google.android.gms.tasks.Tasks")
        val faces = tasksCls.getMethod("await", Class.forName("com.google.android.gms.tasks.Task"))
            .invoke(null, task) as List<*>

        val w = input.width.toFloat(); val h = input.height.toFloat()
        return faces.mapNotNull { face ->
            val rect = face!!.javaClass.getMethod("getBoundingBox").invoke(face) as android.graphics.Rect
            if (rect.width() <= 0 || rect.height() <= 0) null
            else NormalizedBox(
                cx = (rect.exactCenterX()) / w,
                cy = (rect.exactCenterY()) / h,
                w  = rect.width().toFloat() / w,
                h  = rect.height().toFloat() / h,
            )
        }
    }

    override fun close() { try { closeMethod.invoke(detector) } catch (_: Throwable) {} }
}
