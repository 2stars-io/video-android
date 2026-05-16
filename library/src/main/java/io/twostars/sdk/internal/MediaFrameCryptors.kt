package io.twostars.sdk.internal

import org.webrtc.FrameDecryptor
import org.webrtc.FrameEncryptor
import org.webrtc.MediaStreamTrack
import javax.crypto.SecretKey

/**
 * E4 — libwebrtc-side wrappers that thread per-frame SFrame
 * encryption / decryption into mediasoup-android's send / recv
 * pipelines.
 *
 * libwebrtc invokes [FrameEncryptor.encrypt] for every encoded frame
 * before SRTP; we wrap that frame with [SFrame.encryptFrame]. The
 * mirror [FrameDecryptor.decrypt] runs after SRTP unwrap on the
 * receive side. The SFU never sees plaintext.
 */

/** Encrypts every outgoing encoded frame of one local producer. */
internal class SFrameEncryptor(
    private val senderKey: SecretKey,
) : FrameEncryptor {

    private val state = SFrame.CounterState()

    override fun encrypt(
        mediaType: MediaStreamTrack.MediaType?,
        ssrc: Int,
        additionalData: ByteArray?,
        frame: ByteArray?,
    ): ByteArray? {
        if (frame == null) return null
        return try {
            SFrame.encryptFrame(senderKey, frame, state)
        } catch (t: Throwable) {
            // Drop the frame — libwebrtc treats a null return as
            // "skip this frame". Better silent loss than panic.
            null
        }
    }

    /**
     * Maximum size of the encrypted payload for a given plaintext
     * length. libwebrtc preallocates buffers based on this.
     */
    override fun getMaxCiphertextByteSize(
        mediaType: MediaStreamTrack.MediaType?,
        plaintextSize: Int,
    ): Int = plaintextSize + SFrame.HEADER_SIZE + SFrame.TAG_SIZE
}

/** Decrypts every incoming encoded frame of one remote consumer. */
internal class SFrameDecryptor(
    private val senderKey: SecretKey,
) : FrameDecryptor {

    override fun decrypt(
        mediaType: MediaStreamTrack.MediaType?,
        ssrc: Int,
        additionalData: ByteArray?,
        frame: ByteArray?,
    ): FrameDecryptor.Result {
        if (frame == null || frame.size < SFrame.HEADER_SIZE + SFrame.TAG_SIZE) {
            // libwebrtc Result.Status.UNKNOWN tells the receiver to
            // drop without disconnecting the stream.
            return FrameDecryptor.Result(FrameDecryptor.Result.Status.UNKNOWN, ByteArray(0))
        }
        return try {
            val plain = SFrame.decryptFrame(senderKey, frame)
            FrameDecryptor.Result(FrameDecryptor.Result.Status.OK, plain)
        } catch (t: Throwable) {
            // Decryption failure: tampered packet, wrong key (e.g.
            // sender on a different key generation), or pre-rotation
            // jitter. Drop without resetting the stream — the next
            // keyframe restores visuals.
            FrameDecryptor.Result(FrameDecryptor.Result.Status.UNKNOWN, ByteArray(0))
        }
    }

    override fun getMaxPlaintextByteSize(
        mediaType: MediaStreamTrack.MediaType?,
        ciphertextSize: Int,
    ): Int = (ciphertextSize - SFrame.HEADER_SIZE - SFrame.TAG_SIZE).coerceAtLeast(0)
}
