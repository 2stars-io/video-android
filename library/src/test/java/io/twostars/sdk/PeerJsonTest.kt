package io.twostars.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the JSON → [Peer] extension that lives at the bottom
 * of `Room.kt`. Pure data conversion — no Android dependencies, runs
 * on the JVM.
 */
class PeerJsonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `toPeer parses a full server payload`() {
        val obj = json.parseToJsonElement(
            """{"participantId":"u-42","displayName":"Alice","joinedAt":"2026-05-03T10:30:00Z"}"""
        ).jsonObject
        val peer = obj.toPeer()
        assertNotNull(peer)
        assertEquals("u-42", peer!!.participantId)
        assertEquals("Alice", peer.displayName)
        val expectedMs = java.time.Instant.parse("2026-05-03T10:30:00Z").toEpochMilli()
        assertEquals(expectedMs, peer.joinedAtMs)
    }

    @Test fun `toPeer falls back to now when joinedAt missing or unparseable`() {
        val obj = json.parseToJsonElement(
            """{"participantId":"u-42","displayName":null}"""
        ).jsonObject
        val before = System.currentTimeMillis()
        val peer = obj.toPeer()
        val after = System.currentTimeMillis()
        assertNotNull(peer)
        assertNull(peer!!.displayName)
        // joinedAtMs is "now" — should sit in the [before,after] window.
        assertTrue(peer.joinedAtMs in before..after)
    }

    @Test fun `toPeer returns null when participantId is missing`() {
        val obj = json.parseToJsonElement(
            """{"displayName":"orphan"}"""
        ).jsonObject
        assertNull(obj.toPeer())
    }

    @Test fun `Peer equality is by participantId only`() {
        val a = Peer("p1", "First Name",  joinedAtMs = 1L)
        val b = Peer("p1", "Second Name", joinedAtMs = 999L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
