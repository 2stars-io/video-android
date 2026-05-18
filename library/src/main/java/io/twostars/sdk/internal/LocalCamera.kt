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
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
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
     * Callbacks for camera-pipeline lifecycle. Room.kt registers one of
     * these to surface track-ended / track-recovered events at the
     * public [io.twostars.sdk.RoomEvent.LocalTrack*] level. All callbacks
     * are invoked on the WebRTC camera thread - listeners must not block.
     */
    internal interface VideoEventsListener {
        /**
         * Capture failed mid-stream - sensor revoked, USB camera unplugged,
         * focus lost to another foreground app, or vendor-driver kicked us
         * out. [reason] is the libwebrtc error string, may be `null`.
         */
        fun onCaptureError(reason: String?)

        /** First frame received after a [resumeCapture] / retryVideoCapture. */
        fun onCaptureRecovered()
    }

    @Volatile private var videoEventsListener: VideoEventsListener? = null
    @Volatile private var lastErrorAt: Long = 0L
    @Volatile private var sawErrorSinceLastFrame: Boolean = false

    internal fun setVideoEventsListener(listener: VideoEventsListener?) {
        videoEventsListener = listener
    }

    /**
     * Attempt to recover a wedged or stopped video capturer. Best-effort:
     * stops the current capture and re-starts it with the same
     * constraints. Returns true if startCapture succeeded.
     *
     * Does NOT recreate the capturer / source / track - the track ID and
     * any active senders / SFU producers stay bound to the same
     * VideoTrack reference, so the SDP doesn't need renegotiation.
     */
    internal fun retryVideoCapture(): Boolean {
        val cap = capturer ?: return false
        return try {
            try { cap.stopCapture() } catch (_: Throwable) { /* drop */ }
            cap.startCapture(constraints.videoWidth, constraints.videoHeight, constraints.videoFps)
            sawErrorSinceLastFrame = true   // recovery completes once a frame arrives
            true
        } catch (_: Throwable) {
            false
        }
    }

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
            val videoBundle =
                if (constraints.video) buildVideo(context, factory, constraints)
                else VideoBundle.EMPTY

            val (audioSource, audioTrack) =
                if (constraints.audio) buildAudio(factory)
                else null to null

            val cam = LocalCamera(
                factory = factory,
                capturer = videoBundle.capturer,
                helper = videoBundle.helper,
                videoSource = videoBundle.source,
                videoTrack = videoBundle.track,
                audioSource = audioSource,
                audioTrack = audioTrack,
                constraints = constraints,
                context = context.applicationContext,
            )
            // Back-link so the CameraEventsHandler closure can look up
            // the listener / dedup fields.
            videoBundle.owner = cam
            return cam
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

            // We construct one bundle and pass a closure to the
            // CameraEventsHandler so the listener gets resolved at call
            // time (Room.kt sets the listener AFTER LocalCamera.create
            // returns, since LocalCamera is what Room.publish wraps in
            // a LocalMedia).
            val bundleHolder = arrayOfNulls<VideoBundle>(1)
            val events = object : CameraEventsHandler {
                override fun onCameraError(err: String?) {
                    notifyError(bundleHolder[0], err)
                }
                override fun onCameraDisconnected() {
                    notifyError(bundleHolder[0], "camera-disconnected")
                }
                override fun onCameraFreezed(err: String?) {
                    notifyError(bundleHolder[0], err ?: "camera-freezed")
                }
                override fun onCameraOpening(cameraName: String?) { /* no-op */ }
                override fun onFirstFrameAvailable() {
                    bundleHolder[0]?.let { notifyRecovered(it) }
                }
                override fun onCameraClosed() { /* no-op - only fires on dispose */ }
            }

            val capturer = enumerator.createCapturer(cameraName, events)
            val helper = SurfaceTextureHelper.create(
                "TwoStarsLocalCameraThread",
                factory.eglBase.eglBaseContext,
            )
            val source = factory.factory.createVideoSource(/* isScreencast = */ false)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(constraints.videoWidth, constraints.videoHeight, constraints.videoFps)
            val track = factory.factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, source)
            track.setEnabled(true)
            val bundle = VideoBundle(capturer, helper, source, track)
            bundleHolder[0] = bundle
            return bundle
        }

        // Used by the CameraEventsHandler closure to look up the per-
        // instance listener Room.kt registered.
        private fun notifyError(bundle: VideoBundle?, reason: String?) {
            val owner = bundle?.owner ?: return
            // De-dupe identical errors fired within 200ms - vendor
            // drivers occasionally fire onCameraError + onCameraFreezed
            // back-to-back for the same underlying fault.
            val now = System.currentTimeMillis()
            if (now - owner.lastErrorAt < 200) return
            owner.lastErrorAt = now
            owner.sawErrorSinceLastFrame = true
            try { owner.videoEventsListener?.onCaptureError(reason) } catch (_: Throwable) { /* drop */ }
        }

        private fun notifyRecovered(bundle: VideoBundle) {
            val owner = bundle.owner ?: return
            // Only fire "recovered" if we actually had an error since
            // the last frame - onFirstFrameAvailable also fires on the
            // initial startCapture, which isn't a recovery.
            if (!owner.sawErrorSinceLastFrame) return
            owner.sawErrorSinceLastFrame = false
            try { owner.videoEventsListener?.onCaptureRecovered() } catch (_: Throwable) { /* drop */ }
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

    private class VideoBundle(
        val capturer: VideoCapturer?,
        val helper: SurfaceTextureHelper?,
        val source: VideoSource?,
        val track: VideoTrack?,
    ) {
        // Set by LocalCamera.create once the bundle has been wrapped in
        // a LocalCamera. The camera-events closure uses this to look up
        // the per-instance listener / dedup state.
        @Volatile var owner: LocalCamera? = null
        @Volatile var lastError: String? = null

        companion object {
            val EMPTY = VideoBundle(null, null, null, null)
        }
    }
}
