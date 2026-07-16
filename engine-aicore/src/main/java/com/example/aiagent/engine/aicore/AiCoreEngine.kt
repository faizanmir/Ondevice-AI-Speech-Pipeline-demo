package com.example.aiagent.engine.aicore

import android.util.Log
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagent.engine.core.EngineAvailability
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.EngineException
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.GenerationStats
import com.example.aiagent.engine.core.HistoryTurn
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.SamplingParams
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * [InferenceEngine] backed by Gemini Nano through Android's AICore system service, reached via
 * ML Kit's GenAI Prompt API (the shipping successor to the experimental `com.google.ai.edge.aicore`
 * SDK, which Google now reserves for prototyping).
 *
 * This engine is unlike the others in two ways that shape everything below:
 *
 *  - **The OS owns the model.** There is no file: AICore downloads Gemini Nano itself (shared by
 *    every app on the device), keeps it updated through Play, and runs it in its own process on
 *    the NPU. [LoadRequest.modelPath] is accepted and ignored, and the app's RAM budget is barely
 *    touched. The flip side is that only devices on Google's allowlist (recent Pixel and Galaxy
 *    flagships) can run it at all -- and that is only discoverable by asking the service, which is
 *    what [load] does.
 *
 *  - **The Prompt API is stateless.** One request in, one response out, no conversation object and
 *    no KV cache we can hold onto between turns. Multi-turn chat therefore works by replaying the
 *    transcript into every request: the engine keeps [history] and folds it into the prompt text,
 *    trimming oldest-first with the same [ContextWindow] rules the app uses when reopening a chat.
 */
class AiCoreEngine : InferenceEngine {

    override val descriptor = EngineDescriptor(
        id = EngineId.AICORE,
        displayName = "AICore",
        vendor = "Google",
        supportedFormats = setOf(ModelFormat.AICORE),
        supportedAccelerators = setOf(Accelerator.NPU),
        supportsVision = false,
        blurb = "Gemini Nano inside Android's AICore service. The OS downloads, updates and runs " +
            "the model out-of-process on the NPU, so it costs the app almost no memory -- but " +
            "only recent flagship devices support it.",
    )

    private var model: GenerativeModel? = null
    private var systemInstruction: SystemInstruction? = null
    private var systemPromptTokens = 0
    private var contextTokens = FALLBACK_CONTEXT_TOKENS
    private var sampling = SamplingParams()
    private var activeSeed = 0

    /**
     * The conversation so far, engine-owned per the [InferenceEngine] contract. Replayed into
     * every request because the Prompt API keeps no state of its own.
     */
    private val history = mutableListOf<HistoryTurn>()

    @Volatile
    override var loadedModelPath: String? = null
        private set

    @Volatile
    override var activeAccelerator: Accelerator? = null
        private set

    /** Serialises load/unload/reset against each other. */
    private val lifecycleLock = Mutex()

    /**
     * The coroutine currently running [generate]'s flow. The Prompt API has no cancel call of its
     * own, so [cancel] stops the stream by cancelling the collection -- which is safe: the caller
     * already treats CancellationException as "the user stopped the reply".
     */
    @Volatile
    private var generationJob: Job? = null

    /**
     * Whether the *library* is in this build. Whether the *device* can run Gemini Nano is a
     * different question that only AICore itself can answer, and asking means binding a system
     * service -- too heavy for this main-thread call, so it is deferred to [load], which turns a
     * "no" into a [EngineException.LoadFailed] the chat screen can show.
     */
    override fun availability(): EngineAvailability =
        if (sdkPresent) {
            EngineAvailability.Available
        } else {
            EngineAvailability.Unavailable("ML Kit GenAI Prompt library is missing from this build")
        }

    override suspend fun load(request: LoadRequest) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            unloadLocked()

            val client = Generation.getClient()
            try {
                when (client.checkStatus()) {
                    FeatureStatus.UNAVAILABLE -> throw EngineException.LoadFailed(
                        "Gemini Nano is not available on this device. AICore needs a recent " +
                            "flagship (Pixel 9 or newer, Galaxy S25 or newer) with the Android " +
                            "AICore system app enabled.",
                    )
                    // DOWNLOADING too: download() attaches to the in-flight fetch rather than
                    // starting a second one, and returns when the model is on the device.
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> downloadFeature(client)
                    else -> Unit // AVAILABLE
                }

                // Pull the model into AICore's memory now so the first token of the first turn is
                // not also paying the model-load cost. Best-effort: a failed warmup only means a
                // slower first reply.
                runCatching { client.warmup() }
                    .onFailure { Log.w(TAG, "warmup failed; first token will be slower", it) }

                // The service knows its real context window; trust it over the catalogue when it
                // is smaller. (Different devices carry different Gemini Nano builds.)
                contextTokens = runCatching { client.getTokenLimit() }
                    .getOrNull()
                    ?.takeIf { it > 0 }
                    ?.coerceAtMost(request.contextTokens)
                    ?: request.contextTokens
            } catch (t: Throwable) {
                runCatching { client.close() }
                if (t is CancellationException) throw t
                throw t.asEngineException()
            }

            systemInstruction = request.systemPrompt
                ?.takeIf { it.isNotBlank() }
                ?.let { SystemInstruction(it) }
            systemPromptTokens = request.systemPrompt
                ?.let { ContextWindow.estimateTokens(it) } ?: 0
            sampling = request.sampling
            activeSeed = drawSeed(request.sampling)
            history.clear()
            history += request.initialHistory

            model = client
            loadedModelPath = request.modelPath
            activeAccelerator = Accelerator.NPU
        }
    }

    /**
     * Blocks until AICore has fetched Gemini Nano. This is the OS's download, not the app's: it
     * goes through AICore's own Play delivery, lands in system storage shared by every app, and
     * cannot be driven by the app's WorkManager pipeline -- which is why it happens here, inside
     * load, rather than behind the catalogue's Download button.
     */
    private suspend fun downloadFeature(client: GenerativeModel) {
        Log.i(TAG, "Gemini Nano is not on this device yet; asking AICore to fetch it")
        client.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted ->
                    Log.i(TAG, "AICore download started: ${status.bytesToDownload} bytes to fetch")
                is DownloadStatus.DownloadProgress ->
                    Log.i(TAG, "AICore download: ${status.totalBytesDownloaded} bytes so far")
                is DownloadStatus.DownloadFailed ->
                    throw EngineException.LoadFailed(status.e.describeForUser(), status.e)
                DownloadStatus.DownloadCompleted ->
                    Log.i(TAG, "AICore download complete")
            }
        }
    }

    override fun generate(prompt: String): Flow<GenerationEvent> = flow {
        val client = model ?: throw EngineException.NotLoaded()
        generationJob = currentCoroutineContext()[Job]

        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        val response = StringBuilder()
        val promptText = transcriptPrompt(prompt)

        try {
            client.generateContentStream(buildRequest(promptText)).collect { chunk ->
                val text = chunk.candidates.firstOrNull()?.text.orEmpty()
                if (text.isNotEmpty()) {
                    if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
                    response.append(text)
                    emit(GenerationEvent.Token(text))
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw EngineException.GenerationFailed(t.describeForUser(), t)
        } finally {
            generationJob = null
        }

        history += HistoryTurn(HistoryTurn.ROLE_USER, prompt)
        history += HistoryTurn(HistoryTurn.ROLE_ASSISTANT, response.toString())

        // AICore reports no timing or token counts of its own, so estimates are all there is.
        // The same estimator the context budget uses keeps the two consistent with each other.
        emit(
            GenerationEvent.Complete(
                GenerationStats(
                    promptTokens = systemPromptTokens + ContextWindow.estimateTokens(promptText),
                    generatedTokens = ContextWindow.estimateTokens(response.toString()),
                    timeToFirstTokenMs = firstTokenMs,
                    totalMs = System.currentTimeMillis() - startMs,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    /**
     * The whole conversation as one prompt, newest turns kept when it will not all fit. A bare
     * first turn goes through untouched -- wrapping a single question in "User:/Assistant:"
     * scaffolding measurably changes how the model answers it, for no benefit.
     */
    private fun transcriptPrompt(prompt: String): String {
        val kept = ContextWindow.fit(history, contextTokens, systemPromptTokens)
        if (kept.isEmpty()) return prompt
        return buildString {
            for (turn in kept) {
                append(if (turn.role == HistoryTurn.ROLE_USER) "User: " else "Assistant: ")
                append(turn.content.trim())
                append("\n\n")
            }
            append("User: ")
            append(prompt)
            append("\n\nAssistant:")
        }
    }

    private fun buildRequest(promptText: String): GenerateContentRequest {
        val text = TextPart(promptText)
        // SystemInstruction is a first-class part here, unlike history -- so the system prompt
        // does not have to be replayed as transcript text on every turn.
        val builder = systemInstruction
            ?.let { GenerateContentRequest.Builder(it, text) }
            ?: GenerateContentRequest.Builder(text)
        return builder.apply {
            temperature = sampling.temperature
            topK = sampling.topK
            // No topP: the Prompt API does not expose it.
            seed = activeSeed
        }.build()
    }

    override fun cancel() {
        generationJob?.cancel()
    }

    override suspend fun resetConversation() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            if (model == null) throw EngineException.NotLoaded()
            // The Prompt API holds no conversation state, so clearing our transcript is the
            // entire reset. Re-drawing the seed keeps SEED_RANDOM's promise that a fresh chat
            // does not replay the previous one's token stream.
            history.clear()
            activeSeed = drawSeed(sampling)
        }
    }

    /** Estimated, not measured: there is no resident KV cache to count, only the transcript. */
    override fun contextTokensUsed(): Int =
        systemPromptTokens + history.sumOf { ContextWindow.estimateTokens(it.content) }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock { unloadLocked() }
    }

    /** Caller must hold [lifecycleLock]. */
    private fun unloadLocked() {
        generationJob?.cancel()
        generationJob = null

        runCatching { model?.close() }
            .onFailure { Log.w(TAG, "closing AICore client failed", it) }
        model = null

        history.clear()
        systemInstruction = null
        systemPromptTokens = 0
        loadedModelPath = null
        activeAccelerator = null
    }

    private fun drawSeed(params: SamplingParams): Int =
        if (params.seed == SamplingParams.SEED_RANDOM) {
            Random.nextInt(1, Int.MAX_VALUE)
        } else {
            params.seed
        }

    private fun Throwable.asEngineException(): EngineException =
        this as? EngineException ?: EngineException.LoadFailed(describeForUser(), this)

    private companion object {
        const val TAG = "AiCoreEngine"

        /** Used until the service has told us its real limit (see load). */
        const val FALLBACK_CONTEXT_TOKENS = 4096

        /** The AAR is pure JVM bytecode, so if the entry class loads, the SDK is usable. */
        val sdkPresent: Boolean by lazy {
            runCatching { Class.forName("com.google.mlkit.genai.prompt.Generation") }.isSuccess
        }

        /**
         * AICore's failures arrive as [GenAiException]s with an error code, and the code is the
         * difference between "get a newer phone", "free up storage" and "just try again" --
         * distinctions worth surfacing instead of a generic message.
         */
        fun Throwable.describeForUser(): String {
            val genAi = generateSequence(this) { it.cause }
                .filterIsInstance<GenAiException>()
                .firstOrNull()
                ?: return message ?: "AICore failed"
            return when (genAi.errorCode) {
                GenAiException.ErrorCode.NOT_AVAILABLE,
                GenAiException.ErrorCode.NOT_SUPPORTED,
                GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
                ->
                    "Gemini Nano is not available on this device. AICore needs a recent flagship " +
                        "(Pixel 9 or newer, Galaxy S25 or newer) with the AICore system app enabled."
                GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
                    "Gemini Nano needs a system update first -- update Android and the AICore app " +
                        "in the Play Store, then try again."
                GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                    "Not enough free storage for Android to download Gemini Nano. Free up some " +
                        "space and try again."
                GenAiException.ErrorCode.BUSY ->
                    "AICore is busy serving another app right now. Try again in a moment."
                GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                    "This conversation no longer fits in Gemini Nano's context window. Start a " +
                        "new chat to continue."
                GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                    "Android blocks AICore requests from backgrounded apps. Bring the app to the " +
                        "foreground and try again."
                GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                    "This app has used up its AICore battery quota for now. Try again later."
                else -> genAi.message ?: "AICore failed"
            }
        }
    }
}
