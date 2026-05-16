package io.twostars.sdk.internal

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Dedicated mic capture for transcription / wake-word / any feature
 * that needs raw PCM frames *independently* of WebRTC's
 * [JavaAudioDeviceModule].
 *
 * **Why we don't tap the ADM directly.** WebRTC's recording loop only
 * spins up after a peer connection has *negotiated* an audio sender —
 * solo in a room (no peers yet) means `SamplesReadyCallback` never
 * fires and the VAD endpointer sits idle. Browsers don't have this
 * constraint because `getUserMedia` opens the OS mic on its own;
 * mirroring that with a dedicated [AudioRecord] gives us the same
 * guarantee on Android.
 *
 * **Source choice — `VOICE_RECOGNITION`.** This source is deliberately
 * designed for STT use cases and the OS mic-routing layer is generally
 * willing to share it concurrently with other capture (the WebRTC
 * mic). Other sources (`MIC`, `VOICE_COMMUNICATION`) often conflict
 * when a VoIP capture is already running.
 *
 * Always 16 kHz mono 16-bit PCM — matches the JS SDK's WAV encoding so
 * the server's STT path doesn't need to special-case Android.
 */
internal class AudioRecordCapture(
    private val onSamples: (samples: ShortArray, count: Int, sampleRate: Int) -> Unit,
    private val sampleRate: Int = 16_000,
) {

    @Volatile private var running: Boolean = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize returned $minBuf — device doesn't support 16 kHz mono PCM")
        }
        // 2× the OS minimum so we don't drop frames if the consumer
        // thread is briefly slow.
        val bufSize = minBuf * 2

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            throw IllegalStateException("AudioRecord failed to initialise (state=${rec.state}) — RECORD_AUDIO permission?")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
            throw IllegalStateException("AudioRecord.startRecording did not transition to RECORDING (state=${rec.recordingState})")
        }
        record = rec
        running = true

        // Read in modest chunks (~64ms @ 16 kHz). Smaller = lower
        // latency but more wakeups; larger = batchy. 1024 samples is
        // a sweet spot for VAD endpointing.
        val readSize = 1024
        val buf = ShortArray(readSize)

        thread = Thread({
            try {
                while (running) {
                    val n = rec.read(buf, 0, readSize)
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION ||
                            n == AudioRecord.ERROR_BAD_VALUE ||
                            n == AudioRecord.ERROR_DEAD_OBJECT) {
                            Log.w("TwoStarsSDK", "AudioRecord.read returned $n; aborting capture loop")
                            break
                        }
                        continue
                    }
                    try {
                        onSamples(buf, n, sampleRate)
                    } catch (t: Throwable) {
                        Log.w("TwoStarsSDK", "transcription onSamples threw", t)
                    }
                }
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
                try { rec.release() } catch (_: Throwable) {}
            }
        }, "TwoStarsAudioRecord").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // Don't join the thread — the read() loop exits on its own
        // after the next blocking read returns and notices !running.
        // Joining synchronously would block the UI thread for up to
        // one read interval (~64ms), which is unnecessary; the thread
        // is a daemon and tears down its AudioRecord in `finally`.
        thread = null
        record = null
    }
}
