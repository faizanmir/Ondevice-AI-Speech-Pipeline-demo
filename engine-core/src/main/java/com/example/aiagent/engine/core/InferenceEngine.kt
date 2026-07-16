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
) {
    companion object {
        /** Sentinel: the engine picks a random seed instead of a reproducible one. */
        const val SEED_RANDOM = 0
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
)

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

    /** Drops conversation history but keeps the model resident. */
    suspend fun resetConversation()

    /** Number of tokens currently held in the KV cache. Used to warn before context overflow. */
    fun contextTokensUsed(): Int

    /** Releases the model and all native memory. Idempotent. */
    suspend fun unload()
}
