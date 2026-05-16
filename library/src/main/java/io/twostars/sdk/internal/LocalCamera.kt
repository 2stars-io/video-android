package io.twostars.sdk.internal

import android.content.Context
import io.twostars.sdk.CameraFacing
import io.twostars.sdk.PublishConstraints
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns the local camera + mic capture pipeline for the duration of a
 * single [io.twostars.sdk.Room.publish] call.
 *
 * Holds (in order of allocation):
 *
 *   capturer (Camera2)  →  SurfaceTextureHelper  →  VideoSource  →  VideoTrack
 *                                                     AudioSource  →  AudioTrack
 *
 * The same VideoTrack / AudioTrack references are then passed to
 * [PeerConnectionPool] which adds them to every outbound P2P sender,
 * AND surfaced on the public [io.twostars.sdk.LocalMedia] handle so the
 * caller can bind them to a `SurfaceViewRenderer` for self-view.
 *
 * Mute / video-toggle flip the underlying track's `enabled` bit so the
 * capture pipeline keeps running (no re-acquire latency when the user
 * unmutes) but no media bytes are sent on the wire.
 */
internal class LocalCamera private constructor(
    private val factory: WebRTCFactory,
    private val capturer: VideoCapturer?,
    private val helper: SurfaceTextureHelper?,
    private val videoSource: VideoSource?,
    val videoTrack: VideoTrack?,
    private val audioSource: AudioSource?,
    val audioTrack: AudioTrack?,
    private val constraints: PublishConstraints,
    private val context: Context,
) {

    /**
     * Active per-frame processor bridge, or `null` if frames flow
     * straight through to the encoder. Swapped via
     * [setVideoProcessor]; [onAttached] / [onDetached] hooks fire
     * here so consumer code can manage GPU contexts / model handles.
     */
    @Volatile private var processorBridge: VideoProcessorBridge? = null

    fun setVideoProcessor(processor: io.twostars.sdk.FrameProcessor?) {
        val source = videoSource ?: return
        // Detach the previous processor first so consumer cleanup
        // runs before we reassign.
        processorBridge?.let {
            it.detach()
            source.setVideoProcessor(null)
        }
        if (processor == null) {
            processorBridge = null
            return
        }
        val bridge = VideoProcessorBridge(processor)
        processorBridge = bridge
        source.setVideoProcessor(bridge)
        bridge.attach()
    }

    /**
     * Best-effort camera flip. Returns the new facing. Throws when the
     * device has no camera matching the request.
     */
    fun switchCamera(facing: CameraFacing): CameraFacing {
        val cap = capturer as? CameraVideoCapturer
            ?: throw IllegalStateException("No video capturer to switch")
        val enumerator = Camera2Enumerator(context)
        val target = pickCamera(enumerator, facing)
            ?: throw IllegalStateException("No camera matching $facing")
        cap.switchCamera(null, target)
        return facing
    }

    fun stop() {
        // Detach any processor first so its onDetached() runs while
        // the underlying VideoSource is still alive (consumers may
        // touch the source — e.g. release a SurfaceTexture binding —
        // during teardown). Then the regular order: stop capture,
        // dispose source, dispose helper.
        setVideoProcessor(null)
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        capturer?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        helper?.dispose()
    }

    /**
     * Stop pulling camera frames without disposing the capture pipeline.
     * Use when the host activity backgrounds — Android (12+) revokes
     * camera frames from non-foreground apps anyway, so explicit stop
     * frees the camera for other apps + saves battery.
     */
    fun pauseCapture() {
        try { capturer?.stopCapture() } catch (_: Throwable) {}
    }

    /**
     * Resume camera capture after [pauseCapture]. The same VideoTrack
     * stays bound to every PC sender / SFU producer — frames just
     * start flowing again.
     */
    fun resumeCapture() {
        val cap = capturer ?: return
        try {
            cap.startCapture(constraints.videoWidth, constraints.videoHeight, constraints.videoFps)
        } catch (_: Throwable) { /* best-effort */ }
    }

    companion object {
        fun create(
            context: Context,
            factory: WebRTCFactory,
            constraints: PublishConstraints,
        ): LocalCamera {
            val (capturer, helper, videoSource, videoTrack) =
                if (constraints.video) buildVideo(context, factory, constraints)
                else VideoBundle.EMPTY

            val (audioSource, audioTrack) =
                if (constraints.audio) buildAudio(factory)
                else null to null

            return LocalCamera(
                factory = factory,
                capturer = capturer,
                helper = helper,
                videoSource = videoSource,
                videoTrack = videoTrack,
                audioSource = audioSource,
                audioTrack = audioTrack,
                constraints = constraints,
                context = context.applicationContext,
            )
        }

        private fun buildVideo(
            context: Context,
            factory: WebRTCFactory,
            constraints: PublishConstraints,
        ): VideoBundle {
            val enumerator = Camera2Enumerator(context)
            val cameraName = pickCamera(enumerator, constraints.cameraFacing)
                ?: enumerator.deviceNames.firstOrNull()
                ?: return VideoBundle.EMPTY

            val capturer = enumerator.createCapturer(cameraName, null)
            val helper = SurfaceTextureHelper.create(
                "TwoStarsLocalCameraThread",
                factory.eglBase.eglBaseContext,
            )
            val source = factory.factory.createVideoSource(/* isScreencast = */ false)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(constraints.videoWidth, constraints.videoHeight, constraints.videoFps)
            val track = factory.factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source)
            track.setEnabled(true)
            return VideoBundle(capturer, helper, source, track)
        }

        private fun buildAudio(factory: WebRTCFactory): Pair<AudioSource, AudioTrack> {
            // googXxx keys mirror the legacy mediasoup-client constraints used
            // by the JS SDK so cross-platform encoding profiles match.
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            val source = factory.factory.createAudioSource(constraints)
            val track = factory.factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source)
            track.setEnabled(true)
            return source to track
        }

        private fun pickCamera(enumerator: CameraEnumerator, facing: CameraFacing): String? {
            val names = enumerator.deviceNames
            return when (facing) {
                CameraFacing.FRONT -> names.firstOrNull { enumerator.isFrontFacing(it) }
                CameraFacing.BACK  -> names.firstOrNull { enumerator.isBackFacing(it) }
            }
        }

        const val LOCAL_VIDEO_TRACK_ID = "twostars-local-video"
        const val LOCAL_AUDIO_TRACK_ID = "twostars-local-audio"
    }

    private data class VideoBundle(
        val capturer: VideoCapturer?,
        val helper: SurfaceTextureHelper?,
        val source: VideoSource?,
        val track: VideoTrack?,
    ) {
        companion object {
            val EMPTY = VideoBundle(null, null, null, null)
        }
    }
}
