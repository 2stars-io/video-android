package io.twostars.sdk

import io.twostars.sdk.internal.LocalCamera
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

/**
 * Caller-facing handle to the local media this client is publishing
 * into the room. Returned by [Room.publish].
 *
 * Use [videoTrack] to render a self-view (`videoTrack.addSink(renderer)`
 * where `renderer` is a `SurfaceViewRenderer` you've initialised with
 * the SDK's shared EGL context — see the sample app).
 *
 * Mute toggles flip the track's `enabled` flag without releasing the
 * capture pipeline, so the camera light stays on but no encoded bytes
 * leave the device. To fully release the camera, call [Room.unpublish].
 */
public class LocalMedia internal constructor(
    private val camera: LocalCamera,
) {

    /** Local camera track, or `null` when publishing audio-only. */
    public val videoTrack: VideoTrack? get() = camera.videoTrack

    /** Local mic track, or `null` when publishing video-only. */
    public val audioTrack: AudioTrack? get() = camera.audioTrack

    /** True when the audio track is currently muted (no bytes on the wire). */
    public var isMuted: Boolean = false
        private set

    /** True when the video track is currently enabled. */
    public var isVideoEnabled: Boolean = true
        private set

    public fun setMuted(muted: Boolean) {
        isMuted = muted
        camera.audioTrack?.setEnabled(!muted)
    }

    public fun setVideoEnabled(enabled: Boolean) {
        isVideoEnabled = enabled
        camera.videoTrack?.setEnabled(enabled)
    }

    /** Convenience inverse of [setMuted]. */
    public fun toggleMute() { setMuted(!isMuted) }

    /** Convenience inverse of [setVideoEnabled]. */
    public fun toggleVideo() { setVideoEnabled(!isVideoEnabled) }

    /**
     * Best-effort camera flip. Throws when the device doesn't expose a
     * camera matching the requested facing (e.g. `BACK` on a webcam-
     * only Chromebook).
     */
    public fun switchCamera(facing: CameraFacing): CameraFacing =
        camera.switchCamera(facing)

    /**
     * Stop pulling camera frames without tearing down the capture
     * pipeline. Call from `Activity.onPause` so other apps can use
     * the camera while you're backgrounded; pair with [resumeCapture]
     * in `onResume` to start frames flowing again. Tracks remain
     * attached to every peer connection — only the source is paused.
     */
    public fun pauseCapture() {
        camera.pauseCapture()
    }

    /** Resume camera capture after [pauseCapture]. Idempotent. */
    public fun resumeCapture() {
        camera.resumeCapture()
    }

    /**
     * Install [processor] as the per-frame transform for the local
     * camera — call with `null` to remove. The processor runs on
     * the WebRTC capture thread and replaces every frame between
     * the camera and the encoder; see [FrameProcessor] for the
     * threading + lifecycle contract.
     *
     * Use for virtual background (MediaPipe SelfieSegmentation),
     * auto-frame (FaceDetection + crop/scale), or any per-frame
     * filter. No-op if this LocalMedia is audio-only (no video
     * track, hence no source to install on).
     */
    public fun setVideoProcessor(processor: FrameProcessor?) {
        camera.setVideoProcessor(processor)
    }

    internal fun stop() {
        camera.stop()
    }
}
