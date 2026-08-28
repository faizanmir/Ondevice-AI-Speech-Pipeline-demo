package com.example.aiagenttestapp.data.benchmark

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.AudioRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Turns a picked audio + reference pair into a [BenchmarkClip].
 *
 * Everything is copied at import, immediately, while the SAF grant is alive -- the app persists no
 * URI permissions anywhere (a deliberate house pattern), so the picked Uris are worthless the next
 * time the process starts. The audio copy goes to `filesDir/benchmark`, not cache, because a
 * reference corpus that evaporates under storage pressure invalidates the comparisons built on it
 * -- and outside `note-audio`, so the orphan sweep never mistakes a clip for an abandoned
 * recording.
 *
 * The reference is read raw rather than through FileTextExtractor: its sanitise-and-truncate pass
 * mutates text, and the scorer must see exactly the file `wer.py` would have seen.
 *
 * Audio arrives as WAV or as anything the device's codecs can decode; the header decides which of
 * [WavConverter] and [CompressedAudioDecoder] handles it. Both produce the same 16 kHz mono file,
 * but only the WAV path can be bit-exact -- a lossy source is a different recording, so a clip
 * imported from an m4a is not comparable to the same recording imported from its WAV.
 */
class BenchmarkImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clipDao: BenchmarkClipDao,
) {

    sealed interface Result {
        data class Imported(val clipId: Long) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Imports with the reference read from a picked file.
     *
     * Kept as its own entry point rather than folded into [import] so that the two ways of supplying
     * a reference fail differently: a file can be unreadable, the wrong type, or too large, and none
     * of those are things typed text can be. The screen offers them as two choices, and each reports
     * what actually went wrong with the choice the user made.
     */
    suspend fun importFromFile(
        audio: Uri,
        transcript: Uri,
        language: String,
        onProgress: (Float) -> Unit = {},
    ): Result =
        withContext(Dispatchers.IO) {
            val reference = ReferenceText.read(context, transcript)
                ?: return@withContext Result.Failure(ReferenceText.UNREADABLE)

            import(audio, reference, language, onProgress)
        }

    /** Imports with the reference already in hand -- pasted, typed, or read by [importFromFile]. */
    suspend fun import(
        audio: Uri,
        reference: String,
        language: String,
        onProgress: (Float) -> Unit = {},
    ): Result =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver

            if (reference.isBlank()) {
                return@withContext Result.Failure("The reference transcript is empty.")
            }

            val target = File(benchmarkDir(context).apply { mkdirs() }, "clip-${System.currentTimeMillis()}.wav")

            // Which decoder by what the file *is*, never by its name or the picker's MIME: a `.wav`
            // that is really an m4a is ordinary once a file has been through a messaging app, and
            // trusting the extension answers "corrupt header" to something that decodes fine.
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
            }.getOrNull() ?: return@withContext Result.Failure("Could not open the audio file.")

            val converted = try {
                if (WavConverter.looksLikeWav(head)) {
                    resolver.openInputStream(audio)?.use {
                        WavConverter.toPipelineWav(it, target, onProgress)
                    }
                        ?: return@withContext Result.Failure("Could not open the audio file.")
                } else {
                    CompressedAudioDecoder.decode(context, audio, target, onProgress)
                }
            } catch (e: IOException) {
                target.delete()
                return@withContext Result.Failure(e.message ?: "Could not read the audio file.")
            }

            when (converted) {
                is WavConverter.Result.Unsupported -> {
                    target.delete()
                    Result.Failure(converted.reason)
                }

                is WavConverter.Result.Converted -> {
                    if (converted.sampleCount == 0) {
                        target.delete()
                        return@withContext Result.Failure("The audio file holds no samples.")
                    }
                    val id = clipDao.insert(
                        BenchmarkClip(
                            name = displayName(audio) ?: target.name,
                            audioPath = target.absolutePath,
                            referenceText = reference,
                            language = language,
                            durationMillis =
                                converted.sampleCount * 1000L / AudioRecorder.SAMPLE_RATE,
                            createdAtMillis = System.currentTimeMillis(),
                        ),
                    )
                    Result.Imported(id)
                }
            }
        }

    /** The picked file's own name, for anything that has to say which file it is working on. */
    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {

        fun benchmarkDir(context: Context) = File(context.filesDir, "benchmark")
    }
}
