package com.example.aiagenttestapp.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one place every feature's system prompt is assembled.
 *
 * Worth testing because the cost of getting it wrong is invisible and permanent: a system prompt is
 * fixed into a conversation when the model loads, so a missing separator or a stray blank section
 * degrades every turn of that chat and never announces itself.
 */
class SystemPromptBuilderTest {

    @Test
    fun `the base prompt comes back untouched when nothing else applies`() {
        assertEquals(
            "You are a helpful assistant.",
            SystemPromptBuilder.build("You are a helpful assistant."),
        )
    }

    @Test
    fun `thinking off appends the directive, thinking on does not`() {
        val off = SystemPromptBuilder.build("Base.", thinkingEnabled = false)
        val on = SystemPromptBuilder.build("Base.", thinkingEnabled = true)

        assertTrue(off.contains(ReasoningPrompts.NO_THINK_DIRECTIVE))
        assertFalse(on.contains(ReasoningPrompts.NO_THINK_DIRECTIVE))
    }

    @Test
    fun `sections are separated by a blank line and keep their order`() {
        val prompt = SystemPromptBuilder.build("Base.", thinkingEnabled = true, "First.", "Second.")

        assertEquals("Base.\n\nFirst.\n\nSecond.", prompt)
    }

    @Test
    fun `the base always frames what follows`() {
        val prompt = SystemPromptBuilder.build("Base.", thinkingEnabled = false, "Tools.")

        // The order is load-bearing: a directive or a tool list read before the model has been told
        // what it is has nothing to attach itself to.
        assertTrue(prompt.startsWith("Base."))
        assertTrue(prompt.indexOf("Tools.") > prompt.indexOf(ReasoningPrompts.NO_THINK_DIRECTIVE))
    }

    @Test
    fun `absent and empty sections leave no gap`() {
        // Callers pass optional sections inline -- "the tool section, if there is one" -- so a null
        // must not become a double blank line that reads to the model as a missing instruction.
        assertEquals(
            "Base.\n\nReal.",
            SystemPromptBuilder.build("Base.", thinkingEnabled = true, null, "Real.", "", "   "),
        )
    }
}
