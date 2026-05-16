package io.twostars.sdk.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps a ShortArray of 16-bit PCM mono samples in a minimal canonical
 * WAV container (44-byte RIFF header). Output matches the JS SDK's
 * `_encodeWav` byte-for-byte so the server-side STT path doesn't have
 * to special-case Android.
 */
internal object WavEncoder {

    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val byteCount = samples.size * 2
        val buf = ByteBuffer.allocate(44 + byteCount).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + byteCount)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt subchunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)               // subchunk size
        buf.putShort(1)              // audio format = PCM
        buf.putShort(1)              // channels = mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)   // byte rate (sampleRate * channels * 2)
        buf.putShort(2)              // block align (channels * 2)
        buf.putShort(16)             // bits per sample
        // data subchunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(byteCount)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }
}
