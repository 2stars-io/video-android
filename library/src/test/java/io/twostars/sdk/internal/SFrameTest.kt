package io.twostars.sdk.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * E4 — SFrame layer tests.
 *
 * Same contract as `scripts/smoke-e4-sframe.mjs` on the JS side. The
 * frame format + key derivation + IV scheme MUST match byte-for-byte
 * so an Android sender's frame decrypts on a browser receiver and
 * vice versa.
 *
 * Cross-platform interop is verified by inspection of the algorithm
 * here (HKDF-SHA256 with the same salt + info; AES-256-GCM with the
 * same IV layout) plus the JS smoke that exercises identical inputs.
 * A real on-device cross-platform smoke is part of Smoke E.
 */
class SFrameTest {

    // 32 bytes of fixed test material so derivations are reproducible.
    private val ROOM_KEY_RAW = ByteArray(32) { (it * 13 + 7).toByte() }
    private val ROOM_KEY = SecretKeySpec(ROOM_KEY_RAW, "AES")

    private val ALICE = "alice-pid-001"
    private val BOB = "bob-pid-002"

    // 1. Round-trip preserves bytes.
    @Test fun `encrypt then decrypt with same key returns original plaintext`() {
        val key = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val plain = "fake-encoded-frame-bytes".toByteArray(Charsets.UTF_8)
        val state = SFrame.CounterState()
        val cipher = SFrame.encryptFrame(key, plain, state)
        val back = SFrame.decryptFrame(key, cipher)
        assertArrayEquals(plain, back)
    }

    // 1b. Header bytes are stable.
    @Test fun `ciphertext header is version + kid + 8-byte counter`() {
        val key = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val cipher = SFrame.encryptFrame(key, byteArrayOf(1, 2, 3), SFrame.CounterState())
        assertEquals(SFrame.SFRAME_VERSION, cipher[0])
        assertEquals(0x00.toByte(), cipher[1])  // kid
        assertEquals(SFrame.HEADER_SIZE + 3 + SFrame.TAG_SIZE, cipher.size)
    }

    // 2. Per-sender key isolation.
    @Test fun `bob's key cannot decrypt alice's frame`() {
        val aliceKey = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val bobKey = SFrame.deriveSenderKey(ROOM_KEY, BOB)
        val cipher = SFrame.encryptFrame(aliceKey, byteArrayOf(1, 2, 3, 4), SFrame.CounterState())
        try {
            SFrame.decryptFrame(bobKey, cipher)
            fail("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // expected
        }
    }

    // 3. Tampered ciphertext.
    @Test fun `tampered ciphertext fails GCM auth tag check`() {
        val key = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val cipher = SFrame.encryptFrame(key, byteArrayOf(10, 20, 30, 40, 50), SFrame.CounterState())
        cipher[SFrame.HEADER_SIZE + 1] = (cipher[SFrame.HEADER_SIZE + 1].toInt() xor 0x01).toByte()
        try {
            SFrame.decryptFrame(key, cipher)
            fail("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // expected
        }
    }

    // 4. Counter monotonicity — successive encrypts produce distinct counters.
    @Test fun `successive frames carry monotonically increasing counters`() {
        val key = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val state = SFrame.CounterState()
        val f1 = SFrame.encryptFrame(key, byteArrayOf(1), state)
        val f2 = SFrame.encryptFrame(key, byteArrayOf(2), state)
        val f3 = SFrame.encryptFrame(key, byteArrayOf(3), state)
        // Counter is bytes 2..9 big-endian.
        val c1 = java.nio.ByteBuffer.wrap(f1, 2, 8).long
        val c2 = java.nio.ByteBuffer.wrap(f2, 2, 8).long
        val c3 = java.nio.ByteBuffer.wrap(f3, 2, 8).long
        assertEquals(0L, c1)
        assertEquals(1L, c2)
        assertEquals(2L, c3)
    }

    // 5. Wrong version is rejected.
    @Test fun `unrecognised version byte is rejected`() {
        val key = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val cipher = SFrame.encryptFrame(key, byteArrayOf(0xAA.toByte()), SFrame.CounterState())
        cipher[0] = 0xFF.toByte()
        try {
            SFrame.decryptFrame(key, cipher)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    // 6. Derivation determinism.
    @Test fun `derive returns the same key for the same room key + participantId`() {
        val a1 = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val a2 = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        assertArrayEquals(a1.encoded, a2.encoded)
    }

    @Test fun `derive returns different keys for different participantIds`() {
        val a = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val b = SFrame.deriveSenderKey(ROOM_KEY, BOB)
        assertNotEquals(a.encoded.toList(), b.encoded.toList())
    }

    // 7. Cross-room isolation.
    @Test fun `different room keys produce different sender keys for same participant`() {
        val roomA = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val roomB = SecretKeySpec(ByteArray(32) { (it + 99).toByte() }, "AES")
        val ka = SFrame.deriveSenderKey(roomA, ALICE)
        val kb = SFrame.deriveSenderKey(roomB, ALICE)
        assertNotEquals(ka.encoded.toList(), kb.encoded.toList())

        // Sanity: encrypting with one and decrypting with the other fails.
        val cipher = SFrame.encryptFrame(ka, byteArrayOf(7, 8, 9), SFrame.CounterState())
        try {
            SFrame.decryptFrame(kb, cipher)
            fail("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // expected
        }
    }

    // 8. Realistic frame size (64 KB ~ video keyframe).
    @Test fun `64 KB frame round-trips identically`() {
        val key = SFrame.deriveSenderKey(ROOM_KEY, ALICE)
        val big = ByteArray(64 * 1024) { (it and 0xFF).toByte() }
        val cipher = SFrame.encryptFrame(key, big, SFrame.CounterState())
        val back = SFrame.decryptFrame(key, cipher)
        assertArrayEquals(big, back)
    }

    // 9. IV layout: 4 zero bytes || 8-byte big-endian counter.
    @Test fun `IV layout matches the documented format`() {
        val iv = SFrame.ivFromCounter(0x0102030405060708L)
        assertEquals(12, iv.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), iv.copyOfRange(0, 4))
        assertEquals(0x01.toByte(), iv[4])
        assertEquals(0x08.toByte(), iv[11])
    }
}
