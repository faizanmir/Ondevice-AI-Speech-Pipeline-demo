package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.ToolCall
import com.example.aiagenttestapp.functions.AppFunctionRegistry
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.functions.FakeAppFunctionDeps
import com.example.aiagenttestapp.functions.PromptToolCalling
import com.example.aiagenttestapp.functions.tools.OpenSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three bounds on the tool loop, which decide whether a turn ever reaches an answer.
 *
 * None of this was testable while the loop lived inline in the view model, and all of it fails in
 * ways a user sees: a model that keeps calling tools never replies, one that repeats a call burns
 * the whole hop budget on the same search, and a loop that ends mid-call leaves raw JSON on screen
 * as though it were the answer.
 */
class ChatToolLoopTest {

    /** Replies the fake model gives, in order. */
    private class Host(private vararg val replies: String) : ChatToolLoop.Host {
        val prompts = mutableListOf<String>()
        val executed = mutableListOf<String>()
        var limitReached = false

        override suspend fun runTurn(prompt: String): String {
            prompts += prompt
            return replies.getOrElse(prompts.size - 1) { "final answer" }
        }

        override fun onToolExecuted(call: ToolCall, result: AppFunctionResult) {
            executed += call.name
        }

        override fun onToolLimitReached() {
            limitReached = true
        }
    }

    private val deps = FakeAppFunctionDeps()
    private val functions = AppFunctionRegistry(listOf(OpenSettings()))

    private fun loop(host: ChatToolLoop.Host) =
        ChatToolLoop(functions, deps, PromptToolCalling, host)

    private val call = """{"tool": "open_settings", "args": {}}"""

    @Test
    fun `a reply with no tool call is returned untouched`() = runTest {
        val host = Host()

        val answer = loop(host).drive("Just an answer.", maxHops = 5)

        assertEquals("Just an answer.", answer)
        assertTrue("the model must not be asked again", host.prompts.isEmpty())
    }

    @Test
    fun `a call is executed and its result fed back for the real answer`() = runTest {
        val host = Host("Settings are open now.")

        val answer = loop(host).drive(call, maxHops = 5)

        assertEquals(listOf("open_settings"), host.executed)
        assertEquals("Settings are open now.", answer)
    }

    @Test
    fun `navigation ends the turn even with hops left`() = runTest {
        // open_settings navigates, so the user is no longer on this screen -- generating further
        // would be into a chat they cannot see.
        val host = Host(call, call, call)

        loop(host).drive(call, maxHops = 5)

        assertEquals(listOf("open_settings"), host.executed)
    }

    @Test
    fun `an identical repeated call breaks rather than spending the budget on it`() = runTest {
        val functions = AppFunctionRegistry(listOf(NonNavigating()))
        val host = Host(NON_NAV_CALL, NON_NAV_CALL, NON_NAV_CALL)

        ChatToolLoop(functions, deps, PromptToolCalling, host).drive(NON_NAV_CALL, maxHops = 5)

        // Ran once, saw the same call come back, stopped -- a small model spinning on one search
        // would otherwise use every hop on it.
        assertEquals(1, host.executed.size)
    }

    @Test
    fun `the hop cap is honoured and never leaves raw JSON as the answer`() = runTest {
        val functions = AppFunctionRegistry(listOf(NonNavigating()))
        // Distinct calls each time, so neither the repeat break nor navigation stops it. The last
        // reply is plain text: the forced turn is told not to call tools, and this asserts the loop
        // returns what it gave rather than going round again.
        val host = Host(alternating(1), alternating(2), "final answer")

        val answer = ChatToolLoop(functions, deps, PromptToolCalling, host)
            .drive(alternating(0), maxHops = 2)

        assertEquals(2, host.executed.size)
        assertTrue("a dangling call must be replaced, not shown", host.limitReached)
        assertEquals("final answer", answer)
    }

    private fun alternating(n: Int) = """{"tool": "note", "args": {"text": "$n"}}"""

    private class NonNavigating : com.example.aiagenttestapp.functions.AppFunction() {
        override val name = "note"
        override val description = "does nothing visible"
        override suspend fun run(
            arguments: Map<String, String>,
            deps: com.example.aiagenttestapp.functions.AppFunctionDeps,
        ) = AppFunctionResult(summary = "noted", output = "noted")
    }

    private companion object {
        const val NON_NAV_CALL = """{"tool": "note", "args": {"text": "same"}}"""
    }
}
