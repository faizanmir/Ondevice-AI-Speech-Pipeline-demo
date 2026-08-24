package com.example.aiagenttestapp.data.speakers

import android.content.Context
import android.net.Uri
import com.example.aiagenttestapp.data.benchmark.CompressedAudioDecoder
import com.example.aiagenttestapp.data.benchmark.WavConverter
import com.example.aiagenttestapp.data.notes.WavFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a picked audio file into samples an enrolment take can be made from.
 *
 * Enrolment records from the microphone, which is right for a person holding the phone and wrong for
 * a measurement. Playing a known voice through a speaker so the app can hear it adds the room, the
 * speaker and the capture path to the voiceprint: on this app's own benchmark the same audio scored
 * 73.6% imported and 69.0% over the air. When the question is whether the *models* separate two
 * voices, that difference is noise on the answer -- and re-enrolling after an embedding-model change
 * should not quietly cost four points.
 *
 * Deliberately the same two decoders [DiarizedAudioStore] uses rather than a third: they already
 * answer "what is this file actually" by sniffing the header, which matters because a `.wav` that is
 * really an m4a is ordinary once a file has been through a messaging app.
 *
 * The converted copy lives in the cache and is deleted before returning. An enrolment take becomes a
 * voiceprint and nothing else -- keeping the audio would mean holding a recording of somebody's voice
 * for no purpose the feature has.
 */
@Singleton
class TakeAudioReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Samples at the pipeline's rate, or null with a reason the caller can show. */
    suspend fun read(audio: Uri): kotlin.Result<FloatArray> = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "enrol-take-${System.currentTimeMillis()}.wav")
        try {
            val head = runCatching {
                context.contentResolver.openInputStream(audio)?.use { stream ->
                    val bytes = ByteArray(12)
                    var filled = 0
                    while (filled < bytes.size) {
                        val read = stream.read(bytes, filled, bytes.size - filled)
                        if (read <= 0) break
                        filled += read
                    }
                    bytes.copyOf(filled)
                }
            }.getOrNull() ?: return@withContext kotlin.Result.failure(
                IllegalStateException("Could not open the audio file."),
            )

            val converted = if (WavConverter.looksLikeWav(head)) {
                context.contentResolver.openInputStream(audio)?.use {
                    WavConverter.toPipelineWav(it, target) {}
                } ?: return@withContext kotlin.Result.failure(
                    IllegalStateException("Could not open the audio file."),
                )
            } else {
                CompressedAudioDecoder.decode(context, audio, target) {}
            }

            when (converted) {
                is WavConverter.Result.Unsupported ->
                    kotlin.Result.failure(IllegalStateException(converted.reason))

                is WavConverter.Result.Converted -> {
                    if (converted.sampleCount == 0) {
                        kotlin.Result.failure(IllegalStateException("The audio file holds no samples."))
                    } else {
                        kotlin.Result.success(WavFile.read(target))
                    }
                }
            }
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        } finally {
            target.delete()
        }
    }
}
