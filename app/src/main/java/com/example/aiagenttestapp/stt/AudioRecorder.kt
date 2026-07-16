package com.example.aiagenttestapp.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.sqrt

/** One slice of microphone audio, plus enough to draw a level meter. */
data class AudioChunk(
    /** Normalised to -1..1, which is what the recogniser's feature extractor expects. */
    val samples: FloatArray,
    /** 0f..1f loudness, for the waveform. RMS, not peak -- peak jitters and reads as noise. */
    val level: Float,
) {
    // FloatArray gives data classes reference equality, which silently breaks list diffing.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Reads the microphone as 16 kHz mono float samples.
 *
 * 16 kHz because that is what every speech model in this space is trained on -- SenseVoice, Whisper,
 * Zipformer all expect it. Recording at 44.1 kHz and resampling would cost quality and CPU for
 * nothing.
 *
 * `VOICE_RECOGNITION` rather than `MIC` as the source: it asks the platform for the AGC and noise
 * suppression tuned for speech, and disables the processing meant for music.
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000

        /** ~100 ms of audio. Small enough for a responsive meter, large enough not to thrash. */
        private const val CHUNK_SAMPLES = 1600
        private const val TAG = "AudioRecorder"
    }

    /**
     * Emits chunks until the collector stops. Requires `RECORD_AUDIO`, which the caller must
     * already have been granted -- this class does not ask.
     */
    @SuppressLint("MissingPermission")
    fun record(): Flow<AudioChunk> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            close(IllegalStateException("This device cannot record 16 kHz mono audio"))
            return@callbackFlow
        }

        // A generous ring buffer. Undersizing it means dropped audio -- and therefore dropped
        // words -- the moment the app is briefly descheduled.
        val bufferSize = maxOf(minBuffer * 2, CHUNK_SAMPLES * 4)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            close(IllegalStateException("Could not open the microphone"))
            return@callbackFlow
        }

        recorder.startRecording()

        val pcm = ShortArray(CHUNK_SAMPLES)

        try {
            while (!isClosedForSend) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read <= 0) continue

                val samples = FloatArray(read)
                var sumSquares = 0.0

                for (i in 0 until read) {
                    // 16-bit PCM is -32768..32767; the models want -1..1.
                    val value = pcm[i] / 32768f
                    samples[i] = value
                    sumSquares += (value * value).toDouble()
                }

                val rms = sqrt(sumSquares / read).toFloat()
                // Speech sits well below full scale, so a raw RMS meter barely moves. Scaling by 4
                // makes normal speech fill most of the bar without clipping on a shout.
                val level = (rms * 4f).coerceIn(0f, 1f)

                trySend(AudioChunk(samples, level))
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
            close(e)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        awaitClose {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }.flowOn(Dispatchers.IO)
}
