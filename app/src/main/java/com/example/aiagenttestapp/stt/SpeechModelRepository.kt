package com.example.aiagenttestapp.stt

import android.content.Context
import android.util.Log
import com.example.aiagenttestapp.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One file that makes up a speech model. */
internal data class SpeechModelFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

/** Which recogniser family a model belongs to -- decides how [SpeechRecognizer] configures it. */
enum class SpeechEngineKind { SENSE_VOICE, WHISPER }

/**
 * A speech-to-text model the user can pick in Settings.
 *
 * Two shapes exist: SenseVoice is a single ONNX plus tokens, Whisper an encoder/decoder pair plus
 * tokens. The nullable file slots express that; [SpeechModelPaths] carries the same shape onward
 * to the recogniser with the files resolved.
 */
class SpeechModel internal constructor(
    val id: String,
    val label: String,
    /** Shown in the Settings picker: what it covers and what it costs. */
    val blurb: String,
    val kind: SpeechEngineKind,
    internal val model: SpeechModelFile?,
    internal val encoder: SpeechModelFile?,
    internal val decoder: SpeechModelFile?,
    internal val tokens: SpeechModelFile,
) {
    internal val files: List<SpeechModelFile> get() = listOfNotNull(model, encoder, decoder, tokens)
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** A [SpeechModel] with its files resolved to absolute paths, ready for [SpeechRecognizer.load]. */
data class SpeechModelPaths(
    val id: String,
    val kind: SpeechEngineKind,
    val model: File?,
    val encoder: File?,
    val decoder: File?,
    val tokens: File,
)

sealed interface SpeechModelState {
    data object NotDownloaded : SpeechModelState
    data class Downloading(val progress: Float) : SpeechModelState
    data object Ready : SpeechModelState
    data class Failed(val message: String) : SpeechModelState
}

/**
 * Downloads and tracks the speech-to-text models, and knows which one Settings has selected.
 *
 * Kept apart from [com.example.aiagenttestapp.data.ModelRepository] on purpose. That one models a
 * *language* model: one file, one engine, a RAM budget, a fit verdict. A speech model is a different
 * animal -- several files that are only useful together, no parameter count, no bearing on the
 * catalogue's memory arithmetic. Forcing them through the same type would have meant weakening
 * ModelSpec (multi-file, nullable params, an ASR-shaped format enum) to describe something with a
 * two-entry catalogue. Two small honest types beat one dishonest one.
 */
class SpeechModelRepository(context: Context, private val settings: SettingsStore) {

    private val dir = File(context.applicationContext.filesDir, "speech").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Only for deriving [state]; the repository is an app-lifetime singleton, so never cancelled. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Everything the Settings picker offers, in display order. First entry is the default. */
    val available: List<SpeechModel> = listOf(SENSE_VOICE, WHISPER_SMALL)

    private val states: Map<String, MutableStateFlow<SpeechModelState>> =
        available.associate { it.id to MutableStateFlow(stateOnDisk(it)) }

    /** The model chosen in Settings; SenseVoice until the user picks otherwise. */
    val selected: SpeechModel
        get() = byId(settings.settings.value.speechModelId)

    /**
     * Download state of the *selected* model. Re-points itself when the Settings choice changes,
     * so the record screen's download card always describes the model that would actually be used.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SpeechModelState> = settings.settings
        .map { byId(it.speechModelId).id }
        .distinctUntilChanged()
        .flatMapLatest { id -> states.getValue(id) }
        .stateIn(scope, SharingStarted.Eagerly, stateOnDisk(selected))

    val totalBytes: Long get() = selected.totalBytes

    private fun byId(id: String?): SpeechModel =
        available.firstOrNull { it.id == id } ?: available.first()

    /** Every file present and exactly the right size. A truncated ONNX crashes the native loader. */
    fun isDownloaded(model: SpeechModel = selected): Boolean =
        model.files.all { fileFor(it).length() == it.sizeBytes }

    fun refresh() {
        available.forEach { states.getValue(it.id).value = stateOnDisk(it) }
    }

    private fun stateOnDisk(model: SpeechModel): SpeechModelState =
        if (isDownloaded(model)) SpeechModelState.Ready else SpeechModelState.NotDownloaded

    /** The selected model, resolved to paths for [SpeechRecognizer.load]. */
    fun selectedPaths(): SpeechModelPaths = selected.let { m ->
        SpeechModelPaths(
            id = m.id,
            kind = m.kind,
            model = m.model?.let(::fileFor),
            encoder = m.encoder?.let(::fileFor),
            decoder = m.decoder?.let(::fileFor),
            tokens = fileFor(m.tokens),
        )
    }

    suspend fun download(model: SpeechModel = selected) = withContext(Dispatchers.IO) {
        val state = states.getValue(model.id)
        if (isDownloaded(model)) {
            state.value = SpeechModelState.Ready
            return@withContext
        }

        try {
            var completed = 0L
            val total = model.totalBytes

            for (file in model.files) {
                val target = fileFor(file)

                if (target.length() == file.sizeBytes) {
                    completed += file.sizeBytes
                    continue
                }

                // Straight to a .part and renamed on success: a half-written ONNX that looks like a
                // finished one would be handed to the native loader and take the process down.
                val part = File(dir, "${file.name}.part")
                downloadTo(file, part) { bytes ->
                    state.value = SpeechModelState.Downloading(
                        ((completed + bytes).toFloat() / total).coerceIn(0f, 1f),
                    )
                }

                if (part.length() != file.sizeBytes) {
                    part.delete()
                    throw IOException("${file.name} downloaded to the wrong size")
                }
                if (!part.renameTo(target)) throw IOException("Could not save ${file.name}")

                completed += file.sizeBytes
            }

            state.value = SpeechModelState.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            refresh()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "speech model download failed", e)
            state.value = SpeechModelState.Failed(e.message ?: "Download failed")
        }
    }

    private fun downloadTo(file: SpeechModelFile, target: File, onProgress: (Long) -> Unit) {
        val request = Request.Builder().url(file.url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HuggingFace returned HTTP ${response.code} for ${file.name}")
            }
            val body = response.body ?: throw IOException("Empty response for ${file.name}")

            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var written = 0L
                    var lastReport = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read

                        // Throttled: reporting every 128 KB would recompose the UI hundreds of
                        // times a second for a bar that moves a pixel.
                        if (written - lastReport > 1_000_000) {
                            lastReport = written
                            onProgress(written)
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    fun delete() {
        available.flatMap { it.files }.forEach { fileFor(it).delete() }
        refresh()
    }

    private fun fileFor(file: SpeechModelFile): File = File(dir, file.name)

    private companion object {
        const val TAG = "SpeechModelRepository"
        const val HF = "https://huggingface.co"

        /**
         * SenseVoice, int8-quantised. Ungated, Apache-2.0. Fast, and does inverse text
         * normalisation itself -- but its languages are fixed: English, Chinese, Japanese, Korean,
         * Cantonese, nothing else. File names are the pre-picker ones on purpose, so an already-
         * downloaded copy keeps working after an app update.
         *
         * Sizes here and below are HuggingFace's authoritative LFS sizes, checked after download --
         * see [isDownloaded].
         */
        val SENSE_VOICE = SpeechModel(
            id = "sense-voice",
            label = "SenseVoice",
            blurb = "Fast and accurate in English, Chinese, Japanese, Korean and Cantonese. " +
                "No other languages.",
            kind = SpeechEngineKind.SENSE_VOICE,
            model = SpeechModelFile(
                name = "sense-voice.int8.onnx",
                url = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx?download=true",
                sizeBytes = 239_233_841L,
            ),
            encoder = null,
            decoder = null,
            tokens = SpeechModelFile(
                name = "sense-voice-tokens.txt",
                url = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt?download=true",
                sizeBytes = 315_894L,
            ),
        )

        /**
         * Whisper Small, int8-quantised, the multilingual option: ~100 languages including German,
         * French, Spanish and Hindi, with the language detected from the audio itself. The trade
         * is speed -- expect transcription to take several times longer than SenseVoice.
         */
        val WHISPER_SMALL = SpeechModel(
            id = "whisper-small",
            label = "Whisper Small",
            blurb = "OpenAI's multilingual recogniser: German, French, Spanish, Hindi and about " +
                "90 more, detected automatically. Noticeably slower than SenseVoice.",
            kind = SpeechEngineKind.WHISPER,
            model = null,
            encoder = SpeechModelFile(
                name = "whisper-small-encoder.int8.onnx",
                url = "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx?download=true",
                sizeBytes = 112_442_483L,
            ),
            decoder = SpeechModelFile(
                name = "whisper-small-decoder.int8.onnx",
                url = "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx?download=true",
                sizeBytes = 262_226_114L,
            ),
            tokens = SpeechModelFile(
                name = "whisper-small-tokens.txt",
                url = "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt?download=true",
                sizeBytes = 816_730L,
            ),
        )
    }
}
