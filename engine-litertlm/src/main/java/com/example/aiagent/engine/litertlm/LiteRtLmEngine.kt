package com.example.aiagent.engine.litertlm

import android.util.Log
import com.example.aiagent.engine.core.Accelerator
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
import com.example.aiagent.engine.core.OutputGuard
import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagent.engine.core.ToolRunner
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * [InferenceEngine] backed by Google's LiteRT-LM.
 *
 * This is the successor to MediaPipe's `LlmInference`, which carries an `@Deprecated` annotation in
 * source and is in maintenance-only mode. LiteRT-LM is what Google's own AI Edge Gallery ships.
 *
 * Two things about this runtime shape the code below:
 *
 *  - `Engine.initialize()` is expensive (seconds) but a single `Engine` can mint many
 *    `Conversation`s cheaply. So resetting a chat closes just the conversation and keeps the engine
 *    resident -- re-initialising per turn would be a multi-second stall.
 *  - The runtime measures its own prefill/decode throughput and exposes it via `BenchmarkInfo`.
 *    That is more honest than timing the Flow ourselves, so we surface it directly.
 */
class LiteRtLmEngine : InferenceEngine {

    override val descriptor = EngineDescriptor(
        id = EngineId.LITE_RT_LM,
        displayName = "LiteRT-LM",
        vendor = "Google AI Edge",
        supportedFormats = setOf(ModelFormat.LITERTLM),
        supportedAccelerators = setOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU),
        supportsVision = true,
        supportsNativeTools = true,
        blurb = "Google's on-device runtime. GPU and NPU accelerated, memory-maps weights so it " +
            "uses far less RAM than the file size suggests.",
    )

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationConfig: ConversationConfig = ConversationConfig()

    /**
     * Read by the tool adapters on every call, so a model loaded before anyone was driving it can
     * still run tools once a screen adopts it. Volatile because the runtime calls in from its own
     * decode thread, not the one that set this.
     */
    @Volatile
    override var toolRunner: ToolRunner? = null

    // Caller-facing generation bounds, applied by an OutputGuard in generate().
    private var maxOutputTokens: Int = 0
    private var stopSequences: List<String> = emptyList()

    @Volatile
    override var loadedModelPath: String? = null
        private set

    @Volatile
    override var activeAccelerator: Accelerator? = null
        private set

    /** Serialises load/unload/reset against each other; native handles are not thread-safe. */
    private val lifecycleLock = Mutex()

    override fun availability(): EngineAvailability =
        if (nativeLibraryPresent) {
            EngineAvailability.Available
        } else {
            EngineAvailability.Unavailable("LiteRT-LM native library is missing from this build")
        }

    /**
     * Loads with speculative decoding on, and again without it if that is what stopped the model
     * loading.
     *
     * Speculative decoding drafts several tokens with a small model and has the real one check them
     * in a single pass, which is close to free throughput when it works. It only works when the
     * `.litertlm` bundle actually carries a draft model, and the flag that turns it on is a
     * process-wide experimental switch rather than something the runtime negotiates per model --
     * so on a bundle without one it is a way to fail a load that would otherwise have succeeded.
     * Retrying without it turns "this model will not open" into "this model opens at normal speed",
     * which is the right trade for an optional speed-up.
     *
     * The retry is remembered for the process: the second load of an unsupporting model should not
     * pay for another failed engine initialisation, which costs seconds.
     */
    @OptIn(ExperimentalApi::class)
    override suspend fun load(request: LoadRequest) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            unloadLocked()

            ExperimentalFlags.enableSpeculativeDecoding = speculativeDecodingUsable

            try {
                loadOnBestAccelerator(request)
            } catch (t: Throwable) {
                if (!speculativeDecodingUsable) throw t

                Log.w(TAG, "load failed with speculative decoding; retrying without it", t)
                speculativeDecodingUsable = false
                ExperimentalFlags.enableSpeculativeDecoding = false
                loadOnBestAccelerator(request)
            }
        }
    }

    /**
     * Walks down to the CPU rather than failing outright. Caller must hold [lifecycleLock].
     *
     * Asking for the GPU is a request, not a guarantee: the driver may be missing OpenCL, the model
     * may have no GPU-compiled graph, an emulator may have no usable GPU at all, and the NPU path
     * is gated on vendor libraries that most devices do not ship. All of those surface as an
     * exception from initialize(), and every one of them is survivable by simply running on the
     * CPU -- slower, but working. Refusing to load at all would turn a performance problem into a
     * broken app.
     */
    private fun loadOnBestAccelerator(request: LoadRequest) {
        val ladder = buildList {
            add(request.accelerator)
            if (request.accelerator == Accelerator.NPU) add(Accelerator.GPU)
            if (request.accelerator != Accelerator.CPU) add(Accelerator.CPU)
        }.distinct()

        var lastFailure: Throwable? = null

        for (accelerator in ladder) {
            try {
                loadOn(accelerator, request)
                activeAccelerator = accelerator
                loadedModelPath = request.modelPath
                if (accelerator != request.accelerator) {
                    Log.w(
                        TAG,
                        "${request.accelerator.label} unavailable, fell back to " +
                            accelerator.label,
                    )
                }
                return
            } catch (t: Throwable) {
                Log.w(TAG, "load on ${accelerator.label} failed: ${t.message}")
                lastFailure = t
                unloadLocked()
            }
        }

        throw (lastFailure ?: IllegalStateException("Unknown error"))
            .asEngineException(request)
    }

    /** Caller must hold [lifecycleLock]. Throws if this accelerator cannot run the model. */
    private fun loadOn(accelerator: Accelerator, request: LoadRequest) {
        maxOutputTokens = request.sampling.maxOutputTokens
        stopSequences = request.sampling.stopSequences
        val newEngine = Engine(
            EngineConfig(
                modelPath = request.modelPath,
                backend = accelerator.toBackend(request.nativeLibraryDir),
                maxNumTokens = request.contextTokens,
                cacheDir = request.cacheDir,
            ),
        )

        try {
            newEngine.initialize()

            conversationConfig = ConversationConfig(
                systemInstruction = request.systemPrompt?.let { Contents.of(it) },
                // Declared to the runtime as schemas, and left on `automaticToolCalling` (the
                // default): LiteRT-LM emits the call, runs the tool through the adapter below, and
                // feeds the result back itself, all inside one sendMessageAsync. The app never sees
                // a half-finished turn, and the chat layer does not need a hop loop the way the
                // prompt protocol does.
                tools = request.tools.map { definition ->
                    tool(AppFunctionTool(definition) { toolRunner })
                },
                // Seed restored history as proper role-tagged turns. LiteRT-LM prefills these into
                // the new conversation at creation, so a reopened chat continues in context rather
                // than starting blank.
                initialMessages = request.initialHistory.map { turn ->
                    if (turn.role == HistoryTurn.ROLE_USER) {
                        Message.user(turn.content)
                    } else {
                        Message.model(turn.content)
                    }
                },
                // The NPU path has no sampler -- passing one makes initialisation fail.
                samplerConfig = if (accelerator == Accelerator.NPU) {
                    null
                } else {
                    SamplerConfig(
                        topK = request.sampling.topK,
                        topP = request.sampling.topP.toDouble(),
                        temperature = request.sampling.temperature.toDouble(),
                        // LiteRT-LM has no "random" sentinel of its own, so draw one per load.
                        seed = if (request.sampling.seed == SamplingParams.SEED_RANDOM) {
                            Random.nextInt(1, Int.MAX_VALUE)
                        } else {
                            request.sampling.seed
                        },
                    )
                },
            )

            conversation = newEngine.createConversation(conversationConfig)
            engine = newEngine
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        }
    }

    // getBenchmarkInfo() is @ExperimentalApi. We opt in rather than time the Flow ourselves: the
    // runtime's own prefill/decode counters are the honest numbers, and if the API is withdrawn the
    // runCatching below already degrades to wall-clock timing.
    @OptIn(ExperimentalApi::class)
    override fun generate(prompt: String): Flow<GenerationEvent> = flow {
        val activeConversation = conversation ?: throw EngineException.NotLoaded()

        val startMs = System.currentTimeMillis()

        // Always measure, even though the runtime also reports its own numbers. getBenchmarkInfo()
        // is experimental and, in practice, sometimes comes back empty -- and a stats line reading
        // "0.0 tok/s" is worse than no stats line at all, because it looks like a measurement.
        // These are the floor; the runtime's figures refine them when they are actually there.
        var measuredFirstTokenMs = 0L
        var chunks = 0
        val guard = OutputGuard(maxOutputTokens, stopSequences)
        var stopped = false

        try {
            activeConversation.sendMessageAsync(prompt).collect { message ->
                // Once a bound is hit we keep the output trimmed and ignore the rest. The runtime has
                // no interruptible cancel we can call mid-collect without risking a spurious
                // cancellation, so it decodes to its own stop -- correct output, a little wasted work.
                if (stopped) return@collect
                val text = message.text()
                if (text.isNotEmpty()) {
                    if (measuredFirstTokenMs == 0L) {
                        measuredFirstTokenMs = System.currentTimeMillis() - startMs
                    }
                    chunks++
                    val out = guard.push(text)
                    if (out.isNotEmpty()) emit(GenerationEvent.Token(out))
                    if (guard.isDone) stopped = true
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            throw EngineException.GenerationFailed(
                t.message ?: "LiteRT-LM failed while generating",
                t,
            )
        }

        if (!stopped) {
            guard.drain().takeIf { it.isNotEmpty() }?.let { emit(GenerationEvent.Token(it)) }
        }

        // getBenchmarkInfo()/getTokenCount() are declared as functions, not properties, so they do
        // not expose synthetic property access.
        val bench = runCatching { activeConversation.getBenchmarkInfo() }
            .onFailure { Log.w(TAG, "benchmark info unavailable, using wall-clock timing", it) }
            .getOrNull()
        val wallClockMs = System.currentTimeMillis() - startMs

        emit(
            GenerationEvent.Complete(
                GenerationStats(
                    promptTokens = bench?.lastPrefillTokenCount?.takeIf { it > 0 } ?: 0,
                    // A streamed chunk is not exactly a token, but it is the right order of
                    // magnitude and it is a real observation rather than a zero.
                    generatedTokens = bench?.lastDecodeTokenCount?.takeIf { it > 0 } ?: chunks,
                    timeToFirstTokenMs = bench?.timeToFirstTokenInSecond
                        ?.takeIf { it > 0.0 }
                        ?.let { (it * 1000).roundToLong() }
                        ?: measuredFirstTokenMs,
                    totalMs = wallClockMs,
                    reportedTokensPerSecond = bench?.lastDecodeTokensPerSecond
                        ?.takeIf { it > 0.0 },
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        runCatching { conversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelProcess failed", it) }
    }

    override suspend fun resetConversation() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val activeEngine = engine ?: throw EngineException.NotLoaded()
            runCatching { conversation?.close() }
                .onFailure { Log.w(TAG, "closing conversation failed", it) }
            // Cheap: reuses the already-initialised engine and its resident weights.
            conversation = activeEngine.createConversation(conversationConfig)
        }
    }

    override fun contextTokensUsed(): Int =
        runCatching { conversation?.getTokenCount() ?: 0 }.getOrDefault(0)

    override suspend fun unload() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock { unloadLocked() }
    }

    /** Caller must hold [lifecycleLock]. Order matters: conversation before engine. */
    private fun unloadLocked() {
        runCatching { conversation?.close() }
            .onFailure { Log.w(TAG, "closing conversation failed", it) }
        conversation = null

        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "closing engine failed", it) }
        engine = null

        loadedModelPath = null
        activeAccelerator = null
    }

    private fun Accelerator.toBackend(nativeLibraryDir: String?): Backend = when (this) {
        Accelerator.CPU -> Backend.CPU()
        Accelerator.GPU -> Backend.GPU()
        // The NPU backend dlopen()s vendor libraries out of the APK's native dir at run time.
        Accelerator.NPU -> Backend.NPU(
            nativeLibraryDir
                ?: throw EngineException.LoadFailed("NPU backend requires a native library dir"),
        )
    }

    /**
     * The runtime signals out-of-memory as a generic JNI exception, so the message is the only
     * signal we get. Classifying it lets the UI say "close some apps" instead of "unknown error".
     */
    private fun Throwable.asEngineException(request: LoadRequest): EngineException {
        val text = (message ?: "").lowercase()
        val looksLikeOom = OOM_MARKERS.any { it in text }
        return if (looksLikeOom) {
            EngineException.OutOfMemory(
                "Not enough memory to load this model on ${request.accelerator.label}",
                this,
            )
        } else {
            EngineException.LoadFailed(
                message ?: "LiteRT-LM could not load ${request.modelPath.substringAfterLast('/')}",
                this,
            )
        }
    }

    private companion object {
        const val TAG = "LiteRtLmEngine"

        /**
         * Whether speculative decoding is still worth asking for in this process.
         *
         * Process-wide rather than per-engine because the flag it drives is: `ExperimentalFlags` is
         * a singleton read by the runtime at engine initialisation, so two engines cannot hold
         * different answers anyway. Cleared the first time a load fails with it on.
         */
        @Volatile
        var speculativeDecodingUsable = true

        val OOM_MARKERS = listOf(
            "out of memory", "oom", "alloc", "bad_alloc", "insufficient", "resource_exhausted",
        )

        /**
         * The AAR ships its own .so, so if the class loads at all the native side is present.
         * Touching the class is enough to find out, and it fails soft rather than crashing the
         * process on a device with the wrong ABI.
         */
        val nativeLibraryPresent: Boolean by lazy {
            runCatching { Class.forName("com.google.ai.edge.litertlm.Engine") }.isSuccess
        }

        /** A streamed [Message] carries its chunk as text parts; concatenate them. */
        fun Message.text(): String = contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
    }
}
