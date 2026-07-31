package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolRunner
import kotlinx.coroutines.runBlocking

/**
 * Told about every function the model ran, so a screen can show it.
 *
 * Implemented by whoever is driving the chat. Called on the *runtime's* thread, not the main one --
 * see [AppFunctionRunner] -- so an implementation must only touch state that is safe from there.
 */
interface AppFunctionObserver {

    fun onFunctionExecuted(call: ToolCall, result: AppFunctionResult)
}

/**
 * Runs an [AppFunctions] entry for a runtime that calls tools itself.
 *
 * This is the [ToolRunner] a [NativeToolCalling] engine is given after it loads. LiteRT-LM invokes
 * it from inside its decode loop, waits for the string, and carries on generating from it.
 *
 * ## Why it blocks
 *
 * Not an oversight -- [ToolRunner] is synchronous because the runtime has no suspension point to
 * hand back to. What matters is *which* thread blocks: this runs on the runtime's own worker,
 * never the main thread, so the UI keeps drawing while a function does its work. An implementation
 * must not bounce onto the main thread and wait for it, which deadlocks if that thread is what
 * started generation.
 *
 * ## What it does not do
 *
 * Anything visible. Showing a chip, following a navigation, collecting the sources a search drew on
 * -- all of that is the [AppFunctionObserver]'s. Keeping the UI out of here leaves this class one
 * job: turn a tool call into the string the model gets back.
 */
class AppFunctionRunner(
    private val deps: AppFunctionDeps,
    private val observer: AppFunctionObserver,
) : ToolRunner {

    override fun run(call: ToolCall): String {
        // AppFunctions.execute is suspending, and there is nothing to suspend into -- see above.
        val result = runBlocking { AppFunctions.execute(call, deps) }
        observer.onFunctionExecuted(call, result)
        // Failures are results too: AppFunctions turns an unknown function or a failed one into an
        // output the model can read, so it recovers instead of the turn dying.
        return result.output
    }
}
