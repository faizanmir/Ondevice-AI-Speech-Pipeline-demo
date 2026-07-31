package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.ToolCall
import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagent.engine.llamacpp.ToolCallingProtocol

/**
 * How a model is offered [AppFunctions], and how the calls it makes come back.
 *
 * There is one catalogue of functions and two ways to reach it, because the runtimes genuinely
 * differ: LiteRT-LM has a tool API, so the tools are declared to it as schemas and it calls them
 * itself; llama.cpp has none, so the same tools are described in the system prompt and the app
 * drives the call/result loop by hand.
 *
 * That difference used to be a pair of booleans read in two places -- the planner deciding whether
 * to add a prompt section, the chat deciding whether to run a hop loop -- which is the arrangement
 * where a third engine means hunting for every `if`. Here each mechanism is one implementation, and
 * the two are not interchangeable in a way callers have to guess at: [PromptDriven] *has* the
 * parsing methods and [RuntimeDriven] does not, so a caller cannot ask a LiteRT-LM strategy to
 * parse a tool call out of a reply -- that code does not compile rather than silently returning
 * null.
 */
sealed interface ToolCallingStrategy {

    /**
     * The tools to declare to the runtime when the model is loaded. Empty when the model is told
     * about them some other way, which keeps [com.example.aiagent.engine.core.LoadRequest] honest:
     * it lists what the runtime was actually given.
     */
    fun declarations(tools: List<ToolDefinition>): List<ToolDefinition>

    /** Text to append to the system prompt, or null when the model learns its tools elsewhere. */
    fun systemPromptSection(tools: List<ToolDefinition>): String?

    /**
     * The app runs the loop: the model emits a call as text, the app parses it, executes it, and
     * feeds the result back as another turn.
     */
    interface PromptDriven : ToolCallingStrategy {

        /** The call the model is asking for, or null when its reply is an ordinary answer. */
        fun parseCall(reply: String): ToolCall?

        /** The prompt that hands a tool's output back so the model can carry on. */
        fun resultPrompt(call: ToolCall, output: String): String
    }

    /**
     * The runtime runs the loop: it emits the call, executes it through the tool objects it was
     * given at load, and generates the answer from the result -- all inside one generate. The app
     * supplies only the thing that runs a function
     * ([com.example.aiagent.engine.core.InferenceEngine.toolRunner]).
     */
    interface RuntimeDriven : ToolCallingStrategy

    companion object {
        /**
         * The strategy for an engine. The single place the choice is made -- everything downstream
         * asks the strategy rather than asking which engine it is.
         */
        fun forEngine(descriptor: EngineDescriptor): ToolCallingStrategy =
            if (descriptor.supportsNativeTools) NativeToolCalling else PromptToolCalling
    }
}

/**
 * LiteRT-LM: the tools go to the runtime as OpenAPI schemas.
 *
 * Nothing is added to the system prompt. The model was trained against its own tool format and the
 * runtime injects that format itself, so describing the same tools again in prose would be a second,
 * conflicting account of what it can do.
 */
object NativeToolCalling : ToolCallingStrategy.RuntimeDriven {

    override fun declarations(tools: List<ToolDefinition>): List<ToolDefinition> = tools

    override fun systemPromptSection(tools: List<ToolDefinition>): String? = null
}

/**
 * llama.cpp: the tools are described in the system prompt and answered as JSON.
 *
 * A thin adapter over [ToolCallingProtocol], which owns the format itself and lives in the
 * llama.cpp module with the engine it belongs to. This is what makes the choice of mechanism a
 * choice between objects rather than a branch.
 */
object PromptToolCalling : ToolCallingStrategy.PromptDriven {

    /** Nothing is declared to the runtime: llama.cpp has no tool API to declare anything to. */
    override fun declarations(tools: List<ToolDefinition>): List<ToolDefinition> = emptyList()

    override fun systemPromptSection(tools: List<ToolDefinition>): String? =
        ToolCallingProtocol.systemPromptSection(tools)

    override fun parseCall(reply: String): ToolCall? = ToolCallingProtocol.parse(reply)

    override fun resultPrompt(call: ToolCall, output: String): String =
        ToolCallingProtocol.toolResultPrompt(call, output)
}
