package io.twostars.sdk.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server emits ICE servers in two historically-supported shapes
 * (`urls: [...]` and the legacy `url: "..."`). Both should round-trip
 * into the same [IceServerSpec].
 */
class IceServerCodecTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `parses urls array shape`() {
        val arr = json.parseToJsonElement("""[
            {"urls":["stun:stun.l.google.com:19302","stun:stun1.l.google.com:19302"]}
        ]""").let { it as JsonArray }
        val out = IceServerCodec.parseList(arr)
        assertEquals(1, out.size)
        assertEquals(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"), out[0].urls)
        assertNull(out[0].username)
        assertNull(out[0].credential)
    }

    @Test fun `parses single string urls shape`() {
        val arr = json.parseToJsonElement("""[
            {"urls":"turn:turn.example.com:3478","username":"alice","credential":"s3cr3t"}
        ]""").let { it as JsonArray }
        val out = IceServerCodec.parseList(arr)
        assertEquals(1, out.size)
        assertEquals(listOf("turn:turn.example.com:3478"), out[0].urls)
        assertEquals("alice",  out[0].username)
        assertEquals("s3cr3t", out[0].credential)
    }

    @Test fun `parses legacy single 'url' shape`() {
        val arr = json.parseToJsonElement("""[
            {"url":"stun:stun.example.com:19302"}
        ]""").let { it as JsonArray }
        val out = IceServerCodec.parseList(arr)
        assertEquals(1, out.size)
        assertEquals(listOf("stun:stun.example.com:19302"), out[0].urls)
    }

    @Test fun `skips entries with no urls`() {
        val arr = json.parseToJsonElement("""[
            {"username":"orphan"},
            {"urls":[]},
            {"urls":["stun:ok"]}
        ]""").let { it as JsonArray }
        val out = IceServerCodec.parseList(arr)
        assertEquals(1, out.size)
        assertEquals(listOf("stun:ok"), out[0].urls)
    }

    @Test fun `empty input returns empty list`() {
        val arr = json.parseToJsonElement("[]").let { it as JsonArray }
        assertTrue(IceServerCodec.parseList(arr).isEmpty())
    }
}
