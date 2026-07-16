package com.example.aiagenttestapp.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.VoiceCommandMatch
import com.example.aiagent.engine.core.VoiceCommandMatcher
import com.example.aiagent.engine.core.stripCommandPhrases
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.data.notes.Note
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.functions.VoiceCommandAction
import com.example.aiagenttestapp.functions.VoiceCommands
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val isSummarising: Boolean = false,
    val summary: String = "",
    val title: String = "",

    /** The language model that will write (or wrote) the summary. Null when none is usable. */
    val summariser: ModelSpec? = null,
    val error: String? = null,
    val savedNoteId: Long? = null,

    /** The last voice command heard while recording, shown briefly as a chip. Null once cleared. */
    val lastCommandLabel: String? = null,
) {
    val canRecord: Boolean get() = speechModelState is SpeechModelState.Ready && !isTranscribing
    val canSummarise: Boolean
        get() = transcript.isNotBlank() && summariser != null && !isSummarising
}

class RecordViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RecordUiState(speechModelSizeBytes = container.speechModels.totalBytes),
    )
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null
    private var summariseJob: Job? = null
    private var commandJob: Job? = null

    /**
     * Everything recorded this session, at 16 kHz. Transcribed in one go when the user stops.
     *
     * Touched from two coroutines -- the capture loop appends, the command detector reads the tail --
     * so every access is guarded by [capturedLock]. Appending a whole chunk under one lock keeps
     * the contention to once per ~100 ms rather than once per sample.
     */
    private val captured = mutableListOf<Float>()
    private val capturedLock = Any()

    /** Fires app commands the user speaks mid-recording. Cooldown lives inside it. */
    private val commandMatcher = VoiceCommandMatcher(VoiceCommands.specs)

    /** Command phrases heard this session, to strip from the note before it is saved. */
    private val spokenCommandPhrases = mutableSetOf<String>()

    private var summariserEngine: InferenceEngine? = null

    /**
     * Navigation a spoken command asked for. One-shot, so backgrounding and returning does not
     * replay "open settings". The Record screen collects it and leaves.
     */
    private val _navigation = MutableSharedFlow<AppNavigation>(extraBufferCapacity = 4)
    val navigation: SharedFlow<AppNavigation> = _navigation.asSharedFlow()

    init {
        container.speechModels.state
            .onEach { state -> _uiState.update { it.copy(speechModelState = state) } }
            .launchIn(viewModelScope)

        _uiState.update { it.copy(summariser = pickSummariser()) }
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
        val chosen = container.settingsStore.settings.value.activeModelId
            ?.let { container.findModel(it) }
            ?.takeIf { canSummarise(it) }
        if (chosen != null) return chosen

        return container.allModelsSnapshot()
            .filter { canSummarise(it) }
            .maxByOrNull { it.paramsBillions }
    }

    /** Usable for summarising: on disk (or OS-managed) and actually runnable on this device. */
    private fun canSummarise(model: ModelSpec): Boolean {
        if (!container.modelRepository.isDownloaded(model)) return false
        val engine = container.engines.defaultFor(model)?.descriptor ?: return false
        return ModelFitEvaluator
            .evaluateBest(model, engine, container.deviceMemory, isDownloaded = true)
            .canRun
    }

    fun downloadSpeechModel() {
        viewModelScope.launch { container.speechModels.download() }
    }

    fun startRecording() {
        if (!_uiState.value.canRecord || _uiState.value.isRecording) return

        synchronized(capturedLock) { captured.clear() }
        commandMatcher.reset()
        spokenCommandPhrases.clear()

        _uiState.update {
            it.copy(
                isRecording = true,
                durationMillis = 0,
                transcript = "",
                error = null,
                lastCommandLabel = null,
            )
        }

        recordingJob = viewModelScope.launch {
            try {
                container.audioRecorder.record().collect { chunk ->
                    val count = synchronized(capturedLock) {
                        chunk.samples.forEach(captured::add)
                        captured.size
                    }

                    _uiState.update {
                        it.copy(
                            level = chunk.level,
                            durationMillis = count * 1000L / AudioRecorder.SAMPLE_RATE,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isRecording = false, error = e.message ?: "Could not record audio")
                }
            }
        }

        commandJob = viewModelScope.launch { detectCommands() }
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
        val recogniser = container.speechRecognizer
        runCatching {
            // Reload when the Settings choice changed, not only when nothing is loaded yet.
            val paths = container.speechModels.selectedPaths()
            if (recogniser.loadedModelId != paths.id) recogniser.load(paths)
        }.onFailure { return } // no recogniser -> no live commands, but recording still works

        while (currentCoroutineContext().isActive && _uiState.value.isRecording) {
            delay(DETECT_INTERVAL_MS)

            val window = synchronized(capturedLock) {
                if (captured.size < MIN_DETECT_SAMPLES) return@synchronized FloatArray(0)
                captured.takeLast(DETECT_WINDOW_SAMPLES).toFloatArray()
            }
            if (window.isEmpty()) continue

            val text = runCatching {
                withContext(Dispatchers.Default) { recogniser.transcribe(window) }
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
        spokenCommandPhrases.add(match.matchedPhrase)
        _uiState.update { it.copy(lastCommandLabel = VoiceCommands.labelFor(match.id)) }

        when (val action = VoiceCommands.actionFor(match.id)) {
            is VoiceCommandAction.Navigate -> {
                discard()
                viewModelScope.launch { _navigation.emit(action.destination) }
            }

            VoiceCommandAction.StopRecording -> stopRecording()
            VoiceCommandAction.Discard -> discard()
            null -> Unit
        }
    }

    /** Stops recording and transcribes everything captured. */
    fun stopRecording() {
        if (!_uiState.value.isRecording) return

        recordingJob?.cancel()
        recordingJob = null
        // Stop the detector before the full-buffer pass, so the recogniser only decodes one thing.
        commandJob?.cancel()
        commandJob = null
        _uiState.update { it.copy(isRecording = false, level = 0f, isTranscribing = true) }

        viewModelScope.launch {
            try {
                val recogniser = container.speechRecognizer
                val paths = container.speechModels.selectedPaths()
                if (recogniser.loadedModelId != paths.id) recogniser.load(paths)

                val samples = synchronized(capturedLock) { captured.toFloatArray() }
                val raw = recogniser.transcribe(samples)
                // Cut any command the user spoke ("...that's all. Stop recording.") out of the note.
                val text = stripCommandPhrases(raw, spokenCommandPhrases)

                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        transcript = text,
                        stage = RecordStage.ReviewTranscript,
                        error = if (text.isBlank()) "Nothing was recognised. Try again." else null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        error = e.message ?: "Could not transcribe the recording",
                    )
                }
            }
        }
    }

    /** The user's corrections to the transcript. They are what gets summarised and saved. */
    fun onTranscriptChange(text: String) {
        _uiState.update { it.copy(transcript = text) }
    }

    fun onSummaryChange(text: String) {
        _uiState.update { it.copy(summary = text) }
    }

    fun onTitleChange(text: String) {
        _uiState.update { it.copy(title = text) }
    }

    /**
     * Summarises the (corrected) transcript with the on-device language model.
     *
     * Streams into the summary field as it generates, so a slow model on a phone still shows
     * progress rather than a spinner and a long silence.
     */
    fun summarise() {
        val state = _uiState.value
        val model = state.summariser ?: return
        if (!state.canSummarise) return

        _uiState.update {
            it.copy(
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

                val builder = StringBuilder()
                engine.generate(summaryPrompt(state.transcript)).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            builder.append(event.text)
                            _uiState.update { it.copy(summary = builder.toString()) }
                        }

                        is GenerationEvent.Complete -> Unit
                    }
                }

                _uiState.update {
                    it.copy(
                        isSummarising = false,
                        summary = builder.toString().trim(),
                        // A title the user can accept or overwrite. Better than an empty box.
                        title = it.title.ifBlank { defaultTitle(builder.toString()) },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSummarising = false,
                        error = e.message ?: "Could not summarise the transcript",
                    )
                }
            }
        }
    }

    private suspend fun loadSummariser(model: ModelSpec): InferenceEngine {
        val existing = summariserEngine
        val file = container.modelRepository.fileFor(model)

        if (existing != null && existing.loadedModelPath == file.absolutePath) return existing

        val settings = container.settingsStore.settings.value
        val engine = container.engines.defaultFor(model)
            ?: error("No engine can load ${model.name}")

        val accelerator = listOf(settings.preferredAccelerator, Accelerator.GPU, Accelerator.CPU)
            .first { it in engine.descriptor.supportedAccelerators && it in model.accelerators }

        engine.load(
            LoadRequest(
                modelPath = file.absolutePath,
                accelerator = accelerator,
                contextTokens = model.contextTokens,
                sampling = settings.sampling.copy(
                    // Low temperature on purpose. A summary that invents a detail is worse than a
                    // dull one, and creativity is precisely the wrong thing to ask for here.
                    temperature = 0.3f,
                ),
                systemPrompt = SUMMARY_SYSTEM_PROMPT,
                cacheDir = container.cacheDirPath,
                nativeLibraryDir = container.nativeLibraryDir,
            ),
        )

        summariserEngine = engine
        return engine
    }

    fun stopSummarising() {
        summariserEngine?.cancel()
        summariseJob?.cancel()
        _uiState.update { it.copy(isSummarising = false) }
    }

    fun backToTranscript() {
        stopSummarising()
        _uiState.update { it.copy(stage = RecordStage.ReviewTranscript) }
    }

    fun discard() {
        recordingJob?.cancel()
        summariseJob?.cancel()
        commandJob?.cancel()
        commandJob = null
        synchronized(capturedLock) { captured.clear() }
        spokenCommandPhrases.clear()
        commandMatcher.reset()
        _uiState.update {
            RecordUiState(
                speechModelState = it.speechModelState,
                speechModelSizeBytes = it.speechModelSizeBytes,
                summariser = it.summariser,
            )
        }
    }

    /** Clears the "heard: ..." chip once the user has seen it. */
    fun clearLastCommand() {
        _uiState.update { it.copy(lastCommandLabel = null) }
    }

    /** Commits the note. Both texts are saved exactly as the user last edited them. */
    fun save() {
        val state = _uiState.value
        if (state.transcript.isBlank()) return

        viewModelScope.launch {
            val id = container.noteDao.insert(
                Note(
                    title = state.title.ifBlank { defaultTitle(state.summary) },
                    transcript = state.transcript.trim(),
                    summary = state.summary.trim(),
                    createdAtMillis = System.currentTimeMillis(),
                    summarisedBy = state.summariser?.name ?: "none",
                    durationMillis = state.durationMillis,
                ),
            )
            _uiState.update { it.copy(savedNoteId = id) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        container.speechRecognizer.release()

        // Native memory again: the summariser holds gigabytes that the GC knows nothing about.
        val engine = summariserEngine
        summariserEngine = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
            runCatching { engine?.unload() }
        }
    }

    private fun summaryPrompt(transcript: String): String = buildString {
        appendLine("Summarise the following transcript of a voice note.")
        appendLine()
        appendLine("Write 3 to 5 short bullet points covering the key points, decisions and any")
        appendLine("actions mentioned. Use only what is in the transcript -- do not add anything.")
        appendLine("If something is unclear, leave it out rather than guessing.")
        appendLine()
        appendLine("Transcript:")
        appendLine(transcript)
    }

    /** First line of the summary, trimmed to something that fits a list row. */
    private fun defaultTitle(summary: String): String {
        val firstLine = summary.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return "Voice note"

        return if (firstLine.length <= 48) firstLine else firstLine.take(45).trimEnd() + "..."
    }

    private companion object {
        const val TAG = "RecordViewModel"

        const val SUMMARY_SYSTEM_PROMPT =
            "You summarise voice notes. You are accurate and concise, you never invent detail, " +
                "and you only ever report what the transcript actually says."

        /** How often the command detector transcribes the recent audio. */
        const val DETECT_INTERVAL_MS = 1_800L

        /** The tail of audio it looks at each time -- long enough to hold a whole command phrase. */
        const val DETECT_WINDOW_SAMPLES = AudioRecorder.SAMPLE_RATE * 4

        /** Below this much audio there is nothing worth transcribing yet. */
        const val MIN_DETECT_SAMPLES = AudioRecorder.SAMPLE_RATE / 2
    }
}
