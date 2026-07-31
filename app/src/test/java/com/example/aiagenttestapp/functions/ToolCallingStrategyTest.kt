package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallingStrategyTest {

    private val tools = listOf(
        ToolDefinition(name = "open_settings", description = "Opens the settings screen."),
    )

    private fun descriptor(nativeTools: Boolean) = EngineDescriptor(
        id = if (nativeTools) EngineId.LITE_RT_LM else EngineId.LLAMA_CPP,
        displayName = "test",
        vendor = "test",
        supportedFormats = setOf(ModelFormat.GGUF),
        supportedAccelerators = setOf(Accelerator.CPU),
        supportsVision = false,
        supportsNativeTools = nativeTools,
        blurb = "",
    )

    @Test
    fun `an engine with a tool API gets the native strategy, one without gets the prompt strategy`() {
        assertTrue(ToolCallingStrategy.forEngine(descriptor(true)) is NativeToolCalling)
        assertTrue(ToolCallingStrategy.forEngine(descriptor(false)) is PromptToolCalling)
    }

    @Test
    fun `the native strategy declares the tools and says nothing in the prompt`() {
        assertEquals(tools, NativeToolCalling.declarations(tools))
        assertNull(NativeToolCalling.systemPromptSection(tools))
    }

    @Test
    fun `the prompt strategy describes the tools and declares nothing`() {
        assertEquals(emptyList<ToolDefinition>(), PromptToolCalling.declarations(tools))

        val section = PromptToolCalling.systemPromptSection(tools)
        assertNotNull(section)
        assertTrue("the section should name the tool", section!!.contains("open_settings"))
    }

    /**
     * The invariant the whole design rests on. A model told about its tools twice -- once as a
     * runtime declaration and again in prose -- gets two conflicting accounts of what it can do,
     * and the failure is silent: it answers with a call in neither format.
     */
    @Test
    fun `exactly one mechanism is ever populated`() {
        for (strategy in listOf(NativeToolCalling, PromptToolCalling)) {
            val declared = strategy.declarations(tools).isNotEmpty()
            val described = strategy.systemPromptSection(tools) != null
            assertTrue("$strategy uses neither or both mechanisms", declared != described)
        }
    }

    @Test
    fun `neither strategy offers anything when there are no tools`() {
        for (strategy in listOf(NativeToolCalling, PromptToolCalling)) {
            assertEquals(emptyList<ToolDefinition>(), strategy.declarations(emptyList()))
            assertNull(
                "$strategy must not prime a model to call tools it does not have",
                strategy.systemPromptSection(emptyList()),
            )
        }
    }

    @Test
    fun `the prompt strategy reads a call back out of a reply and feeds its result forward`() {
        val call = PromptToolCalling.parseCall("""{"tool": "open_settings", "args": {}}""")

        assertEquals("open_settings", call?.name)
        // The result has to travel back as a prompt, since a prompt-driven model has no other way
        // of learning what its call produced.
        assertTrue(PromptToolCalling.resultPrompt(call!!, "Settings opened.").contains("Settings opened."))
    }
}
