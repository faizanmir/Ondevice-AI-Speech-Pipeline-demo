package com.example.aiagenttestapp.ui.notes

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.VoiceCommandMatch
import com.example.aiagent.engine.core.VoiceCommandMatcher
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.audiomodels.AudioModelState
import com.example.aiagenttestapp.data.notes.Note
import com.example.aiagenttestapp.data.audit.AuditAnalysis
import com.example.aiagenttestapp.data.audit.QuickRead
import com.example.aiagenttestapp.data.notes.NoteAnalysis
import com.example.aiagenttestapp.data.notes.NoteAnalysisParser
import com.example.aiagenttestapp.data.notes.NoteChunking
import com.example.aiagenttestapp.data.notes.NoteSummaryMode
import com.example.aiagenttestapp.data.notes.mergeAnalyses
import com.example.aiagenttestapp.data.notes.toNoteAnalysis
import com.example.aiagenttestapp.data.notes.NoteFinding
import com.example.aiagenttestapp.data.notes.NoteFindingDao
import com.example.aiagenttestapp.data.notes.NoteStatus
import com.example.aiagenttestapp.data.notes.NoteTranscribeWorker
import com.example.aiagenttestapp.data.notes.ParsedFinding
import com.example.aiagenttestapp.data.notes.PipelinePlanner
import com.example.aiagenttestapp.data.notes.SpokenMarker
import com.example.aiagenttestapp.data.notes.SpokenMarkers
import com.example.aiagenttestapp.data.notes.SttBackend
import android.os.Build
import com.example.aiagenttestapp.stt.PlatformSpeech
import com.example.aiagenttestapp.stt.Transcriber
import com.example.aiagenttestapp.stt.Punctuator
import com.example.aiagenttestapp.stt.PlatformTranscriber
import com.example.aiagenttestapp.stt.OnnxTranscriber
import com.example.aiagenttestapp.data.notes.renderReport
import com.example.aiagenttestapp.data.notes.withTaggedFloor
import com.example.aiagenttestapp.data.notes.TranscriptMarkup
import com.example.aiagenttestapp.data.notes.TranscriptionCheckpoint
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.functions.KeywordAction
import com.example.aiagenttestapp.functions.MarkerEdge
import com.example.aiagenttestapp.functions.MarkerKind
import com.example.aiagenttestapp.functions.SpokenKeywords
import com.example.aiagenttestapp.functions.VoiceCommandAction
import com.example.aiagenttestapp.functions.VoiceCommands
import com.example.aiagenttestapp.prompts.NotePromptBudget
import com.example.aiagenttestapp.prompts.audit.AuditSystemPrompts
import com.example.aiagenttestapp.prompts.NotePrompts
import com.example.aiagenttestapp.prompts.SystemPromptBuilder
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.AudioSegmenter
import com.example.aiagenttestapp.stt.KeywordDetector
import com.example.aiagenttestapp.stt.KeywordModelPaths
import com.example.aiagenttestapp.stt.SpeechActivityDetector
import com.example.aiagenttestapp.stt.SpeechRegions
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.stt.SpottedKeyword
import com.example.aiagenttestapp.stt.SttLoadPlanner
import com.example.aiagenttestapp.stt.SttModelPlan
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiEffect
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import com.example.aiagenttestapp.util.Reasoning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.notes.NoteDao
import com.example.aiagenttestapp.di.CacheDirPath
import com.example.aiagenttestapp.di.NativeLibraryDir
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Where the user is in the capture flow.
 *
 * A single linear path -- record, correct, summarise, correct, save -- with the user in control at
 * every hand-off. Neither the transcript nor the summary is ever committed without them seeing it
 * first: speech recognition mishears, and language models invent. Both are shown as editable text
 * precisely because both are wrong often enough to matter.
 */
enum class RecordStage {
    /** Idle, or recording. */
    Capture,

    /** Transcript is on screen and editable. */
    ReviewTranscript,

    /** Summary is on screen and editable. */
    ReviewSummary,
}

data class RecordUiState(
    val stage: RecordStage = RecordStage.Capture,

    val speechModelState: SpeechModelState = SpeechModelState.NotDownloaded,
    val speechModelSizeBytes: Long = 0,

    /**
     * The speech models on offer, each listed separately on the picker.
     *
     * They used to hide behind one "Speech model" option that silently used whatever Settings held.
     * That is a bad trade on this screen: the two differ in the only way that matters here -- the
     * languages they can hear -- so choosing a recording's recogniser without being told which one
     * you were getting could hand a German note to a model that recognises no German at all.
     */
    val speechModelOptions: List<SpeechModel> = emptyList(),

    /** Download state per speech model id, so each row can show its own download or progress. */
    val speechModelStates: Map<String, SpeechModelState> = emptyMap(),

    /** Which speech model the ONNX path would use. Mirrors the Settings choice. */
    val selectedSpeechModelId: String? = null,

    /**
     * Download state of the punctuation model, offered only alongside a streaming speech model.
     *
     * It sits here rather than in Settings because it is not a feature to switch on -- it is a
     * dependency of one recogniser. A transducer emits `GOOD MORNING THIS IS` and nothing else on
     * the screen explains why, so the offer belongs next to the model that needs it.
     */
    val punctuationState: AudioModelState = AudioModelState.NotDownloaded,

    /** Which recogniser the next recording will use. Chosen here, then fixed for that recording. */
    val sttBackend: SttBackend = SttBackend.DEFAULT,

    /**
     * Whether the system's own recogniser can be used, or why it cannot.
     *
     * Read once when the screen opens. Unlike the other two backends there is nothing to download
     * and so nothing to watch: the answer is a property of the OS version and of which speech
     * service the device shipped, neither of which changes while the screen is open.
     */
    val platformSpeech: PlatformSpeech.Availability =
        PlatformSpeech.Availability.Unsupported("Not checked yet."),

    /**
     * The model that would transcribe on the Gemma path, or the reason there is none.
     *
     * Resolved when the screen opens rather than when recording stops, because it decides whether
     * the option can be offered at all -- finding out after a five-minute walkthrough that nothing
     * could transcribe it would be the worst possible moment.
     */
    val gemmaSttPlan: SttModelPlan = SttModelPlan.Unavailable("Checking for an audio model…"),

    val isRecording: Boolean = false,
    val level: Float = 0f,
    val durationMillis: Long = 0,

    val isTranscribing: Boolean = false,
    val transcript: String = "",

    /** Transcript built so far while a long recording is transcribed segment by segment. */
    val partialTranscript: String = "",

    /** 0..1 progress of the segmented transcription; stays 0 until the first segment lands. */
    val transcriptionProgress: Float = 0f,

    /**
     * ISO 639 code of the language the recogniser heard ("de", "en"), or null when it reported
     * none. The summary is written in this language, and it is saved on the note.
     */
    val detectedLanguage: String? = null,

    val isSummarising: Boolean = false,
    val summary: String = "",
    val title: String = "",

    /** Whether the summary is the full inspection record or the short one. Remembered in Settings. */
    val summaryMode: NoteSummaryMode = NoteSummaryMode.DETAILED,

    /**
     * Which section of a chunked transcript is being summarised, 1-based; 0 when none is.
     *
     * On screen because a long note is now several inferences rather than one, and without this the
     * user watches a progress-free screen for minutes with no way to tell a slow run from a stuck
     * one -- the same reason transcription reports its own progress.
     */
    val summaryPart: Int = 0,

    /** How many sections the transcript was split into. 1 for a note that fits in one window. */
    val summaryTotalParts: Int = 1,

    /** The language model that will write (or wrote) the summary. Null when none is usable. */
    val summariser: ModelSpec? = null,
    val error: String? = null,

    /**
     * Whether the failed transcription on screen can simply be run again.
     *
     * True only when the note carries an error *and* its recording is still on disk. Transcription
     * failures are often transient or fixable -- the model was busy, or the user has since freed
     * memory -- and the checkpoint means a retry resumes rather than starting over, so refusing to
     * offer one threw away work that had already been done.
     */
    val canRetryTranscription: Boolean = false,

    /** The last voice command heard while recording, shown briefly as a chip. Null once cleared. */
    val lastCommandLabel: String? = null,

    /**
     * Tags the user has opened by voice and not yet closed.
     *
     * Shown live while recording, because a spoken tag has no button press to confirm it landed --
     * without this the user cannot tell an open non-conformity from a marker that was never heard, and
     * would only find out after the recording was finished.
     */
    val openMarkers: Set<MarkerKind> = emptySet(),

    /** How many spans of each kind have been opened *and* closed so far this recording. */
    val markerCounts: Map<MarkerKind, Int> = emptyMap(),

    /** Whether the keyword spotter is loaded, so the hint text can promise only what works. */
    val keywordsActive: Boolean = false,

    /**
     * Non-conformities and actions from the last summarisation.
     *
     * Derived when the summary was generated, not re-derived as the user edits the text -- exactly like
     * [summarisedBy][com.example.aiagenttestapp.data.notes.Note.summarisedBy], this records what the
     * model produced at the time rather than tracking the user's later corrections.
     */
    val findings: List<ParsedFinding> = emptyList(),

    /**
     * The note row this session is filling in, when transcription happened in the background.
     *
     * Null for a session that has not produced a row yet. Saving updates this row rather than
     * inserting, so a recording does not end up in the list twice.
     */
    val noteId: Long? = null,

) : UiState {

    /** Whether the *chosen* backend is ready. Each has its own precondition and its own fix. */
    val sttReady: Boolean
        get() = when (sttBackend) {
            SttBackend.ONNX -> speechModelState is SpeechModelState.Ready
            SttBackend.GEMMA -> gemmaSttPlan is SttModelPlan.Ready
            // Nothing for this app to download, so readiness is entirely the device's answer.
            SttBackend.PLATFORM -> platformSpeech is PlatformSpeech.Availability.Ready
        }

    val canRecord: Boolean get() = sttReady && !isTranscribing

    val canSummarise: Boolean
        get() = transcript.isNotBlank() && summariser != null && !isSummarising
}

sealed interface RecordIntent : UiIntent {
    /**
     * Kicks off a one-time speech-model download.
     *
     * [modelId] names which one; null means the currently selected model, which is what the older
     * setup card asks for.
     */
    data class DownloadSpeechModel(val modelId: String? = null) : RecordIntent

    /** Picks a speech model *and* the ONNX backend that uses it, in one tap. */
    data class SelectSpeechModel(val modelId: String) : RecordIntent

    /** Fetches the punctuation model a streaming transcript needs to be readable. */
    data object DownloadPunctuation : RecordIntent
    /** The caller must already hold RECORD_AUDIO. */
    data object StartRecording : RecordIntent
    data object StopRecording : RecordIntent

    /** The user's corrections to the transcript. They are what gets summarised and saved. */
    data class TranscriptChanged(val text: String) : RecordIntent
    data class SummaryChanged(val text: String) : RecordIntent
    data class TitleChanged(val text: String) : RecordIntent

    /** Re-runs a transcription that failed, resuming from its checkpoint. */
    data object RetryTranscription : RecordIntent

    data object Summarise : RecordIntent

    /** Picks the full or the short summary. Refused while one is being written. */
    data class SummaryModeChanged(val mode: NoteSummaryMode) : RecordIntent

    data object StopSummarising : RecordIntent
    data object BackToTranscript : RecordIntent
    data object Discard : RecordIntent
    /** Clears the "heard: ..." chip once the user has seen it. */
    data object ClearLastCommand : RecordIntent
    data object Save : RecordIntent

    /**
     * Reopens a note the background worker transcribed, at the review step.
     *
     * A recording becomes a durable note the moment it stops, so the user can leave and come back --
     * and when they do, they land back in this same flow rather than in a second one built to
     * duplicate it.
     */
    data class ResumeNote(val noteId: Long) : RecordIntent

    /** Switches the recogniser the next recording will use. Remembered in Settings. */
    data class SttBackendChanged(val backend: SttBackend) : RecordIntent
}

sealed interface RecordEffect : UiEffect {
    /**
     * A spoken command asked to go somewhere. One-shot, so backgrounding and returning does not
     * replay "open settings".
     */
    data class Navigate(val destination: AppNavigation) : RecordEffect

    /**
     * The note is committed. An effect rather than a `savedNoteId` on the state: leaving the screen
     * is something that happens once, and as state it would re-fire the moment the screen was
     * recomposed with the id still set.
     */
    data class Saved(val noteId: Long) : RecordEffect
}

@HiltViewModel
class RecordViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val models: ModelDirectory,
    private val audioModels: AudioModelRepository,
    private val audioRecorder: AudioRecorder,
    private val keywordDetector: KeywordDetector,
    @param:CacheDirPath private val cacheDirPath: String,
    private val deviceMemory: DeviceMemoryProfile,
    private val engines: EngineRegistry,
    private val modelRepository: ModelRepository,
    @param:NativeLibraryDir private val nativeLibraryDir: String,
    private val noteDao: NoteDao,
    private val noteFindingDao: NoteFindingDao,
    private val residency: ModelResidency,
    private val settingsStore: SettingsStore,
    private val speechModels: SpeechModelRepository,
    private val speechRecognizer: SpeechRecognizer,
    private val sttLoadPlanner: SttLoadPlanner,
    /**
     * Only for pre-decoding, and only so it matches what the worker would have produced.
     *
     * Punctuation is applied per slice inside the transcriber, and the pre-decoded text is written
     * into the same checkpoint the worker reads back. Pre-decoding without it would hand the user a
     * transcript punctuated in the stretches the worker decoded and bare in the stretches this did.
     */
    private val punctuator: Punctuator,
) : MviViewModel<RecordUiState, RecordIntent, RecordEffect>(
    RecordUiState(
        speechModelSizeBytes = speechModels.totalBytes,
        speechModelOptions = speechModels.available,
        selectedSpeechModelId = speechModels.selected.id,
        // A starting value only; init resolves it once the audio-model plan is known.
        sttBackend = settingsStore.settings.value.sttBackend ?: SttBackend.DEFAULT,
        platformSpeech = PlatformSpeech.availability(appContext),
    ),
) {

    private var recordingJob: Job? = null
    private var summariseJob: Job? = null
    private var commandJob: Job? = null

    /**
     * Transcribes settled slices while the recording is still running, so the wait after stop is
     * mostly already paid. Null on the backends where that would cost more than it saves.
     */
    private var pipelineJob: Job? = null

    /** How far [pipelineJob] has decoded. Only it touches this. */
    private var pipelineWatermark = 0

    /** Watches the note row while a background worker fills it in. */
    private var followJob: Job? = null

    /**
     * The tail of the recording, for the live command detector only -- **not** the whole note.
     *
     * It used to be the whole note, and that made the length of a recording a memory question: the
     * buffer grew for the entire session and the WAV was only written when the user pressed stop, so
     * a long walkthrough died with an `OutOfMemoryError` inside the buffer's own grow and took every
     * second of audio with it (measured: 38 min 55 s on a 7.6 GB tablet). Audio now goes to
     * [wavWriter] as it arrives and only [RollingSampleWindow.CAPACITY_SAMPLES] of tail is kept, so
     * the heap cost of recording is a fixed ~256 KB however long the user talks.
     *
     * Touched from two coroutines -- the capture loop appends, the command detector reads the tail --
     * so every access is guarded by [capturedLock], along with [capturedTotal], [wavWriter] and
     * [recordingFile]. Appending a whole chunk under one lock keeps the contention to once per
     * ~100 ms rather than once per sample.
     */
    private val captured = RollingSampleWindow()
    private val capturedLock = Any()

    /**
     * Samples captured this recording. The real length, which [captured] no longer knows.
     *
     * The keyword spotter is told where each chunk sits using this, so marker offsets stay absolute
     * positions in the finished WAV rather than positions in a window that keeps sliding.
     */
    private var capturedTotal = 0

    /** The WAV being appended to while recording, and the file behind it. Null when not recording. */
    private var wavWriter: WavFile.Writer? = null
    private var recordingFile: File? = null

    /** Fires app commands the user speaks mid-recording. Cooldown lives inside it. */
    private val commandMatcher = VoiceCommandMatcher(VoiceCommands.specs)

    /**
     * Command phrases heard this session, to strip from the note before it is saved.
     *
     * Only used by the *fallback* detector. The keyword spotter knows where each phrase was in the
     * audio and has it cut out before transcription instead, which is strictly better -- string
     * stripping removes every occurrence of a phrase, including one the user genuinely dictated.
     */
    private val spokenCommandPhrases = mutableSetOf<String>()

    /**
     * Spoken markers heard this recording, in order, with their positions in the audio.
     *
     * Written from the capture coroutine and read when recording stops, hence the lock. A list rather
     * than a set: three non-conformities in one walkthrough are three separate spans, and their order
     * and offsets are the whole point.
     */
    private val spokenMarkers = mutableListOf<SpokenMarker>()

    /** Audio ranges of spoken *commands*, excluded from transcription the same way markers are. */
    private val spokenCommandRanges = mutableListOf<IntRange>()

    private val markerLock = Any()

    private var summariserEngine: InferenceEngine? = null

    /** Which mode [summariserEngine] was loaded for, so a mode switch reloads rather than reuses. */
    private var summariserMode: NoteSummaryMode? = null

    /**
     * Whether this screen currently holds the resident model against a memory-pressure release.
     *
     * A guard as well as a flag: [ModelResidency.attach] is a bare counter with no cap, so an
     * unbalanced second attach would pin the model until process death. Only [pinResidency] and
     * [unpinResidency] touch it.
     */
    private var residencyPinned = false

    /**
     * The live voice-activity detector for the recording in progress -- Silero fed from the capture
     * loop, chunk by chunk, so the VAD verdict exists the moment the user presses stop instead of
     * being a serial read of the whole recording afterwards. It also lets the pre-decode pipeline
     * skip silence, which used to be decoded live and thrown away by the worker.
     *
     * Non-null only between a successful load and the end of the recording, and only while healthy:
     * any failure releases it and leaves detection to the worker's own pass, because this is an
     * optimisation and must never cost a recording anything. All three fields below are guarded by
     * [liveVadLock] -- the capture loop feeds, the pipeline reads regions, the loader drains the
     * backlog, and stop tears down, each from its own coroutine.
     */
    private var liveVad: SpeechActivityDetector? = null

    /**
     * Chunks captured while the model was still loading, waiting to be fed -- in order, because
     * Silero's running position *is* the recording's position and a skipped chunk would silently
     * shift every region boundary after it. Null once the load has resolved either way; bounded,
     * because a load that has not finished within the backlog's worth of audio is not going to.
     */
    private var liveVadPending: ArrayDeque<FloatArray>? = null
    private var liveVadPendingSamples = 0

    /** Samples the detector was actually fed, to prove at stop that it heard the whole recording. */
    private var liveVadFed = 0

    private val liveVadLock = Any()

    init {
        speechModels.state.collectIntoState { state -> copy(speechModelState = state) }

        // One collector per model rather than one for the selection: the picker shows every model's
        // download state at once, including the one that is not selected.
        speechModels.available.forEach { model ->
            speechModels.stateOf(model.id).collectIntoState { state ->
                copy(speechModelStates = speechModelStates + (model.id to state))
            }
        }

        settingsStore.settings.collectIntoState { s ->
            copy(
                selectedSpeechModelId = speechModels.byIdOrDefault(s.speechModelId).id,
                summaryMode = s.noteSummaryMode,
            )
        }

        audioModels.state(audioModels.punctuation).collectIntoState { state ->
            copy(punctuationState = state)
        }

        setState { copy(summariser = pickSummariser()) }

        // Warmed when the screen opens rather than when recording starts: the ONNX session takes a
        // moment to build, and paying that after the user taps record would either delay the recording
        // or miss the first seconds of keyword audio.
        viewModelScope.launch { loadKeywordDetector() }

        // Off the main thread: resolving this stats a file on disk for every audio-capable model in
        // the catalogue. It loads nothing -- the model is only opened once a recording has actually
        // been made and the worker picks it up.
        viewModelScope.launch(Dispatchers.IO) {
            val plan = sttLoadPlanner.plan()
            setState {
                copy(
                    gemmaSttPlan = plan,
                    // Resolved here rather than at construction because it depends on this plan.
                    sttBackend = resolveInitialBackend(plan),
                )
            }
        }
    }

    /**
     * Which recogniser to start on when the user has never chosen.
     *
     * The speech model wins whenever it is actually on disk -- it is faster and it is the only path
     * that reports a detected language, so someone who has already paid for that download should keep
     * getting the better of the two.
     *
     * The case this exists for is the other one. Defaulting blindly to the speech model showed a
     * 240 MB download prompt to people who already had an audio-capable model sitting on the
     * device, for a job it could do immediately -- a dead end presented as a requirement. When there
     * is nothing to fall back to, the prompt is honest and stands.
     *
     * A choice the user has actually made always wins; this only fills the blank.
     */
    private fun resolveInitialBackend(plan: SttModelPlan): SttBackend {
        settingsStore.settings.value.sttBackend?.let { return it }

        if (speechModels.isDownloaded()) return SttBackend.ONNX
        return if (plan is SttModelPlan.Ready) SttBackend.GEMMA else SttBackend.ONNX
    }

    /**
     * Loads the keyword spotter, if the user wants it and its model is on disk.
     *
     * Silent when unavailable. Spoken markers are an addition to recording, not a precondition for it,
     * so a missing or broken model must leave the recorder working exactly as it did before -- the
     * transcript-scanning fallback in [TranscriptMarkup.wrapSpokenMarkers] still catches markers the
     * recogniser wrote down.
     */
    private suspend fun loadKeywordDetector() {
        if (!settingsStore.settings.value.keywordMarkersEnabled) return

        val bundle = audioModels.keywords
        if (!audioModels.isReady(bundle)) return
        if (keywordDetector.isLoaded) {
            setState { copy(keywordsActive = true) }
            return
        }

        val loaded = withContext(Dispatchers.Default) {
            runCatching {
                keywordDetector.load(
                    KeywordModelPaths(
                        encoder = audioModels.fileFor(bundle, AudioModelCatalog.KWS_ENCODER),
                        decoder = audioModels.fileFor(bundle, AudioModelCatalog.KWS_DECODER),
                        joiner = audioModels.fileFor(bundle, AudioModelCatalog.KWS_JOINER),
                        tokens = audioModels.fileFor(bundle, AudioModelCatalog.KWS_TOKENS),
                        keywords = audioModels.fileFor(bundle, AudioModelCatalog.KWS_KEYWORDS),
                    ),
                )
            }.onFailure { android.util.Log.w(TAG, "keyword spotter unavailable", it) }.isSuccess
        }

        setState { copy(keywordsActive = loaded) }
    }

    override fun reduce(intent: RecordIntent): Unit = when (intent) {
        is RecordIntent.DownloadSpeechModel -> speechModels.enqueueDownload(
            intent.modelId?.let(speechModels::byIdOrDefault) ?: speechModels.selected,
        )

        is RecordIntent.SelectSpeechModel -> selectSpeechModel(intent.modelId)
        RecordIntent.DownloadPunctuation -> audioModels.enqueueDownload(audioModels.punctuation)
        RecordIntent.StartRecording -> startRecording()
        RecordIntent.StopRecording -> stopRecording()
        is RecordIntent.TranscriptChanged -> setState { copy(transcript = intent.text) }
        is RecordIntent.SummaryChanged -> setState { copy(summary = intent.text) }
        is RecordIntent.TitleChanged -> setState { copy(title = intent.text) }
        RecordIntent.Summarise -> summarise()
        is RecordIntent.SummaryModeChanged -> changeSummaryMode(intent.mode)
        RecordIntent.StopSummarising -> stopSummarising()
        RecordIntent.BackToTranscript -> backToTranscript()
        RecordIntent.Discard -> discard()
        RecordIntent.ClearLastCommand -> setState { copy(lastCommandLabel = null) }
        RecordIntent.Save -> save()
        is RecordIntent.ResumeNote -> resumeNote(intent.noteId)
        RecordIntent.RetryTranscription -> retryTranscription()

        is RecordIntent.SttBackendChanged -> changeSttBackend(intent.backend)
    }

    /**
     * Switches recogniser, and remembers it.
     *
     * Refused mid-recording rather than queued for afterwards: the backend decides how the audio is
     * sliced, and that is settled from the recording that is already in progress.
     */
    private fun changeSttBackend(backend: SttBackend) {
        if (currentState.isRecording || currentState.isTranscribing) return
        settingsStore.update { it.copy(sttBackend = backend) }
        setState { copy(sttBackend = backend, error = null) }
    }

    /**
     * Picks a speech model, and the backend that runs it, from one tap.
     *
     * Two settings move together because on this screen they are one decision. The user is choosing
     * "transcribe this with Whisper Small", not "select the ONNX backend, then separately point it
     * at Whisper Small in another screen" -- and leaving the backend behind would silently give them
     * Gemma while the Whisper chip looked selected.
     */
    private fun selectSpeechModel(modelId: String) {
        if (currentState.isRecording || currentState.isTranscribing) return
        val model = speechModels.byIdOrDefault(modelId)
        settingsStore.update {
            it.copy(speechModelId = model.id, sttBackend = SttBackend.ONNX)
        }
        setState {
            copy(
                selectedSpeechModelId = model.id,
                sttBackend = SttBackend.ONNX,
                error = null,
            )
        }
    }

    /**
     * Follows a note being transcribed in the background, and moves to review when it is done.
     *
     * Observed from the database rather than from WorkManager's progress, because the database is what
     * survives the process dying -- which is the entire reason the work is a worker.
     */
    /**
     * Puts a failed transcription back in the queue.
     *
     * Nothing is passed to the worker but the note id -- the markers, the chosen backend and the
     * slices already decoded all live in the checkpoint beside the audio, exactly as they do for a
     * job re-enqueued after a process death. The DAO refuses if the recording is gone, and the
     * `observeById` collector already running for this note flips the screen to "Transcribing…" on
     * its own, so there is no state to set here.
     */
    private fun retryTranscription() {
        val noteId = currentState.noteId ?: return
        if (!currentState.canRetryTranscription) return

        viewModelScope.launch {
            noteDao.retryTranscription(noteId)
            NoteTranscribeWorker.enqueue(appContext, noteId)
        }
    }

    private fun resumeNote(noteId: Long) {
        if (currentState.noteId == noteId) return
        setState { copy(noteId = noteId) }

        followJob?.cancel()
        followJob = viewModelScope.launch {
            noteDao.observeById(noteId).collect { note ->
                if (note == null) return@collect

                setState {
                    when (note.status) {
                        NoteStatus.Transcribing -> copy(
                            isTranscribing = true,
                            transcriptionProgress = note.transcribeProgress,
                            durationMillis = note.durationMillis,
                            stage = RecordStage.Capture,
                            // Cleared as the retry starts. Leaving the previous failure on screen
                            // under a live "Transcribing…" reads as the retry having failed
                            // instantly, which is the opposite of what is happening.
                            error = null,
                            canRetryTranscription = false,
                        )

                        NoteStatus.Draft, NoteStatus.Ready -> copy(
                            isTranscribing = false,
                            transcriptionProgress = 1f,
                            partialTranscript = "",
                            transcript = note.transcript,
                            summary = note.summary,
                            title = note.title.takeIf { it != PENDING_TITLE }.orEmpty(),
                            detectedLanguage = note.language,
                            durationMillis = note.durationMillis,
                            stage = RecordStage.ReviewTranscript,
                            error = note.error,
                            // Offered only when the recording is genuinely still there. The failure
                            // that motivated this -- a context overflow -- left a perfectly good WAV
                            // and a checkpoint on disk, so a retry costs only the slices that never
                            // got decoded.
                            canRetryTranscription = note.error != null && note.audioPath != null,
                        )
                    }
                }
            }
        }
    }

    /**
     * The model that will summarise: the one chosen in Settings, when it is usable on this device.
     *
     * The user's choice wins for the same reason it wins in chat -- they picked it. It also tends
     * to be the model already resident in memory, so summarising does not pay a second multi-GB
     * load. The old behaviour ("largest downloaded model") survives only as the fallback, and it
     * became actively wrong once Gemini Nano joined the catalogue: a system-managed model is
     * always "downloaded", so it silently won this contest on every device that supports it.
     *
     * The fallback stays largest-first on purpose: summarising is the one job here where quality
     * is worth waiting a few extra seconds for, and unlike chat the user is not sitting watching
     * each token appear.
     */
    private fun pickSummariser(): ModelSpec? {
        val chosen = settingsStore.settings.value.activeModelId
            ?.let { models.find(it) }
            ?.takeIf { canSummarise(it) }
        if (chosen != null) return chosen

        return models.snapshot()
            .filter { canSummarise(it) }
            .maxByOrNull { it.paramsBillions }
    }

    /** Usable for summarising: on disk (or OS-managed) and actually runnable on this device. */
    private fun canSummarise(model: ModelSpec): Boolean {
        if (!modelRepository.isDownloaded(model)) return false
        val engine = engines.defaultFor(model)?.descriptor ?: return false
        return ModelFitEvaluator
            .evaluateBest(model, engine, deviceMemory, isDownloaded = true)
            .canRun
    }

    private fun pinResidency() {
        if (residencyPinned) return
        residencyPinned = true
        residency.attach()
    }

    private fun unpinResidency() {
        if (!residencyPinned) return
        residencyPinned = false
        residency.detach()
    }

    /**
     * Starts loading the live VAD for this recording, buffering chunks until it is ready.
     *
     * The capture loop must not wait for the load -- delaying the microphone loses the user's first
     * words, which is a worse failure than losing the whole optimisation. But the chunks that
     * arrive during the load cannot simply be dropped either (Silero counts its position from what
     * it is fed), so they queue and are drained the moment the model is up. The same containment
     * rule as the keyword spotter applies throughout: any failure logs, releases, and leaves the
     * recording exactly as it was before live detection existed -- the worker's own VAD pass covers
     * for it after stop.
     */
    private fun startLiveVad(backend: SttBackend) {
        if (!settingsStore.settings.value.vadEnabled) return

        synchronized(liveVadLock) {
            liveVadPending = ArrayDeque()
            liveVadPendingSamples = 0
            liveVadFed = 0
        }

        // Resolved now, not in the loader coroutine: the cap is the recording's, pinned with its
        // backend, and must not move if the user changes screens mid-load.
        val cap = AudioSegmenter.capFor(
            backend,
            speechModels.selected.kind,
            settingsStore.settings.value.sliceWindow,
        )
        val provider = settingsStore.settings.value.onnxProvider.slug

        viewModelScope.launch(Dispatchers.Default) {
            val detector = SpeechActivityDetector(appContext.assets)
            val loaded = runCatching { detector.load(maxSpeechSamples = cap, provider = provider) }
                .onFailure { android.util.Log.w(TAG, "live VAD unavailable; the worker will run it", it) }
                .isSuccess

            synchronized(liveVadLock) {
                val pending = liveVadPending
                liveVadPending = null

                // pending == null means the recording already ended or gave up waiting.
                if (!loaded || pending == null) {
                    if (loaded) detector.release()
                    return@launch
                }

                runCatching {
                    pending.forEach { chunk ->
                        detector.acceptStream(chunk)
                        liveVadFed += chunk.size
                    }
                    liveVad = detector
                }.onFailure {
                    android.util.Log.w(TAG, "live VAD failed on the buffered chunks", it)
                    detector.release()
                }
            }
        }
    }

    /** One capture chunk to the live VAD -- queued while loading, fed directly once it is up. */
    private fun feedLiveVad(samples: FloatArray) {
        synchronized(liveVadLock) {
            liveVadPending?.let { pending ->
                if (liveVadPendingSamples + samples.size > LIVE_VAD_PENDING_MAX_SAMPLES) {
                    // The load has clearly hung. Give up on live detection rather than letting the
                    // backlog grow with the recording; the loader sees null and releases.
                    liveVadPending = null
                    android.util.Log.w(TAG, "live VAD load too slow; leaving detection to the worker")
                } else {
                    pending.addLast(samples)
                    liveVadPendingSamples += samples.size
                }
                return
            }

            val vad = liveVad ?: return
            runCatching {
                vad.acceptStream(samples)
                liveVadFed += samples.size
            }.onFailure {
                android.util.Log.w(TAG, "live VAD failed; the worker will run it after stop", it)
                liveVad = null
                runCatching { vad.release() }
            }
        }
    }

    /**
     * What the live VAD can say so far, shaped for the planner, or null for "assume speech
     * throughout" -- while it is loading, absent, or broken. Null is the pre-VAD behaviour, so the
     * pipeline can only ever skip audio the detector has genuinely ruled on.
     */
    private fun liveVadRegionsForPlanning(
        capturedTotal: Int,
        markers: List<SpokenMarker>,
    ): List<IntRange>? {
        // Snapshotted under the lock because classifiedUpTo touches the native model, which the
        // capture loop is feeding on another coroutine.
        val (settled, classifiedUpTo) = synchronized(liveVadLock) {
            val vad = liveVad ?: return null
            runCatching { vad.regionsSoFar() to vad.classifiedUpTo() }.getOrNull() ?: return null
        }

        return SpeechRegions.resolve(
            detected = SpeechRegions.provisional(settled, classifiedUpTo, capturedTotal),
            totalSamples = capturedTotal,
            protectedRanges = SpokenMarkers.pair(markers, capturedTotal).map { it.range },
        )
    }

    /**
     * Ends the live stream and returns the recording's regions -- or null when the verdict cannot
     * be trusted, which the caller must treat as "run the VAD after stop instead".
     *
     * The trust test is exact: the detector must have consumed every captured sample. A *partial*
     * region list recorded as complete would make the worker silently skip real speech after the
     * point of failure, which is the one failure mode this feature is not allowed to have.
     */
    private fun finishLiveVad(totalSamples: Int): List<IntRange>? {
        val (vad, fed) = synchronized(liveVadLock) {
            val v = liveVad
            liveVad = null
            liveVadPending = null
            v to liveVadFed
        }
        if (vad == null) return null

        val regions = runCatching { vad.endStream() }
            .onFailure { android.util.Log.w(TAG, "live VAD failed at stop; the worker will run it", it) }
            .getOrNull()
        runCatching { vad.release() }

        return regions?.takeIf { fed == totalSamples }
    }

    /** Tears the live VAD down without a verdict -- discard, or the screen going away. */
    private fun releaseLiveVad() {
        val vad = synchronized(liveVadLock) {
            val v = liveVad
            liveVad = null
            liveVadPending = null
            v
        }
        runCatching { vad?.release() }
    }

    private fun startRecording() {
        if (!currentState.canRecord || currentState.isRecording) return

        // The file is opened here, before a single sample arrives, because that is the whole point:
        // audio is on disk continuously from now on, so a crash or a kill costs the tail rather than
        // the recording. Opening it is one file handle and a 44-byte header -- cheap next to the
        // AudioRecord this is about to start -- so it does not need to go off-thread.
        // Same helper the orphan sweep uses to find these again, so the two can never disagree about
        // where recordings live.
        val dir = NoteTranscribeWorker.audioDir(File(cacheDirPath)).apply { mkdirs() }
        val file = File(dir, "note-${System.currentTimeMillis()}.wav")
        val writer = try {
            WavFile.Writer(file, AudioRecorder.SAMPLE_RATE)
        } catch (e: IOException) {
            setState { copy(error = e.message ?: "Could not start recording") }
            return
        }

        synchronized(capturedLock) {
            captured.clear()
            capturedTotal = 0
            recordingFile = file
            wavWriter = writer
        }

        // A first checkpoint now, before a word is spoken, naming the recogniser this recording is
        // for. Stop rewrites it with the markers, so this one is only ever read for a recording that
        // never reached stop -- and that is exactly the case that needs it: audio recovered by
        // [NoteTranscribeWorker.recoverOrphanedAudio] would otherwise default to the ONNX backend,
        // which on a Gemma-only device means a recovered note that can never be transcribed.
        val backend = currentState.sttBackend
        val modelId = (currentState.gemmaSttPlan as? SttModelPlan.Ready)?.resolved?.model?.id

        // On the Gemma path, pin the resident model for the recording *and* the hand-off to the
        // worker. Memory pressure while the user is mid-walkthrough could otherwise evict the model
        // the worker is about to need, and the transcription would then open with a multi-gigabyte
        // reload -- the single largest avoidable cost on that path. The worker's own attach
        // (GemmaTranscriber's init) takes over moments after stop enqueues the job.
        if (backend == SttBackend.GEMMA && currentState.gemmaSttPlan is SttModelPlan.Ready) {
            pinResidency()
        }

        // The VAD listens as the audio is captured, so its verdict is ready the moment the user
        // presses stop -- and the pre-decode pipeline can skip silence instead of decoding it.
        startLiveVad(backend)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                TranscriptionCheckpoint.forAudio(file).recordRequest(
                    TranscriptionCheckpoint.Request(
                        markers = emptyList(),
                        excludedRanges = emptyList(),
                        sttBackend = backend,
                        sttModelId = modelId,
                    ),
                )
            }
        }
        commandMatcher.reset()
        spokenCommandPhrases.clear()
        synchronized(markerLock) {
            spokenMarkers.clear()
            spokenCommandRanges.clear()
        }
        keywordDetector.reset()

        setState {
            copy(
                isRecording = true,
                durationMillis = 0,
                transcript = "",
                partialTranscript = "",
                transcriptionProgress = 0f,
                error = null,
                lastCommandLabel = null,
                openMarkers = emptySet(),
                markerCounts = emptyMap(),
            )
        }

        // Dispatchers.Default, not the ViewModel's main dispatcher: this loop now runs keyword
        // inference on every chunk, ~10 times a second, and doing that on the main thread would jank
        // the level meter it is trying to draw. setState is a MutableStateFlow update, so it is safe
        // off the main thread.
        recordingJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                audioRecorder.record().collect { chunk ->
                    // To disk first, then to the window. If the write fails there is no point
                    // carrying on: the note is the file now, and a recording that is only in a
                    // 4-second window is not a recording.
                    withContext(Dispatchers.IO) {
                        synchronized(capturedLock) { wavWriter }?.append(chunk.samples)
                    }

                    val (chunkStart, count) = synchronized(capturedLock) {
                        val before = capturedTotal
                        captured.append(chunk.samples)
                        capturedTotal += chunk.samples.size
                        before to capturedTotal
                    }

                    // The spotter is told where this chunk sits in the recording rather than counting
                    // for itself, so its marker offsets line up with the capture buffer even when it
                    // finished loading after recording had already begun.
                    val fired = keywordDetector.accept(chunk.samples, chunkStart.toLong())
                    if (fired.isNotEmpty()) onKeywordsSpotted(fired)

                    feedLiveVad(chunk.samples)

                    setState {
                        copy(
                            level = chunk.level,
                            durationMillis = count * 1000L / AudioRecorder.SAMPLE_RATE,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(isRecording = false, error = e.message ?: "Could not record audio")
                }
            }
        }

        // The expensive fallback runs only when the cheap detector cannot: it re-transcribes a rolling
        // window with the *full* ASR model every 1.8 s, which on Whisper Small dwarfs everything else
        // happening during a recording. With the keyword spotter loaded there is nothing for it to add.
        //
        // On the Gemma path it is not merely expensive but unusable: a multi-gigabyte language model
        // cannot answer a 4-second window every 1.8 seconds, and asking it to would starve the
        // recording itself. Gemma mode therefore has live commands only via the keyword spotter --
        // which is what the on-screen hint says.
        if (!keywordDetector.isLoaded && currentState.sttBackend == SttBackend.ONNX) {
            commandJob = viewModelScope.launch { detectCommands() }
        }

        // Transcribe as we go, where that is worth doing. Not on Gemma -- the comment above explains
        // why a multi-gigabyte model cannot be asked for anything mid-recording -- and not on a
        // streaming model, which already decodes continuously and would be doing the work twice.
        //
        // The platform backend *is* included, and the reason it was not is worth recording, because
        // the reasoning was wrong rather than merely cautious. It was excluded on the grounds that a
        // recognition session would compete "for the same service the keyword spotter is using". The
        // keyword spotter is sherpa-onnx running in this process; it never touches the system
        // recognition service. What that service will not tolerate is two sessions *from this app at
        // once*, which is what ERROR_RECOGNIZER_BUSY reported when PlatformTranscriber briefly ran
        // three in parallel. Pre-decoding opens exactly one at a time, which is the number that works.
        //
        // It is also where that backend has the most to gain: decode is 94% of its post-stop cost, so
        // moving it under the recording removes almost all of the wait rather than shortening it.
        val model = speechModels.selected
        val canPipeline = when (currentState.sttBackend) {
            SttBackend.ONNX -> !model.kind.isStreaming && speechModels.isDownloaded(model)
            SttBackend.PLATFORM -> currentState.platformSpeech is PlatformSpeech.Availability.Ready
            SttBackend.GEMMA -> false
        }
        if (canPipeline) {
            pipelineWatermark = 0
            pipelineJob = viewModelScope.launch(Dispatchers.Default) { pipelineTranscription() }
        }
    }

    /**
     * Decodes slices that are already final while the user keeps talking.
     *
     * The measured cost this attacks: transcription runs at about twice real time, so a twenty-minute
     * walkthrough is followed by roughly forty minutes of waiting, none of which had to happen after
     * the fact. [PipelinePlanner] decides what is safe to touch; everything here is plumbing around
     * that decision.
     *
     * Results go into the same checkpoint the worker resumes from, keyed by exact sample range. That
     * is what makes this free of new failure modes: the worker looks every slice up before decoding
     * it, so pipelined work is either found and reused or missed and redone. Being wrong costs time,
     * never correctness.
     */
    private suspend fun pipelineTranscription() {
        val audio = synchronized(capturedLock) { recordingFile } ?: return
        val checkpoint = TranscriptionCheckpoint.forAudio(audio)

        // Built here, once, before any slice is looked at. The worker builds its own for the same
        // backend; this one has to agree with it on two things or the whole exercise is wasted --
        // the slice cap, because a boundary that differs is a checkpoint miss, and the punctuator,
        // because the text written here is the text the worker reads back.
        //
        // A recogniser that has not loaded answers a decode by throwing, which a per-slice
        // `runCatching` swallows into silence. The first version of this looked exactly like a
        // working pipeline: ranges planned every five seconds, not one of them decoded.
        val transcriber = runCatching { openPipelineTranscriber() }.getOrElse {
            android.util.Log.w(TAG, "pipelining off: the backend would not load", it)
            null
        } ?: return

        try {
            pipelineLoop(audio, checkpoint, transcriber)
        } finally {
            // NonCancellable, and it matters more here than anywhere else: stopRecording cancels
            // this job on its way to the final transcription, so *every* recording ended with a
            // cancellation pending when this line ran. The runCatching then swallowed the
            // CancellationException that release() threw at its first suspension point, which is
            // how a leak on every single stop looked like a clean shutdown. On the platform backend
            // that meant one undestroyed system recogniser per recording.
            withContext(NonCancellable) { runCatching { transcriber.release() } }
        }
    }

    /**
     * The transcriber used for pre-decoding, or null when this backend cannot pre-decode.
     *
     * Deliberately mirrors `NoteTranscribeWorker.openTranscriber` rather than sharing it: the worker
     * resolves a *stored* request from a recording that already exists, while this resolves what the
     * live screen is set to. What they must not differ on is the resulting transcriber's slice cap
     * and punctuation, which is why both go through the same constructors.
     */
    private suspend fun openPipelineTranscriber(): Transcriber? = when (currentState.sttBackend) {
        SttBackend.ONNX -> {
            val paths = speechModels.selectedPaths()
            if (speechRecognizer.loadedModelId != paths.id) speechRecognizer.load(paths)
            OnnxTranscriber(
                speechRecognizer,
                speechModels,
                settingsStore.settings.value.sliceWindow,
            )
        }

        // Nothing to load: the models belong to the system. The session that this opens per slice is
        // the *only* one running, which is what makes it safe -- the service serves one at a time,
        // and the keyword spotter beside it is sherpa in this process, not that service.
        //
        // The version check is redundant, since the chip is only selectable when availability said
        // Ready, but lint cannot see through that call and an explicit test beats suppressing NewApi.
        SttBackend.PLATFORM -> if (Build.VERSION.SDK_INT >= PlatformSpeech.MIN_API) {
            PlatformTranscriber(
                appContext,
                punctuator = loadPipelinePunctuator(),
                // Read once, at recording start -- the same moment the backend is pinned -- so one
                // recording is never fed at two different rates.
                language = settingsStore.settings.value.platformLanguage,
                feedPace = settingsStore.settings.value.platformFeedPace.multiplier,
                chunkMillis = settingsStore.settings.value.platformFeedChunk.millis,
            )
        } else {
            null
        }

        // A multi-gigabyte language model cannot be asked for anything while a recording is running.
        SttBackend.GEMMA -> null
    }

    /** The worker's punctuator, loaded the same way, or null when its bundle is not downloaded. */
    private suspend fun loadPipelinePunctuator(): Punctuator? {
        val bundle = audioModels.punctuation
        if (!audioModels.isReady(bundle)) return null
        val ok = punctuator.load(
            model = audioModels.fileFor(bundle, AudioModelCatalog.PUNCT_MODEL),
            vocab = audioModels.fileFor(bundle, AudioModelCatalog.PUNCT_VOCAB),
        )
        return punctuator.takeIf { ok }
    }

    private suspend fun pipelineLoop(
        audio: File,
        checkpoint: TranscriptionCheckpoint,
        transcriber: Transcriber,
    ) {
        var failedPasses = 0
        while (currentCoroutineContext().isActive && currentState.isRecording) {
            delay(PIPELINE_INTERVAL_MS)

            // Every pass is wrapped, and cancellation is rethrown so stopping still stops.
            //
            // This is an optimisation running beside a live recording, and the ranking is not close:
            // a pass that throws should cost the user a few seconds of pre-decoding, never their
            // walkthrough. It is not hypothetical -- an off-by-window bug here threw
            // ArrayIndexOutOfBounds out of a Dispatchers.Default coroutine and killed the app
            // mid-recording, and only the fact that audio streams to disk saved the 52 seconds that
            // had been captured.
            val outcome = runCatching { pipelinePass(audio, checkpoint, transcriber) }
            outcome.exceptionOrNull()?.let { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A failing pass leaves the watermark where it was, so every retry re-reads a
                // window that only grows -- and a refusal that repeats identically (the platform
                // service refusing the request, say) would have this loop paying that growing read
                // every five seconds until the user stops. Three strikes and the pipeline bows out;
                // the worker transcribes everything after stop, exactly as if pre-decoding had
                // never been available.
                if (++failedPasses >= PIPELINE_MAX_FAILED_PASSES) {
                    android.util.Log.w(TAG, "pipelining off after $failedPasses failed passes", e)
                    return
                }
                android.util.Log.w(TAG, "a pipelined pass failed; recording continues", e)
            } ?: run { failedPasses = 0 }
        }
    }

    /** One pass of [pipelineTranscription]. Separated so a failure in it is containable. */
    private suspend fun pipelinePass(
        audio: File,
        checkpoint: TranscriptionCheckpoint,
        transcriber: Transcriber,
    ) {
        val captured = synchronized(capturedLock) { capturedTotal }
        val markers = synchronized(markerLock) { spokenMarkers.toList() }
        val excluded = synchronized(markerLock) { spokenCommandRanges.toList() }

        val windowStart = pipelineWatermark
        val windowEnd = captured - PipelinePlanner.TAIL_SAMPLES
        if (windowEnd - windowStart < PipelinePlanner.MIN_READY_SAMPLES) return

        // One file handle for the pass, and audio is read per cut search and per decoded slice --
        // never as one up-front window. The up-front read was quietly quadratic in a way only long
        // recordings exposed: the watermark cannot advance through unbroken silence (silence
        // between markers has no real boundary in it, and a made-up one would miss the worker's
        // slicing forever), so a long quiet stretch kept the window growing and each pass re-read
        // the whole of it. Planning is pure arithmetic; not reading what will not be decoded is
        // what makes a pass over silence cost nothing.
        WavFile.Reader(audio).use { reader ->
            val plan = PipelinePlanner.readyRanges(
                windowStart = windowStart,
                capturedTotal = captured,
                markers = markers,
                excludedRanges = excluded,
                // What the live VAD has settled so far, so silence is skipped here exactly as the
                // worker will skip it after stop. Null while the detector is loading, absent or
                // broken -- which means "assume speech throughout", never "skip". Regions cannot
                // move a boundary (SpokenMarkers.slice only lets them mark slices silent), so a
                // verdict that later differs costs a wasted or missed pre-decode, never a
                // checkpoint mismatch.
                speechRegions = liveVadRegionsForPlanning(captured, markers),
                // From the transcriber, not a constant. These two decide where cuts land, and a cut
                // that lands anywhere other than where the worker would put it makes the slice a
                // checkpoint miss -- the pre-decode still runs, the text is still written, and the
                // worker decodes the whole recording again anyway. Hard-coding the ONNX cap here
                // was harmless only for as long as ONNX was the only backend that pre-decoded.
                maxSliceSamples = transcriber.maxSliceSamples,
                // Window coordinates in and out, with the samples fetched on demand -- the same
                // shape as the worker's cutBetween, bounded by one slice cap however long the
                // recording is. An earlier version handed absolute positions against a windowed
                // array and read off the front of it.
                cutLongSlice = { from, until ->
                    val sub = reader.read(windowStart + from, windowStart + until)
                    if (sub.isEmpty()) {
                        until
                    } else {
                        from + transcriber.quietestCutBetween(sub, 0, sub.size)
                    }
                },
            )

            var decoded = 0
            for (range in plan.ranges) {
                currentCoroutineContext().ensureActive()
                if (!currentState.isRecording) return
                if (checkpoint.textFor(range) != null) {
                    // Already decoded -- but the watermark must still move past it. Skipping
                    // without advancing re-planned the same range on every pass, forever.
                    pipelineWatermark = range.last + 1
                    continue
                }

                val window = withContext(Dispatchers.IO) { reader.read(range.first, range.last + 1) }
                if (window.isEmpty()) {
                    pipelineWatermark = range.last + 1
                    continue
                }

                val piece = transcriber.transcribe(window, listOf(window.indices)).firstOrNull()
                if (piece == null) {
                    // A decode that answered nothing is skipped for good rather than retried every
                    // pass: the worker looks this range up after stop, misses, and decodes it then
                    // -- being wrong here costs time, never correctness. Retrying from the
                    // pipeline instead is the watermark stall above in a different coat.
                    pipelineWatermark = range.last + 1
                    continue
                }

                withContext(Dispatchers.IO) {
                    checkpoint.record(range, piece.text, piece.language)
                }
                pipelineWatermark = range.last + 1
                decoded++
            }

            // Even a pass that decoded nothing can settle audio -- a silent slice *bounded by a
            // marker* moves the frontier -- and the watermark has to follow, or the next pass
            // re-plans it forever.
            pipelineWatermark = maxOf(pipelineWatermark, plan.frontier)

            // Counts what was *decoded*, not what was planned. The earlier version logged the plan,
            // which read as success while every decode was failing.
            if (decoded > 0) {
                android.util.Log.d(TAG, "pipelined $decoded slice(s); watermark $pipelineWatermark")
            }
        }
    }

    /**
     * Acts on what the keyword spotter heard.
     *
     * Markers are recorded with their audio positions and become cut points later. Commands are routed
     * through the very same [VoiceCommands] dispatch the fallback detector uses, so "open settings"
     * does one thing regardless of which detector heard it.
     */
    private fun onKeywordsSpotted(fired: List<SpottedKeyword>) {
        for (spotted in fired) {
            android.util.Log.i(
                TAG,
                "spotted ${spotted.id} at ${spotted.startSample}..${spotted.endSample} " +
                    "(timestamped=${spotted.timestamped})",
            )

            when (val action = SpokenKeywords.actionFor(spotted.id)) {
                is KeywordAction.Mark -> recordMarker(action, spotted)

                is KeywordAction.Command -> {
                    // Cut the command phrase out of the audio so "stop recording" never lands in the
                    // note. No string stripping needed -- we know exactly where it was.
                    synchronized(markerLock) {
                        spokenCommandRanges += spotted.startSample until spotted.endSample
                    }
                    applyCommand(action.id, SpokenKeywords.labelFor(spotted.id))
                }

                null -> android.util.Log.w(TAG, "spotted unmapped keyword ${spotted.id}")
            }
        }
    }

    private fun recordMarker(action: KeywordAction.Mark, spotted: SpottedKeyword) {
        val markers = synchronized(markerLock) {
            spokenMarkers += SpokenMarker(
                kind = action.kind,
                edge = action.edge,
                startSample = spotted.startSample,
                endSample = spotted.endSample,
            )
            spokenMarkers.toList()
        }

        setState {
            copy(
                lastCommandLabel = SpokenKeywords.labelFor(
                    if (action.edge == MarkerEdge.Start) {
                        if (action.kind == MarkerKind.NonConformity) {
                            SpokenKeywords.NC_START
                        } else {
                            SpokenKeywords.ACTION_START
                        }
                    } else {
                        if (action.kind == MarkerKind.NonConformity) {
                            SpokenKeywords.NC_END
                        } else {
                            SpokenKeywords.ACTION_END
                        }
                    },
                ),
                openMarkers = openMarkersIn(markers),
                markerCounts = closedCountsIn(markers),
            )
        }
    }

    /** Kinds with a start that has not been closed yet. */
    private fun openMarkersIn(markers: List<SpokenMarker>): Set<MarkerKind> {
        val open = mutableSetOf<MarkerKind>()
        markers.sortedBy { it.startSample }.forEach { marker ->
            when (marker.edge) {
                MarkerEdge.Start -> open += marker.kind
                MarkerEdge.End -> open -= marker.kind
            }
        }
        return open
    }

    /** How many spans of each kind have been both opened and closed. */
    private fun closedCountsIn(markers: List<SpokenMarker>): Map<MarkerKind, Int> {
        val counts = mutableMapOf<MarkerKind, Int>()
        val open = mutableSetOf<MarkerKind>()
        markers.sortedBy { it.startSample }.forEach { marker ->
            when (marker.edge) {
                MarkerEdge.Start -> open += marker.kind
                MarkerEdge.End -> if (open.remove(marker.kind)) {
                    counts[marker.kind] = (counts[marker.kind] ?: 0) + 1
                }
            }
        }
        return counts
    }

    /**
     * Listens for spoken commands for the length of the recording.
     *
     * It transcribes only the last few seconds every so often, rather than the whole buffer, so the
     * cost stays flat no matter how long the recording runs. The same on-device speech model
     * that produces the final transcript does this too -- there is no second model, and the recorder
     * feeds both at once.
     *
     * Detection and the final transcription never overlap: [stopRecording] cancels this loop before
     * it runs the full-buffer pass, so the recogniser is only ever decoding one thing at a time.
     */
    private suspend fun detectCommands() {
        val recogniser = speechRecognizer
        runCatching {
            // Reload when the Settings choice changed, not only when nothing is loaded yet.
            val paths = speechModels.selectedPaths()
            if (recogniser.loadedModelId != paths.id) recogniser.load(paths)
        }.onFailure { return } // no recogniser -> no live commands, but recording still works

        while (currentCoroutineContext().isActive && currentState.isRecording) {
            delay(DETECT_INTERVAL_MS)

            val window = synchronized(capturedLock) {
                if (capturedTotal < MIN_DETECT_SAMPLES) return@synchronized FloatArray(0)
                captured.takeLast(DETECT_WINDOW_SAMPLES)
            }
            if (window.isEmpty()) continue

            val text = runCatching {
                withContext(Dispatchers.Default) { recogniser.transcribe(window).text }
            }.getOrNull() ?: continue

            if (text.isNotBlank()) android.util.Log.d(TAG, "heard window: \"$text\"")

            val match = commandMatcher.match(text, System.currentTimeMillis()) ?: continue
            android.util.Log.i(TAG, "command matched: ${match.id} (\"${match.matchedPhrase}\")")
            onCommandDetected(match)
        }
    }

    /**
     * Acts on a command the user spoke.
     *
     * Navigation commands leave the recorder, and per the chosen behaviour that discards the
     * in-progress recording. Recording-control commands ("stop", "discard") stay on this screen.
     * Either way the trigger phrase is remembered so it can be cut from the saved note -- the user
     * said "open settings" to issue a command, not to write it down.
     */
    private fun onCommandDetected(match: VoiceCommandMatch) {
        // Only this path needs the phrase remembered for string stripping: it has no idea *where* in
        // the audio the words were, so cutting them out is not an option.
        spokenCommandPhrases.add(match.matchedPhrase)
        applyCommand(match.id, VoiceCommands.labelFor(match.id))
    }

    /**
     * Runs a spoken command, however it was heard.
     *
     * The one dispatch point for both detectors. Navigation commands leave the recorder, and per the
     * chosen behaviour that discards the in-progress recording; recording-control commands stay here.
     */
    private fun applyCommand(id: String, label: String) {
        setState { copy(lastCommandLabel = label) }

        when (val action = VoiceCommands.actionFor(id)) {
            is VoiceCommandAction.Navigate -> {
                discard()
                emitEffect(RecordEffect.Navigate(action.destination))
            }

            VoiceCommandAction.StopRecording -> stopRecording()
            VoiceCommandAction.Discard -> discard()
            null -> Unit
        }
    }

    /**
     * Stops recording, parks the audio on disk, and hands transcription to a background worker.
     *
     * The recording becomes a durable note here rather than at Save. Transcribing a long walkthrough
     * is minutes of work, and doing it in this ViewModel meant losing all of it the moment the user
     * navigated away. Writing the WAV also frees the capture buffer straight
     * away instead of holding ~115 MB of half-hour recording on the heap while a model loads.
     */
    private fun stopRecording() {
        if (!currentState.isRecording) return

        // Joined below rather than fire-and-forgotten: the capture loop may be mid-chunk, and both
        // things stop is about to do -- finish the WAV writer, end the live VAD's stream -- would
        // race the append and the feed still in flight. (The writer reference is taken under the
        // lock but used outside it, so nulling the field alone does not close that window.)
        val capture = recordingJob
        recordingJob = null
        capture?.cancel()
        // Joined with the detector below for the same reason: it holds the recogniser's decode lock,
        // and the worker is about to want it.
        val pipeline = pipelineJob
        pipelineJob = null
        // The fallback detector is cancelled *and joined* below, not just cancelled: cancel() alone does
        // not wait, and its in-flight decode would still be running on the shared recogniser.
        val detector = commandJob
        commandJob = null
        setState {
            copy(
                isRecording = false,
                level = 0f,
                isTranscribing = true,
                partialTranscript = "",
                transcriptionProgress = 0f,
            )
        }

        viewModelScope.launch {
            try {
                capture?.cancelAndJoin()
                detector?.cancelAndJoin()
                pipeline?.cancelAndJoin()

                // The audio is already on disk -- the capture loop has been appending to it all
                // along -- so stopping is a matter of closing the file, not of copying a buffer into
                // one. Nothing here is proportional to the length of the recording any more.
                val (writer, file) = synchronized(capturedLock) {
                    val w = wavWriter
                    val f = recordingFile
                    wavWriter = null
                    recordingFile = null
                    captured.clear()
                    w to f
                }

                val sampleCount = withContext(Dispatchers.IO) {
                    writer?.finish()
                    writer?.sampleCount ?: 0
                }

                // The capture loop is joined, so nothing is feeding any more; this closes the
                // stream and hands over the recording's speech regions -- or null when the verdict
                // cannot be trusted, in which case the worker runs its own pass exactly as before.
                val liveRegions = withContext(Dispatchers.Default) { finishLiveVad(sampleCount) }

                val markers = synchronized(markerLock) { spokenMarkers.toList() }
                val excluded = synchronized(markerLock) { spokenCommandRanges.toList() }

                if (file == null || sampleCount == 0) {
                    withContext(Dispatchers.IO) { file?.delete() }
                    setState {
                        copy(isTranscribing = false, error = "Nothing was recorded. Try again.")
                    }
                    return@launch
                }

                val durationMillis = sampleCount * 1000L / AudioRecorder.SAMPLE_RATE
                val language = currentState.detectedLanguage

                val audio = file

                // The markers go beside the audio, not only into the job's input data: if WorkManager
                // ever loses the request, re-enqueueing from the surviving audio would otherwise produce
                // an untagged transcript and quietly discard what the user marked out loud.
                withContext(Dispatchers.IO) {
                    val checkpoint = TranscriptionCheckpoint.forAudio(audio)
                    checkpoint.recordRequest(
                        TranscriptionCheckpoint.Request(
                            markers = markers,
                            excludedRanges = excluded,
                            // Pinned to the recording, not read from Settings by the worker. The
                            // user can change their mind on the record screen while this note is
                            // still in the queue, and that must not retarget a transcription that
                            // has already begun -- or, after a process death, resume one on the
                            // other backend halfway through.
                            sttBackend = currentState.sttBackend,
                            sttModelId = (currentState.gemmaSttPlan as? SttModelPlan.Ready)
                                ?.resolved?.model?.id,
                        ),
                    )

                    // The live VAD's verdict, resolved exactly as the worker would resolve its own
                    // (same padding, same merge, same marker protection) and checkpointed so the
                    // worker's post-stop pass becomes a lookup. Only a verdict that heard the whole
                    // recording gets here -- see [finishLiveVad] -- so recording it is never a way
                    // to lose speech, only a way to skip a pass.
                    if (liveRegions != null) {
                        checkpoint.recordSpeechActivity(
                            SpeechRegions.resolve(
                                detected = liveRegions,
                                totalSamples = sampleCount,
                                protectedRanges = SpokenMarkers.pair(markers, sampleCount)
                                    .map { it.range },
                            ),
                        )
                    }
                }

                val noteId = noteDao.insert(
                    Note(
                        title = PENDING_TITLE,
                        transcript = "",
                        summary = "",
                        createdAtMillis = System.currentTimeMillis(),
                        summarisedBy = "none",
                        durationMillis = durationMillis,
                        language = language,
                        status = NoteStatus.Transcribing,
                        audioPath = audio.absolutePath,
                    ),
                )

                NoteTranscribeWorker.enqueue(appContext, noteId)

                // From here the note row is the source of truth, including its progress -- so leaving
                // this screen and coming back picks up exactly where it left off.
                resumeNote(noteId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(
                        isTranscribing = false,
                        error = e.message ?: "Could not save the recording",
                    )
                }
            } finally {
                // Either the job is enqueued and the worker's own attach takes over from here, or
                // the stop failed and there is nothing to keep resident. Both ways the pin ends now.
                unpinResidency()
            }
        }
    }

    /**
     * Summarises the (corrected) transcript with the on-device language model.
     *
     * Streams into the summary field as it generates, so a slow model on a phone still shows
     * progress rather than a spinner and a long silence.
     */
    private fun summarise() {
        val state = currentState
        val model = state.summariser ?: return
        if (!state.canSummarise) return
        // Read once, before the job starts. The mode decides the system prompt, the prompt, the
        // sizing and the reduce, and a switch landing mid-run would leave those disagreeing.
        val mode = state.summaryMode

        setState {
            copy(
                isSummarising = true,
                summary = "",
                stage = RecordStage.ReviewSummary,
                error = null,
            )
        }

        summariseJob = viewModelScope.launch {
            try {
                val engine = loadSummariser(model, mode)

                // A fresh conversation each time. The summariser is loaded with a system prompt for
                // this job alone, and leftover turns from a previous note would leak into the next.
                runCatching { engine.resetConversation() }

                // A reasoning model's <think> block is scratch work, never part of the summary. With
                // reasoning on it may reason before answering, so drop everything up to </think>;
                // with it off the model can emit a stray closing tag with the summary outside it, so
                // keep that text (see Reasoning.stripAllThinking).
                val strip: (String) -> String =
                    if (settingsStore.settings.value.thinkingEnabled) Reasoning::stripThinking
                    else Reasoning::stripAllThinking

                val tagged = TranscriptMarkup.taggedItems(state.transcript)

                // Sized against the window the model is actually loaded with, so the sizing and the
                // load cannot disagree about how much room there is -- the same property the audit
                // pipeline gets by reading its context from one place.
                //
                // Quick sizes through QuickRead, which is the audit queue's own sizing: its prompt,
                // its output reserve, its budget. Detailed sizes against the note's own prompt,
                // which is the only thing about the two paths that is still note-specific.
                val plan = if (mode == NoteSummaryMode.QUICK) {
                    QuickRead.plan(
                        text = state.transcript,
                        contextTokens = model.contextTokens,
                        maxChunks = NoteChunking.MAX_CHUNKS,
                    )
                } else {
                    NoteChunking.plan(
                        transcript = state.transcript,
                        contextTokens = model.contextTokens,
                        promptTokens = NotePromptBudget.fixedPromptTokens(
                            tagged = tagged,
                            language = state.detectedLanguage,
                        ),
                    )
                }

                val totalParts = plan.chunks.size
                setState { copy(summaryTotalParts = totalParts, summaryPart = 0) }

                // Two lists because the two modes speak different languages on the wire: quick
                // returns the shared RECORDS shape that QuickRead parses and merges, detailed returns
                // the note's own headed markdown. Only one is ever filled.
                val quickPartials = mutableListOf<AuditAnalysis>()
                val perChunk = mutableListOf<NoteAnalysis>()

                plan.chunks.forEachIndexed { index, chunk ->
                    setState { copy(summaryPart = index + 1, summary = "") }

                    // A fresh conversation per section, not just per note. Sections are independent
                    // reads that get merged in code; letting one section's answer sit in the context
                    // of the next would have the model summarising its own summary, and on a 4K
                    // window it would also spend that window on text it has already accounted for.
                    if (index > 0) runCatching { engine.resetConversation() }

                    val prompt = if (mode == NoteSummaryMode.QUICK) {
                        QuickRead.prompt(chunk, partNumber = index + 1, totalParts = totalParts)
                    } else {
                        NotePrompts.analysisPrompt(
                            transcript = chunk,
                            // Rebuilt from this section's own markers: asking a section to reproduce
                            // a marker that is not in the text it was given is asking it to invent
                            // one. The whole note's tagged list is still guaranteed by the floor.
                            tagged = TranscriptMarkup.taggedItems(chunk),
                            language = state.detectedLanguage,
                            partNumber = index + 1,
                            totalParts = totalParts,
                        )
                    }

                    val builder = StringBuilder()
                    engine.generate(prompt).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                builder.append(event.text)
                                setState { copy(summary = strip(builder.toString())) }
                            }

                            is GenerationEvent.Complete -> Unit
                        }
                    }

                    val raw = strip(builder.toString())
                    if (mode == NoteSummaryMode.QUICK) {
                        quickPartials += QuickRead.parseSection(raw)
                    } else {
                        perChunk += NoteAnalysisParser.parse(raw)
                    }
                }

                // Quick reduces through the shared implementation and is then rendered as a note;
                // detailed merges the note's own per-section analyses. Either way the tagged floor
                // runs last, so every marker the speaker actually spoke reaches the note whatever
                // the model returned and whichever mode produced it.
                val analysis = if (mode == NoteSummaryMode.QUICK) {
                    QuickRead.reduce(quickPartials).toNoteAnalysis()
                } else {
                    mergeAnalyses(perChunk)
                }.withTaggedFloor(tagged)

                val report = analysis.renderReport()

                setState {
                    copy(
                        isSummarising = false,
                        summaryPart = 0,
                        summary = report,
                        findings = analysis.findings,
                        // A cap that silently ate the tail of a note is the failure the audit
                        // pipeline's droppedChars exists to prevent, so it is reported here too
                        // rather than left for the user to notice.
                        error = if (plan.isTruncated) {
                            "This note was too long to read in full — the last " +
                                "${plan.droppedChars} characters of the transcript were not " +
                                "summarised."
                        } else {
                            error
                        },
                        // A title the user can accept or overwrite. Better than an empty box.
                        title = title.ifBlank { defaultTitle(analysis.summary) },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(
                        isSummarising = false,
                        summaryPart = 0,
                        error = e.message ?: "Could not summarise the transcript",
                    )
                }
            }
        }
    }

    private suspend fun loadSummariser(
        model: ModelSpec,
        mode: NoteSummaryMode = NoteSummaryMode.DETAILED,
    ): InferenceEngine {
        val existing = summariserEngine
        val file = modelRepository.fileFor(model)

        // The mode is part of the load, not just the prompt: quick is framed by the shared quick
        // system prompt and detailed by the note's own. Reusing an engine loaded for the other mode
        // would run one mode's sections under the other's framing, which is the kind of mismatch
        // that produces a well-formed answer to the wrong question. Switching modes reloads, exactly
        // as it does for an audit.
        if (existing != null &&
            existing.loadedModelPath == file.absolutePath &&
            summariserMode == mode
        ) {
            return existing
        }

        val settings = settingsStore.settings.value
        val engine = engines.defaultFor(model)
            ?: error("No engine can load ${model.name}")

        val accelerator = listOf(settings.preferredAccelerator, Accelerator.GPU, Accelerator.CPU)
            .first { it in engine.descriptor.supportedAccelerators && it in model.accelerators }

        engine.load(
            LoadRequest(
                modelPath = file.absolutePath,
                accelerator = accelerator,
                contextTokens = model.contextTokens,
                sampling = settings.effectiveSampling.copy(
                    // Low temperature on purpose. A summary that invents a detail is worse than a
                    // dull one, and creativity is precisely the wrong thing to ask for here. Inert
                    // under "Reproducible output", which pins top-K to 1 -- scaling logits cannot
                    // reorder them, so the argmax token is the same either way.
                    temperature = 0.3f,
                ),
                // A summary is the distilled result, so reasoning off costs nothing here -- the
                // <think> block is hidden from the note either way, it just takes time to produce.
                // Quick runs under the same system prompt the audit queue gives a quick read, so
                // the shared pipeline is shared all the way down to how the model is framed.
                systemPrompt = SystemPromptBuilder.build(
                    base = when (mode) {
                        NoteSummaryMode.QUICK -> AuditSystemPrompts.QUICK_SYSTEM_PROMPT
                        NoteSummaryMode.DETAILED -> NotePrompts.SYSTEM_PROMPT
                    },
                    thinkingEnabled = settings.thinkingEnabled,
                ),
                cacheDir = cacheDirPath,
                nativeLibraryDir = nativeLibraryDir,
                threadCount = settings.threadCount,
            ),
        )

        summariserEngine = engine
        summariserMode = mode
        return engine
    }

    /**
     * Switches between the full and the short summary, and remembers it.
     *
     * Refused mid-run rather than queued for afterwards, exactly as [changeSttBackend] is: the mode
     * decides the output reserve, which decides where the transcript was cut into sections, and those
     * boundaries are settled the moment the run starts. Unlike an audit the choice is not pinned to
     * stored work -- the transcript is still on screen, so summarising again simply re-chunks.
     */
    private fun changeSummaryMode(mode: NoteSummaryMode) {
        if (currentState.isSummarising) return
        settingsStore.update { it.copy(noteSummaryMode = mode) }
        setState { copy(summaryMode = mode) }
    }

    private fun stopSummarising() {
        summariserEngine?.cancel()
        summariseJob?.cancel()
        setState { copy(isSummarising = false, summaryPart = 0) }
    }

    private fun backToTranscript() {
        stopSummarising()
        setState { copy(stage = RecordStage.ReviewTranscript) }
    }

    private fun discard() {
        unpinResidency()
        releaseLiveVad()
        recordingJob?.cancel()
        summariseJob?.cancel()
        commandJob?.cancel()
        commandJob = null
        pipelineJob?.cancel()
        pipelineJob = null
        pipelineWatermark = 0
        followJob?.cancel()
        followJob = null

        // A discarded recording has a real file behind it now, so throwing it away means deleting
        // one rather than dropping a reference and letting the GC do it.
        val (writer, file) = synchronized(capturedLock) {
            val w = wavWriter
            val f = recordingFile
            wavWriter = null
            recordingFile = null
            captured.clear()
            capturedTotal = 0
            w to f
        }
        if (writer != null || file != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { writer?.close() }
                file?.delete()
            }
        }

        spokenCommandPhrases.clear()
        synchronized(markerLock) {
            spokenMarkers.clear()
            spokenCommandRanges.clear()
        }
        commandMatcher.reset()
        keywordDetector.reset()
        setState {
            RecordUiState(
                speechModelState = speechModelState,
                speechModelSizeBytes = speechModelSizeBytes,
                // Carried across a discard, like every other "how this screen is set up" value here:
                // throwing a recording away is not a reason to forget which recogniser was chosen.
                sttBackend = sttBackend,
                gemmaSttPlan = gemmaSttPlan,
                summariser = summariser,
                keywordsActive = keywordsActive,
            )
        }
    }

    /**
     * Commits the note. Both texts are saved exactly as the user last edited them.
     *
     * Updates the row the background worker already created, when there is one -- a recording becomes a
     * durable note the moment it stops, so by the time the user presses Save the note usually exists
     * and inserting again would leave a duplicate behind.
     */
    private fun save() {
        val state = currentState
        if (state.transcript.isBlank()) return

        viewModelScope.launch {
            val title = state.title.ifBlank { defaultTitle(state.summary) }
            val existing = state.noteId

            val id = if (existing != null) {
                noteDao.save(
                    id = existing,
                    title = title,
                    transcript = state.transcript.trim(),
                    summary = state.summary.trim(),
                    summarisedBy = state.summariser?.name ?: "none",
                )
                existing
            } else {
                noteDao.insert(
                    Note(
                        title = title,
                        transcript = state.transcript.trim(),
                        summary = state.summary.trim(),
                        createdAtMillis = System.currentTimeMillis(),
                        summarisedBy = state.summariser?.name ?: "none",
                        durationMillis = state.durationMillis,
                        language = state.detectedLanguage,
                        status = NoteStatus.Ready,
                    ),
                )
            }

            // Replaced wholesale rather than merged: re-summarising produces a fresh set, and leaving
            // the previous one behind would silently double every finding.
            noteFindingDao.deleteForNote(id)
            noteFindingDao.insertAll(
                state.findings.mapIndexed { index, finding ->
                    NoteFinding(
                        noteId = id,
                        kind = finding.kind,
                        text = finding.text,
                        source = finding.source,
                        owner = finding.owner,
                        orderIndex = index,
                    )
                },
            )

            emitEffect(RecordEffect.Saved(id))
        }
    }

    override fun onCleared() {
        super.onCleared()

        // A recording abandoned by leaving the screen must not leave the model pinned forever.
        unpinResidency()
        releaseLiveVad()

        // Native memory: the summariser holds gigabytes that the GC knows nothing about, and the
        // recogniser holds hundreds of megabytes. Both are freed off the main thread on a scope that
        // outlives this ViewModel -- releasing the recogniser now *waits* for any decode still in
        // flight (a background transcription worker shares this instance), which cannot be done from
        // a non-suspending onCleared().
        val engine = summariserEngine
        summariserEngine = null
        val recogniser = speechRecognizer
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
            runCatching { engine?.unload() }
            runCatching { recogniser.release() }
        }

        // Small by comparison (~5 MB) but still native memory, and there is nothing to wait for --
        // the capture loop that feeds it is cancelled with viewModelScope.
        keywordDetector.release()
    }

    /** First line of the summary, trimmed to something that fits a list row. */
    private fun defaultTitle(summary: String): String {
        val firstLine = summary.lineSequence()
            // Skip the section headings the report is built from -- "## Summary" is a useless title.
            .filterNot { it.trimStart().startsWith("#") }
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return "Voice note"

        return if (firstLine.length <= 48) firstLine else firstLine.take(45).trimEnd() + "..."
    }

    private companion object {
        const val TAG = "RecordViewModel"

        /**
         * Title a note carries while it is being transcribed.
         *
         * A placeholder rather than an empty string, because the notes list shows the row immediately
         * and a blank title reads as a broken note rather than one in progress.
         */
        const val PENDING_TITLE = "Voice note"

        /** How often the command detector transcribes the recent audio. */
        const val DETECT_INTERVAL_MS = 1_800L

        /** The tail of audio it looks at each time -- long enough to hold a whole command phrase. */
        const val DETECT_WINDOW_SAMPLES = AudioRecorder.SAMPLE_RATE * 4

        /** Below this much audio there is nothing worth transcribing yet. */
        const val MIN_DETECT_SAMPLES = AudioRecorder.SAMPLE_RATE / 2

        /**
         * How often the pipelined transcriber looks for settled slices.
         *
         * Slow on purpose. It shares the recogniser's decode lock with the live command detector and
         * competes with the keyword spotter for CPU, and none of that may cost the recording a
         * sample. Five seconds keeps it comfortably ahead of a recording it only has to beat by the
         * time the user presses stop.
         */
        const val PIPELINE_INTERVAL_MS = 5_000L

        /**
         * Consecutive failed passes after which the pipeline gives up for this recording.
         *
         * Three, not one, because a pass can fail transiently -- the recogniser busy with the
         * command detector's decode, a momentary IO error -- and pre-decoding is worth a couple of
         * retries. What it is not worth is the failure mode the cap exists for: a refusal that
         * repeats identically, paid for with a re-read that grows with the recording.
         */
        const val PIPELINE_MAX_FAILED_PASSES = 3

        /**
         * How much audio may queue while the live VAD's model loads: ten seconds, ~640 KB.
         *
         * The load normally finishes inside a second, so the bound is not a budget but a tripwire:
         * a loader that has fallen this far behind is stuck, and an unbounded queue would then grow
         * with the recording -- on the heap, which is exactly where recording length is not allowed
         * to cost anything.
         */
        const val LIVE_VAD_PENDING_MAX_SAMPLES = AudioRecorder.SAMPLE_RATE * 10
    }
}

/**
 * A fixed-size ring of the most recent audio samples.
 *
 * Replaces the growable buffer that used to hold the entire recording. That buffer was the reason a
 * long note could not be made: it grew for the whole session and only reached disk when the user
 * pressed stop, so capture died with a 224 MB `OutOfMemoryError` at 38 min 55 s on a 7.6 GB tablet
 * and the recording was unrecoverable -- nothing had been written yet. Audio now streams to a
 * [com.example.aiagenttestapp.data.notes.WavFile.Writer] as it arrives and this keeps only what the
 * live command detector actually reads: the last few seconds.
 *
 * So the memory cost of recording is constant. [CAPACITY_SAMPLES] of headroom over the detector's
 * window means a chunk landing mid-window never overwrites samples the detector is about to ask for.
 *
 * Unboxed [FloatArray] rather than `MutableList<Float>` for the same reason as before: a boxed list
 * is one heap object per sample, 16,000 a second.
 *
 * Not thread-safe: callers serialise access.
 */
// Internal rather than private so its wraparound can be unit-tested: a bug here would
// corrupt the command detector's window silently, with no crash to notice.
internal class RollingSampleWindow(private val capacity: Int = CAPACITY_SAMPLES) {

    private val data = FloatArray(capacity)

    /** Where the next sample goes. Wraps. */
    private var write = 0

    /** How much of [data] is real, capped at [capacity]. */
    private var filled = 0

    /** Appends a chunk, dropping whatever it pushes off the back. */
    fun append(samples: FloatArray) {
        // A chunk larger than the ring can only contribute its own tail; copying the front would be
        // written and immediately overwritten.
        val from = maxOf(0, samples.size - capacity)
        for (i in from until samples.size) {
            data[write] = samples[i]
            write = (write + 1) % capacity
        }
        filled = minOf(capacity, filled + (samples.size - from))
    }

    /** Resets to empty. Keeps the backing array, so the next recording reuses it. */
    fun clear() {
        write = 0
        filled = 0
    }

    /** A copy of the most recent [n] samples, or everything held when fewer than [n] are available. */
    fun takeLast(n: Int): FloatArray {
        val count = minOf(n, filled)
        val out = FloatArray(count)
        // `write` is one past the newest sample, so the run of interest starts `count` behind it.
        var read = ((write - count) % capacity + capacity) % capacity
        for (i in 0 until count) {
            out[i] = data[read]
            read = (read + 1) % capacity
        }
        return out
    }

    companion object {
        /**
         * Twice the detector's window, so a chunk arriving mid-read cannot eat the samples it wants.
         * 128k floats is 512 KB, flat, however long the recording runs.
         */
        const val CAPACITY_SAMPLES = AudioRecorder.SAMPLE_RATE * 8
    }
}
