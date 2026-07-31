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
import com.example.aiagenttestapp.data.notes.Note
import com.example.aiagenttestapp.data.notes.NoteAnalysisParser
import com.example.aiagenttestapp.data.notes.NoteFinding
import com.example.aiagenttestapp.data.notes.NoteFindingDao
import com.example.aiagenttestapp.data.notes.NotePrompts
import com.example.aiagenttestapp.data.notes.NoteStatus
import com.example.aiagenttestapp.data.notes.NoteTranscribeWorker
import com.example.aiagenttestapp.data.notes.ParsedFinding
import com.example.aiagenttestapp.data.notes.SpeakerDao
import com.example.aiagenttestapp.data.notes.SpokenMarker
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
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.KeywordDetector
import com.example.aiagenttestapp.stt.KeywordModelPaths
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.stt.SpottedKeyword
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiEffect
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import com.example.aiagenttestapp.util.Reasoning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.notes.NoteDao
import com.example.aiagenttestapp.di.CacheDirPath
import com.example.aiagenttestapp.di.NativeLibraryDir
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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

    /** The language model that will write (or wrote) the summary. Null when none is usable. */
    val summariser: ModelSpec? = null,
    val error: String? = null,

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

    /** Enrolled speaker names, so transcript parsing knows which "Name:" prefixes are real. */
    val knownSpeakers: Set<String> = emptySet(),

    /**
     * The note row this session is filling in, when transcription happened in the background.
     *
     * Null for a session that has not produced a row yet. Saving updates this row rather than
     * inserting, so a recording does not end up in the list twice.
     */
    val noteId: Long? = null,

    /** The user's answer to "how many people are in this recording"; 0 means let diarisation decide. */
    val expectedSpeakers: Int = 0,

    /** Whether speaker identification will run, so the UI only offers what is actually available. */
    val speakerIdActive: Boolean = false,
) : UiState {
    val canRecord: Boolean get() = speechModelState is SpeechModelState.Ready && !isTranscribing
    val canSummarise: Boolean
        get() = transcript.isNotBlank() && summariser != null && !isSummarising
}

sealed interface RecordIntent : UiIntent {
    /** Kicks off the one-time speech-model download. */
    data object DownloadSpeechModel : RecordIntent
    /** The caller must already hold RECORD_AUDIO. */
    data object StartRecording : RecordIntent
    data object StopRecording : RecordIntent

    /** The user's corrections to the transcript. They are what gets summarised and saved. */
    data class TranscriptChanged(val text: String) : RecordIntent
    data class SummaryChanged(val text: String) : RecordIntent
    data class TitleChanged(val text: String) : RecordIntent

    data object Summarise : RecordIntent
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

    /**
     * How many people the user says are in the recording; 0 means work it out.
     *
     * Fixing the cluster count is a far better instruction to diarisation than any similarity
     * threshold, which has to guess how many groups exist and habitually splits one person in two on
     * short or noisy audio.
     */
    data class ExpectedSpeakersChanged(val count: Int) : RecordIntent
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
    private val speakerDao: SpeakerDao,
    private val settingsStore: SettingsStore,
    private val speechModels: SpeechModelRepository,
    private val speechRecognizer: SpeechRecognizer,
) : MviViewModel<RecordUiState, RecordIntent, RecordEffect>(
    RecordUiState(speechModelSizeBytes = speechModels.totalBytes),
) {

    private var recordingJob: Job? = null
    private var summariseJob: Job? = null
    private var commandJob: Job? = null

    /** Watches the note row while a background worker fills it in. */
    private var followJob: Job? = null

    /**
     * Everything recorded this session, at 16 kHz. Transcribed in one go when the user stops.
     *
     * A primitive [SampleBuffer], not a `MutableList<Float>`: a boxed list is one heap object per
     * sample -- 16,000 of them a second, millions over a few minutes -- which is ~5x the memory and
     * enough GC churn to jank the meter and eventually run a long note out of heap.
     *
     * Touched from two coroutines -- the capture loop appends, the command detector reads the tail --
     * so every access is guarded by [capturedLock]. Appending a whole chunk under one lock keeps
     * the contention to once per ~100 ms rather than once per sample.
     */
    private val captured = SampleBuffer()
    private val capturedLock = Any()

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

    init {
        speechModels.state.collectIntoState { state -> copy(speechModelState = state) }

        setState { copy(summariser = pickSummariser()) }

        speakerDao.observeAll().collectIntoState { speakers ->
            copy(knownSpeakers = speakers.map { it.name }.toSet())
        }

        // Warmed when the screen opens rather than when recording starts: the ONNX session takes a
        // moment to build, and paying that after the user taps record would either delay the recording
        // or miss the first seconds of keyword audio.
        viewModelScope.launch { loadKeywordDetector() }

        val speakerIdReady = settingsStore.settings.value.speakerIdEnabled &&
            audioModels.isReady(audioModels.speaker)
        setState { copy(speakerIdActive = speakerIdReady) }
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
        RecordIntent.DownloadSpeechModel -> speechModels.enqueueDownload()
        RecordIntent.StartRecording -> startRecording()
        RecordIntent.StopRecording -> stopRecording()
        is RecordIntent.TranscriptChanged -> setState { copy(transcript = intent.text) }
        is RecordIntent.SummaryChanged -> setState { copy(summary = intent.text) }
        is RecordIntent.TitleChanged -> setState { copy(title = intent.text) }
        RecordIntent.Summarise -> summarise()
        RecordIntent.StopSummarising -> stopSummarising()
        RecordIntent.BackToTranscript -> backToTranscript()
        RecordIntent.Discard -> discard()
        RecordIntent.ClearLastCommand -> setState { copy(lastCommandLabel = null) }
        RecordIntent.Save -> save()
        is RecordIntent.ResumeNote -> resumeNote(intent.noteId)
        is RecordIntent.ExpectedSpeakersChanged ->
            setState { copy(expectedSpeakers = intent.count.coerceIn(0, 8)) }
    }

    /**
     * Follows a note being transcribed in the background, and moves to review when it is done.
     *
     * Observed from the database rather than from WorkManager's progress, because the database is what
     * survives the process dying -- which is the entire reason the work is a worker.
     */
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

    private fun startRecording() {
        if (!currentState.canRecord || currentState.isRecording) return

        synchronized(capturedLock) { captured.clear() }
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
                    val (chunkStart, count) = synchronized(capturedLock) {
                        val before = captured.size
                        captured.append(chunk.samples)
                        before to captured.size
                    }

                    // The spotter is told where this chunk sits in the recording rather than counting
                    // for itself, so its marker offsets line up with the capture buffer even when it
                    // finished loading after recording had already begun.
                    val fired = keywordDetector.accept(chunk.samples, chunkStart.toLong())
                    if (fired.isNotEmpty()) onKeywordsSpotted(fired)

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
        if (!keywordDetector.isLoaded) {
            commandJob = viewModelScope.launch { detectCommands() }
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
                if (captured.size < MIN_DETECT_SAMPLES) return@synchronized FloatArray(0)
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
     * The recording becomes a durable note here rather than at Save. Diarising and transcribing a long
     * walkthrough per speaker turn is minutes of work, and doing it in this ViewModel meant losing all
     * of it the moment the user navigated away. Writing the WAV also frees the capture buffer straight
     * away instead of holding ~115 MB of half-hour recording on the heap while a model loads.
     */
    private fun stopRecording() {
        if (!currentState.isRecording) return

        recordingJob?.cancel()
        recordingJob = null
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
                detector?.cancelAndJoin()

                val samples = synchronized(capturedLock) {
                    captured.toArray().also { captured.clear() }
                }
                val markers = synchronized(markerLock) { spokenMarkers.toList() }
                val excluded = synchronized(markerLock) { spokenCommandRanges.toList() }

                if (samples.isEmpty()) {
                    setState {
                        copy(isTranscribing = false, error = "Nothing was recorded. Try again.")
                    }
                    return@launch
                }

                val durationMillis = samples.size * 1000L / AudioRecorder.SAMPLE_RATE
                val language = currentState.detectedLanguage

                val audio = withContext(Dispatchers.IO) {
                    val dir = File(cacheDirPath, "note-audio").apply { mkdirs() }
                    val file = File(dir, "note-${System.currentTimeMillis()}.wav")
                    WavFile.write(file, samples, AudioRecorder.SAMPLE_RATE)
                    file
                }

                // The markers go beside the audio, not only into the job's input data: if WorkManager
                // ever loses the request, re-enqueueing from the surviving audio would otherwise produce
                // an untagged transcript and quietly discard what the user marked out loud.
                withContext(Dispatchers.IO) {
                    TranscriptionCheckpoint.forAudio(audio).recordRequest(
                        TranscriptionCheckpoint.Request(
                            markers = markers,
                            excludedRanges = excluded,
                            expectedSpeakers = currentState.expectedSpeakers,
                        ),
                    )
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
                val engine = loadSummariser(model)

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

                val tagged = TranscriptMarkup.taggedItems(state.transcript, state.knownSpeakers)

                val builder = StringBuilder()
                engine.generate(
                    NotePrompts.analysisPrompt(
                        transcript = state.transcript,
                        tagged = tagged,
                        language = state.detectedLanguage,
                        speakers = TranscriptMarkup.speakersIn(
                            state.transcript,
                            state.knownSpeakers,
                        ),
                    ),
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            builder.append(event.text)
                            setState { copy(summary = strip(builder.toString())) }
                        }

                        is GenerationEvent.Complete -> Unit
                    }
                }

                // Parse, then put back any tagged item the model dropped, then re-render. The floor is
                // why a spoken marker is a guarantee: whatever the model returned, every phrase the
                // user deliberately tagged reaches the note.
                val analysis = NoteAnalysisParser
                    .parse(strip(builder.toString()))
                    .withTaggedFloor(tagged)

                val report = analysis.renderReport()

                setState {
                    copy(
                        isSummarising = false,
                        summary = report,
                        findings = analysis.findings,
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
                        error = e.message ?: "Could not summarise the transcript",
                    )
                }
            }
        }
    }

    private suspend fun loadSummariser(model: ModelSpec): InferenceEngine {
        val existing = summariserEngine
        val file = modelRepository.fileFor(model)

        if (existing != null && existing.loadedModelPath == file.absolutePath) return existing

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
                systemPrompt = buildString {
                    append(NotePrompts.SYSTEM_PROMPT)
                    // A summary is the distilled result, so skip reasoning when it is turned off --
                    // it only costs time here, since the <think> block is hidden from the note anyway.
                    if (!settings.thinkingEnabled) {
                        append("\n\n")
                        append(Reasoning.NO_THINK_DIRECTIVE)
                    }
                },
                cacheDir = cacheDirPath,
                nativeLibraryDir = nativeLibraryDir,
                threadCount = settings.threadCount,
            ),
        )

        summariserEngine = engine
        return engine
    }

    private fun stopSummarising() {
        summariserEngine?.cancel()
        summariseJob?.cancel()
        setState { copy(isSummarising = false) }
    }

    private fun backToTranscript() {
        stopSummarising()
        setState { copy(stage = RecordStage.ReviewTranscript) }
    }

    private fun discard() {
        recordingJob?.cancel()
        summariseJob?.cancel()
        commandJob?.cancel()
        commandJob = null
        followJob?.cancel()
        followJob = null
        synchronized(capturedLock) { captured.clear() }
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
                summariser = summariser,
                keywordsActive = keywordsActive,
                speakerIdActive = speakerIdActive,
                expectedSpeakers = expectedSpeakers,
                knownSpeakers = knownSpeakers,
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
    }
}

/**
 * A growable buffer of audio samples backed by a primitive [FloatArray].
 *
 * Replaces `MutableList<Float>` for the capture buffer: the list boxes every sample into its own
 * heap object, which at 16 kHz is millions of allocations over a few minutes -- ~5x the memory and
 * a steady stream of garbage. This holds the samples unboxed and grows the backing array
 * geometrically, so appending stays amortised O(1) and a long recording costs 4 bytes a sample.
 *
 * Not thread-safe: callers serialise access exactly as they did the list it replaces.
 */
private class SampleBuffer(initialCapacity: Int = AudioRecorder.SAMPLE_RATE * 8) {

    private var data = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    /** Appends a whole chunk, growing the backing array when it will not fit. */
    fun append(samples: FloatArray) {
        ensureCapacity(size + samples.size)
        System.arraycopy(samples, 0, data, size, samples.size)
        size += samples.size
    }

    /** Resets to empty. Keeps the backing array, so the next recording reuses it without regrowing. */
    fun clear() {
        size = 0
    }

    /** A copy of everything captured so far. */
    fun toArray(): FloatArray = data.copyOf(size)

    /** A copy of the most recent [n] samples, or everything when fewer than [n] have been captured. */
    fun takeLast(n: Int): FloatArray {
        val count = minOf(n, size)
        return data.copyOfRange(size - count, size)
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var capacity = data.size + (data.size shr 1) // 1.5x
        if (capacity < needed) capacity = needed
        data = data.copyOf(capacity)
    }
}
