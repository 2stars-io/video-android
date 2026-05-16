package io.twostars.sdk.internal

import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Common interface for the SDK-internal mic-frame sink. Set on
 * [WebRTCFactory.installAudioSink]; the WebRTC ADM forwards every
 * captured frame.
 */
internal fun interface AudioSamplesSink {
    fun onSamples(samples: JavaAudioDeviceModule.AudioSamples)
}

/**
 * Voice-activity-detection (VAD) endpointer. Accumulates 16-bit PCM
 * mic frames, watches per-frame RMS for silence, and emits one
 * "utterance" worth of samples each time a voiced burst ends with
 * [endSilenceMs] of quiet.
 *
 * Behaviour mirrors the JS SDK's [Transcription.js] AudioWorklet so
 * the server sees the same chunk shape (no leading silence, no
 * trailing silence, ≥ [minUtteranceMs] of speech, ≤ [maxUtteranceMs]
 * before a hard cut).
 *
 * Thread-safety: the WebRTC ADM calls [onSamples] from a single
 * recording thread. Don't share an instance across rooms; instantiate
 * one per [io.twostars.sdk.Room.startTranscription] call.
 */
internal class VadEndpointer(
    private val onUtterance: (samples: ShortArray, sampleRate: Int) -> Unit,
    private val silenceThresh: Float = 0.018f,
    private val endSilenceMs: Int = 600,
    private val lookbackMs: Int = 200,
    private val minUtteranceMs: Int = 250,
    private val maxUtteranceMs: Int = 12_000,
) : AudioSamplesSink {

    @Volatile var enabled: Boolean = false

    private var sampleRate: Int = 0
    private var channels: Int = 1

    // Diagnostic counters — printed periodically so we can verify the
    // mic-capture chain is actually running end-to-end.
    private var framesSeen: Long = 0
    private var lastLogAt: Long = 0L
    private var maxRmsThisWindow: Float = 0f

    // Lookback ring (so we capture the start of speech before VAD trips).
    private lateinit var lookback: ShortArray
    private var lookbackPos: Int = 0
    private var lookbackFilled: Int = 0

    private val buffer = ArrayList<ShortArray>()
    private var bufferSize: Int = 0
    private var inSpeech: Boolean = false
    private var silentRunSamples: Int = 0
    private var endSilenceSamples: Int = 0
    private var minSamples: Int = 0
    private var maxSamples: Int = 0

    override fun onSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        if (!enabled) return
        val rate = samples.sampleRate
        val ch = samples.channelCount
        if (rate != sampleRate || ch != channels) reinit(rate, ch)

        val mono = bytesToMono16(samples.data, ch)
        process(mono, mono.size)
    }

    /**
     * Feed already-decoded 16-bit PCM mono samples (from
     * [AudioRecordCapture] etc.). [count] is how many of [samples]
     * to consume from index 0 — the caller's read buffer is reused
     * across reads, so we can't trust `samples.size`.
     */
    fun feed(samples: ShortArray, count: Int, sampleRate: Int) {
        if (!enabled || count <= 0) return
        if (sampleRate != this.sampleRate || channels != 1) reinit(sampleRate, 1)
        // Copy the live portion so downstream emit() can stash the
        // ShortArray without our caller's next read trampling it.
        val frame = ShortArray(count)
        System.arraycopy(samples, 0, frame, 0, count)
        process(frame, count)
    }

    private fun reinit(rate: Int, ch: Int) {
        sampleRate = rate
        channels = ch
        lookback = ShortArray(rate * lookbackMs / 1000)
        lookbackPos = 0
        lookbackFilled = 0
        endSilenceSamples = rate * endSilenceMs / 1000
        minSamples = rate * minUtteranceMs / 1000
        maxSamples = rate * maxUtteranceMs / 1000
        buffer.clear()
        bufferSize = 0
        inSpeech = false
        silentRunSamples = 0
    }

    private fun process(frame: ShortArray, count: Int) {
        // RMS in float32 normalized space, matching the JS threshold.
        var sumSq = 0.0
        for (i in 0 until count) {
            val f = frame[i] / 32768f
            sumSq += (f * f).toDouble()
        }
        val rms = kotlin.math.sqrt(sumSq / count).toFloat()
        val isVoiced = rms >= silenceThresh

        framesSeen += 1
        if (rms > maxRmsThisWindow) maxRmsThisWindow = rms
        val now = System.currentTimeMillis()
        if (now - lastLogAt >= 2000) {
            android.util.Log.w(
                "TwoStarsSDK",
                "VAD frames=$framesSeen rate=${sampleRate}Hz peakRMS=${"%.4f".format(maxRmsThisWindow)} thresh=$silenceThresh inSpeech=$inSpeech bufSize=$bufferSize",
            )
            lastLogAt = now
            maxRmsThisWindow = 0f
        }

        if (inSpeech) {
            buffer.add(frame)
            bufferSize += frame.size
            if (isVoiced) {
                silentRunSamples = 0
            } else {
                silentRunSamples += frame.size
                if (silentRunSamples >= endSilenceSamples) {
                    emit()
                    return
                }
            }
            if (bufferSize >= maxSamples) emit()
        } else {
            ringPush(frame)
            if (isVoiced) {
                val seed = drainLookback()
                buffer.clear()
                if (seed.isNotEmpty()) {
                    buffer.add(seed)
                    bufferSize = seed.size
                } else {
                    bufferSize = 0
                }
                buffer.add(frame)
                bufferSize += frame.size
                inSpeech = true
                silentRunSamples = 0
            }
        }
    }

    private fun emit() {
        if (bufferSize >= minSamples) {
            val out = ShortArray(bufferSize)
            var off = 0
            for (chunk in buffer) {
                System.arraycopy(chunk, 0, out, off, chunk.size)
                off += chunk.size
            }
            android.util.Log.w(
                "TwoStarsSDK",
                "VAD emit utterance: ${out.size} samples (${out.size * 1000 / sampleRate}ms @ ${sampleRate}Hz)",
            )
            try { onUtterance(out, sampleRate) } catch (t: Throwable) {
                android.util.Log.w("TwoStarsSDK", "VAD onUtterance threw", t)
            }
        } else if (bufferSize > 0) {
            android.util.Log.w(
                "TwoStarsSDK",
                "VAD discard utterance: ${bufferSize} samples below min $minSamples",
            )
        }
        buffer.clear()
        bufferSize = 0
        inSpeech = false
        silentRunSamples = 0
        lookback.fill(0)
        lookbackPos = 0
        lookbackFilled = 0
    }

    private fun ringPush(frame: ShortArray) {
        val n = lookback.size
        if (frame.size >= n) {
            System.arraycopy(frame, frame.size - n, lookback, 0, n)
            lookbackPos = 0
            lookbackFilled = n
            return
        }
        for (s in frame) {
            lookback[lookbackPos] = s
            lookbackPos = (lookbackPos + 1) % n
        }
        lookbackFilled = minOf(n, lookbackFilled + frame.size)
    }

    private fun drainLookback(): ShortArray {
        val n = lookbackFilled
        val out = ShortArray(n)
        if (n < lookback.size) {
            System.arraycopy(lookback, 0, out, 0, n)
        } else {
            val head = lookback.size - lookbackPos
            System.arraycopy(lookback, lookbackPos, out, 0, head)
            System.arraycopy(lookback, 0, out, head, lookbackPos)
        }
        return out
    }

    /**
     * Convert interleaved 16-bit PCM bytes to a mono 16-bit short array.
     * For multi-channel input we average channels into a single sample.
     */
    private fun bytesToMono16(bytes: ByteArray, channels: Int): ShortArray {
        val frameCount = bytes.size / 2 / channels
        val out = ShortArray(frameCount)
        var src = 0
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                val lo = bytes[src].toInt() and 0xff
                val hi = bytes[src + 1].toInt()
                sum += (hi shl 8) or lo
                src += 2
            }
            out[i] = (sum / channels).toShort()
        }
        return out
    }
}
