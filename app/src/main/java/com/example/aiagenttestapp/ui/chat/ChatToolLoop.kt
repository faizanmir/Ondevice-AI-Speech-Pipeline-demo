package com.example.aiagenttestapp.ui.chat

import com.example.aiagent.engine.core.ToolCall
import com.example.aiagenttestapp.functions.AppFunctionDeps
import com.example.aiagenttestapp.functions.AppFunctionRegistry
import com.example.aiagenttestapp.functions.AppFunctionResult
import com.example.aiagenttestapp.functions.ToolCallingStrategy

/**
 * Drives a prompt-driven engine's tool calls to an answer.
 *
 * Only llama.cpp needs this. Its tool calls arrive as JSON in the model's *output*, so the app has
 * to read each one, run it, feed the result back as a new turn, and go round again until the model
 * stops asking -- which is a loop with three ways of going wrong, and it was written out inline in
 * the middle of sending a message. A runtime-driven engine ([ToolCallingStrategy.RuntimeDriven])
 * never gets here: its runtime has already done all of this before the app sees anything.
 *
 * The three bounds are the whole reason this is worth its own type, and they are independent:
 *
 *  - a **hop cap**, so a model that keeps calling tools still reaches an answer;
 *  - an **identical-repeat break**, for a small model spinning on the same search;
 *  - a **navigation stop**, because once the user has been moved, the turn is over.
 *
 * And after all of them, the guarantee that raw JSON never stands as the reply: if the model is
 * still emitting a call when the loop ends, one final tool-free turn is forced.
 */
class ChatToolLoop(
    private val functions: AppFunctionRegistry,
    private val deps: AppFunctionDeps,
    private val strategy: ToolCallingStrategy.PromptDriven,
    private val host: Host,
) {

    /** What the loop needs from the screen driving it. */
    interface Host {
        /** Sends [prompt] to the model and returns its complete reply. */
        suspend fun runTurn(prompt: String): String

        /** A tool ran: show it, follow any navigation, keep its sources. */
        fun onToolExecuted(call: ToolCall, result: AppFunctionResult)

        /** The loop ended with a call still pending; replace it with a note rather than JSON. */
        fun onToolLimitReached()
    }

    /**
     * Takes the model's first reply and returns the one the user should actually see.
     *
     * Returns [firstReply] untouched when it is already an answer -- the common case, since most
     * messages call no tools at all.
     */
    suspend fun drive(firstReply: String, maxHops: Int): String {
        var reply = firstReply
        var hops = 0
        var lastSignature: String? = null

        while (hops < maxHops) {
            val call = strategy.parseCall(reply) ?: break

            val signature = "${call.name}(${call.arguments})"
            if (signature == lastSignature) break
            lastSignature = signature

            val result = functions.execute(call, deps)
            host.onToolExecuted(call, result)

            // Hand the result back so the model can use it. Without this the user gets a bare chip
            // and no reply, which reads as the model having ignored them.
            reply = host.runTurn(strategy.resultPrompt(call, result.output))
            hops++

            // A navigation tool has moved the user off this screen; carrying on would generate into
            // a chat they are no longer looking at.
            if (result.navigation != null) break
        }

        // Cap hit, or broke on a repeat, while the model is still emitting a call: never let raw
        // JSON stand as the answer.
        if (strategy.parseCall(reply) != null) {
            host.onToolLimitReached()
            reply = host.runTurn(FINAL_ANSWER_PROMPT)
        }

        return reply
    }

    private companion object {
        const val FINAL_ANSWER_PROMPT =
            "You have reached the maximum number of tool calls for this message. Answer the user " +
                "now using the information you already have. Do not call any more tools."
    }
}
