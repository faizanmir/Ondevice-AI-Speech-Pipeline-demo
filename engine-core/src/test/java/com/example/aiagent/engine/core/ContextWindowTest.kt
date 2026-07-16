package com.example.aiagent.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    private fun user(text: String) = HistoryTurn(HistoryTurn.ROLE_USER, text)
    private fun assistant(text: String) = HistoryTurn(HistoryTurn.ROLE_ASSISTANT, text)

    @Test
    fun `everything fits when the context is generous`() {
        val history = listOf(user("hi"), assistant("hello"), user("how are you"))
        val kept = ContextWindow.fit(history, contextTokens = 4096, systemPromptTokens = 20)
        assertEquals(history, kept)
    }

    @Test
    fun `keeps the newest turns and drops the oldest when over budget`() {
        // Each turn ~250 chars ~= 72 est. tokens. Budget = 200 - 0 - 0 leaves room for ~2 turns.
        val big = "x".repeat(250)
        val history = (1..10).map { user("$it $big") }
        val kept = ContextWindow.fit(
            history,
            contextTokens = 200,
            systemPromptTokens = 0,
            reserveTokens = 0,
        )
        assertTrue("should drop some turns", kept.size < history.size)
        // The kept turns are the tail, in order.
        assertEquals(history.takeLast(kept.size), kept)
    }

    @Test
    fun `history never opens on an assistant turn`() {
        val big = "y".repeat(300)
        // Alternating; a naive tail could start on an assistant turn.
        val history = listOf(
            user("q1 $big"), assistant("a1 $big"),
            user("q2 $big"), assistant("a2 $big"),
        )
        val kept = ContextWindow.fit(
            history,
            contextTokens = 260,
            systemPromptTokens = 0,
            reserveTokens = 0,
        )
        assertTrue(kept.isEmpty() || kept.first().role == HistoryTurn.ROLE_USER)
    }

    @Test
    fun `returns empty when the budget is exhausted by the system prompt`() {
        val history = listOf(user("hi"), assistant("hello"))
        val kept = ContextWindow.fit(history, contextTokens = 100, systemPromptTokens = 100)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `empty history yields empty`() {
        assertTrue(ContextWindow.fit(emptyList(), 4096, 10).isEmpty())
    }
}
