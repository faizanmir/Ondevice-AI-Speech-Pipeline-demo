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
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolCallingProtocol
import com.example.aiagent.engine.core.ToolRunner
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.ChatLoadPlanner
import com.example.aiagenttestapp.data.FileTextExtractor
import com.example.aiagenttestapp.data.Source
import com.example.aiagenttestapp.data.chat.Conversation
import com.example.aiagenttestapp.data.chat.StoredMessage
import com.example.aiagenttestapp.data.chat.toHistoryTurn
import com.example.aiagenttestapp.functions.AppFunctions
import com.example.aiagenttestapp.functions.AppNavigation
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiEffect
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val chatDao: ChatDao,
    private val chatLoadPlanner: ChatLoadPlanner,
    private val fileTextExtractor: FileTextExtractor,
    private val modelResidency: ModelResidency,
    private val settingsStore: SettingsStore,
    private val speechModels: SpeechModelRepository,
    private val speechRecognizer: SpeechRecognizer,
) : MviViewModel<ChatUiState, ChatIntent, ChatEffect>(
    ChatUiState(speechModelSizeBytes = speechModels.totalBytes),
) {

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

    /** Extracted text of the staged file. Kept off UI state -- it can be large -- until sent. */
    private var attachedFileText: String? = null

    /** Whether app functions were switched on when this chat's model was loaded. */
    private var toolsEnabled = false

    /**
     * Whether this model's engine runs tools itself rather than through the prompt protocol.
     *
     * The two paths are mutually exclusive and look nothing alike from here: the protocol needs a
     * hop loop, because each call arrives as JSON in the model's *output* and the result has to be
     * fed back as a new turn; the native path needs none, because the runtime does all of that
     * inside one generate and hands back only the final answer.
     */
    private var nativeToolsActive = false

    /**
     * The bubble a turn is streaming into right now, so a natively-executed tool can slot its chip
     * in above the answer as it happens. [pendingReplyId] cannot serve: it is only set once a turn
     * has finished, which for the native path is after every tool has already run.
     */
    @Volatile
    private var streamingReplyId: Long? = null

    /** The persisted conversation this chat is writing to. Null until the first message creates it. */
    private var conversationId: Long? = null

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
        nativeToolsActive = plan.nativeTools.isNotEmpty()
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
            conversationId = restored?.conversation?.id
            if (pastMessages.isNotEmpty()) {
                // Reuse the DB ids as bubble ids (unique), and start new bubbles past them.
                nextMessageId = pastMessages.maxOf { it.id } + 1
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
                modelResidency.open(
                    plan = plan.resolved,
                    request = request,
                    reuseWhenResident = resumeConversationId == null,
                )
                // After open(), not as part of the request: the model may well have been warmed in
                // the background before this screen existed, and the runner belongs to this screen.
                // Harmlessly ignored by an engine without native tool support.
                selected.toolRunner = if (nativeToolsActive) nativeToolRunner() else null
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
            when (val result = fileTextExtractor.extract(uri, maxChars = fileCharBudget())) {
                is FileTextExtractor.Result.Success -> {
                    attachedFileText = result.text
                    setState {
                        copy(
                            isExtractingFile = false,
                            attachmentName = result.name,
                            attachmentTruncated = result.truncated,
                        )
                    }
                }

                is FileTextExtractor.Result.Failure -> {
                    attachedFileText = null
                    setState {
                        copy(
                            isExtractingFile = false,
                            attachmentName = null,
                            attachmentTruncated = false,
                            attachmentError = result.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * How much of an attached file to feed the loaded model, in characters.
     *
     * The file shares the model's context window with the system prompt, the user's question and the
     * summary the model has to write, so it gets [FILE_CONTEXT_FRACTION] of the window and the rest
     * is left as headroom. Sized off the *loaded* model's context ([ChatUiState.contextTotal]), which
     * is now device-dependent -- so a large-context model swallows a whole document while a 4K model
     * still takes the ~10K chars it always did. Falls back to a 4K-model budget before a model loads.
     */
    private fun fileCharBudget(): Int {
        val contextTokens = currentState.contextTotal.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val fileTokens = (contextTokens * FILE_CONTEXT_FRACTION).toInt()
        return ContextWindow.estimateChars(fileTokens)
    }

    private fun clearAttachment() {
        attachedFileText = null
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
        val fileText = attachedFileText
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
                    id = nextMessageId++,
                    isUser = true,
                    text = userText,
                    attachmentName = fileName,
                ),
                isGenerating = true,
            )
        }
        attachedFileText = null

        generationJob = viewModelScope.launch {
            try {
                persistMessage(HistoryTurn.ROLE_USER, userText, title = userText)

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
                // Only the prompt protocol needs this. On a native-tool engine the runtime has
                // already called every tool it wanted and generated the answer from the results, so
                // `response` here is the final answer and there is nothing left to parse out of it.
                if (toolsEnabled && !nativeToolsActive) {
                    val maxHops = settingsStore.settings.value.maxToolHops
                    var hops = 0
                    var lastSignature: String? = null
                    while (hops < maxHops) {
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

                // Attach the web sources gathered this turn to the final answer, as citations.
                if (pendingSources.isNotEmpty()) {
                    pendingReplyId?.let { id ->
                        updateMessage(id) { it.copy(sources = pendingSources.toList()) }
                    }
                }

                // Tool-call chips are not persisted, but the model's final answer is.
                persistMessage(HistoryTurn.ROLE_ASSISTANT, response, title = userText)
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
        val replyId = nextMessageId++
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
     * Runs app functions for an engine that calls tools itself.
     *
     * Blocking is not an oversight here, it is the contract. LiteRT-LM invokes the tool from inside
     * its decode loop and waits for the string, so there is nothing to suspend into --
     * [ToolRunner] is synchronous for that reason. What matters is *which* thread blocks: this runs
     * on the runtime's own worker, never the main thread, so the UI keeps drawing while a function
     * does its work.
     *
     * Everything it touches is safe from that thread: `setState` is an atomic flow update and
     * effects go through a buffered channel. [pendingSources] is the exception -- a plain list --
     * so it is guarded.
     */
    private fun nativeToolRunner(): ToolRunner = ToolRunner { call ->
        val result = runBlocking { AppFunctions.execute(call, appFunctionDeps) }

        synchronized(pendingSources) {
            result.sources.forEach { source ->
                if (pendingSources.none { it.url == source.url }) pendingSources += source
            }
        }

        // The chip goes in *above* the reply being streamed, so the transcript reads in the order
        // things happened: the model called a function, then answered with what it learned. The
        // prompt protocol instead rewrites its JSON bubble into a chip, because there the call was
        // the model's visible output; here the call never appears in the text at all.
        val chip = ChatMessage(
            id = nextMessageId++,
            isUser = false,
            text = "",
            functionCall = FunctionCallDisplay(
                name = call.name,
                summary = result.summary,
                succeeded = !result.isError,
            ),
        )
        val streaming = streamingReplyId
        setState {
            val at = messages.indexOfFirst { it.id == streaming }
            copy(
                messages = if (at >= 0) {
                    messages.toMutableList().apply { add(at, chip) }
                } else {
                    messages + chip
                },
            )
        }

        result.navigation?.let { emitEffect(ChatEffect.Navigate(it)) }

        result.output
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
        val result = AppFunctions.execute(call, appFunctionDeps)

        // Gather any web pages this call drew on, de-duplicated across the turn's tool calls.
        synchronized(pendingSources) {
            result.sources.forEach { source ->
                if (pendingSources.none { it.url == source.url }) pendingSources += source
            }
        }

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
            emitEffect(ChatEffect.Navigate(navigation))
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

        synchronized(voiceLock) { voiceSamples.clear() }
        setState { copy(isDictating = true, micLevel = 0f) }

        voiceJob = viewModelScope.launch {
            try {
                audioRecorder.record().collect { chunk ->
                    synchronized(voiceLock) { chunk.samples.forEach(voiceSamples::add) }
                    setState { copy(micLevel = chunk.level) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(isDictating = false, micLevel = 0f) }
            }
        }
    }

    /** Stops the mic, transcribes what was captured, and appends the text to the draft. */
    private fun stopVoiceInput() {
        if (!currentState.isDictating) return
        voiceJob?.cancel()
        voiceJob = null
        setState { copy(isDictating = false, micLevel = 0f, isTranscribing = true) }

        viewModelScope.launch {
            try {
                val recogniser = speechRecognizer
                val paths = speechModels.selectedPaths()
                if (recogniser.loadedModelId != paths.id) {
                    recogniser.load(paths)
                    loadedRecogniserForVoice = true
                }

                val samples = synchronized(voiceLock) { voiceSamples.toFloatArray() }
                // Dictation only wants the words; the reply's language is the conversation's business.
                // transcribeLong so a dictation past ~30 s is not clipped to its first half-minute.
                val text = recogniser.transcribeLong(samples).text

                setState {
                    // Append, not replace: the user may have already typed something, and dictation
                    // is meant to add to the message, not clobber it.
                    val merged = when {
                        text.isBlank() -> draft
                        draft.isBlank() -> text
                        else -> "${draft.trimEnd()} $text"
                    }
                    copy(isTranscribing = false, draft = merged)
                }
            } catch (e: Exception) {
                setState { copy(isTranscribing = false) }
            }
        }
    }

    private fun resetConversation() {
        viewModelScope.launch {
            stopGenerating()
            runCatching { engine?.resetConversation() }
            // Detach from the saved conversation: the next message starts a fresh one, and the old
            // conversation stays in history.
            conversationId = null
            setState { copy(messages = emptyList(), contextUsed = 0, replyingTo = null) }
        }
    }

    // ---- Persistence -----------------------------------------------------------------------------

    private suspend fun ensureConversationId(title: String): Long {
        conversationId?.let { return it }
        val now = System.currentTimeMillis()
        return chatDao.insertConversation(
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
        chatDao.insertMessage(
            StoredMessage(conversationId = id, role = role, content = content, createdAtMillis = now),
        )
        chatDao.touchConversation(id, now)
    }

    // ---- Per-message actions ---------------------------------------------------------------------

    /**
     * Removes a bubble from the view. It stays in the model's active context until the chat is reset
     * -- evicting a single turn from the running KV cache is not something the engines expose -- so
     * this is a display edit, not a memory edit.
     */
    private fun deleteMessage(id: Long) {
        setState {
            copy(
                messages = messages.filterNot { it.id == id },
                replyingTo = replyingTo?.takeUnless { it.id == id },
            )
        }
    }

    private fun updateMessage(id: Long, transform: (ChatMessage) -> ChatMessage) {
        setState {
            copy(messages = messages.map { if (it.id == id) transform(it) else it })
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
        //
        // On its own scope because releasing now *waits* for any decode still in flight -- the
        // recogniser is shared with the background transcription worker, and freeing native memory
        // under a running decode takes the process down. viewModelScope is already cancelled here.
        if (loadedRecogniserForVoice) {
            val recogniser = speechRecognizer
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
                runCatching { recogniser.release() }
            }
        }

        val active = engine
        val convId = conversationId
        active?.cancel() // stop any in-flight decode before the engine is reused for the summary

        // Roll the conversation up into a stored summary on the way out -- but only once it has grown
        // enough to need one, and not mid-turn. The loaded model already holds the conversation, so
        // it summarises itself in a single turn; and because its context is [previous summary] +
        // recent turns, each close produces an *updated* rolling summary rather than starting over.
        val shouldSummarise = active != null && convId != null &&
            !currentState.isGenerating && needsSummary(active)

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
                    modelResidency.runExclusive {
                        runCatching { rollUpSummary(active, convId) }
                    }
                }
            } finally {
                modelResidency.detach()
            }
        }
    }

    /** True once the live context is over half full -- the point where older turns start falling off. */
    private fun needsSummary(engine: InferenceEngine): Boolean {
        val total = currentState.contextTotal
        return total > 0 && engine.contextTokensUsed() > total * SUMMARY_TRIGGER_FRACTION
    }

    /** Asks the loaded model to summarise the conversation it already holds, and stores the result. */
    private suspend fun rollUpSummary(engine: InferenceEngine, convId: Long) {
        val builder = StringBuilder()
        engine.generate(SUMMARISE_PROMPT).collect { event ->
            if (event is GenerationEvent.Token) builder.append(event.text)
        }
        val summary = builder.toString().trim()
        if (summary.isNotBlank()) chatDao.updateSummary(convId, summary)
    }

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

        /** Context fraction past which a chat is summarised on close, so reopening stays in budget. */
        const val SUMMARY_TRIGGER_FRACTION = 0.5f

        /**
         * Share of the model's context window an attached file may fill. The remaining ~30% holds the
         * system prompt, the user's question and the model's reply. At a 4K context this lands on the
         * ~10K-char cap the extractor used before; a larger context scales it up proportionally.
         */
        const val FILE_CONTEXT_FRACTION = 0.7

        /** Attachment budget before a model is loaded and [ChatUiState.contextTotal] is known. */
        const val DEFAULT_CONTEXT_TOKENS = 4096

        const val SUMMARISE_PROMPT =
            "Summarise our conversation so far in 3 to 5 sentences, capturing the key facts, " +
                "questions, decisions and anything I asked you to remember. Write it as concise " +
                "notes to yourself so you can continue the conversation later. Use only what was " +
                "actually discussed."
    }
}
