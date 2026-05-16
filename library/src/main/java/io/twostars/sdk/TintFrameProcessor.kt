package io.twostars.sdk

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/**
 * Diagnostic processor that pushes the incoming frame's chroma
 * (U/V planes) toward 0, which tints everything red. Useful purely
 * as a smoke test to prove the [FrameProcessor] pipeline is wired —
 * if you see red faces on the receiver side, the camera frame
 * actually flowed through your processor.
 *
 * Production use: pick a real processor (virtual background, auto-
 * frame, etc.). This one's here for development.
 *
 * Implementation note: we operate directly on the I420 buffer's
 * plane bytes (no Bitmap round-trip), so this is fast — well under
 * the 33ms budget at 720p / 30fps.
 */
public class TintFrameProcessor : FrameProcessor() {

    override fun processFrame(input: VideoFrame): VideoFrame {
        val src = input.buffer.toI420() ?: return input
        try {
            val w = src.width
            val h = src.height
            val out = JavaI420Buffer.allocate(w, h)
            try {
                // Y stays unchanged (luma = brightness; we want a
                // recognisable face, not a black frame).
                copyPlane(src.dataY, src.strideY, out.dataY, out.strideY, w, h)
                // Push U + V to fixed values — Y'CbCr math: U=128 V≈200
                // produces a strong red shift that's hard to miss.
                fillPlane(out.dataU, out.strideU, w / 2, h / 2, value = 128)
                fillPlane(out.dataV, out.strideV, w / 2, h / 2, value = 200)
            } catch (t: Throwable) {
                out.release()
                throw t
            }
            // VideoFrame takes ownership of the buffer's existing
            // refcount; the encoder/sink calls release() when done.
            return VideoFrame(out, input.rotation, input.timestampNs)
        } finally {
            src.release()
        }
    }

    private fun copyPlane(
        src: java.nio.ByteBuffer, srcStride: Int,
        dst: java.nio.ByteBuffer, dstStride: Int,
        width: Int, height: Int,
    ) {
        val rowBytes = width
        val srcDup = src.duplicate(); val dstDup = dst.duplicate()
        for (row in 0 until height) {
            srcDup.position(row * srcStride).limit(row * srcStride + rowBytes)
            dstDup.position(row * dstStride)
            dstDup.put(srcDup)
        }
    }

    private fun fillPlane(
        dst: java.nio.ByteBuffer, dstStride: Int,
        width: Int, height: Int,
        value: Int,
    ) {
        val dstDup = dst.duplicate()
        val targetByte = value.toByte()
        val row = ByteArray(width) { targetByte }
        for (y in 0 until height) {
            dstDup.position(y * dstStride)
            dstDup.put(row)
        }
    }
}
