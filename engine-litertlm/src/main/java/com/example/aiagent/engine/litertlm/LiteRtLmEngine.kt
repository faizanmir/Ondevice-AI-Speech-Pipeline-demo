package com.example.aiagent.engine.litertlm

import android.util.Log
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.AudioClip
import com.example.aiagent.engine.core.AudioInputEngine
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
import com.example.aiagent.engine.core.NativeToolEngine
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
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
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
class LiteRtLmEngine : InferenceEngine, NativeToolEngine, AudioInputEngine {

    override val descriptor = EngineDescriptor(
        id = EngineId.LITE_RT_LM,
        displayName = "LiteRT-LM",
        vendor = "Google AI Edge",
        supportedFormats = setOf(ModelFormat.LITERTLM),
        supportedAccelerators = setOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU),
        supportsVision = true,
        supportsNativeTools = true,
        supportsAudioInput = true,
        blurb = "Google's on-device runtime. GPU and NPU accelerated, memory-maps weights so it " +
            "uses far less RAM than the file size suggests.",
    )

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationConfig: ConversationConfig = ConversationConfig()

    /**
     * What the resident [Engine] was built from, or null when nothing is loaded.
     *
     * Only the fields that go into `EngineConfig`. Everything else in a [LoadRequest] -- the system
     * prompt, restored history, tools, sampling -- belongs to the conversation, and changing it does
     * not justify reading the weights again.
     */
    private var residentKey: EngineKey? = null

    /** The part of a [LoadRequest] that decides whether the loaded model can be kept. */
    private data class EngineKey(
        val modelPath: String,
        val accelerator: Accelerator,
        val contextTokens: Int,
        val cacheDir: String?,
        /** Only the NPU backend reads it, and it never varies in a process -- keyed on anyway, so
         *  the key is provably every input to EngineConfig rather than nearly every one. */
        val nativeLibraryDir: String?,
        /**
         * Whether the audio executor was built. In the key because it IS an EngineConfig input, and
         * leaving it out would let a text-only resident engine be handed back for a transcription --
         * which fails at the first slice, not at load, with an error about a null audio executor.
         */
        val audioInput: Boolean,
    ) {
        constructor(request: LoadRequest) : this(
            modelPath = request.modelPath,
            // The accelerator ASKED FOR, not the one the ladder settled on. Both readings satisfy
            // the case this field exists for -- a GPU request that fell back to the CPU is served by
            // the resident CPU engine, because the next GPU request builds the same key -- but only
            // this one still notices the user changing the setting. Keying on the accelerator in use
            // made the field self-comparing: it was read off the resident engine on both sides, so
            // it always matched, and switching GPU to CPU silently kept the GPU engine.
            accelerator = request.accelerator,
            contextTokens = request.contextTokens,
            cacheDir = request.cacheDir,
            nativeLibraryDir = request.nativeLibraryDir,
            audioInput = request.audioInput,
        )
    }

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
            // Off by default in the runtime, which is why getBenchmarkInfo() has been coming back
            // empty: generate() then reports 0 prompt tokens and falls back to wall-clock timing for
            // prefill. Those counters are the only ground truth this app has for what a prompt
            // actually tokenises to -- AuditDrainWorker.logEstimateDrift disables itself without
            // them, so every chunk budget goes unverified -- and turning them on costs a few
            // counters in the runtime's own loop. Set before reuseResidentEngine, so a conversation
            // rebuilt on a resident engine gets it too.
            ExperimentalFlags.enableBenchmark = true

            if (reuseResidentEngine(request)) return@withLock

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
     * Rebuilds just the conversation when the resident engine already holds this exact model.
     *
     * The whole point of keeping a model resident is undone if every chat pays `Engine.initialize()`
     * again, and most of what a chat changes is not engine-level at all: opening a second chat on
     * the same model changes nothing but the conversation, and resuming one changes the system
     * prompt and seeds some history. Both used to cost a full multi-second reload of weights that
     * were already in memory.
     *
     * Returns false when the request needs a different engine -- another model, another accelerator,
     * a different context size -- leaving the caller to load properly.
     *
     * Caller must hold [lifecycleLock].
     */
    private fun reuseResidentEngine(request: LoadRequest): Boolean {
        val activeEngine = engine ?: return false
        val accelerator = activeAccelerator ?: return false
        if (residentKey != EngineKey(request)) return false

        return try {
            maxOutputTokens = request.sampling.maxOutputTokens
            stopSequences = request.sampling.stopSequences

            runCatching { conversation?.close() }
                .onFailure { Log.w(TAG, "closing conversation failed", it) }
            conversationConfig = conversationConfigFor(accelerator, request)
            conversation = activeEngine.createConversation(conversationConfig)

            Log.i(TAG, "reused the resident engine; rebuilt the conversation only")
            true
        } catch (t: Throwable) {
            // The engine is still initialised but now has no usable conversation, so it cannot be
            // left as-is. Drop it and report failure; the caller loads from scratch.
            Log.w(TAG, "could not rebuild the conversation; falling back to a full load", t)
            unloadLocked()
            false
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
                // The audio encoder is a separate executor with its own backend, and the runtime
                // only builds it when this is set. Left null, a multimodal model loads perfectly,
                // reports no error, and then rejects the first clip with "Audio executor should not
                // be null, please TryLoadingAudioExecutor() first" -- at generation time, inside a
                // background transcription, long after the load that could have reported it.
                //
                // Deliberately the same backend as the decoder rather than pinned to the CPU: the
                // ladder in loadOnBestAccelerator has already established that this accelerator
                // works for this model, and splitting the two would mean a GPU decoder feeding a
                // CPU encoder for no reason. If the audio executor cannot build here, the whole
                // load throws and the ladder steps down together.
                audioBackend = if (request.audioInput) {
                    accelerator.toBackend(request.nativeLibraryDir)
                } else {
                    null
                },
                maxNumTokens = request.contextTokens,
                cacheDir = request.cacheDir,
            ),
        )

        try {
            newEngine.initialize()

            conversationConfig = conversationConfigFor(accelerator, request)
            conversation = newEngine.createConversation(conversationConfig)
            engine = newEngine
            residentKey = EngineKey(request)
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        }
    }

    /**
     * Everything that is settled per *conversation* rather than per engine.
     *
     * The split matters: `Engine.initialize()` reads the weights and takes seconds, while a
     * conversation is minted from an initialised engine almost for free. Keeping the two apart is
     * what lets [load] swap a system prompt, a restored history or a tool list without paying for
     * the model again.
     */
    private fun conversationConfigFor(
        accelerator: Accelerator,
        request: LoadRequest,
    ): ConversationConfig = ConversationConfig(
                systemInstruction = request.systemPrompt?.let { Contents.of(it) },
                // Declared to the runtime as schemas, and left on `automaticToolCalling` (the
                // default): LiteRT-LM emits the call, runs the tool through the adapter below, and
                // feeds the result back itself, all inside one sendMessageAsync. The app never sees
                // a half-finished turn, and the chat layer does not need a hop loop the way the
                // prompt protocol does.
                tools = request.tools.map { definition ->
                    tool(AppFunctionTool(definition, this))
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

    override fun generate(prompt: String): Flow<GenerationEvent> = stream(Contents.of(prompt))

    /**
     * The audio-in overload. The clip goes *before* the instruction so the prompt reads as being
     * about it, which is how the model was trained and how Google's own samples order the parts.
     *
     * The bytes are handed over whole and decoded inside the runtime by miniaudio, so a WAV's header
     * is what tells it the sample rate and channel count -- there is no separate place to declare
     * them, and raw PCM would be read as a corrupt file rather than as audio.
     *
     * No clip-length check here. The runtime enforces its own ceiling on how much audio one turn can
     * carry, and where that ceiling sits depends on the model bundle rather than on this class; the
     * caller that splits a recording is the one that knows which model it is talking to.
     */
    override fun generate(prompt: String, audio: AudioClip): Flow<GenerationEvent> =
        stream(Contents.of(Content.AudioBytes(audio.bytes), Content.Text(prompt)))

    // getBenchmarkInfo() is @ExperimentalApi. We opt in rather than time the Flow ourselves: the
    // runtime's own prefill/decode counters are the honest numbers, and if the API is withdrawn the
    // runCatching below already degrades to wall-clock timing.
    @OptIn(ExperimentalApi::class)
    private fun stream(contents: Contents): Flow<GenerationEvent> = flow {
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
        // Reset per turn, and published only when the turn ends: a caller asking mid-stream would be
        // reading a question this turn has not answered yet.
        var schemaBound = false

        try {
            activeConversation.sendMessageAsync(contents).collect { message ->
                // Once a bound is hit we keep the output trimmed and ignore the rest. The runtime has
                // no interruptible cancel we can call mid-collect without risking a spurious
                // cancellation, so it decodes to its own stop -- correct output, a little wasted work.
                if (stopped) return@collect
                // A schema-constrained reply arrives as a "call" rather than as prose, because a
                // tool description is the only way to hand this runtime a schema. Its arguments ARE
                // the answer, so they are rendered back into the JSON object the schema describes
                // and emitted as text -- a collector never learns which shape the reply took.
                //
                // Arriving through that channel at all is also the only evidence available that the
                // constraint bound: a runtime that ignored the schema answers as ordinary text.
                if (message.toolCalls.isNotEmpty()) schemaBound = true
                val text = message.text().ifEmpty { message.toolCalls.joinToString("") { it.asJson() } }
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
        lastReplyWasSchemaBound = schemaBound

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
                    reportedPrefillTokensPerSecond = bench?.lastPrefillTokensPerSecond
                        ?.takeIf { it > 0.0 },
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        runCatching { conversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelProcess failed", it) }
    }

    override val supportsResponseSchema: Boolean get() = true

    /**
     * Observed, not requested -- see the interface. Volatile because the runtime's decode thread
     * sets it and the caller reads it from its own.
     */
    @Volatile
    override var lastReplyWasSchemaBound: Boolean = false
        private set

    /**
     * Declares a single tool whose parameters are [schema] and turns LLGuidance on, so the runtime
     * can only sample a reply that satisfies it.
     *
     * The tool is a vehicle, not a tool. LiteRT-LM 0.14 embeds LLGuidance -- Lark grammars, regex
     * and JSON Schema all -- but exposes no way to hand it a grammar directly; a tool description is
     * the only public surface that reaches it. So the "call" the model makes IS the answer, and
     * [generate] renders its arguments straight back out as JSON text. Nothing is executed:
     * `automaticToolCalling` is off, because the runtime running a tool and feeding the result back
     * would spend another prefill on a hop that has nothing to do.
     *
     * `enableConversationConstrainedDecoding` is process-wide and read when a conversation is
     * created, so it is set here and the conversation rebuilt -- which is also what puts the tool in
     * front of the model, since tools belong to a conversation and not to a turn.
     *
     * Known limitation, from the runtime's own diagnostics: constrained decoding is supported for
     * SentencePiece tokenizers only. On a BPE model (Qwen among them) the flag is accepted and the
     * constraint simply never binds, which is why the caller logs what it got rather than assuming.
     */
    @OptIn(ExperimentalApi::class)
    override suspend fun setResponseSchema(schema: String?): Boolean = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val activeEngine = engine ?: return@withLock false
            try {
                ExperimentalFlags.enableConversationConstrainedDecoding = schema != null
                conversationConfig = conversationConfig.copy(
                    tools = if (schema == null) {
                        emptyList()
                    } else {
                        listOf(tool(SchemaTool(schema)))
                    },
                    automaticToolCalling = false,
                )
                runCatching { conversation?.close() }
                    .onFailure { Log.w(TAG, "closing conversation failed", it) }
                conversation = activeEngine.createConversation(conversationConfig)
                true
            } catch (t: Throwable) {
                // A conversation that failed to rebuild leaves nothing to generate with, so put the
                // unconstrained one back rather than reporting a failure and leaving a dead engine.
                Log.w(TAG, "could not apply the response schema", t)
                ExperimentalFlags.enableConversationConstrainedDecoding = false
                conversationConfig = conversationConfig.copy(tools = emptyList())
                conversation = runCatching { activeEngine.createConversation(conversationConfig) }
                    .getOrNull()
                false
            }
        }
    }

    /**
     * The schema carrier. Its `execute` is never called -- `automaticToolCalling` is off -- and it
     * exists only because a tool description is the one place this API accepts a JSON Schema.
     */
    private class SchemaTool(private val descriptionJson: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson

        override fun execute(arguments: String): String = "{}"
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
        residentKey = null
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

        /**
         * A schema-constrained answer, rendered back into the JSON object its schema describes.
         *
         * The runtime hands the arguments back as a decoded map, so the JSON it was sampled from is
         * gone by the time this sees it and has to be written again. Gson rather than a hand-rolled
         * writer: the values came from the document and contain the quotes, newlines and backslashes
         * that a hand-rolled writer gets wrong, and this text is about to be parsed.
         */
        fun ToolCall.asJson(): String = Gson().toJson(arguments)
    }
}
