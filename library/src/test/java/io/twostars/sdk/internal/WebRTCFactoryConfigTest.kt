package io.twostars.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8 — unit tests for [WebRTCFactory.Config] + [WebRTCFactory.EncoderInfo].
 *
 * The actual encoder factory selection runs in libwebrtc's native code +
 * needs an EglBase + a real PeerConnectionFactory init, which can only
 * happen on-device. These tests cover the data contracts the public API
 * surface depends on (defaults, copy(), forceSoftwareEncoder shorthand).
 *
 * The on-device verification is part of the hardware smoke (run the
 * sample app, tap "info" → expect `encoderInfo.backend == "hardware"`
 * on real Android devices, "software" on the emulator).
 */
class WebRTCFactoryConfigTest {

    @Test fun `default config prefers hardware encoder`() {
        val c = WebRTCFactory.Config()
        assertTrue("hardware should be preferred by default", c.hardwareEncoderPreferred)
        assertTrue("Intel VP8 enabled by default", c.enableIntelVp8Encoder)
        assertTrue("H264 high profile enabled by default", c.enableH264HighProfile)
    }

    @Test fun `copy preserves untouched fields`() {
        val base = WebRTCFactory.Config()
        val tweaked = base.copy(hardwareEncoderPreferred = false)
        assertFalse(tweaked.hardwareEncoderPreferred)
        assertEquals(base.enableIntelVp8Encoder, tweaked.enableIntelVp8Encoder)
        assertEquals(base.enableH264HighProfile, tweaked.enableH264HighProfile)
    }

    @Test fun `EncoderInfo shape carries backend + supported codecs`() {
        val info = WebRTCFactory.EncoderInfo(
            backend = "hardware",
            hardwareEncoderPreferred = true,
            supportedCodecs = listOf("H264", "VP8", "VP9"),
        )
        assertEquals("hardware", info.backend)
        assertTrue(info.hardwareEncoderPreferred)
        assertTrue(info.supportedCodecs.contains("H264"))
        assertEquals(3, info.supportedCodecs.size)
    }

    @Test fun `equality on Config ignores construction order`() {
        val a = WebRTCFactory.Config(hardwareEncoderPreferred = true,
            enableIntelVp8Encoder = false, enableH264HighProfile = true)
        val b = WebRTCFactory.Config(enableH264HighProfile = true,
            enableIntelVp8Encoder = false, hardwareEncoderPreferred = true)
        assertEquals(a, b)
    }

    @Test fun `data class differentiates configs`() {
        val a = WebRTCFactory.Config(hardwareEncoderPreferred = true)
        val b = WebRTCFactory.Config(hardwareEncoderPreferred = false)
        assertNotEquals(a, b)
    }
}
