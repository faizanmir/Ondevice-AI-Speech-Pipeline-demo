package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.EngineDescriptor
import com.example.aiagent.engine.core.ToolDefinition

/**
 * How a model is offered the [AppFunctionRegistry], and how the calls it makes come back.
 *
 * There is one catalogue of functions and, in principle, more than one way to reach it, because
 * runtimes genuinely differ in whether they have a tool API of their own. LiteRT-LM has one, so the
 * tools are declared to it as schemas and it calls them itself.
 *
 * It used to have a second mechanism. llama.cpp had no tool API, so the same tools were described in
 * the system prompt and the app drove the call/result loop by hand -- a `PromptDriven` strategy over
 * a `ToolCallingProtocol` that lived in the llama.cpp module. That engine is gone and the mechanism
 * went with it, along with the hop loop in the chat and the `maxToolHops` setting that bounded it.
 *
 * What is kept is the shape: the choice of mechanism is made in exactly one place ([forEngine]) and
 * everything downstream asks the strategy rather than asking which engine it is. That is what made
 * removing a mechanism a deletion rather than a hunt through every `if`, and it is what a fourth
 * engine would slot into.
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
     * The runtime runs the loop: it emits the call, executes it through the tool objects it was
     * given at load, and generates the answer from the result -- all inside one generate. The app
     * supplies only the thing that runs a function
     * ([com.example.aiagent.engine.core.InferenceEngine.toolRunner]).
     */
    interface RuntimeDriven : ToolCallingStrategy

    companion object {
        /**
         * The strategy for an engine. The single place the choice is made.
         *
         * An engine that does not drive its own tool loop gets [NoToolCalling] rather than a
         * guess: with the prompt-driven mechanism gone there is no honest way to offer it tools,
         * and silently declaring them to a runtime that cannot call them would leave the model
         * describing functions it can never reach.
         */
        fun forEngine(descriptor: EngineDescriptor): ToolCallingStrategy =
            if (descriptor.supportsNativeTools) NativeToolCalling else NoToolCalling
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
 * No tools at all: nothing is declared and nothing is added to the prompt.
 *
 * This is also the state a chat is in *before* a model is loaded, which is the reason it is a real
 * object rather than a null. Deliberately not [ToolCallingStrategy.RuntimeDriven], so the check in
 * `ChatSession.bindToolRunner` cannot bind a tool runner against an engine that has not been loaded.
 */
object NoToolCalling : ToolCallingStrategy {

    override fun declarations(tools: List<ToolDefinition>): List<ToolDefinition> = emptyList()

    override fun systemPromptSection(tools: List<ToolDefinition>): String? = null
}
