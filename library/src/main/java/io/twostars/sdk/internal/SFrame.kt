package io.twostars.sdk.internal

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E4 — End-to-end media encryption (SFrame-style), Kotlin mirror of
 * `sdk/js/src/sframe.js`.
 *
 * Per-frame AES-256-GCM encryption of encoded video / audio frames.
 * Sender computes a per-sender key from the shared room key + the
 * sender's participantId via HKDF-SHA256. Receiver derives the
 * same key the same way. SFU sees only opaque ciphertext — frame
 * payloads are encrypted between sender libwebrtc encoder and
 * receiver libwebrtc decoder.
 *
 * Frame format (v1) — interop with the JS SDK:
 *   [version:1 = 0x01]
 *   [kid:1     = 0x00]               key id; reserved for rotation
 *   [ctr:8]                          big-endian per-sender counter
 *   [ciphertext + 16-byte AES-GCM tag]
 *
 * IV = 12 bytes: 4 zero bytes || 8 bytes of ctr (big-endian).
 *
 * What this DOESN'T do (deferred — same as JS side):
 *   - Key rotation when membership changes (E4.1)
 *   - Strict replay protection on the receiver (E4.2)
 *   - RTP header extension obfuscation (E4.3)
 */
internal object SFrame {

    const val SFRAME_VERSION: Byte = 0x01
    const val HEADER_SIZE = 1 /* version */ + 1 /* kid */ + 8 /* ctr */
    const val TAG_SIZE = 16   // AES-GCM auth tag

    private val DEFAULT_SALT = "2stars-sframe-v1".toByteArray(Charsets.UTF_8)

    /**
     * Per-sender mutable counter. One per (sender, key). Sharing across
     * keys risks IV reuse if the key rotates.
     */
    class CounterState {
        @Volatile var counter: Long = 0L
    }

    /**
     * Derive a sender-specific 256-bit AES key from the shared room
     * key + the sender's participantId. Both sides compute this
     * deterministically; no key transport beyond the room key itself.
     */
    fun deriveSenderKey(
        roomKey: SecretKey,
        participantId: String,
        salt: ByteArray = DEFAULT_SALT,
    ): SecretKey {
        require(participantId.isNotEmpty()) { "deriveSenderKey: participantId required" }
        val info = "media-$participantId".toByteArray(Charsets.UTF_8)
        val derived = hkdfSha256(
            ikm = roomKey.encoded,   // raw 32-byte AES key bytes
            salt = salt,
            info = info,
            length = 32,
        )
        return SecretKeySpec(derived, "AES")
    }

    /**
     * Encrypt one encoded frame. Returns
     * `[version || kid || ctr || ciphertext+tag]`. Bumps the counter
     * monotonically; pass the same CounterState across frames.
     */
    fun encryptFrame(
        senderKey: SecretKey,
        frameBytes: ByteArray,
        state: CounterState,
    ): ByteArray {
        val ctr = state.counter
        state.counter = ctr + 1L
        val iv = ivFromCounter(ctr)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, senderKey, GCMParameterSpec(TAG_SIZE * 8, iv))
        val ct = cipher.doFinal(frameBytes)
        val out = ByteArray(HEADER_SIZE + ct.size)
        out[0] = SFRAME_VERSION
        out[1] = 0x00       // kid
        // ctr → bytes 2..9 (big-endian)
        ByteBuffer.wrap(out, 2, 8).putLong(ctr)
        System.arraycopy(ct, 0, out, HEADER_SIZE, ct.size)
        return out
    }

    /**
     * Decrypt one encrypted frame produced by [encryptFrame]. Returns
     * the original plaintext bytes. Throws on:
     *   - Frame too short
     *   - Unknown version
     *   - GCM auth tag failure (tampered, wrong key, wrong sender)
     */
    fun decryptFrame(senderKey: SecretKey, encryptedBytes: ByteArray): ByteArray {
        require(encryptedBytes.size >= HEADER_SIZE + TAG_SIZE) { "decryptFrame: frame too short" }
        require(encryptedBytes[0] == SFRAME_VERSION) {
            "decryptFrame: unsupported version 0x${"%02x".format(encryptedBytes[0])}"
        }
        // KID at byte 1 reserved; v1 ignores.
        val ctr = ByteBuffer.wrap(encryptedBytes, 2, 8).long
        val iv = ivFromCounter(ctr)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, senderKey, GCMParameterSpec(TAG_SIZE * 8, iv))
        return cipher.doFinal(encryptedBytes, HEADER_SIZE, encryptedBytes.size - HEADER_SIZE)
    }

    // ---- internals ----------------------------------------------------------

    /** 96-bit IV: 4 zero bytes || 8-byte big-endian counter. */
    internal fun ivFromCounter(ctr: Long): ByteArray {
        val iv = ByteArray(12)
        ByteBuffer.wrap(iv, 4, 8).putLong(ctr)
        return iv
    }

    /**
     * RFC 5869 HKDF-Extract + HKDF-Expand using HMAC-SHA256.
     * `length` must be ≤ 8160 (255 × 32) bytes — enforced by
     * spec, irrelevant for our 32-byte derived key.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        // Extract.
        val saltOrZero = if (salt.isNotEmpty()) salt else ByteArray(32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(saltOrZero, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand.
        val out = ByteArray(length)
        var t = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val take = minOf(t.size, length - written)
            System.arraycopy(t, 0, out, written, take)
            written += take
            counter += 1
        }
        return out
    }
}
