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
enum class SpeechEngineKind {
    SENSE_VOICE,
    WHISPER,

    /**
     * An offline NeMo transducer -- Parakeet -- run through sherpa's `OfflineRecognizer`.
     *
     * It shares the encoder/decoder/joiner file shape with [STREAMING_ZIPFORMER] and shares nothing
     * else with it. Only the *decoder* is a transducer here; the model is still handed a clip at a
     * time and answers once, like the other two offline families. What it does inherit from the
     * transducer side is the absence of a length wall -- it runs on [AudioSegmenter.PARAKEET], four
     * times the slice length the other two are held to -- and the absence of a detected-language
     * field.
     */
    NEMO_TRANSDUCER,

    /**
     * A streaming Zipformer transducer, run through sherpa's `OnlineRecognizer`.
     *
     * The odd one out, and in the way that matters most: the offline families decode a clip and
     * answer once, while this one is fed audio as it arrives and revises its answer continuously.
     * It also has no length limit -- the 29.5 s truncation that forces every other ONNX recording to
     * be cut into pieces does not apply -- so a streaming model only ever gets sliced where a marker
     * or a speaker turn says it should.
     */
    STREAMING_ZIPFORMER,
    ;

    /** Whether audio is fed to this family incrementally rather than a clip at a time. */
    val isStreaming: Boolean get() = this == STREAMING_ZIPFORMER

    /**
     * Whether this family reports a time for every word, which is what speaker attribution needs.
     *
     * Declared here rather than discovered by a type check at the call site, the same way the engine
     * layer declares `supportsNativeTools` and `supportsVision` -- a capability nobody can read off
     * the class is a capability every caller ends up guessing at.
     *
     * This flag was wrong once, and cheaply: it started life as "only the NeMo transducer", written
     * after Whisper was measured returning empty timestamp arrays. It does return them -- sherpa
     * computes token times for Whisper by cross-attention DTW -- but only when
     * `enableTokenTimestamps` is set, and it had not been. A measurement of a switched-off feature
     * was read as a property of the model.
     *
     * SenseVoice fills timestamps too, and is still false here: its vocabulary mixes sentencepiece
     * with per-character Chinese tokens, and nothing has checked what a word boundary means in it.
     * False for "unverified" rather than "cannot" -- and it stays that way until someone runs it.
     */
    val reportsWordTimings: Boolean
        get() = this == NEMO_TRANSDUCER || this == WHISPER
}

/**
 * A speech-to-text model the user can pick in Settings.
 *
 * Three shapes exist: SenseVoice is a single ONNX plus tokens, Whisper an encoder/decoder pair plus
 * tokens, and the two transducers add a joiner to that pair. The nullable file slots express that;
 * [SpeechModelPaths] carries the same shape onward to the recogniser with the files resolved.
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
    /** Transducers only: the joiner network. Null for the encoder/decoder and single-file shapes. */
    internal val joiner: SpeechModelFile? = null,
    internal val tokens: SpeechModelFile,
) {
    internal val files: List<SpeechModelFile>
        get() = listOfNotNull(model, encoder, decoder, joiner, tokens)
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** A [SpeechModel] with its files resolved to absolute paths, ready for [SpeechRecognizer.load]. */
data class SpeechModelPaths(
    val id: String,
    val kind: SpeechEngineKind,
    val model: File?,
    val encoder: File?,
    val decoder: File?,
    val joiner: File?,
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
    val available: List<SpeechModel> =
        listOf(SENSE_VOICE, WHISPER_SMALL, PARAKEET_V3, STREAMING_EN)

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

    /**
     * Download state of one named model, regardless of which is selected.
     *
     * [state] only ever describes the selected model, which is all the old record screen needed --
     * it offered a single "Speech model" option and downloaded whatever Settings pointed at. Now
     * that the screen lists SenseVoice and Whisper Small separately, it has to show each one's own
     * download state next to it, including for the model the user has *not* chosen.
     */
    fun stateOf(modelId: String): StateFlow<SpeechModelState> =
        states[modelId] ?: MutableStateFlow(SpeechModelState.NotDownloaded)

    private fun byId(id: String?): SpeechModel =
        available.firstOrNull { it.id == id } ?: available.first()

    /** [byId] for callers outside this class -- the picker resolving a stored Settings id. */
    fun byIdOrDefault(id: String?): SpeechModel = byId(id)

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
            joiner = m.joiner?.let(::fileFor),
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

    /** Deletes one recogniser without discarding the other models the user chose to keep. */
    fun delete(model: SpeechModel) {
        cancelDownload(model)
        model.files.forEach { fileFor(it).delete() }
        refresh()
    }

    fun delete() {
        available.forEach(::delete)
    }

    private fun fileFor(file: SpeechModelFile): File = File(dir, file.name)

    companion object {
        private const val HF = "https://huggingface.co"

        /**
         * Spelled out because the neighbouring `-v2` repo is a trap: same file names, same sizes to
         * within a megabyte, English only. Downloading v2 by mistake would produce a model that
         * loads, transcribes English perfectly, and returns nonsense for the German notes this entry
         * exists to serve.
         */
        private const val PARAKEET_V3_REPO = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

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
         * A streaming Zipformer transducer: the only model here that transcribes *while* you speak.
         *
         * English only, and that is a hard limit rather than a shortlist -- sherpa-onnx publishes no
         * streaming model for German in any family, so a German recording still needs Whisper. The
         * trade is the usual one for streaming: a transducer decides each word with only a little
         * audio after it, where Whisper sees the whole clip, so identifiers and rare nouns suffer.
         *
         * What it buys is latency. It has no 30-second wall to be cut around, so a recording of any
         * length is one continuous decode that finishes when the recording does, rather than a queue
         * of clips worked through afterwards.
         */
        private val STREAMING_EN = SpeechModel(
            id = "streaming-zipformer-en",
            label = "Streaming (English)",
            blurb = "Transcribes live while you record, and is ready the moment you stop. " +
                "English only, and less accurate on codes and names than Whisper Small.",
            kind = SpeechEngineKind.STREAMING_ZIPFORMER,
            model = null,
            encoder = SpeechModelFile(
                name = "streaming-en-encoder.int8.onnx",
                url = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/" +
                    "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx?download=true",
                sizeBytes = 71_083_163L,
            ),
            // Not quantised, unlike its neighbours: the decoder is a small embedding lookup where
            // int8 saves two megabytes and costs accuracy on exactly the rare tokens -- names and
            // identifiers -- this model is already weakest on.
            decoder = SpeechModelFile(
                name = "streaming-en-decoder.onnx",
                url = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/" +
                    "decoder-epoch-99-avg-1-chunk-16-left-128.onnx?download=true",
                sizeBytes = 2_092_621L,
            ),
            joiner = SpeechModelFile(
                name = "streaming-en-joiner.int8.onnx",
                url = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/" +
                    "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx?download=true",
                sizeBytes = 259_335L,
            ),
            tokens = SpeechModelFile(
                name = "streaming-en-tokens.txt",
                url = "$HF/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/" +
                    "tokens.txt?download=true",
                sizeBytes = 5_048L,
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

        /**
         * Parakeet TDT 0.6B v3, int8-quantised: NVIDIA's FastConformer encoder with a transducer
         * decoder, covering English, German and 23 other European languages.
         *
         * It is here for German. Whisper Small is the only other model in this list that speaks it,
         * and `docs/stt-benchmark.html` measured Whisper at 9.4% number-normalised WER on the German
         * audit script -- the best German result this pipeline has produced. Parakeet publishes 5.0%
         * on FLEURS German and 6.34% averaged over the Open ASR leaderboard, against Whisper Small's
         * ~15.9% on ESB. Different corpora, so those are not a like-for-like promise; what they are
         * is a wide gap pointing the same way on every set either model has been run on.
         *
         * Whisper Small stays regardless. Parakeet's 25 languages are all European, so a Hindi,
         * Japanese or Arabic note still has exactly one option.
         *
         * Two things it does not do, both of which the pipeline already tolerates:
         *
         *  - **It reports no detected language.** sherpa's transducer result carries text, tokens
         *    and timestamps but no `lang` field, so a Parakeet note comes back with a null language
         *    however confidently the model identified the audio to transcribe it. That is the same
         *    answer [GemmaTranscriber] gives, and `dominantLanguage` in `TranscriptionRun` already
         *    handles a run of nulls.
         *  - **It has no 30-second wall**, which is the main reason to carry it. It runs on
         *    [AudioSegmenter.PARAKEET] -- 120 s slices against the 28 s the other two are held
         *    to, so a ten-minute note costs a quarter of the encoder passes and each one sees
         *    four times the context. Note what that cap is *not*: NVIDIA's 24-minute figure is
         *    quoted for an 80 GB A100, and the checkpoint's `att_context_size: [-1, -1]` is why
         *    -- full attention costs memory as the square of the clip. [AudioSegmenter.PARAKEET]
         *    carries the arithmetic, and the second ceiling sitting above it.
         *
         * The cost is size. 670 MB against Whisper Small's 375 MB, nearly all of it the encoder --
         * int8 quantisation barely shrinks a 0.6 B-parameter encoder, since the weights were already
         * headed for one byte each.
         */
        private val PARAKEET_V3 = SpeechModel(
            id = "parakeet-tdt-v3",
            label = "Parakeet v3",
            blurb = "NVIDIA's recogniser for English, German and 23 more European languages. " +
                "Scores better than Whisper Small on every published benchmark, and costs " +
                "roughly twice the download.",
            kind = SpeechEngineKind.NEMO_TRANSDUCER,
            model = null,
            encoder = SpeechModelFile(
                name = "parakeet-v3-encoder.int8.onnx",
                url = "$HF/csukuangfj/$PARAKEET_V3_REPO/resolve/main/encoder.int8.onnx?download=true",
                sizeBytes = 652_184_281L,
            ),
            // int8 throughout, decoder included -- where the streaming Zipformer above deliberately
            // takes the float build, because quantising a small embedding lookup saves two megabytes
            // and costs accuracy on exactly the rare tokens it is weakest on. That preference is not
            // available here: the float export is a single 2.5 GB repo with no per-network choice,
            // four times this download for a model that is already the largest on the list.
            decoder = SpeechModelFile(
                name = "parakeet-v3-decoder.int8.onnx",
                url = "$HF/csukuangfj/$PARAKEET_V3_REPO/resolve/main/decoder.int8.onnx?download=true",
                sizeBytes = 11_845_275L,
            ),
            joiner = SpeechModelFile(
                name = "parakeet-v3-joiner.int8.onnx",
                url = "$HF/csukuangfj/$PARAKEET_V3_REPO/resolve/main/joiner.int8.onnx?download=true",
                sizeBytes = 6_355_277L,
            ),
            // Ten times the v2 export's 9 KB, and that ratio is where the multilingual part lives:
            // a BPE vocabulary covering 25 languages rather than English alone. A tokens file that
            // arrived at 9 KB would mean the wrong repo was fetched -- see [PARAKEET_V3_REPO].
            tokens = SpeechModelFile(
                name = "parakeet-v3-tokens.txt",
                url = "$HF/csukuangfj/$PARAKEET_V3_REPO/resolve/main/tokens.txt?download=true",
                sizeBytes = 93_939L,
            ),
        )
    }
}
