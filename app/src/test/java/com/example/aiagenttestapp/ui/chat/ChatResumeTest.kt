package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.HistoryTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResumeTest {

    private val base = "You are a helpful assistant."

    @Test
    fun `a chat with no summary keeps the exact system prompt it was warmed with`() {
        // Identity matters, not just equality of text: a fresh chat's LoadRequest has to equal the
        // one the residency manager preloaded, or the model is loaded again instead of reused.
        assertSame(base, ChatResume.systemPrompt(base, storedSummary = null))
        assertSame(base, ChatResume.systemPrompt(base, storedSummary = "   "))
    }

    @Test
    fun `a stored summary is folded in below the base prompt`() {
        val prompt = ChatResume.systemPrompt(base, "They asked about tides.")

        assertTrue(prompt.startsWith(base))
        assertTrue(prompt.contains("They asked about tides."))
    }

    @Test
    fun `history is fitted against the summary-bearing prompt, not the base one`() {
        // Enough history that the window is the binding constraint -- otherwise everything fits
        // either way and the comparison proves nothing.
        val past = (1..200).map { HistoryTurn(HistoryTurn.ROLE_USER, "turn $it of the conversation") }
        val withSummary = ChatResume.systemPrompt(base, "a ".repeat(400))

        val fittedToBase = ChatResume.fittedHistory(past, contextTokens = 1400, systemPrompt = base)
        val fittedToReal = ChatResume.fittedHistory(past, contextTokens = 1400, systemPrompt = withSummary)

        // The summary costs context, so less history fits. Measuring against the base prompt would
        // over-fill by exactly the summary's length -- an overflow that only shows up later as the
        // model having lost the start of the conversation.
        assertTrue(
            "a longer system prompt must leave room for fewer turns",
            fittedToReal.size < fittedToBase.size,
        )
    }

    @Test
    fun `an empty conversation fits nothing`() {
        assertEquals(
            emptyList<HistoryTurn>(),
            ChatResume.fittedHistory(emptyList(), contextTokens = 4096, systemPrompt = base),
        )
    }

    @Test
    fun `the newest turns are the ones kept`() {
        val past = listOf(
            HistoryTurn(HistoryTurn.ROLE_USER, "oldest"),
            HistoryTurn(HistoryTurn.ROLE_ASSISTANT, "reply"),
            HistoryTurn(HistoryTurn.ROLE_USER, "newest"),
        )

        val fitted = ChatResume.fittedHistory(past, contextTokens = 4096, systemPrompt = base)

        // What the model is re-sent is the tail; the summary stands in for whatever was dropped.
        assertEquals("newest", fitted.last().content)
    }
}
