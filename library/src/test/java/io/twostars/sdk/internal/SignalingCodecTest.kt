package io.twostars.sdk.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.SessionDescription

/**
 * Coverage for the wire-protocol parsers under [SignalingCodec].
 *
 * The WebRTC [SessionDescription] / [org.webrtc.IceCandidate] classes
 * are pure-Java data holders, so they're safe to instantiate on the
 * JVM without the native libwebrtc loaded.
 */
class SignalingCodecTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `parseSdp accepts an offer`() {
        val obj = json.parseToJsonElement(
            """{"type":"offer","sdp":"v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n"}"""
        ).jsonObject
        val sdp = SignalingCodec.parseSdp(obj)
        assertNotNull(sdp)
        assertEquals(SessionDescription.Type.OFFER, sdp!!.type)
        assertEquals("v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n", sdp.description)
    }

    @Test fun `parseSdp accepts an answer with mixed-case type`() {
        val obj = json.parseToJsonElement("""{"type":"Answer","sdp":"v=0"}""").jsonObject
        val sdp = SignalingCodec.parseSdp(obj)
        assertEquals(SessionDescription.Type.ANSWER, sdp!!.type)
    }

    @Test fun `parseSdp returns null on unknown type`() {
        val obj = json.parseToJsonElement("""{"type":"banana","sdp":"v=0"}""").jsonObject
        assertNull(SignalingCodec.parseSdp(obj))
    }

    @Test fun `parseSdp returns null on missing fields`() {
        val obj = json.parseToJsonElement("""{"type":"offer"}""").jsonObject
        assertNull(SignalingCodec.parseSdp(obj))
        val obj2 = json.parseToJsonElement("""{"sdp":"v=0"}""").jsonObject
        assertNull(SignalingCodec.parseSdp(obj2))
    }

    @Test fun `parseSdp handles null input`() {
        assertNull(SignalingCodec.parseSdp(null))
    }

    @Test fun `parseIce parses a full candidate`() {
        val obj = json.parseToJsonElement(
            """{"candidate":"candidate:1 1 UDP 2013266431 192.0.2.1 56789 typ host","sdpMid":"0","sdpMLineIndex":"0"}"""
        ).jsonObject
        val ice = SignalingCodec.parseIce(obj)
        assertNotNull(ice)
        assertEquals("0", ice!!.sdpMid)
        assertEquals(0, ice.sdpMLineIndex)
        assertEquals("candidate:1 1 UDP 2013266431 192.0.2.1 56789 typ host", ice.sdp)
    }

    @Test fun `parseIce defaults sdpMLineIndex to 0 when missing`() {
        val obj = json.parseToJsonElement(
            """{"candidate":"candidate:foo","sdpMid":"video"}"""
        ).jsonObject
        val ice = SignalingCodec.parseIce(obj)
        assertNotNull(ice)
        assertEquals(0, ice!!.sdpMLineIndex)
    }

    @Test fun `parseIce returns null when candidate missing`() {
        val obj = json.parseToJsonElement("""{"sdpMid":"0","sdpMLineIndex":"0"}""").jsonObject
        assertNull(SignalingCodec.parseIce(obj))
    }
}
