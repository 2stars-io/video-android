package io.twostars.sdk.internal

import io.twostars.sdk.FrameProcessor
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

/**
 * Bridges the SDK's [FrameProcessor] surface to libwebrtc's
 * [VideoProcessor] callback interface.
 *
 * Subtle bit: [VideoProcessor.setSink] can fire before any frames
 * arrive (during install) and again at teardown with a `null` sink.
 * We hold the sink in a volatile field so the capture thread can
 * read the latest value without a lock per frame.
 *
 * Lifecycle:
 *   - constructed once when [LocalMedia.setVideoProcessor] gets a
 *     non-null processor;
 *   - installed via `videoSource.setVideoProcessor(this)`;
 *   - replaced (or removed) by another bridge or `null` when the
 *     processor is swapped out.
 */
internal class VideoProcessorBridge(
    private val processor: FrameProcessor,
) : VideoProcessor {

    @Volatile private var sink: VideoSink? = null

    fun attach() = processor.onAttached()
    fun detach() = processor.onDetached()

    override fun setSink(sink: VideoSink?) { this.sink = sink }

    // libwebrtc's CapturerObserver methods (VideoProcessor extends
    // CapturerObserver). Only onFrameCaptured is interesting; the
    // others just need to exist so the capturer can drive lifecycle.
    override fun onCapturerStarted(success: Boolean) { /* no-op */ }
    override fun onCapturerStopped() { /* no-op */ }

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = try { processor.processFrame(frame) } catch (t: Throwable) {
            android.util.Log.w("TwoStarsSDK", "FrameProcessor threw — passing original frame", t)
            frame
        }
        sink?.onFrame(out)
    }
}
