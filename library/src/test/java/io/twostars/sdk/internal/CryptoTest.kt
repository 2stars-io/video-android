package io.twostars.sdk.internal

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto layer tests. Deterministic round-trips for everything that
 * has to interop with the JS SDK's `crypto.js` byte-for-byte.
 *
 * Doesn't run against a real JS-produced fixture (we don't have one
 * checked in) — relies on the spec being followed correctly. The
 * actual cross-platform compat is verified during the device smoke
 * (phone Kotlin + browser JS in the same room must decrypt each
 * other's messages).
 */
class CryptoTest {

    // --- JWK shape ----------------------------------------------------------

    @Test fun `pubkey to JWK has the canonical EC P-256 fields`() {
        val kp = Crypto.generateEcdhKeypair()
        val jwk = kp.pubJwk
        assertEquals("EC",     jwk["kty"]?.jsonPrimitive?.contentOrNull)
        assertEquals("P-256",  jwk["crv"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(jwk["x"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(jwk["y"]?.jsonPrimitive?.contentOrNull)
        // ext + key_ops are present in JS WebCrypto exports — keep them so
        // the byte signature matches what JS receivers see.
        assertNotNull(jwk["ext"])
        assertNotNull(jwk["key_ops"])

        // x + y are 32-byte big-endian unsigned ints, base64url no-pad.
        // 32 raw bytes → 43 chars in base64url-no-pad.
        val xLen = jwk["x"]!!.jsonPrimitive.content.length
        val yLen = jwk["y"]!!.jsonPrimitive.content.length
        assertEquals(43, xLen)
        assertEquals(43, yLen)
    }

    @Test fun `JWK round-trips through importPublicKey`() {
        val kp = Crypto.generateEcdhKeypair()
        val pub = Crypto.jwkToPublicKey(kp.pubJwk)
        val again = Crypto.publicKeyToJwk(pub)
        assertEquals(
            kp.pubJwk["x"]?.jsonPrimitive?.content,
            again["x"]?.jsonPrimitive?.content,
        )
        assertEquals(
            kp.pubJwk["y"]?.jsonPrimitive?.content,
            again["y"]?.jsonPrimitive?.content,
        )
    }

    // --- Room key derivation ------------------------------------------------

    @Test fun `ECDH derive yields the same shared key both directions`() {
        // Two peers each generate a keypair, exchange JWK pubkeys, derive
        // a room key from each side. If we then wrap-with-A's-derive +
        // unwrap-with-B's-derive, the unwrapped bytes must equal the
        // original room-key bytes — which only happens if both sides
        // computed the same wrapping key.
        val a = Crypto.generateEcdhKeypair()
        val b = Crypto.generateEcdhKeypair()
        val roomKey = Crypto.generateRoomKey()
        val rawRoomKey = roomKey.encoded!!

        val wrap = Crypto.wrapRoomKeyForPeer(roomKey, a.privateKey, b.pubJwk)
        val unwrapped = Crypto.unwrapRoomKey(wrap.wrapped, wrap.iv, b.privateKey, a.pubJwk)
        assertArrayEquals(rawRoomKey, unwrapped.encoded)
    }

    @Test fun `unwrap with wrong private key fails`() {
        val sender = Crypto.generateEcdhKeypair()
        val intended = Crypto.generateEcdhKeypair()
        val attacker = Crypto.generateEcdhKeypair()
        val roomKey = Crypto.generateRoomKey()

        val wrap = Crypto.wrapRoomKeyForPeer(roomKey, sender.privateKey, intended.pubJwk)
        try {
            // Attacker tries to unwrap — derived shared key won't match,
            // GCM auth tag verification should fail.
            Crypto.unwrapRoomKey(wrap.wrapped, wrap.iv, attacker.privateKey, sender.pubJwk)
            fail("attacker should not be able to unwrap")
        } catch (e: AEADBadTagException) { /* expected */ }
        catch (e: javax.crypto.BadPaddingException) { /* also expected — JCE wraps GCM */ }
    }

    // --- Message text encrypt/decrypt --------------------------------------

    @Test fun `encryptText then decryptText round-trips a plaintext`() {
        val key = Crypto.generateRoomKey()
        val plaintext = "Hello, 2Stars 👋 测试 🌟"
        val enc = Crypto.encryptText(key, plaintext)
        val out = Crypto.decryptText(key, enc.encrypted, enc.iv)
        assertEquals(plaintext, out)
    }

    @Test fun `encryptText uses a fresh 12-byte IV each call`() {
        val key = Crypto.generateRoomKey()
        val a = Crypto.encryptText(key, "same text")
        val b = Crypto.encryptText(key, "same text")
        // Different IVs ⇒ different ciphertext, even for identical
        // plaintext. If someone removes the rng.nextBytes call, this
        // catches it.
        assertTrue("IVs must differ", a.iv != b.iv)
        assertTrue("ciphertexts must differ", a.encrypted != b.encrypted)
        // 12 raw bytes → 16 chars base64url-no-pad.
        assertEquals(16, a.iv.length)
    }

    @Test fun `decrypt with wrong room key fails`() {
        val k1 = Crypto.generateRoomKey()
        val k2 = Crypto.generateRoomKey()
        val enc = Crypto.encryptText(k1, "secret")
        try {
            Crypto.decryptText(k2, enc.encrypted, enc.iv)
            fail("decrypting with wrong key should not succeed")
        } catch (e: AEADBadTagException) { /* expected */ }
        catch (e: javax.crypto.BadPaddingException) { /* JCE alias */ }
    }

    @Test fun `decrypt with tampered ciphertext fails`() {
        val key = Crypto.generateRoomKey()
        val enc = Crypto.encryptText(key, "secret")
        val raw = Crypto.b64uDecode(enc.encrypted)
        // Flip the first byte — the GCM auth tag should reject.
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        val tamperedB64 = Crypto.b64u(raw)
        try {
            Crypto.decryptText(key, tamperedB64, enc.iv)
            fail("tampered ciphertext should fail GCM auth")
        } catch (e: AEADBadTagException) { /* expected */ }
        catch (e: javax.crypto.BadPaddingException) { /* JCE alias */ }
    }

    // --- Base64URL ----------------------------------------------------------

    @Test fun `b64u uses URL-safe alphabet without padding`() {
        // Bytes that exercise + / and end-padding.
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte(), 0x00, 0x10)
        val out = Crypto.b64u(bytes)
        // Standard base64 of these bytes is "+/+/ABA=" — URL-safe is "-_-_ABA",
        // and we strip padding.
        assertEquals("-_-_ABA", out)
        assertArrayEquals(bytes, Crypto.b64uDecode(out))
    }
}
