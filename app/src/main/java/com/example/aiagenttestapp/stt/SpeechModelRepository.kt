package com.example.aiagenttestapp.stt

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.downloadToFile
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
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
 *
 * Downloads run in a WorkManager foreground job ([SpeechModelDownloadWorker]), the same way the
 * language models download: the transfer survives leaving the screen, backgrounding the app and
 * process death, waits for a network connection, and shows its progress in a notification.
 * Download state is derived from WorkManager rather than written by the download loop, so it is
 * correct even when the app restarts while a worker is mid-transfer.
 */
class SpeechModelRepository(context: Context, private val settings: SettingsStore) {

    private val dir = File(context.applicationContext.filesDir, "speech").apply { mkdirs() }

    private val workManager = WorkManager.getInstance(context.applicationContext)

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

    init {
        // The single writer of the per-model states: whatever WorkManager says about a model's
        // download, reconciled with the disk. Surviving work from a previous process shows up
        // here too, which the old in-process download could never do.
        scope.launch {
            workManager.getWorkInfosByTagFlow(WORK_TAG).collect { infos ->
                available.forEach { model ->
                    states.getValue(model.id).value = deriveState(model, infos)
                }
            }
        }
    }

    private fun deriveState(model: SpeechModel, infos: List<WorkInfo>): SpeechModelState {
        // Files complete on disk always win: an old FAILED record lingering in WorkManager's DB
        // must not override a model that is actually there.
        if (isDownloaded(model)) return SpeechModelState.Ready

        // Finished attempts linger next to a new one, so prefer the live work: running, then
        // queued, then the most recent failure.
        val group = infos.filter { it.tags.contains(modelTag(model.id)) }
        val live = group.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: group.firstOrNull {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
            }
            ?: group.firstOrNull { it.state == WorkInfo.State.FAILED }

        return when (live?.state) {
            WorkInfo.State.RUNNING ->
                SpeechModelState.Downloading(live.progress.getFloat(KEY_PROGRESS, 0f))
            // Queued (waiting for network, or for the worker to spin up) -- show a download at 0%
            // rather than nothing, so the tap visibly took.
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                SpeechModelState.Downloading(0f)
            WorkInfo.State.FAILED ->
                SpeechModelState.Failed(
                    live.outputData.getString(KEY_ERROR) ?: "Download failed",
                )
            else -> SpeechModelState.NotDownloaded
        }
    }

    val totalBytes: Long get() = selected.totalBytes

    private fun byId(id: String?): SpeechModel =
        available.firstOrNull { it.id == id } ?: available.first()

    /** Strict lookup for the download worker, which must not fall back to a different model. */
    internal fun modelWithId(id: String): SpeechModel? = available.firstOrNull { it.id == id }

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

    /**
     * Enqueues the download as persistent WorkManager foreground work ([SpeechModelDownloadWorker]).
     * Safe to call repeatedly: [ExistingWorkPolicy.KEEP] ignores the request while one is already
     * running, and starts a fresh one once the last attempt has finished -- so this doubles as the
     * retry path after a failure or a cancel.
     */
    fun enqueueDownload(model: SpeechModel = selected) {
        val request = OneTimeWorkRequestBuilder<SpeechModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL_ID to model.id))
            .addTag(WORK_TAG)
            .addTag(modelTag(model.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(uniqueName(model.id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(model: SpeechModel = selected) {
        workManager.cancelUniqueWork(uniqueName(model.id))
    }

    /**
     * The bytes-to-disk logic, run inside [SpeechModelDownloadWorker]. Reports overall progress in
     * [0, 1] and throws on failure. Deliberately writes no state: the repository derives state from
     * WorkManager, which stays correct even when this runs in a restarted process.
     */
    internal suspend fun performDownload(
        model: SpeechModel,
        onProgress: suspend (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (isDownloaded(model)) return@withContext

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
                onProgress(((completed + bytes).toFloat() / total).coerceIn(0f, 1f))
            }

            if (part.length() != file.sizeBytes) {
                part.delete()
                throw IOException("${file.name} downloaded to the wrong size")
            }
            if (!part.renameTo(target)) throw IOException("Could not save ${file.name}")

            completed += file.sizeBytes
        }
    }

    private suspend fun downloadTo(
        file: SpeechModelFile,
        target: File,
        onProgress: suspend (Long) -> Unit,
    ) = downloadToFile(client, file.url, target, file.name, onProgress)

    fun delete() {
        available.flatMap { it.files }.forEach { fileFor(it).delete() }
        refresh()
    }

    private fun fileFor(file: SpeechModelFile): File = File(dir, file.name)

    companion object {
        private const val HF = "https://huggingface.co"

        /** Work-data keys shared with [SpeechModelDownloadWorker]. */
        internal const val KEY_MODEL_ID = "speechModelId"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_ERROR = "error"

        internal const val WORK_TAG = "speech-model-download"

        private fun uniqueName(modelId: String) = "speech-download:$modelId"
        private fun modelTag(modelId: String) = "speech-model:$modelId"

        /**
         * SenseVoice, int8-quantised. Ungated, Apache-2.0. Fast, and does inverse text
         * normalisation itself -- but its languages are fixed: English, Chinese, Japanese, Korean,
         * Cantonese, nothing else. File names are the pre-picker ones on purpose, so an already-
         * downloaded copy keeps working after an app update.
         *
         * Sizes here and below are HuggingFace's authoritative LFS sizes, checked after download --
         * see [isDownloaded].
         */
        private val SENSE_VOICE = SpeechModel(
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
        private val WHISPER_SMALL = SpeechModel(
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
