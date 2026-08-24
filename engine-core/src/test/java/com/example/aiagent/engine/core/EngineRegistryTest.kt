package com.example.aiagent.engine.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pairing between advertising native tools and being able to run them.
 *
 * The two used to be able to come apart silently: `toolRunner` was a property on every engine with
 * a do-nothing default, so an engine could claim native tool support, be given tools to declare and
 * be handed a runner it quietly dropped. Every call the model then made failed at run time with a
 * generic "not available", and nothing pointed at the cause.
 */
class EngineRegistryTest {

    private open class TestEngine(
        nativeTools: Boolean,
        id: EngineId = EngineId.LITE_RT_LM,
    ) : InferenceEngine {
        override val descriptor = EngineDescriptor(
            id = id,
            displayName = "Test ${id.slug}",
            vendor = "test",
            supportedFormats = setOf(ModelFormat.GGUF),
            supportedAccelerators = setOf(Accelerator.CPU),
            supportsVision = false,
            supportsNativeTools = nativeTools,
            blurb = "",
        )
        override val loadedModelPath: String? = null
        override val activeAccelerator: Accelerator? = null
        override fun availability() = EngineAvailability.Available
        override suspend fun load(request: LoadRequest) = Unit
        override fun generate(prompt: String): Flow<GenerationEvent> = emptyFlow()
        override fun cancel() = Unit
        override suspend fun resetConversation() = Unit
        override fun contextTokensUsed() = 0
        override suspend fun unload() = Unit
    }

    private class RunnableEngine : TestEngine(nativeTools = true), NativeToolEngine {
        override var toolRunner: ToolRunner? = null
    }

    @Test
    fun `an engine that declares native tools but cannot run them is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            EngineRegistry(listOf(TestEngine(nativeTools = true)))
        }

        // Named, because the whole point is to say which engine is wrong.
        assertTrue(error.message!!.contains("Test litertlm"))
        assertTrue(error.message!!.contains("NativeToolEngine"))
    }

    @Test
    fun `an engine that declares native tools and implements the interface is accepted`() {
        val registry = EngineRegistry(listOf(RunnableEngine()))

        assertEquals(1, registry.all.size)
    }

    @Test
    fun `an engine without native tools needs no runner`() {
        // llama.cpp: tool calling is arranged in the prompt, so there is nothing to hand it.
        val registry = EngineRegistry(listOf(TestEngine(nativeTools = false, id = EngineId.LLAMA_CPP)))

        assertEquals(1, registry.all.size)
    }

    @Test
    fun `a runner set on a native engine is the one that comes back`() {
        val engine = RunnableEngine()
        val runner = ToolRunner { """{"ok": true}""" }

        engine.toolRunner = runner

        // The failure this replaced: the inherited setter accepted the runner and dropped it, so
        // reading it back gave null and every tool call reported itself unavailable.
        assertEquals(runner, engine.toolRunner)
    }
}
