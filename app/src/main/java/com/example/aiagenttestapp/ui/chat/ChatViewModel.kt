package com.example.aiagenttestapp.ui.chat

import android.net.Uri
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
import com.example.aiagent.engine.core.NativeToolEngine
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.ChatLoadPlanner
import com.example.aiagenttestapp.data.FileTextExtractor
import com.example.aiagenttestapp.data.Source
import com.example.aiagenttestapp.data.chat.toHistoryTurn
import com.example.aiagenttestapp.functions.AppFunctionObserver
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.ui.chat.ChatMessages.insertingBefore
import com.example.aiagenttestapp.ui.chat.ChatMessages.replacing
import com.example.aiagenttestapp.ui.chat.ChatMessages.without
import com.example.aiagenttestapp.functions.AppFunctionRunner
import com.example.aiagenttestapp.functions.AppFunctionRegistry
import com.example.aiagenttestapp.functions.PromptToolCalling
import com.example.aiagenttestapp.functions.ToolCallingStrategy
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.prompts.ChatPrompts
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiEffect
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.chat.ChatDao
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeechModelRepository
import com.example.aiagenttestapp.stt.SpeechRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    /** Present on assistant messages once generation completes. */
    val stats: GenerationStats? = null,
    val isError: Boolean = false,
    /** Set when this "message" is really the model having done something to the app. */
    val functionCall: FunctionCallDisplay? = null,
    /** Web pages the model consulted this turn, shown as citations under the answer. */
    val sources: List<Source> = emptyList(),
    /** Name of the file attached to a user message, shown as a paperclip chip on the bubble. */
    val attachmentName: String? = null,
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

    // ---- Attached file (extracted into the next message's prompt) ------------------------------
    /** Name of the file staged for the next message, or null. Shown as a chip above the input. */
    val attachmentName: String? = null,
    /** A file is being read and its text extracted right now. */
    val isExtractingFile: Boolean = false,
    /** The attached file was longer than the model can take, so only the start was kept. */
    val attachmentTruncated: Boolean = false,
    /** Why the last attachment failed to read, shown briefly above the input. */
    val attachmentError: String? = null,

    /**
     * Whether reasoning is shown for this chat. Captured at open from the thinking setting (which is
     * baked into the system prompt). Off means model output is rendered as a plain answer with any
     * residual `<think>` tags stripped, never a thinking card.
     */
    val showThinking: Boolean = true,
) : UiState {
    val canSend: Boolean
        get() = loadState is ModelLoadState.Ready && !isGenerating && !isDictating &&
            !isTranscribing && !isExtractingFile

    val isSpeechReady: Boolean get() = speechModelState is SpeechModelState.Ready

    /** 0f..1f. Past ~0.9 the next turn is likely to overflow, so the UI warns. */
    val contextFraction: Float
        get() = if (contextTotal <= 0) 0f
        else (contextUsed.toFloat() / contextTotal.toFloat()).coerceIn(0f, 1f)
}

sealed interface ChatIntent : UiIntent {
    /**
     * Loads a model and gets the chat ready. Sent once per (model, conversation) by the nav host --
     * re-sending it reloads the chat from scratch, so it is not a per-recomposition intent.
     */
    data class OpenChat(val modelId: String, val resumeConversationId: Long?) : ChatIntent

    data class DraftChanged(val text: String) : ChatIntent
    data object Send : ChatIntent
    data object StopGenerating : ChatIntent
    data object ResetConversation : ChatIntent

    /** Reads a picked file, extracts its text, and stages it for the next message. */
    data class AttachFile(val uri: Uri) : ChatIntent
    data object ClearAttachment : ChatIntent

    data object DownloadSpeechModel : ChatIntent
    /** The caller must already hold RECORD_AUDIO. */
    data object StartVoiceInput : ChatIntent
    data object StopVoiceInput : ChatIntent

    data class DeleteMessage(val id: Long) : ChatIntent
    data class StartReply(val message: ChatMessage) : ChatIntent
    data object CancelReply : ChatIntent
}

sealed interface ChatEffect : UiEffect {
    /**
     * The model asked to move the user. One-shot, not state: replaying "open settings" on every
     * recomposition would trap them on the Settings screen.
     */
    data class Navigate(val destination: AppNavigation) : ChatEffect
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val appFunctionDeps: AppFunctionDeps,
    private val appFunctions: AppFunctionRegistry,
    private val chatDao: ChatDao,
    private val chatLoadPlanner: ChatLoadPlanner,
    private val fileTextExtractor: FileTextExtractor,
    private val modelResidency: ModelResidency,
    private val settingsStore: SettingsStore,
    private val speechModels: SpeechModelRepository,
    private val speechRecognizer: SpeechRecognizer,
) : MviViewModel<ChatUiState, ChatIntent, ChatEffect>(
    ChatUiState(speechModelSizeBytes = speechModels.totalBytes),
),
    // Implemented rather than passed as a lambda: this is a real collaborator of
    // AppFunctionRunner, called on the runtime's thread, and worth being findable as one.
    AppFunctionObserver,
    ChatDictation.Listener {

    private var engine: InferenceEngine? = null
    private var generationJob: Job? = null
    private val messageIds = ChatMessageIds()

    /** Buffers dictation audio between start and stop. Guarded by [voiceLock]. */
    /** Dictation for the message box: mic, transcription and the recogniser's lifetime. */
    private val dictation = ChatDictation(
        audioRecorder = audioRecorder,
        speechRecognizer = speechRecognizer,
        speechModels = speechModels,
        scope = viewModelScope,
        listener = this,
    )

    init {
        // Dictation reuses the Voice Notes speech model, so mirror its readiness here -- that lets
        // the mic button offer a one-time download when it is missing instead of failing silently.
        speechModels.state.collectIntoState { s -> copy(speechModelState = s) }
    }

    override fun reduce(intent: ChatIntent): Unit = when (intent) {
        is ChatIntent.OpenChat -> openChat(intent.modelId, intent.resumeConversationId)
        is ChatIntent.DraftChanged -> setState { copy(draft = intent.text) }
        ChatIntent.Send -> send()
        ChatIntent.StopGenerating -> stopGenerating()
        ChatIntent.ResetConversation -> resetConversation()
        is ChatIntent.AttachFile -> attachFile(intent.uri)
        ChatIntent.ClearAttachment -> clearAttachment()
        ChatIntent.DownloadSpeechModel -> speechModels.enqueueDownload()
        ChatIntent.StartVoiceInput -> startVoiceInput()
        ChatIntent.StopVoiceInput -> stopVoiceInput()
        is ChatIntent.DeleteMessage -> deleteMessage(intent.id)
        is ChatIntent.StartReply -> setState { copy(replyingTo = intent.message) }
        ChatIntent.CancelReply -> setState { copy(replyingTo = null) }
    }

    /** Id of the bubble the current turn is streaming into, so a tool call can rewrite it. */
    private var pendingReplyId: Long? = null

    /** Web sources gathered across this turn's tool calls, attached to the final answer. */
    private val pendingSources = mutableListOf<Source>()

    /** The file staged for the next message. Its text is kept off UI state -- it can be large. */
    private val attachment = ChatAttachment(fileTextExtractor)

    /** Whether app functions were switched on when this chat's model was loaded. */
    private var toolsEnabled = false

    /**
     * How this model's engine is offered the app's functions.
     *
     * Held rather than re-derived because it is fixed for the life of the loaded model, like the
     * tools themselves. A [ToolCallingStrategy.PromptDriven] engine needs the hop loop below -- each
     * call arrives as JSON in the model's *output* and its result has to be fed back as a new turn.
     * A runtime-driven one needs none of it: the runtime does all of that inside one generate and
     * hands back only the final answer.
     */
    private var toolStrategy: ToolCallingStrategy = PromptToolCalling

    /**
     * The bubble a turn is streaming into right now, so a natively-executed tool can slot its chip
     * in above the answer as it happens. [pendingReplyId] cannot serve: it is only set once a turn
     * has finished, which for the native path is after every tool has already run.
     */
    @Volatile
    private var streamingReplyId: Long? = null

    /**
     * The engine this chat handed a runner to, so teardown can hand it back empty.
     *
     * Held rather than looked up again: the resident engine may have been swapped by another
     * screen in between, and clearing a runner off the wrong one would leave this chat's runner
     * live on the engine it actually bound.
     */
    private var boundEngine: NativeToolEngine? = null

    /** The persisted conversation this chat is writing to. Null until the first message creates it. */
    /** Everything this chat writes down. Created when the model is known, so it can be named. */
    private var transcript: ChatTranscriptStore? = null

    /** The model this chat is for, so a new conversation row records which model it belongs to. */
    private var currentModelId: String? = null

    /** Whether this chat registered itself with the residency manager, so onCleared detaches once. */
    private var attachedResidency = false

    /**
     * Loads [modelId] into an engine and gets the chat ready.
     *
     * How the engine, accelerator and system prompt are chosen lives in [planChatLoad], shared with
     * the startup [com.example.aiagenttestapp.data.ModelResidency] so the two agree exactly -- that
     * agreement is what lets a fresh chat reuse the resident model with a reset instead of a load.
     */
    private fun openChat(modelId: String, resumeConversationId: Long?) {
        // planChatLoad looks in the built-in catalogue *and* the user's added models -- a model
        // pulled from HuggingFace has to open a chat exactly like a built-in one.
        val plan = when (val result = chatLoadPlanner.plan(modelId)) {
            is ChatLoadPlan.UnknownModel -> {
                setState { copy(loadState = ModelLoadState.Failed("Unknown model")) }
                return
            }
            is ChatLoadPlan.NoEngine -> {
                setState {
                    copy(
                        model = result.model,
                        loadState = ModelLoadState.Failed(
                            "No engine in this build can load ${result.model.format.label} files",
                        ),
                    )
                }
                return
            }
            is ChatLoadPlan.Ready -> result
        }

        val model = plan.model
        val selected = plan.engine
        val accelerator = plan.accelerator

        // Publish which engine and accelerator we settled on *before* anything can fail, so the
        // title bar reads "LiteRT-LM · GPU" even on the error screens. Filling it in only on the
        // success path leaves a failed load captioned with a bare "· CPU".
        setState {
            ChatUiState(
                model = model,
                engineId = plan.engineId,
                engineName = plan.engineName,
                accelerator = accelerator,
                loadState = ModelLoadState.Loading("Loading ${model.name} on ${accelerator.label}"),
                contextTotal = model.contextTokens,
                // Fixed for the life of the loaded model, like tools: the thinking setting is baked
                // into the system prompt, so a mid-chat toggle cannot change how this chat renders.
                showThinking = settingsStore.settings.value.thinkingEnabled,
                // Dictation readiness is not part of the conversation, so carry it across the reset
                // rather than briefly flashing the mic button as unavailable on every model open.
                speechModelState = speechModelState,
                speechModelSizeBytes = speechModelSizeBytes,
            )
        }

        // Tools go into the system prompt, so this is fixed for the life of the loaded model --
        // toggling the setting mid-chat cannot retroactively give the model tools it was never told
        // about. (Whether this model gets the tool section at all is decided in planChatLoad.)
        toolsEnabled = plan.toolsEnabled
        toolStrategy = ToolCallingStrategy.forEngine(plan.engine.descriptor)
        setState {
            copy(
                toolsActive = plan.toolsEnabled,
                toolsUnavailableReason = plan.toolsUnavailableReason,
            )
        }

        if (!plan.downloaded) {
            setState {
                copy(loadState = ModelLoadState.Failed("${model.name} is not downloaded"))
            }
            return
        }

        engine = selected
        currentModelId = modelId
        transcript = ChatTranscriptStore(chatDao, modelId)

        // Tell the residency manager a chat is now using the engine, so it will not release the
        // resident model under memory pressure while this chat is on screen. Balanced by onCleared.
        if (!attachedResidency) {
            modelResidency.attach()
            attachedResidency = true
        }

        viewModelScope.launch {
            // Reopen a specific saved conversation (from the history list), or start fresh. The whole
            // transcript is restored to the display; only a fitted tail is fed to the model, with the
            // rolling summary of the older turns folded into the system prompt ahead of it.
            val restored = resumeConversationId
                ?.let { id -> runCatching { chatDao.conversationById(id) }.getOrNull() }
            val pastMessages = restored?.messages.orEmpty().sortedBy { it.id }
            restored?.conversation?.id?.let { transcript?.resume(it) }
            if (pastMessages.isNotEmpty()) {
                // Reuse the DB ids as bubble ids (unique), and start new bubbles past them.
                messageIds.startAfter(pastMessages.maxOf { it.id })
                setState {
                    copy(
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

            val effectiveSystemPrompt =
                ChatResume.systemPrompt(plan.systemPrompt, restored?.conversation?.summary)

            val initialHistory = ChatResume.fittedHistory(
                past = pastMessages.map { it.toHistoryTurn() },
                contextTokens = model.contextTokens,
                systemPrompt = effectiveSystemPrompt,
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
                modelResidency.open(
                    plan = plan.resolved,
                    request = request,
                    reuseWhenResident = resumeConversationId == null,
                )
                // After open(), not as part of the request: the model may well have been warmed in
                // the background before this screen existed, and the runner belongs to this screen.
                // Only a NativeToolEngine has anywhere to put this. The cast cannot silently
                // miss: an engine that claims native tool support without implementing it is
                // rejected when EngineRegistry is built.
                boundEngine = selected as? NativeToolEngine
                boundEngine?.toolRunner =
                    if (toolsEnabled && toolStrategy is ToolCallingStrategy.RuntimeDriven) {
                        AppFunctionRunner(appFunctions, appFunctionDeps, this@ChatViewModel)
                    } else {
                        null
                    }
                setState {
                    copy(
                        loadState = ModelLoadState.Ready,
                        // The engine may have fallen back (GPU -> CPU) if the requested accelerator
                        // turned out to be unusable. Show what actually happened, not what we asked
                        // for -- otherwise the speed the user sees has no explanation.
                        accelerator = selected.activeAccelerator ?: accelerator,
                    )
                }
            } catch (e: EngineException.OutOfMemory) {
                setState {
                    copy(
                        loadState = ModelLoadState.Failed(
                            "${model.name} ran out of memory while loading. Close other apps and " +
                                "try again, or pick a smaller model.",
                        ),
                    )
                }
            } catch (e: Exception) {
                setState {
                    copy(
                        loadState = ModelLoadState.Failed(
                            e.message ?: "Could not load ${model.name}",
                        ),
                    )
                }
            }
        }
    }

    private fun attachFile(uri: Uri) {
        setState {
            copy(
                isExtractingFile = true,
                attachmentError = null,
                attachmentName = null,
                attachmentTruncated = false,
            )
        }
        viewModelScope.launch {
            when (val outcome = attachment.stage(uri, currentState.contextTotal)) {
                is ChatAttachment.Outcome.Attached -> setState {
                    copy(
                        isExtractingFile = false,
                        attachmentName = outcome.name,
                        attachmentTruncated = outcome.truncated,
                    )
                }

                is ChatAttachment.Outcome.Failed -> setState {
                    copy(
                        isExtractingFile = false,
                        attachmentName = null,
                        attachmentTruncated = false,
                        attachmentError = outcome.message,
                    )
                }
            }
        }
    }

    private fun clearAttachment() {
        attachment.clear()
        setState {
            copy(attachmentName = null, attachmentTruncated = false, attachmentError = null)
        }
    }

    private fun send() {
        val activeEngine = engine ?: return
        val typed = currentState.draft.trim()
        if (!currentState.canSend || typed.isBlank()) return

        // A quote-reply prepends the message being answered so the model knows which earlier point
        // the user means. The model has the whole transcript, but not which part they are picking out.
        val quoted = currentState.replyingTo?.text?.trim()?.replace('\n', ' ')?.take(240)
        val userText = if (quoted != null) {
            "Regarding this earlier message:\n\"$quoted\"\n\n$typed"
        } else {
            typed
        }

        // The model also gets the attached file's text, prepended; the bubble shows only what the
        // user wrote plus a paperclip, so a many-page file does not become a many-page message.
        val fileName = currentState.attachmentName
        val fileText = attachment.text
        val modelPrompt = if (fileText != null && fileName != null) {
            "The user attached a file named \"$fileName\". Use its contents to answer.\n\n" +
                "----- BEGIN $fileName -----\n$fileText\n----- END $fileName -----\n\n$userText"
        } else {
            userText
        }

        setState {
            copy(
                draft = "",
                replyingTo = null,
                attachmentName = null,
                attachmentTruncated = false,
                attachmentError = null,
                messages = messages + ChatMessage(
                    id = messageIds.next(),
                    isUser = true,
                    text = userText,
                    attachmentName = fileName,
                ),
                isGenerating = true,
            )
        }
        attachment.clear()

        generationJob = viewModelScope.launch {
            try {
                transcript?.append(HistoryTurn.ROLE_USER, userText, title = userText)

                // Let the send transition settle before prefill starts. The keyboard is dismissed on
                // send, and prefill is the heaviest CPU/GPU burst of the whole turn; starting it while
                // the keyboard is still animating away makes that animation drop frames and play in
                // slow motion. The user's message is already on screen and persisted, so this brief
                // wait costs nothing they can see -- prefill alone runs into seconds regardless.
                delay(SEND_SETTLE_MS.milliseconds)

                pendingSources.clear()
                var response = runTurn(activeEngine, modelPrompt)

                // Let the model chain a few tool calls per turn -- search, read a result, search
                // again -- and then answer, instead of stopping after one. Kept bounded and guarded:
                // the hop cap stops runaway chaining, an identical repeated call (a small model
                // spinning on the same search) breaks early, and a navigation tool ends the turn
                // since the user has been moved.
                // Only a prompt-driven engine has a loop to drive: its calls arrive as text
                // the app has to read. A runtime-driven one has already run them all.
                val prompted = toolStrategy as? ToolCallingStrategy.PromptDriven
                if (toolsEnabled && prompted != null) {
                    response = ChatToolLoop(
                        functions = appFunctions,
                        deps = appFunctionDeps,
                        strategy = prompted,
                        host = ChatToolHost(activeEngine),
                    ).drive(response, settingsStore.settings.value.maxToolHops)
                }

                // Attach the web sources gathered this turn to the final answer, as citations.
                if (pendingSources.isNotEmpty()) {
                    pendingReplyId?.let { id ->
                        updateMessage(id) { it.copy(sources = pendingSources.toList()) }
                    }
                }

                // Tool-call chips are not persisted, but the model's final answer is.
                transcript?.append(HistoryTurn.ROLE_ASSISTANT, response, title = userText)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                setState {
                    copy(
                        isGenerating = false,
                        contextUsed = activeEngine.contextTokensUsed(),
                    )
                }
            }
        }
    }

    /** Streams one model response into a fresh bubble and returns the full text. */
    private suspend fun runTurn(activeEngine: InferenceEngine, prompt: String): String {
        val replyId = messageIds.next()
        setState {
            copy(messages = messages + ChatMessage(id = replyId, isUser = false, text = ""))
        }
        streamingReplyId = replyId

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

        streamingReplyId = null
        pendingReplyId = replyId
        return buffer.toString()
    }

    /**
     * Shows what a natively-executed function did. Called by [AppFunctionRunner].
     *
     * On the runtime's decode thread, not the main one, so everything touched here has to be safe
     * from there: `setState` is an atomic flow update and effects go through a buffered channel.
     * [pendingSources] is the exception -- a plain list -- so it is guarded.
     */
    override fun onFunctionExecuted(call: ToolCall, result: AppFunctionResult) {
        collectSources(result)

        // The chip goes in *above* the reply being streamed, so the transcript reads in the order
        // things happened: the model called a function, then answered with what it learned. The
        // prompt protocol instead rewrites its JSON bubble into a chip, because there the call was
        // the model's visible output; here the call never appears in the text at all.
        val chip = ChatMessage(
            id = messageIds.next(),
            isUser = false,
            text = "",
            functionCall = result.asChip(call.name),
        )
        val streaming = streamingReplyId
        setState { copy(messages = messages.insertingBefore(streaming, chip)) }

        result.navigation?.let { emitEffect(ChatEffect.Navigate(it)) }
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
    /**
     * The screen, as [ChatToolLoop] sees it.
     *
     * An inner class so it can reach the streaming and message-list machinery, but a named type
     * rather than an anonymous one: it is bound to a single engine for the length of a turn, and
     * that is worth saying in its constructor.
     */
    private inner class ChatToolHost(
        private val activeEngine: InferenceEngine,
    ) : ChatToolLoop.Host {

        override suspend fun runTurn(prompt: String): String = runTurn(activeEngine, prompt)

        override fun onToolExecuted(call: ToolCall, result: AppFunctionResult) {
            collectSources(result)

            // Replace the JSON bubble with the function chip: the call was the model's visible
            // output here, so the chip takes its place rather than being inserted beside it.
            pendingReplyId?.let { id ->
                updateMessage(id) {
                    it.copy(text = "", functionCall = result.asChip(call.name))
                }
            }

            result.navigation?.let { emitEffect(ChatEffect.Navigate(it)) }
        }

        override fun onToolLimitReached() {
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
        }
    }

    /** Web pages a call drew on, de-duplicated across the turn. */
    private fun collectSources(result: AppFunctionResult) {
        synchronized(pendingSources) {
            result.sources.forEach { source ->
                if (pendingSources.none { it.url == source.url }) pendingSources += source
            }
        }
    }

    private fun AppFunctionResult.asChip(name: String) = FunctionCallDisplay(
        name = name,
        summary = summary,
        succeeded = !isError,
    )

    private fun stopGenerating() {
        engine?.cancel()
        generationJob?.cancel()
    }

    // ---- Voice input (dictation) -----------------------------------------------------------------

    /**
     * Starts dictation. Audio is buffered until [stopVoiceInput], which transcribes it on-device and
     * *appends* the text to the message box for the user to edit before sending -- speech
     * recognition mishears, so nothing is ever sent blind.
     */
    private fun startVoiceInput() {
        val state = currentState
        if (state.isDictating || state.isTranscribing || !state.isSpeechReady) return

        setState { copy(isDictating = true, micLevel = 0f) }
        dictation.start()
    }

    /** Stops the mic, transcribes what was captured, and appends the text to the draft. */
    private fun stopVoiceInput() {
        if (!currentState.isDictating) return

        setState { copy(isDictating = false, micLevel = 0f, isTranscribing = true) }
        dictation.stopAndTranscribe()
    }

    override fun onLevel(level: Float) = setState { copy(micLevel = level) }

    override fun onRecordingFailed() = setState { copy(isDictating = false, micLevel = 0f) }

    override fun onTranscribed(text: String) = setState {
        // Append, not replace: the user may have already typed something, and dictation is meant
        // to add to the message, not clobber it.
        val merged = when {
            text.isBlank() -> draft
            draft.isBlank() -> text
            else -> "${draft.trimEnd()} $text"
        }
        copy(isTranscribing = false, draft = merged)
    }

    override fun onTranscriptionFailed() = setState { copy(isTranscribing = false) }

    private fun resetConversation() {
        viewModelScope.launch {
            stopGenerating()
            runCatching { engine?.resetConversation() }
            // Detach from the saved conversation: the next message starts a fresh one, and the old
            // conversation stays in history.
            transcript = ChatTranscriptStore(chatDao, currentModelId.orEmpty())
            setState { copy(messages = emptyList(), contextUsed = 0, replyingTo = null) }
        }
    }

    // ---- Persistence -----------------------------------------------------------------------------

    // ---- Per-message actions ---------------------------------------------------------------------

    /**
     * Removes a bubble from the view. It stays in the model's active context until the chat is reset
     * -- evicting a single turn from the running KV cache is not something the engines expose -- so
     * this is a display edit, not a memory edit.
     */
    private fun deleteMessage(id: Long) {
        setState {
            copy(
                messages = messages.without(id),
                replyingTo = replyingTo?.takeUnless { it.id == id },
            )
        }
    }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        setState { copy(messages = messages.replacing(id, transform)) }
    }

    /**
     * The model is deliberately NOT unloaded here: the residency manager keeps it resident so the
     * next chat opens instantly, and frees it only under memory pressure. Leaving a chat just rolls
     * up a summary (if the conversation grew) and detaches, so the model can later be released.
     */
    override fun onCleared() {
        super.onCleared()

        // Hand the engine back without a runner. The model stays resident by design, so a runner
        // left behind would keep this view model -- and everything it holds -- alive inside a
        // singleton, and would still execute functions for a screen that no longer exists.
        boundEngine?.toolRunner = null
        boundEngine = null

        // viewModelScope is already cancelled here, so the recogniser's release -- which waits on
        // any decode still running -- needs a scope that outlives this one.
        dictation.releaseRecogniserIfOwned(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )

        val active = engine
        val store = transcript
        active?.cancel() // stop any in-flight decode before the engine is reused for the summary

        // Roll the conversation up into a stored summary on the way out -- but only once it has grown
        // enough to need one, and not mid-turn. The loaded model already holds the conversation, so
        // it summarises itself in a single turn; and because its context is [previous summary] +
        // recent turns, each close produces an *updated* rolling summary rather than starting over.
        val shouldSummarise = active != null && store != null && !currentState.isGenerating &&
            store.needsSummary(active, currentState.contextTotal)

        engine = null

        if (!attachedResidency) return
        attachedResidency = false

        // viewModelScope is already cancelled, so this outlives it. Summarise under the residency
        // lock so a chat opening right now waits rather than resetting the model mid-summary, then
        // detach -- staying attached until the summary is done keeps the model from being released
        // out from under it. No unload: the model stays resident for the next chat.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
            try {
                if (shouldSummarise && active != null && store != null) {
                    modelResidency.runExclusive {
                        runCatching { store.rollUpSummary(active) }
                    }
                }
            } finally {
                modelResidency.detach()
            }
        }
    }

    /** True once the live context is over half full -- the point where older turns start falling off. */
    private companion object {
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

    }
}
