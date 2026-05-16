package io.twostars.sdk

/**
 * E8 — WebRTC encoder configuration applied at first [StarsClient.connect].
 *
 * Defaults are sensible (hardware encoder preferred, H.264 high profile +
 * Intel VP8 enabled) so most apps don't need to touch this. Pass an
 * instance to [StarsClient.configureWebRTC] BEFORE the first connect.
 * Once `PeerConnectionFactory` is built it can't be reconfigured live.
 *
 * Lifted out of the internal `WebRTCFactory.Companion` so the type is
 * reachable from public API surface (parameter of `configureWebRTC`).
 *
 * @property hardwareEncoderPreferred Try the hardware codec path first.
 *   Falls back to software automatically when the vendor encoder
 *   misbehaves or isn't available. Turn off for QA on devices with
 *   broken vendor codecs.
 * @property enableIntelVp8Encoder Allow the Intel VP8 hardware encoder
 *   on x86 devices. Off-by-default on stock libwebrtc — we enable it.
 * @property enableH264HighProfile Forwarded to libwebrtc; lets the peers
 *   negotiate H.264 high profile when both ends support it. Lower
 *   bitrate at the same visual quality; slightly higher encode CPU.
 */
public data class WebRTCConfig(
    val hardwareEncoderPreferred: Boolean = true,
    val enableIntelVp8Encoder: Boolean = true,
    val enableH264HighProfile: Boolean = true,
)

/**
 * E8 — diagnostic snapshot of which encoder backend is in use and which
 * codecs the selected backend supports. Read via [Room.encoderInfo].
 *
 * Lifted out of the internal `WebRTCFactory.Companion` so the type is
 * reachable from public API surface (`Room.encoderInfo` return type).
 *
 * @property backend "hardware" | "software"
 * @property hardwareEncoderPreferred mirrors the value used at init
 * @property supportedCodecs e.g. `["H264", "VP8", "VP9"]`
 */
public data class EncoderInfo(
    val backend: String,
    val hardwareEncoderPreferred: Boolean,
    val supportedCodecs: List<String>,
)
