package io.twostars.sdk.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E crypto layer — port of `sdk/js/src/crypto.js` to Kotlin/JVM.
 *
 * Wire-format-compatible with the JS SDK so a Kotlin client can join a
 * room with JS clients and decrypt their messages (and vice versa).
 * Every byte that goes on the socket — JWK shape, IV layout, AAD, tag
 * length, Base64URL encoding — must match what `crypto.subtle` in the
 * browser produces, otherwise mixed-platform rooms fail silently with
 * "decrypt failed" errors.
 *
 * Scheme:
 *   1. Each peer mints an ECDH P-256 keypair on connect, broadcasts
 *      the public key as JWK via `key-bundle` socket event.
 *   2. The peer that joins an empty room mints a random 256-bit AES
 *      room key. Any peer who already holds the room key, on
 *      receiving a new peer's pubkey, derives a shared AES wrap key
 *      via ECDH+HKDF-SHA256 and AES-GCM-wraps the room key for that
 *      peer.
 *   3. Once every peer has the same room key, message text is
 *      AES-GCM-encrypted client-side; the server only ever sees
 *      ciphertext + 12-byte IV.
 *
 * Intentionally simple — for production groups MLS would be the right
 * answer. But this satisfies the architectural promise that the
 * server cannot read message contents.
 */
internal object Crypto {

    private const val EC_CURVE = "secp256r1" // == NIST P-256
    private const val EC_KEY_BYTES = 32       // each coord of a P-256 point
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private val HKDF_INFO = "hebbs/room-key-wrap".toByteArray(Charsets.UTF_8)

    private val rng = SecureRandom()

    // --- ECDH keypair --------------------------------------------------------

    fun generateEcdhKeypair(): EcdhKeypair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), rng)
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey
        return EcdhKeypair(
            keypair = kp,
            pubJwk = publicKeyToJwk(pub),
        )
    }

    // --- JWK serialisation ---------------------------------------------------

    /**
     * Format a P-256 public key as the JWK that JS WebCrypto's
     * `exportKey('jwk', publicKey)` produces. Must match field-for-
     * field — the receiving JS client uses the same import API and
     * rejects unrecognised shapes.
     */
    fun publicKeyToJwk(pub: ECPublicKey): JsonObject {
        val w: ECPoint = pub.w
        val x = w.affineX.toUnsignedFixed(EC_KEY_BYTES)
        val y = w.affineY.toUnsignedFixed(EC_KEY_BYTES)
        return buildJsonObject {
            put("kty", JsonPrimitive("EC"))
            put("crv", JsonPrimitive("P-256"))
            put("x", JsonPrimitive(b64u(x)))
            put("y", JsonPrimitive(b64u(y)))
            put("ext", JsonPrimitive(true))
            put("key_ops", buildJsonArray { /* empty per JS export */ })
        }
    }

    fun jwkToPublicKey(jwk: JsonObject): ECPublicKey {
        val xB64 = jwk["x"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("JWK missing 'x'")
        val yB64 = jwk["y"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("JWK missing 'y'")
        val x = BigInteger(1, b64uDecode(xB64))
        val y = BigInteger(1, b64uDecode(yB64))

        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(EC_CURVE))
        }.getParameterSpec(ECParameterSpec::class.java)

        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    // --- Room key ------------------------------------------------------------

    fun generateRoomKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256, rng)
        return kg.generateKey()
    }

    /**
     * Wrap (encrypt) a room key for transport to a specific peer. Returns
     * Base64URL-encoded ciphertext + IV (matches the JS wire shape so the
     * recipient just plugs them into [unwrapRoomKey]).
     */
    fun wrapRoomKeyForPeer(
        roomKey: SecretKey,
        ourPriv: PrivateKey,
        theirPubJwk: JsonObject,
    ): WrappedRoomKey {
        val wrappingKey = deriveWrappingKey(ourPriv, theirPubJwk)
        val raw = roomKey.encoded
            ?: throw IllegalStateException("room key has no raw encoding")
        val iv = randomIv()
        val ct = encryptGcm(wrappingKey, iv, raw, aad = null)
        return WrappedRoomKey(wrapped = b64u(ct), iv = b64u(iv))
    }

    fun unwrapRoomKey(
        wrappedB64: String,
        ivB64: String,
        ourPriv: PrivateKey,
        senderPubJwk: JsonObject,
    ): SecretKey {
        val wrappingKey = deriveWrappingKey(ourPriv, senderPubJwk)
        val pt = decryptGcm(wrappingKey, b64uDecode(ivB64), b64uDecode(wrappedB64), aad = null)
        return SecretKeySpec(pt, "AES")
    }

    // --- Message text encrypt / decrypt --------------------------------------

    fun encryptText(roomKey: SecretKey, plaintext: String): EncryptedMessage {
        val iv = randomIv()
        val ct = encryptGcm(roomKey, iv, plaintext.toByteArray(Charsets.UTF_8), aad = null)
        return EncryptedMessage(encrypted = b64u(ct), iv = b64u(iv))
    }

    fun decryptText(roomKey: SecretKey, encryptedB64: String, ivB64: String): String {
        val pt = decryptGcm(roomKey, b64uDecode(ivB64), b64uDecode(encryptedB64), aad = null)
        return String(pt, Charsets.UTF_8)
    }

    // --- AES-GCM primitives --------------------------------------------------

    private fun encryptGcm(key: SecretKey, iv: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad != null) updateAAD(aad)
        }
        return c.doFinal(plaintext)
    }

    private fun decryptGcm(key: SecretKey, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad != null) updateAAD(aad)
        }
        return c.doFinal(ciphertext)
    }

    // --- ECDH + HKDF ---------------------------------------------------------

    /**
     * ECDH-derive a 256-bit AES key from our private key + the peer's
     * JWK pubkey, using HKDF-SHA256 with salt=empty and the same
     * `info` string the JS SDK uses (`"hebbs/room-key-wrap"`). Both
     * sides MUST agree on `info` or the derived key bytes diverge.
     */
    private fun deriveWrappingKey(ourPriv: PrivateKey, theirPubJwk: JsonObject): SecretKey {
        val theirPub = jwkToPublicKey(theirPubJwk)
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(ourPriv)
            doPhase(theirPub, true)
        }
        val sharedSecret = ka.generateSecret()
        val derived = hkdfSha256(ikm = sharedSecret, salt = ByteArray(0), info = HKDF_INFO, length = 32)
        return SecretKeySpec(derived, "AES")
    }

    /**
     * HKDF-SHA256. Standard Android JCE doesn't ship an HKDF
     * implementation, so we run it manually with HMAC-SHA256.
     * Reference: RFC 5869.
     *
     *   PRK = HMAC-SHA256(salt, ikm)
     *   T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)   ; T(0) = empty
     *   OKM = first `length` bytes of T(1) || T(2) || …
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0 && length <= 255 * 32) { "invalid HKDF length" }
        val mac = Mac.getInstance("HmacSHA256")

        // Empty salt is treated as a zero-filled byte string of HashLen bytes
        // per the RFC (and what JS WebCrypto does).
        val saltKey = SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256")
        mac.init(saltKey)
        val prk = mac.doFinal(ikm)

        val prkKey = SecretKeySpec(prk, "HmacSHA256")
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.init(prkKey)
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal()
            val take = minOf(prev.size, length - pos)
            System.arraycopy(prev, 0, out, pos, take)
            pos += take
            counter++
        }
        return out
    }

    // --- Base64URL (no padding) ----------------------------------------------

    fun b64u(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun b64uDecode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

    private fun randomIv(): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES)
        rng.nextBytes(iv)
        return iv
    }

    // --- Helpers -------------------------------------------------------------

    /**
     * Convert a [BigInteger] to a fixed-width unsigned byte array.
     * `BigInteger.toByteArray()` returns a 2's complement big-endian
     * representation that may have a leading `0x00` (when the high
     * bit is set on a positive value) or be shorter than `length`
     * bytes — neither is what JWK expects. JWK fields are fixed-
     * width unsigned big-endian integers padded with leading zeros.
     */
    private fun BigInteger.toUnsignedFixed(length: Int): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == length -> raw
            raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, length + 1)
            raw.size < length -> ByteArray(length - raw.size) + raw
            else -> throw IllegalStateException("BigInteger does not fit in $length bytes")
        }
    }

    // --- Data classes --------------------------------------------------------

    data class EcdhKeypair(
        val keypair: KeyPair,
        val pubJwk: JsonObject,
    ) {
        val privateKey: PrivateKey get() = keypair.private
    }

    data class WrappedRoomKey(
        val wrapped: String, // base64url
        val iv: String,      // base64url
    )

    data class EncryptedMessage(
        val encrypted: String, // base64url ciphertext
        val iv: String,        // base64url 12-byte IV
    )
}
