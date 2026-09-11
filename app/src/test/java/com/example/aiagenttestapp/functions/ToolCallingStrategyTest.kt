package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallingStrategyTest {

    private val tools = listOf(
        ToolDefinition(name = "open_settings", description = "Opens the settings screen."),
    )

    private fun descriptor(nativeTools: Boolean) = EngineDescriptor(
        id = EngineId.LITE_RT_LM,
        displayName = "test",
        vendor = "test",
        supportedFormats = setOf(ModelFormat.LITERTLM),
        supportedAccelerators = setOf(Accelerator.CPU),
        supportsVision = false,
        supportsNativeTools = nativeTools,
        blurb = "",
    )

    @Test
    fun `an engine with a tool API gets the native strategy, one without is offered no tools`() {
        assertTrue(ToolCallingStrategy.forEngine(descriptor(true)) is NativeToolCalling)
        assertTrue(ToolCallingStrategy.forEngine(descriptor(false)) is NoToolCalling)
    }

    @Test
    fun `the native strategy declares the tools and says nothing in the prompt`() {
        assertEquals(tools, NativeToolCalling.declarations(tools))
        assertNull(NativeToolCalling.systemPromptSection(tools))
    }

    /**
     * The prompt-driven strategy that used to sit here went with llama.cpp. What replaced it offers
     * nothing at all, which is the honest answer for an engine that cannot call a tool: declaring
     * them to a runtime that will never invoke them would leave the model describing functions it
     * can never reach.
     */
    @Test
    fun `the no-tools strategy neither declares nor describes anything`() {
        assertEquals(emptyList<ToolDefinition>(), NoToolCalling.declarations(tools))
        assertNull(NoToolCalling.systemPromptSection(tools))
    }

    /**
     * The invariant the whole design rests on. A model told about its tools twice -- once as a
     * runtime declaration and again in prose -- gets two conflicting accounts of what it can do,
     * and the failure is silent: it answers with a call in neither format.
     */
    @Test
    fun `no strategy ever populates both mechanisms`() {
        for (strategy in listOf(NativeToolCalling, NoToolCalling)) {
            val declared = strategy.declarations(tools).isNotEmpty()
            val described = strategy.systemPromptSection(tools) != null
            assertTrue("$strategy uses both mechanisms at once", !(declared && described))
        }
    }

    @Test
    fun `no strategy offers anything when there are no tools`() {
        for (strategy in listOf(NativeToolCalling, NoToolCalling)) {
            assertEquals(emptyList<ToolDefinition>(), strategy.declarations(emptyList()))
            assertNull(
                "$strategy must not prime a model to call tools it does not have",
                strategy.systemPromptSection(emptyList()),
            )
        }
    }

    /**
     * [NoToolCalling] is what a chat holds before a model is loaded, so it must not be mistaken for
     * a runtime-driven one -- `ChatSession.bindToolRunner` binds a tool runner on exactly that
     * check, and binding one against an engine that has not loaded is the bug this prevents.
     */
    @Test
    fun `the no-tools strategy is not runtime-driven`() {
        assertTrue(NoToolCalling !is ToolCallingStrategy.RuntimeDriven)
        assertTrue(NativeToolCalling is ToolCallingStrategy.RuntimeDriven)
    }
}
