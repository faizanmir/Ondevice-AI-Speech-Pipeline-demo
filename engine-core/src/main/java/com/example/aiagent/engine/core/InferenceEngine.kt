package com.example.aiagent.engine.core

import kotlinx.coroutines.flow.Flow

/** Stable identifier for a pluggable inference backend. */
enum class EngineId(val slug: String) {
    LITE_RT_LM("litertlm"),
    LLAMA_CPP("llamacpp"),
    MNN("mnn"),
    AICORE("aicore"),
}

/** Static description of what an engine can do. Safe to read without the engine being loaded. */
data class EngineDescriptor(
    val id: EngineId,
    val displayName: String,
    val vendor: String,
    val supportedFormats: Set<ModelFormat>,
    val supportedAccelerators: Set<Accelerator>,
    val supportsVision: Boolean,
    val blurb: String,
) {
    fun canLoad(format: ModelFormat): Boolean = format in supportedFormats
}

/**
 * Whether an engine can actually run on *this* device+build right now. An engine can be compiled
 * in but unavailable -- llama.cpp reports [Unavailable] when its native library was excluded from
 * the build (see the `enableLlamaCpp` Gradle property).
 */
sealed interface EngineAvailability {
    data object Available : EngineAvailability
    data class Unavailable(val reason: String) : EngineAvailability
}

data class SamplingParams(
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    /**
     * RNG seed for sampling. [SEED_RANDOM] (the default) tells the engine to draw a fresh random
     * seed for each model load -- and, where the runtime supports it, each conversation reset -- so
     * two chats given the same prompt do not replay the identical token stream. Any other value
     * makes generation reproducible, which is what tests and evaluations want.
     */
    val seed: Int = SEED_RANDOM,
    /**
     * Hard cap on the tokens generated in one reply. [UNLIMITED] (the default) lets the model run to
     * its own end-of-sequence or until the context fills. Enforced uniformly across engines by
     * [OutputGuard], because no two native runtimes expose the same control.
     */
    val maxOutputTokens: Int = UNLIMITED,
    /**
     * Strings that end a reply the instant the model emits one -- the text up to the match is kept,
     * the match and everything after are dropped. Also enforced by [OutputGuard], matched across
     * streamed chunk boundaries. Empty = no stop strings.
     */
    val stopSequences: List<String> = emptyList(),
) {
    /**
     * Argmax decoding, which is the only setting that makes output genuinely repeatable.
     *
     * A fixed [seed] alone is not enough: it makes the *draws* repeatable, but the sampler still
     * draws, so the run only matches if every logit before it matched bit-for-bit too. Any numeric
     * drift -- a GPU kernel reducing in a different order, a fallback to a different accelerator --
     * flips one token, and from there the two runs diverge completely.
     *
     * [topK] = 1 removes the draw itself: one candidate means the highest-probability token wins by
     * construction, with no RNG consulted at all. [temperature] and [topP] then cannot change the
     * outcome -- scaling logits does not reorder them, and a nucleus over a one-element set is that
     * element -- so they are pinned to neutral values rather than 0, which some native samplers
     * divide by. The seed is fixed as well, so engines that draw for other reasons stay put.
     */
    fun greedy(seed: Int) = copy(temperature = 1f, topK = 1, topP = 1f, seed = seed)

    companion object {
        /** Sentinel: the engine picks a random seed instead of a reproducible one. */
        const val SEED_RANDOM = 0

        /** Sentinel for [maxOutputTokens]: no cap. */
        const val UNLIMITED = 0
    }
}

data class LoadRequest(
    val modelPath: String,
    val accelerator: Accelerator,
    val contextTokens: Int,
    val sampling: SamplingParams = SamplingParams(),
    val systemPrompt: String? = null,
    /** Speeds up second and subsequent loads of the same model. */
    val cacheDir: String? = null,
    /** Required by LiteRT-LM's NPU backend; ignored elsewhere. */
    val nativeLibraryDir: String? = null,
    /**
     * Prior turns to seed the conversation with when reopening a persisted chat, so the model
     * continues with context instead of starting blank. Already trimmed to fit (see [ContextWindow]).
     * Empty for a fresh chat.
     */
    val initialHistory: List<HistoryTurn> = emptyList(),
    /**
     * CPU decode threads for the engines that run on the CPU (llama.cpp, MNN). [AUTO] (the default)
     * lets the engine choose from the core count. Ignored by GPU/NPU engines.
     */
    val threadCount: Int = AUTO,
) {
    companion object {
        /** Sentinel for [threadCount]: let the engine decide. */
        const val AUTO = 0
    }
}

sealed interface GenerationEvent {
    /** One incremental chunk of the response. Not necessarily a whole token. */
    data class Token(val text: String) : GenerationEvent

    data class Complete(val stats: GenerationStats) : GenerationEvent
}

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val timeToFirstTokenMs: Long,
    val totalMs: Long,
    /**
     * Decode speed as measured by the runtime itself. LiteRT-LM reports this; llama.cpp does not,
     * so it stays null there and [tokensPerSecond] falls back to wall-clock. Prefer the engine's
     * own number when it has one -- it excludes the JNI and Flow-collection overhead that
     * wall-clock timing wrongly attributes to the model.
     */
    val reportedTokensPerSecond: Double? = null,
) {
    /** Decode speed, excluding prefill -- the number users actually compare between models. */
    val tokensPerSecond: Double
        get() = reportedTokensPerSecond ?: run {
            val decodeMs = totalMs - timeToFirstTokenMs
            if (decodeMs <= 0 || generatedTokens <= 0) 0.0
            else generatedTokens * 1000.0 / decodeMs
        }
}

sealed class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotAvailable(reason: String) : EngineException(reason)
    class UnsupportedFormat(format: ModelFormat, engine: String) :
        EngineException("$engine cannot load ${format.label} models")

    class LoadFailed(message: String, cause: Throwable? = null) : EngineException(message, cause)
    class OutOfMemory(message: String, cause: Throwable? = null) : EngineException(message, cause)
    class NotLoaded : EngineException("No model is loaded")
    class GenerationFailed(message: String, cause: Throwable? = null) :
        EngineException(message, cause)
}

/**
 * A swappable local-inference backend.
 *
 * Implementations wrap a native runtime (LiteRT-LM, llama.cpp, ...) behind one API so the chat
 * layer never learns which one it is talking to. Contract:
 *
 *  - [load] is expensive (seconds) and MUST be called off the main thread.
 *  - Exactly one model is resident at a time. [load] on an already-loaded engine replaces it.
 *  - [generate] is cold: nothing runs until the returned flow is collected. Collecting it twice
 *    concurrently is undefined -- callers serialize turns.
 *  - Conversation history is owned by the engine, so multi-turn works without re-sending the
 *    transcript. [resetConversation] clears it without paying the [load] cost again.
 *  - [unload] must be called to release native memory; it is not reclaimed by the GC.
 */
interface InferenceEngine {

    val descriptor: EngineDescriptor

    /** Path of the currently resident model, or null if none. */
    val loadedModelPath: String?

    /**
     * The accelerator the model *actually* loaded on, which is not always the one that was asked
     * for -- engines fall back down to the CPU when a GPU or NPU turns out to be unusable. The UI
     * shows this rather than the request, because telling a user they are on the GPU when they are
     * not makes the speed they observe inexplicable.
     */
    val activeAccelerator: Accelerator?

    /** Cheap enough to call on the main thread; used to grey out engines in the picker. */
    fun availability(): EngineAvailability

    /** Loads [request] into memory. Blocking and slow -- call from [kotlinx.coroutines.Dispatchers.IO]. */
    suspend fun load(request: LoadRequest)

    /** Streams a response. Emits [GenerationEvent.Token] repeatedly, then exactly one [GenerationEvent.Complete]. */
    fun generate(prompt: String): Flow<GenerationEvent>

    /** Interrupts an in-flight [generate]. No-op when idle. */
    fun cancel()

    /**
     * Whether [cancel] may be called *from inside* a [generate] collector.
     *
     * llama.cpp can: cancelling sets a flag its pull loop checks, the loop ends, and the flow
     * completes normally. LiteRT-LM cannot: its cancel surfaces a CANCELLED error through the
     * runtime's own callback, which cancels the collecting coroutine -- and a caller collecting
     * inside a WorkManager job loses the whole job, not just the turn. Its engine says as much in
     * its own comments, and enforces its output limits by ignoring the rest of the stream instead.
     *
     * So a caller that wants to stop a turn early must ask first. False by default, because "this
     * runtime has no safe interruptible cancel" is the assumption that cannot break anything.
     */
    val supportsMidTurnCancel: Boolean get() = false

    /** Drops conversation history but keeps the model resident. */
    suspend fun resetConversation()

    /**
     * Same guarantee as [resetConversation] -- the next prompt is independent of everything before
     * it -- but the engine may keep prefill state the next prompt would recompute identically.
     *
     * For a batch of self-contained prompts sharing a long fixed preamble (the audit pipeline), that
     * is the difference between paying for the preamble once and paying for it on every chunk. Only
     * meaningful where the engine diffs an incoming prompt against what it already decoded; the
     * default is a full reset, so an engine without that machinery is simply unaffected.
     */
    suspend fun resetKeepingPrefixCache() = resetConversation()

    /** Number of tokens currently held in the KV cache. Used to warn before context overflow. */
    fun contextTokensUsed(): Int

    /** Releases the model and all native memory. Idempotent. */
    suspend fun unload()
}
