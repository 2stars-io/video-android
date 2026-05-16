package io.twostars.sdk

/**
 * Capture constraints handed to [Room.publish]. Defaults match the
 * profile the JS SDK ships with — 720p30 + AEC/NS audio — so a
 * cross-platform call between Android and web clients negotiates
 * identical encoder configurations.
 *
 * For audio-only / video-only publishes, set the corresponding
 * boolean to `false`.
 *
 * @property video      Enable video capture. Default `true`.
 * @property audio      Enable audio capture. Default `true`.
 * @property videoWidth Requested capture width in pixels.
 * @property videoHeight Requested capture height in pixels.
 * @property videoFps   Requested capture frame rate.
 * @property cameraFacing Which physical camera to start with.
 */
public data class PublishConstraints(
    val video: Boolean = true,
    val audio: Boolean = true,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val videoFps: Int = 30,
    val cameraFacing: CameraFacing = CameraFacing.FRONT,
) {
    public companion object {
        @JvmField
        public val DEFAULT: PublishConstraints = PublishConstraints()

        @JvmField
        public val AUDIO_ONLY: PublishConstraints = PublishConstraints(video = false)
    }
}

public enum class CameraFacing { FRONT, BACK }
