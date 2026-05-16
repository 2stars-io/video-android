package io.twostars.sdk

import org.webrtc.VideoFrame

/**
 * Per-frame transform applied to the local camera before WebRTC
 * encodes + sends it. Subscribe via [LocalMedia.setVideoProcessor]
 * (passing `null` removes any active processor).
 *
 * **Lifecycle.** [onAttached] fires when this processor first goes
 * live on a video source; [onDetached] fires when it's removed
 * (different processor installed, [LocalMedia.setVideoProcessor]
 * called with null, [Room.unpublish], or [Room.leave]). Use them to
 * spin up / tear down ML model handles, GPU contexts, etc.
 *
 * **Threading.** [processFrame] is called on the WebRTC capture
 * thread. It must complete in less than one frame interval (~33ms at
 * 30fps) or the encoder starts dropping frames. If your processor is
 * heavy, kick the work to a worker pool and either:
 *   - return the *original* frame for that tick (skip processing), or
 *   - keep a small queue and return the most recently processed
 *     frame (slight latency, smooth output).
 *
 * **VideoFrame ref-counting.** The frame passed in is retained by
 * the framework — you don't need to release it. If you allocate a
 * brand-new [VideoFrame] (different buffer), the framework releases
 * the original one automatically once you return.
 *
 * Typical implementations:
 *   - virtual background → MediaPipe SelfieSegmentation per frame
 *   - auto-frame → MediaPipe FaceDetection + crop + scale
 *   - filters → colour transform on the I420 Y/U/V planes
 */
public abstract class FrameProcessor {

    /**
     * Transform [input] and return the frame to forward to the
     * encoder. Returning [input] unchanged is fine (pass-through).
     */
    public abstract fun processFrame(input: VideoFrame): VideoFrame

    /** Called once when this processor is installed on the camera. */
    public open fun onAttached() {}

    /** Called once when this processor is removed / replaced. */
    public open fun onDetached() {}
}
