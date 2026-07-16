package com.example.aiagent.engine.mnn

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
 * [InferenceEngine] backed by Alibaba's MNN, compiled from source by the NDK (see CMakeLists.txt).
 *
 * MNN's LLM runtime is the third leg of the model story: LiteRT-LM runs Google's curated builds,
 * llama.cpp runs the GGUF world, and MNN runs the exports Alibaba publishes for its own model
 * families (Qwen above all), with CPU kernels tuned harder for phones than either.
 *
 * Unlike llama.cpp, MNN natively understands a *conversation*: the JNI layer holds the transcript
 * as (role, content) messages and MNN renders them through the model's own chat template, reusing
 * the KV cache incrementally across turns via its prompt cache. So this class owns almost no
 * state -- the session handle is the conversation.
 *
 * Two honest limitations, both of MNN's config surface rather than this integration:
 * [com.example.aiagent.engine.core.SamplingParams.seed] is ignored (MNN's sampler has no seed
 * setting), and generation is CPU-only (see CMakeLists.txt for why no GPU backend is compiled in).
 */
class MnnEngine : InferenceEngine {

    override val descriptor = EngineDescriptor(
        id = EngineId.MNN,
        displayName = "MNN",
        vendor = "Alibaba",
        supportedFormats = setOf(ModelFormat.MNN),
        supportedAccelerators = setOf(Accelerator.CPU),
        supportsVision = false,
        blurb = "Alibaba's mobile inference engine, with CPU kernels built for phones. Runs the " +
            "MNN exports Alibaba publishes on HuggingFace -- the Qwen family's home turf.",
    )

    private var handle: Long = 0L

    @Volatile
    override var loadedModelPath: String? = null
        private set

    @Volatile
    override var activeAccelerator: Accelerator? = null
        private set

    private val lifecycleLock = Mutex()

    override fun availability(): EngineAvailability {
        val error = MnnNative.loadError
        return if (error == null) {
            EngineAvailability.Available
        } else {
            EngineAvailability.Unavailable(error)
        }
    }

    override suspend fun load(request: LoadRequest) = withContext(Dispatchers.IO) {
        (availability() as? EngineAvailability.Unavailable)?.let {
            throw EngineException.NotAvailable(it.reason)
        }

        lifecycleLock.withLock {
            unloadLocked()

            val newHandle = MnnNative.nativeCreateSession(
                configPath = request.modelPath,
                nCtx = request.contextTokens,
                nThreads = recommendedThreadCount(),
                temperature = request.sampling.temperature,
                topK = request.sampling.topK,
                topP = request.sampling.topP,
                cacheDir = request.cacheDir.orEmpty(),
            )

            if (newHandle == 0L) {
                // MNN reports load failure as a bare false, without distinguishing "file is
                // corrupt or incomplete" from "could not allocate". Memory is the likelier cause
                // on a phone, so the message leads with it while admitting the other.
                throw EngineException.LoadFailed(
                    "MNN could not load ${request.modelPath.substringAfterLast('/')}. " +
                        "There may not be enough free memory, or the model files may be corrupt.",
                )
            }

            // Seed the native transcript: system prompt first, then any restored turns. MNN
            // renders these through the model's chat template on the first ingest, so a reopened
            // chat continues with its context instead of starting blank.
            val roles = mutableListOf<String>()
            val contents = mutableListOf<String>()
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                roles += ROLE_SYSTEM
                contents += it
            }
            request.initialHistory.forEach {
                roles += it.role
                contents += it.content
            }
            if (roles.isNotEmpty()) {
                MnnNative.nativeSeedHistory(newHandle, roles.toTypedArray(), contents.toTypedArray())
            }

            handle = newHandle
            loadedModelPath = request.modelPath
            activeAccelerator = Accelerator.CPU
        }
    }

    override fun generate(prompt: String): Flow<GenerationEvent> = flow {
        val session = handle
        if (session == 0L) throw EngineException.NotLoaded()

        val startMs = System.currentTimeMillis()
        val promptTokens = MnnNative.nativeIngestPrompt(session, prompt)
        if (promptTokens < 0) throw promptTokens.asIngestError()

        var firstTokenMs = 0L
        var generated = 0

        while (true) {
            // Honour coroutine cancellation, not just an explicit cancel() call: if the collector
            // goes away the native decode loop must stop too. The JNI side folds whatever was
            // already decoded into the transcript on the next ingest, so nothing is corrupted.
            currentCoroutineContext().ensureActive()

            val piece = MnnNative.nativeNextToken(session) ?: break

            if (piece.isEmpty()) continue // half a UTF-8 codepoint; wait for the rest

            if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
            generated++
            emit(GenerationEvent.Token(piece))
        }

        emit(
            GenerationEvent.Complete(
                GenerationStats(
                    promptTokens = promptTokens,
                    generatedTokens = generated,
                    timeToFirstTokenMs = firstTokenMs,
                    totalMs = System.currentTimeMillis() - startMs,
                    // MNN times its own decode loop, which excludes the JNI overhead that
                    // wall-clock timing would wrongly attribute to the model.
                    reportedTokensPerSecond = MnnNative.nativeDecodeTokensPerSecond(session)
                        .takeIf { it > 0.0 },
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        val session = handle
        if (session != 0L) MnnNative.nativeCancel(session)
    }

    override suspend fun resetConversation() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val session = handle
            if (session == 0L) throw EngineException.NotLoaded()
            MnnNative.nativeResetContext(session)
        }
    }

    override fun contextTokensUsed(): Int {
        val session = handle
        return if (session == 0L) 0 else MnnNative.nativeContextUsed(session)
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock { unloadLocked() }
    }

    private fun unloadLocked() {
        val session = handle
        if (session != 0L) MnnNative.nativeFreeSession(session)
        handle = 0L
        loadedModelPath = null
        activeAccelerator = null
    }

    private fun Int.asIngestError(): EngineException = when (this) {
        MnnNative.ERR_CONTEXT_FULL -> EngineException.GenerationFailed(
            "This conversation has outgrown the model's context window. Start a new chat.",
        )
        MnnNative.ERR_NO_SESSION -> EngineException.NotLoaded()
        else -> EngineException.GenerationFailed("MNN failed to process the prompt")
    }

    /**
     * Same reasoning as llama.cpp's: leave headroom rather than taking every core, because
     * saturating a big.LITTLE phone pulls in the little cores and the batch waits on them.
     */
    private fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

    private companion object {
        const val ROLE_SYSTEM = "system"
    }
}
