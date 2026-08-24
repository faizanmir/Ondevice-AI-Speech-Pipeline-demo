package com.example.aiagenttestapp.data.speakers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.aiagenttestapp.data.benchmark.CompressedAudioDecoder
import com.example.aiagenttestapp.data.benchmark.WavConverter
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.AudioRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets audio onto disk for a diarisation run, however it arrived.
 *
 * Both ways in land in the same place and the same format -- 16 kHz mono WAV under `filesDir` -- so
 * that everything after this point is one code path. A recording made on the screen and a file
 * dropped in from a messaging app are indistinguishable to [DiarizeWorker], which is what keeps the
 * "does this work for imports too?" question from having to be asked of every later change.
 *
 * `filesDir`, not cache: these recordings are re-run when a speaker comes out wrong, sometimes days
 * later, and a corpus that evaporates under storage pressure takes the comparisons with it. The
 * same call the benchmark clips make, for the same reason.
 */
@Singleton
class DiarizedAudioStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DiarizedDao,
) {

    sealed interface Result {
        data class Imported(val id: Long) : Result
        data class Failed(val message: String) : Result
    }

    private fun dir(): File = File(context.filesDir, "diarized").apply { mkdirs() }

    private fun target(): File = File(dir(), "audio-${System.currentTimeMillis()}.wav")

    /**
     * Converts a picked file and records it.
     *
     * Reuses the benchmark's two decoders rather than growing a third: they already answer "what is
     * this file actually" by sniffing the header, which matters because a `.wav` that is really an
     * m4a is ordinary once a file has been through a messaging app.
     */
    suspend fun import(
        audio: Uri,
        expectedSpeakers: Int,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val target = target()

        val head = runCatching {
            resolver.openInputStream(audio)?.use { stream ->
                val bytes = ByteArray(12)
                var filled = 0
                while (filled < bytes.size) {
                    val read = stream.read(bytes, filled, bytes.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                bytes.copyOf(filled)
            }
        }.getOrNull() ?: return@withContext Result.Failed("Could not open the audio file.")

        val converted = try {
            if (WavConverter.looksLikeWav(head)) {
                resolver.openInputStream(audio)?.use { WavConverter.toPipelineWav(it, target, onProgress) }
                    ?: return@withContext Result.Failed("Could not open the audio file.")
            } else {
                CompressedAudioDecoder.decode(context, audio, target, onProgress)
            }
        } catch (e: IOException) {
            target.delete()
            return@withContext Result.Failed(e.message ?: "Could not read the audio file.")
        }

        when (converted) {
            is WavConverter.Result.Unsupported -> {
                target.delete()
                Result.Failed(converted.reason)
            }

            is WavConverter.Result.Converted -> {
                if (converted.sampleCount == 0) {
                    target.delete()
                    return@withContext Result.Failed("The audio file holds no samples.")
                }
                Result.Imported(
                    record(
                        name = displayName(audio) ?: target.name,
                        file = target,
                        sampleCount = converted.sampleCount,
                        expectedSpeakers = expectedSpeakers,
                    ),
                )
            }
        }
    }

    /** Records audio captured on the screen, already written as a pipeline WAV. */
    suspend fun adopt(file: File, name: String, expectedSpeakers: Int): Result =
        withContext(Dispatchers.IO) {
            val samples = runCatching { WavFile.Reader(file).use { it.sampleCount } }
                .getOrElse { return@withContext Result.Failed("The recording could not be read.") }
            if (samples <= 0) {
                file.delete()
                return@withContext Result.Failed("The recording is empty.")
            }
            Result.Imported(record(name, file, samples, expectedSpeakers))
        }

    private suspend fun record(
        name: String,
        file: File,
        sampleCount: Int,
        expectedSpeakers: Int,
    ): Long = dao.insert(
        DiarizedRecording(
            name = name,
            audioPath = file.absolutePath,
            durationMillis = sampleCount * 1000L / AudioRecorder.SAMPLE_RATE,
            createdAtMillis = System.currentTimeMillis(),
            expectedSpeakers = expectedSpeakers,
        ),
    )

    /** Removes the row and the audio behind it. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val row = dao.byId(id) ?: return@withContext
        dao.delete(id) // blocks go with it via the cascade
        File(row.audioPath).delete()
    }

    /** Where a live take is written while it is being recorded. */
    fun newLiveFile(): File = target()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}
