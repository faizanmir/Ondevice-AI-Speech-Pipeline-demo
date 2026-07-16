package com.example.aiagenttestapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagent.engine.core.EngineException
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.GenerationStats
import com.example.aiagent.engine.core.HistoryTurn
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolCallingProtocol
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.planChatLoad
import com.example.aiagenttestapp.data.chat.Conversation
import com.example.aiagenttestapp.data.chat.StoredMessage
import com.example.aiagenttestapp.data.chat.toHistoryTurn
import com.example.aiagenttestapp.functions.AppFunctions
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.stt.SpeechModelState
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    /** Present on assistant messages once generation completes. */
    val stats: GenerationStats? = null,
    val isError: Boolean = false,
    /** Set when this "message" is really the model having done something to the app. */
    val functionCall: FunctionCallDisplay? = null,
)

/** An app function the model invoked, rendered as a chip in the transcript. */
data class FunctionCallDisplay(
    val name: String,
    val summary: String,
    val succeeded: Boolean,
)

sealed interface ModelLoadState {
    data object Idle : ModelLoadState
    data class Loading(val message: String) : ModelLoadState
    data object Ready : ModelLoadState
    data class Failed(val message: String) : ModelLoadState
}

data class ChatUiState(
    val model: ModelSpec? = null,
    val engineId: EngineId? = null,
    val engineName: String = "",
    val accelerator: Accelerator = Accelerator.CPU,
    val loadState: ModelLoadState = ModelLoadState.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val contextUsed: Int = 0,
    val contextTotal: Int = 0,
    /** Whether this model can drive the app. Shown in the empty state so it is never a mystery. */
    val toolsActive: Boolean = false,
    /** Set when app functions are on globally but this particular model cannot use them. */
    val toolsUnavailableReason: String? = null,

    /** The message box text, held here so on-device dictation can append to it. */
    val draft: String = "",
    // ---- Voice input (dictation) -- shares the Voice Notes speech model -----------------------
    val speechModelState: SpeechModelState = SpeechModelState.NotDownloaded,
    val speechModelSizeBytes: Long = 0,
    /** Recording from the mic right now. */
    val isDictating: Boolean = false,
    /** Decoding the just-recorded audio into text. */
    val isTranscribing: Boolean = false,
    /** 0f..1f mic loudness, so the mic button can pulse while listening. */
    val micLevel: Float = 0f,

    /** The message being quote-replied to, shown as a chip above the input. */
    val replyingTo: ChatMessage? = null,
) {
    val canSend: Boolean
        get() = loadState is ModelLoadState.Ready && !isGenerating && !isDictating && !isTranscribing

    val isSpeechReady: Boolean get() = speechModelState is SpeechModelState.Ready

    /** 0f..1f. Past ~0.9 the next turn is likely to overflow, so the UI warns. */
    val contextFraction: Float
        get() = if (contextTotal <= 0) 0f
        else (contextUsed.toFloat() / contextTotal.toFloat()).coerceIn(0f, 1f)
}

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(speechModelSizeBytes = container.speechModels.totalBytes),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var engine: InferenceEngine? = null
    private var generationJob: Job? = null
    private var nextMessageId = 0L

    /** Buffers dictation audio between start and stop. Guarded by [voiceLock]. */
    private var voiceJob: Job? = null
    private val voiceSamples = mutableListOf<Float>()
    private val voiceLock = Any()
    /** So onCleared only releases the shared recogniser when this chat is what loaded it. */
    private var loadedRecogniserForVoice = false

    init {
        // Dictation reuses the Voice Notes speech model, so mirror its readiness here -- that lets
        // the mic button offer a one-time download when it is missing instead of failing silently.
        container.speechModels.state
            .onEach { s -> _uiState.update { it.copy(speechModelState = s) } }
            .launchIn(viewModelScope)
    }

    /** Id of the bubble the current turn is streaming into, so a tool call can rewrite it. */
    private var pendingReplyId: Long? = null

    /** Whether app functions were switched on when this chat's model was loaded. */
    private var toolsEnabled = false

    /** The persisted conversation this chat is writing to. Null until the first message creates it. */
    private var conversationId: Long? = null

    /** The model this chat is for, so a new conversation row records which model it belongs to. */
    private var currentModelId: String? = null

    /** Whether this chat registered itself with the residency manager, so onCleared detaches once. */
    private var attachedResidency = false

    /**
     * Navigation the model asked for. A one-shot event, not state: replaying "open settings" on
     * every recomposition would trap the user on the Settings screen.
     */
    private val _navigation = MutableSharedFlow<AppNavigation>(extraBufferCapacity = 4)
    val navigation: SharedFlow<AppNavigation> = _navigation.asSharedFlow()

    /**
     * Loads [modelId] into an engine and gets the chat ready.
     *
     * How the engine, accelerator and system prompt are chosen lives in [planChatLoad], shared with
     * the startup [com.example.aiagenttestapp.data.ModelResidency] so the two agree exactly -- that
     * agreement is what lets a fresh chat reuse the resident model with a reset instead of a load.
     */
    fun openChat(modelId: String, resumeConversationId: Long?) {
        // planChatLoad looks in the built-in catalogue *and* the user's added models -- a model
        // pulled from HuggingFace has to open a chat exactly like a built-in one.
        val plan = when (val result = planChatLoad(container, modelId)) {
            is ChatLoadPlan.UnknownModel -> {
                _uiState.update { it.copy(loadState = ModelLoadState.Failed("Unknown model")) }
                return
            }
            is ChatLoadPlan.NoEngine -> {
                _uiState.update {
                    it.copy(
                        model = result.model,
                        loadState = ModelLoadState.Failed(
                            "No engine in this build can load ${result.model.format.label} files",
                        ),
                    )
                }
                return
            }
            is ChatLoadPlan.Resolved -> result
        }

        val model = plan.model
        val selected = plan.engine
        val accelerator = plan.accelerator

        // Publish which engine and accelerator we settled on *before* anything can fail, so the
        // title bar reads "LiteRT-LM · GPU" even on the error screens. Filling it in only on the
        // success path leaves a failed load captioned with a bare "· CPU".
        _uiState.update { current ->
            ChatUiState(
                model = model,
                engineId = plan.engineId,
                engineName = plan.engineName,
                accelerator = accelerator,
                loadState = ModelLoadState.Loading("Loading ${model.name} on ${accelerator.label}"),
                contextTotal = model.contextTokens,
                // Dictation readiness is not part of the conversation, so carry it across the reset
                // rather than briefly flashing the mic button as unavailable on every model open.
                speechModelState = current.speechModelState,
                speechModelSizeBytes = current.speechModelSizeBytes,
            )
        }

        // Tools go into the system prompt, so this is fixed for the life of the loaded model --
        // toggling the setting mid-chat cannot retroactively give the model tools it was never told
        // about. (Whether this model gets the tool section at all is decided in planChatLoad.)
        toolsEnabled = plan.toolsEnabled
        _uiState.update {
            it.copy(
                toolsActive = plan.toolsEnabled,
                toolsUnavailableReason = plan.toolsUnavailableReason,
            )
        }

        if (!plan.downloaded) {
            _uiState.update {
                it.copy(
                    loadState = ModelLoadState.Failed("${model.name} is not downloaded"),
                )
            }
            return
        }

        engine = selected
        currentModelId = modelId

        // Tell the residency manager a chat is now using the engine, so it will not release the
        // resident model under memory pressure while this chat is on screen. Balanced by onCleared.
        if (!attachedResidency) {
            container.modelResidency.attach()
            attachedResidency = true
        }

        viewModelScope.launch {
            // Reopen a specific saved conversation (from the history list), or start fresh. The whole
            // transcript is restored to the display; only a fitted tail is fed to the model, with the
            // rolling summary of the older turns folded into the system prompt ahead of it.
            val restored = resumeConversationId
                ?.let { id -> runCatching { container.chatDao.conversationById(id) }.getOrNull() }
            val pastMessages = restored?.messages.orEmpty().sortedBy { it.id }
            conversationId = restored?.conversation?.id
            if (pastMessages.isNotEmpty()) {
                // Reuse the DB ids as bubble ids (unique), and start new bubbles past them.
                nextMessageId = pastMessages.maxOf { it.id } + 1
                _uiState.update { st ->
                    st.copy(
                        messages = pastMessages.map { m ->
                            ChatMessage(
                                id = m.id,
                                isUser = m.role == HistoryTurn.ROLE_USER,
                                text = m.content,
                            )
                        },
                    )
                }
            }

            val effectiveSystemPrompt = restored?.conversation?.summary
                ?.takeIf { it.isNotBlank() }
                ?.let { "${plan.systemPrompt}\n\nSummary of the earlier part of this conversation:\n$it" }
                ?: plan.systemPrompt

            val initialHistory = ContextWindow.fit(
                history = pastMessages.map { it.toHistoryTurn() },
                contextTokens = model.contextTokens,
                systemPromptTokens = ContextWindow.estimateTokens(effectiveSystemPrompt),
            )

            // A fresh chat's request is exactly plan.freshLoadRequest(), which is what the model was
            // warmed with -- so the residency manager can hand it back with just a conversation reset,
            // no load. A resumed chat overrides the system prompt (summary folded in) and seeds the
            // restored history, so it always loads.
            val request = plan.freshLoadRequest().copy(
                systemPrompt = effectiveSystemPrompt,
                initialHistory = initialHistory,
            )

            try {
                container.modelResidency.open(
                    plan = plan,
                    request = request,
                    reuseWhenResident = resumeConversationId == null,
                )
                _uiState.update {
                    it.copy(
                        loadState = ModelLoadState.Ready,
                        // The engine may have fallen back (GPU -> CPU) if the requested accelerator
                        // turned out to be unusable. Show what actually happened, not what we asked
                        // for -- otherwise the speed the user sees has no explanation.
                        accelerator = selected.activeAccelerator ?: accelerator,
                    )
                }
            } catch (e: EngineException.OutOfMemory) {
                _uiState.update {
                    it.copy(
                        loadState = ModelLoadState.Failed(
                            "${model.name} ran out of memory while loading. Close other apps and " +
                                "try again, or pick a smaller model.",
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loadState = ModelLoadState.Failed(
                            e.message ?: "Could not load ${model.name}",
                        ),
                    )
                }
            }
        }
    }

    fun onDraftChange(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun send() {
        val activeEngine = engine ?: return
        val typed = _uiState.value.draft.trim()
        if (!_uiState.value.canSend || typed.isBlank()) return

        // A quote-reply prepends the message being answered so the model knows which earlier point
        // the user means. The model has the whole transcript, but not which part they are picking out.
        val quoted = _uiState.value.replyingTo?.text?.trim()?.replace('\n', ' ')?.take(240)
        val prompt = if (quoted != null) {
            "Regarding this earlier message:\n\"$quoted\"\n\n$typed"
        } else {
            typed
        }

        _uiState.update {
            it.copy(
                draft = "",
                replyingTo = null,
                messages = it.messages + ChatMessage(
                    id = nextMessageId++,
                    isUser = true,
                    text = prompt,
                ),
                isGenerating = true,
            )
        }

        generationJob = viewModelScope.launch {
            try {
                persistMessage(HistoryTurn.ROLE_USER, prompt, title = prompt)

                // Let the send transition settle before prefill starts. The keyboard is dismissed on
                // send, and prefill is the heaviest CPU/GPU burst of the whole turn; starting it while
                // the keyboard is still animating away makes that animation drop frames and play in
                // slow motion. The user's message is already on screen and persisted, so this brief
                // wait costs nothing they can see -- prefill alone runs into seconds regardless.
                delay(SEND_SETTLE_MS.milliseconds)

                var response = runTurn(activeEngine, prompt)

                // Let the model chain a few tool calls per turn -- search, read a result, search
                // again -- and then answer, instead of stopping after one. Kept bounded and guarded:
                // the hop cap stops runaway chaining, an identical repeated call (a small model
                // spinning on the same search) breaks early, and a navigation tool ends the turn
                // since the user has been moved.
                if (toolsEnabled) {
                    var hops = 0
                    var lastSignature: String? = null
                    while (hops < MAX_TOOL_HOPS) {
                        val call = ToolCallingProtocol.parse(response) ?: break
                        val signature = "${call.name}(${call.arguments})"
                        if (signature == lastSignature) break
                        lastSignature = signature

                        val (next, navigated) = runToolCall(activeEngine, call)
                        response = next
                        hops++
                        if (navigated) break
                    }

                    // Cap hit (or broke on a repeat) while the model is still emitting a tool call:
                    // never let raw JSON stand as the answer -- force one final, tool-free reply.
                    if (ToolCallingProtocol.parse(response) != null) {
                        response = forceFinalAnswer(activeEngine)
                    }
                }

                // Tool-call chips are not persisted, but the model's final answer is.
                persistMessage(HistoryTurn.ROLE_ASSISTANT, response, title = prompt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        contextUsed = activeEngine.contextTokensUsed(),
                    )
                }
            }
        }
    }

    /** Streams one model response into a fresh bubble and returns the full text. */
    private suspend fun runTurn(activeEngine: InferenceEngine, prompt: String): String {
        val replyId = nextMessageId++
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage(id = replyId, isUser = false, text = ""))
        }

        val buffer = StringBuilder()
        var lastUiMs = 0L
        try {
            activeEngine.generate(prompt).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        buffer.append(event.text)
                        // Push to the UI at a bounded rate, not on every token. On a GPU model the
                        // decode already contends with the UI for the GPU; recomposing (and
                        // re-rendering Markdown) 40+ times a second on top of that is what makes the
                        // app jank -- most visibly through a multi-turn tool call. ~16 fps is smooth.
                        val now = System.currentTimeMillis()
                        if (now - lastUiMs >= UI_STREAM_INTERVAL_MS) {
                            lastUiMs = now
                            updateMessage(replyId) { it.copy(text = buffer.toString()) }
                        }
                    }

                    is GenerationEvent.Complete -> {
                        // Always flush the complete text, whatever the throttle last showed.
                        updateMessage(replyId) {
                            it.copy(text = buffer.toString(), stats = event.stats)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateMessage(replyId) { it.copy(text = buffer.toString()) }
            throw e
        } catch (e: Exception) {
            updateMessage(replyId) {
                it.copy(text = e.message ?: "Generation failed", isError = true)
            }
            return ""
        }

        pendingReplyId = replyId
        return buffer.toString()
    }

    /**
     * Runs a function the model asked for, then feeds the result back and streams whatever it does
     * next -- which may be another tool call or the final answer. Returns that next output and
     * whether the tool moved the user (a navigation function ends the turn).
     *
     * The raw `{"tool": ...}` JSON is deleted from the transcript rather than shown: it is protocol,
     * not conversation, and leaving it on screen makes the app look broken. What replaces it is a
     * function chip -- so the user can still see exactly what the model did to their app.
     */
    private suspend fun runToolCall(
        activeEngine: InferenceEngine,
        call: ToolCall,
    ): Pair<String, Boolean> {
        val result = AppFunctions.execute(call, container)

        // Replace the JSON bubble with the function chip.
        pendingReplyId?.let { id ->
            updateMessage(id) {
                it.copy(
                    text = "",
                    functionCall = FunctionCallDisplay(
                        name = call.name,
                        summary = result.summary,
                        succeeded = !result.isError,
                    ),
                )
            }
        }

        result.navigation?.let { navigation ->
            _navigation.emit(navigation)
        }

        // Hand the result back so the model can use it. Without this the user gets a bare chip and
        // no reply, which reads as the model having ignored them.
        val next = runTurn(activeEngine, ToolCallingProtocol.toolResultPrompt(call, result.output))
        return next to (result.navigation != null)
    }

    /**
     * Ends a turn that ran out of tool hops while the model was still calling tools. The dangling
     * tool-call bubble becomes a note (never raw JSON), then the model answers with what it has and
     * no more tools offered.
     */
    private suspend fun forceFinalAnswer(activeEngine: InferenceEngine): String {
        pendingReplyId?.let { id ->
            updateMessage(id) {
                it.copy(
                    text = "",
                    functionCall = FunctionCallDisplay(
                        name = "tool_limit",
                        summary = "Reached the tool-call limit for this turn",
                        succeeded = false,
                    ),
                )
            }
        }
        return runTurn(
            activeEngine,
            "You have reached the maximum number of tool calls for this message. Answer the user " +
                "now using the information you already have. Do not call any more tools.",
        )
    }

    fun stopGenerating() {
        engine?.cancel()
        generationJob?.cancel()
    }

    // ---- Voice input (dictation) -----------------------------------------------------------------

    /** Kicks off the one-time speech-model download used for dictation. */
    fun downloadSpeechModel() {
        viewModelScope.launch { container.speechModels.download() }
    }

    /**
     * Starts dictation. The caller must already hold RECORD_AUDIO. Audio is buffered until
     * [stopVoiceInput], which transcribes it on-device and *appends* the text to the message box for
     * the user to edit before sending -- speech recognition mishears, so nothing is ever sent blind.
     */
    fun startVoiceInput() {
        val state = _uiState.value
        if (state.isDictating || state.isTranscribing || !state.isSpeechReady) return

        synchronized(voiceLock) { voiceSamples.clear() }
        _uiState.update { it.copy(isDictating = true, micLevel = 0f) }

        voiceJob = viewModelScope.launch {
            try {
                container.audioRecorder.record().collect { chunk ->
                    synchronized(voiceLock) { chunk.samples.forEach(voiceSamples::add) }
                    _uiState.update { it.copy(micLevel = chunk.level) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isDictating = false, micLevel = 0f)
                }
            }
        }
    }

    /** Stops the mic, transcribes what was captured, and appends the text to the draft. */
    fun stopVoiceInput() {
        if (!_uiState.value.isDictating) return
        voiceJob?.cancel()
        voiceJob = null
        _uiState.update { it.copy(isDictating = false, micLevel = 0f, isTranscribing = true) }

        viewModelScope.launch {
            try {
                val recogniser = container.speechRecognizer
                val paths = container.speechModels.selectedPaths()
                if (recogniser.loadedModelId != paths.id) {
                    recogniser.load(paths)
                    loadedRecogniserForVoice = true
                }

                val samples = synchronized(voiceLock) { voiceSamples.toFloatArray() }
                val text = recogniser.transcribe(samples)

                _uiState.update { st ->
                    // Append, not replace: the user may have already typed something, and dictation
                    // is meant to add to the message, not clobber it.
                    val merged = when {
                        text.isBlank() -> st.draft
                        st.draft.isBlank() -> text
                        else -> "${st.draft.trimEnd()} $text"
                    }
                    st.copy(isTranscribing = false, draft = merged)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isTranscribing = false) }
            }
        }
    }

    fun resetConversation() {
        viewModelScope.launch {
            stopGenerating()
            runCatching { engine?.resetConversation() }
            // Detach from the saved conversation: the next message starts a fresh one, and the old
            // conversation stays in history.
            conversationId = null
            _uiState.update { it.copy(messages = emptyList(), contextUsed = 0, replyingTo = null) }
        }
    }

    // ---- Persistence -----------------------------------------------------------------------------

    private suspend fun ensureConversationId(title: String): Long {
        conversationId?.let { return it }
        val now = System.currentTimeMillis()
        return container.chatDao.insertConversation(
            Conversation(
                modelId = currentModelId.orEmpty(),
                title = title.trim().replace('\n', ' ').take(60).ifBlank { "New chat" },
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        ).also { conversationId = it }
    }

    /** Appends one turn to the persisted conversation, creating the conversation on the first call. */
    private suspend fun persistMessage(role: String, content: String, title: String) {
        if (content.isBlank()) return
        val id = ensureConversationId(title)
        val now = System.currentTimeMillis()
        container.chatDao.insertMessage(
            StoredMessage(conversationId = id, role = role, content = content, createdAtMillis = now),
        )
        container.chatDao.touchConversation(id, now)
    }

    // ---- Per-message actions ---------------------------------------------------------------------

    /**
     * Removes a bubble from the view. It stays in the model's active context until the chat is reset
     * -- evicting a single turn from the running KV cache is not something the engines expose -- so
     * this is a display edit, not a memory edit.
     */
    fun deleteMessage(id: Long) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == id },
                replyingTo = state.replyingTo?.takeUnless { it.id == id },
            )
        }
    }

    fun startReply(message: ChatMessage) {
        _uiState.update { it.copy(replyingTo = message) }
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyingTo = null) }
    }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == id) transform(it) else it },
            )
        }
    }

    /**
     * The model is deliberately NOT unloaded here: the residency manager keeps it resident so the
     * next chat opens instantly, and frees it only under memory pressure. Leaving a chat just rolls
     * up a summary (if the conversation grew) and detaches, so the model can later be released.
     */
    override fun onCleared() {
        super.onCleared()

        // The speech recogniser holds ~240 MB of native memory. Release it only if dictation here is
        // what loaded it, so leaving a chat does not tear down a recogniser the Voice Notes screen
        // still owns.
        if (loadedRecogniserForVoice) container.speechRecognizer.release()

        val active = engine
        val convId = conversationId
        active?.cancel() // stop any in-flight decode before the engine is reused for the summary

        // Roll the conversation up into a stored summary on the way out -- but only once it has grown
        // enough to need one, and not mid-turn. The loaded model already holds the conversation, so
        // it summarises itself in a single turn; and because its context is [previous summary] +
        // recent turns, each close produces an *updated* rolling summary rather than starting over.
        val shouldSummarise = active != null && convId != null &&
            !_uiState.value.isGenerating && needsSummary(active)

        engine = null

        if (!attachedResidency) return
        attachedResidency = false

        // viewModelScope is already cancelled, so this outlives it. Summarise under the residency
        // lock so a chat opening right now waits rather than resetting the model mid-summary, then
        // detach -- staying attached until the summary is done keeps the model from being released
        // out from under it. No unload: the model stays resident for the next chat.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
            try {
                if (shouldSummarise && active != null && convId != null) {
                    container.modelResidency.runExclusive {
                        runCatching { rollUpSummary(active, convId) }
                    }
                }
            } finally {
                container.modelResidency.detach()
            }
        }
    }

    /** True once the live context is over half full -- the point where older turns start falling off. */
    private fun needsSummary(engine: InferenceEngine): Boolean {
        val total = _uiState.value.contextTotal
        return total > 0 && engine.contextTokensUsed() > total * SUMMARY_TRIGGER_FRACTION
    }

    /** Asks the loaded model to summarise the conversation it already holds, and stores the result. */
    private suspend fun rollUpSummary(engine: InferenceEngine, convId: Long) {
        val builder = StringBuilder()
        engine.generate(SUMMARISE_PROMPT).collect { event ->
            if (event is GenerationEvent.Token) builder.append(event.text)
        }
        val summary = builder.toString().trim()
        if (summary.isNotBlank()) container.chatDao.updateSummary(convId, summary)
    }

    private companion object {
        /** Most tool calls the model may chain in a single turn before it must answer. */
        const val MAX_TOOL_HOPS = 4

        /** Minimum gap between streamed-text UI updates, so recomposition does not fight the GPU. */
        const val UI_STREAM_INTERVAL_MS = 60L

        /**
         * Grace period between accepting a message and starting prefill. Covers the keyboard-dismiss
         * animation with room to spare, keeping that animation off the same frames as the turn's
         * heaviest compute burst so the keyboard slides away smoothly instead of stuttering. The
         * user's message is already on screen and persisted, so this second is not felt as lag --
         * prefill runs into seconds regardless.
         */
        const val SEND_SETTLE_MS = 1000L

        /** Context fraction past which a chat is summarised on close, so reopening stays in budget. */
        const val SUMMARY_TRIGGER_FRACTION = 0.5f

        const val SUMMARISE_PROMPT =
            "Summarise our conversation so far in 3 to 5 sentences, capturing the key facts, " +
                "questions, decisions and anything I asked you to remember. Write it as concise " +
                "notes to yourself so you can continue the conversation later. Use only what was " +
                "actually discussed."
    }
}
