package com.example.aiagent.engine.llamacpp

import android.util.Log
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineAvailability
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.EngineException
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.GenerationEvent
import com.example.aiagent.engine.core.GenerationStats
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.OutputGuard
import com.example.aiagent.engine.core.SamplingParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [InferenceEngine] backed by llama.cpp, compiled from source by the NDK (see CMakeLists.txt).
 *
 * This is the open half of the app's model story: LiteRT-LM runs Google's curated `.litertlm`
 * models fast, and llama.cpp runs anything anyone has ever quantised to GGUF. Keeping both behind
 * one interface is the whole point -- the chat layer cannot tell them apart.
 *
 * Unlike LiteRT-LM, llama.cpp has no notion of a conversation: it takes a flat token sequence. So
 * this class owns the transcript and re-renders it through the model's own chat template on every
 * turn. The KV cache still makes that cheap -- llama.cpp reuses the cached prefix rather than
 * re-prefilling the whole history.
 */
class LlamaCppEngine : InferenceEngine {

    /** The GPU we can offload to, or null. Decided once, from what ggml actually enumerated. */
    private val gpuName: String? = LlamaNative.gpuDeviceName

    override val descriptor = EngineDescriptor(
        id = EngineId.LLAMA_CPP,
        displayName = "llama.cpp",
        vendor = "ggml-org",
        supportedFormats = setOf(ModelFormat.GGUF),
        // GPU is advertised only when ggml enumerated a real Vulkan device. Claiming it
        // unconditionally would make the memory estimates lie: the fit calculation charges far less
        // RAM to the app on the GPU path, because the weights live in GPU memory instead.
        supportedAccelerators = if (gpuName != null) {
            setOf(Accelerator.CPU, Accelerator.GPU)
        } else {
            setOf(Accelerator.CPU)
        },
        supportsVision = false,
        blurb = if (gpuName != null) {
            "The open-source standard. Runs any GGUF model, with Vulkan GPU offload on $gpuName " +
                "and the widest community model catalogue of any runtime."
        } else {
            "The open-source standard. Runs any GGUF model, CPU-only on this device, with the " +
                "widest community model catalogue of any runtime."
        },
    )

    private var handle: Long = 0L
    private val transcript = mutableListOf<ChatTurn>()
    private var systemPrompt: String? = null

    /**
     * What the resident native session was created from, or null when nothing is loaded.
     *
     * Only what `nativeCreateSession` bakes in. The system prompt, the restored transcript and the
     * output bounds are plain fields on this class, so changing them does not need a new session --
     * and a new session means reading the whole GGUF again.
     */
    private var residentKey: SessionKey? = null

    /** The part of a [LoadRequest] that decides whether the loaded model can be kept. */
    private data class SessionKey(
        val modelPath: String,
        val contextTokens: Int,
        val threadCount: Int,
        val accelerator: Accelerator,
        val sampling: SamplingParams,
    ) {
        constructor(request: LoadRequest) : this(
            modelPath = request.modelPath,
            contextTokens = request.contextTokens,
            threadCount = request.threadCount,
            // What was ASKED FOR, not what the ladder settled on. Both readings serve a GPU request
            // that fell back to the CPU -- the next GPU request builds the same key and reuses the
            // resident CPU session -- but only this one still notices the user changing the setting.
            // Keying on the accelerator in use made the field self-comparing: it was read off the
            // resident session on both sides, so it always matched, and switching GPU to CPU
            // silently kept the GPU session.
            accelerator = request.accelerator,
            // Sampling is baked into the session here, unlike LiteRT-LM where it is per
            // conversation -- so a changed temperature really does need a new one.
            sampling = request.sampling.copy(maxOutputTokens = 0, stopSequences = emptyList()),
        )
    }

    // Caller-facing generation bounds, applied by an OutputGuard in generate(). Captured at load
    // because generate() gets only the prompt, not the request.
    private var maxOutputTokens: Int = 0
    private var stopSequences: List<String> = emptyList()

    @Volatile
    override var loadedModelPath: String? = null
        private set

    @Volatile
    override var activeAccelerator: Accelerator? = null
        private set

    private val lifecycleLock = Mutex()

    override fun availability(): EngineAvailability {
        val error = LlamaNative.loadError
        return if (error == null) {
            EngineAvailability.Available
        } else {
            EngineAvailability.Unavailable(error)
        }
    }

    /**
     * Keeps the loaded model when only conversation-level things changed.
     *
     * Opening a second chat on the resident model changes nothing the native session cares about;
     * resuming one changes the system prompt and the transcript. Neither is a reason to read a
     * multi-gigabyte GGUF again, which is what a full load costs.
     *
     * A [SamplingParams.SEED_RANDOM] session is not re-seeded here -- the existing RNG stream simply
     * carries on, which serves the same purpose the redraw does: two chats on one model do not
     * replay the same tokens.
     *
     * Caller must hold [lifecycleLock].
     */
    private fun reuseResidentSession(request: LoadRequest): Boolean {
        if (handle == 0L) return false
        if (activeAccelerator == null) return false
        if (residentKey != SessionKey(request)) return false

        systemPrompt = request.systemPrompt
        maxOutputTokens = request.sampling.maxOutputTokens
        stopSequences = request.sampling.stopSequences
        transcript.clear()
        request.initialHistory.forEach { transcript += ChatTurn(it.role, it.content) }
        LlamaNative.nativeResetContext(handle)

        Log.i(TAG, "reused the resident session; only the transcript changed")
        return true
    }

    override suspend fun load(request: LoadRequest) = withContext(Dispatchers.IO) {
        (availability() as? EngineAvailability.Unavailable)?.let {
            throw EngineException.NotAvailable(it.reason)
        }

        lifecycleLock.withLock {
            if (reuseResidentSession(request)) return@withLock
            unloadLocked()

            // Only try the GPU if one was actually enumerated. NPU falls to GPU, GPU falls to CPU:
            // Vulkan can fail at model-load time even when the device exists (out of VRAM on a
            // model that is too big for it, or a driver that enumerates but cannot run the shaders),
            // and dropping to the CPU is always better than refusing to load.
            val wantsGpu = request.accelerator != Accelerator.CPU && gpuName != null
            val ladder = if (wantsGpu) {
                listOf(Accelerator.GPU, Accelerator.CPU)
            } else {
                listOf(Accelerator.CPU)
            }

            for (accelerator in ladder) {
                val newHandle = LlamaNative.nativeCreateSession(
                    modelPath = request.modelPath,
                    nCtx = request.contextTokens,
                    nThreads = request.threadCount.takeIf { it > 0 }?.coerceIn(1, MAX_THREADS)
                        ?: recommendedThreadCount(),
                    // -1 offloads every layer. Partial offload is possible but a poor deal on a
                    // phone: the layers left on the CPU become the bottleneck for every token, so a
                    // half-offloaded model is often slower than an all-CPU one.
                    nGpuLayers = if (accelerator == Accelerator.GPU) ALL_LAYERS else 0,
                    temperature = request.sampling.temperature,
                    topK = request.sampling.topK,
                    topP = request.sampling.topP,
                    seed = if (request.sampling.seed == SamplingParams.SEED_RANDOM) {
                        LLAMA_SEED_RANDOM
                    } else {
                        request.sampling.seed
                    },
                )

                if (newHandle != 0L) {
                    handle = newHandle
                    systemPrompt = request.systemPrompt
                    maxOutputTokens = request.sampling.maxOutputTokens
                    stopSequences = request.sampling.stopSequences
                    // Seed the transcript with any restored history. llama.cpp re-renders the whole
                    // transcript through the chat template each turn, so prior turns become context
                    // for free -- the first generate() prefills them, and the KV cache carries them.
                    transcript.clear()
                    request.initialHistory.forEach { transcript += ChatTurn(it.role, it.content) }
                    loadedModelPath = request.modelPath
                    activeAccelerator = accelerator
                    residentKey = SessionKey(request)
                    if (accelerator == Accelerator.CPU && wantsGpu) {
                        Log.w(TAG, "GPU offload failed for this model, fell back to CPU")
                    }
                    return@withLock
                }

                Log.w(TAG, "llama.cpp could not load on ${accelerator.label}")
            }

            // llama.cpp returns a null model pointer for both "file is corrupt" and "could not
            // allocate", without distinguishing them. Memory is by far the likelier cause on a
            // phone, so the message leads with it while admitting the other.
            throw EngineException.LoadFailed(
                "llama.cpp could not load ${request.modelPath.substringAfterLast('/')}. " +
                    "There may not be enough free memory, or the file may be corrupt.",
            )
        }
    }

    override fun generate(prompt: String): Flow<GenerationEvent> = flow {
        val session = handle
        if (session == 0L) throw EngineException.NotLoaded()

        transcript += ChatTurn(role = ROLE_USER, content = prompt)

        val formatted = renderPrompt(session)
            ?: throw EngineException.GenerationFailed(
                "This GGUF has no chat template, so the conversation cannot be formatted for it",
            )

        val startMs = System.currentTimeMillis()
        val promptTokens = LlamaNative.nativeIngestPrompt(session, formatted)
        if (promptTokens < 0) {
            transcript.removeLastOrNull()
            throw promptTokens.asIngestError()
        }

        var firstTokenMs = 0L
        var generated = 0
        val reply = StringBuilder()
        val guard = OutputGuard(maxOutputTokens, stopSequences)

        while (true) {
            // Honour coroutine cancellation, not just an explicit cancel() call: if the collector
            // goes away (user navigates off the chat) the native decode loop must stop too, or it
            // keeps burning the CPU on a response nobody will read.
            currentCoroutineContext().ensureActive()

            val piece = LlamaNative.nativeNextToken(session) ?: break

            if (piece.isEmpty()) continue // half a UTF-8 codepoint; wait for the rest

            if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
            generated++

            val out = guard.push(piece)
            if (out.isNotEmpty()) {
                reply.append(out)
                emit(GenerationEvent.Token(out))
            }
            // Hitting the token cap or a stop string ends the turn; not calling nextToken again is
            // all it takes to stop the native decode -- this is a pull loop.
            if (guard.isDone) break
        }

        // Release any tail the stop-string guard was holding back, if we reached a natural stop.
        guard.drain().takeIf { it.isNotEmpty() }?.let {
            reply.append(it)
            emit(GenerationEvent.Token(it))
        }

        transcript += ChatTurn(role = ROLE_ASSISTANT, content = reply.toString())

        emit(
            GenerationEvent.Complete(
                GenerationStats(
                    promptTokens = promptTokens,
                    generatedTokens = generated,
                    timeToFirstTokenMs = firstTokenMs,
                    totalMs = System.currentTimeMillis() - startMs,
                    // llama.cpp does not self-report throughput, so GenerationStats falls back to
                    // wall-clock. Left null deliberately rather than faked.
                    reportedTokensPerSecond = null,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        val session = handle
        if (session != 0L) LlamaNative.nativeCancel(session)
    }

    /**
     * Safe here: nativeCancel only sets a flag, nativeNextToken then returns null, and the decode
     * loop ends the turn the same way an end-of-sequence token would. Nothing throws.
     */
    override val supportsMidTurnCancel: Boolean get() = true

    override val supportsGrammar: Boolean get() = true

    /**
     * Rebuilds the sampler chain with (or without) the grammar at its head.
     *
     * Under [lifecycleLock] like every other native call that mutates the session: the chain is
     * swapped out from under whatever might be decoding, and a decode loop holding a freed sampler
     * is a crash rather than a wrong answer.
     */
    override suspend fun setGrammar(grammar: String?, triggerPattern: String?): Boolean =
        withContext(Dispatchers.IO) {
            lifecycleLock.withLock {
                val session = handle
                if (session == 0L) return@withLock false
                LlamaNative.nativeSetGrammar(session, grammar, triggerPattern).also { applied ->
                    if (grammar != null && !applied) {
                        Log.w(TAG, "grammar rejected; this turn decodes unconstrained")
                    }
                }
            }
        }

    override suspend fun resetConversation() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val session = handle
            if (session == 0L) throw EngineException.NotLoaded()
            LlamaNative.nativeResetContext(session)
            transcript.clear()
        }
    }

    /**
     * The transcript is dropped exactly as in [resetConversation], so the next prompt is rendered on
     * its own with no prior turns -- but the KV cache is left standing. nativeIngestPrompt already
     * diffs each prompt against the tokens it last decoded and evicts everything past the shared
     * prefix, so isolation comes from that diff rather than from a wholesale clear, and a repeated
     * preamble is not decoded twice.
     */
    override suspend fun resetKeepingPrefixCache() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val session = handle
            if (session == 0L) throw EngineException.NotLoaded()
            LlamaNative.nativeResetTurnKeepCache(session)
            transcript.clear()
        }
    }

    override fun contextTokensUsed(): Int {
        val session = handle
        return if (session == 0L) 0 else LlamaNative.nativeContextUsed(session)
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock { unloadLocked() }
    }

    private fun unloadLocked() {
        val session = handle
        if (session != 0L) LlamaNative.nativeFreeSession(session)
        handle = 0L
        transcript.clear()
        residentKey = null
        loadedModelPath = null
        activeAccelerator = null
    }

    /** Renders the system prompt plus the whole transcript through the GGUF's own chat template. */
    private fun renderPrompt(session: Long): String? {
        val turns = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatTurn(ROLE_SYSTEM, it)) }
            addAll(transcript)
        }
        return LlamaNative.nativeFormatChat(
            handle = session,
            roles = turns.map { it.role }.toTypedArray(),
            contents = turns.map { it.content }.toTypedArray(),
            addAssistant = true,
        )
    }

    private fun Int.asIngestError(): EngineException = when (this) {
        LlamaNative.ERR_CONTEXT_FULL -> EngineException.GenerationFailed(
            "This conversation has outgrown the model's context window. Start a new chat.",
        )
        LlamaNative.ERR_NO_SESSION -> EngineException.NotLoaded()
        else -> EngineException.GenerationFailed("llama.cpp failed to process the prompt")
    }

    /**
     * Leave headroom rather than taking every core. Phones are big.LITTLE: saturating all cores
     * pulls the little ones in, and they are slower than useless here -- the batch waits on them,
     * so throughput drops while the phone gets hot and the UI janks.
     */
    private fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

    private data class ChatTurn(val role: String, val content: String)

    private companion object {
        const val TAG = "LlamaCppEngine"

        /** Ceiling for a user-set thread override; a phone gains nothing past this. */
        const val MAX_THREADS = 16

        /** llama.cpp treats a negative n_gpu_layers as "offload all of them". */
        const val ALL_LAYERS = -1

        /**
         * As uint32 this is llama.cpp's LLAMA_DEFAULT_SEED (0xFFFFFFFF): the dist sampler draws a
         * fresh random seed when created AND on every llama_sampler_reset -- so each new chat on a
         * resident model gets its own stream, not a replay of the last one.
         */
        const val LLAMA_SEED_RANDOM = -1

        // The role strings chat templates expect; they are a Jinja convention, not ours to choose.
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
