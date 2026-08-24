package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.ToolDefinition
import com.example.aiagenttestapp.functions.AppFunctionRegistry
import com.example.aiagenttestapp.functions.ToolCallingStrategy
import com.example.aiagenttestapp.prompts.ChatPrompts
import com.example.aiagenttestapp.prompts.SystemPromptBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a model is loaded *for chat*: the shared [ModelLoadPlan] plus the things only chat has -- the
 * user's system prompt, the reasoning directive, and the tool section.
 *
 * These used to live on [ModelLoadPlan.Resolved], which meant every caller carried them whether or
 * not they applied. Audit in particular inherited a chat system prompt it immediately overwrote and
 * a tool section it can never use, so the shared type was quietly describing a chat turn rather than
 * a model load.
 */

sealed interface ChatLoadPlan {

    /** No model in the catalogue -- built-in or user-added -- has this id. */
    data object UnknownModel : ChatLoadPlan

    /** The format has no usable engine in this build (a GGUF model with llama.cpp excluded). */
    data class NoEngine(val model: ModelSpec) : ChatLoadPlan

    data class Ready(
        val resolved: ModelLoadPlan.Resolved,
        /** Whether this model is told about the app's tools on every turn. */
        val toolsEnabled: Boolean,
        /** Set when app functions are on globally but this particular model cannot use them. */
        val toolsUnavailableReason: String?,
        /**
         * The system prompt for a *fresh* chat: the user's prompt, plus the tool section when tools
         * are on *and* the engine has no native tool API. A resumed chat folds a rolling summary in
         * on top of this before loading.
         */
        val systemPrompt: String,
        /**
         * The tools declared to the runtime, as the engine's [ToolCallingStrategy] decided. Empty
         * for a prompt-driven engine, which learns about its tools from [systemPrompt] instead --
         * exactly one of the two is ever populated.
         */
        val nativeTools: List<ToolDefinition> = emptyList(),
    ) : ChatLoadPlan {

        // Read-through to the shared plan, so the chat screen reads the same as it always did.
        val model get() = resolved.model
        val engine get() = resolved.engine
        val accelerator get() = resolved.accelerator

        /** The window this device affords, not the one the model advertises. */
        val contextTokens get() = resolved.contextTokens
        val engineId get() = resolved.engineId
        val engineName get() = resolved.engineName
        val downloaded get() = resolved.downloaded

        /**
         * The load a brand-new chat performs: empty history, no summary. Two equal requests describe
         * two identical resident models, which is exactly what the warm handoff checks.
         */
        fun freshLoadRequest(): LoadRequest =
            resolved.baseLoadRequest().copy(systemPrompt = systemPrompt, tools = nativeTools)
    }
}

/**
 * Resolves a model for chat: the shared plan, plus the tool section only for a model that can call
 * tools while app functions are enabled.
 */
@Singleton
class ChatLoadPlanner @Inject constructor(
    private val modelLoadPlanner: ModelLoadPlanner,
    private val settingsStore: SettingsStore,
    private val appFunctions: AppFunctionRegistry,
) {

    fun plan(modelId: String): ChatLoadPlan {
        val resolved = when (val plan = modelLoadPlanner.plan(modelId)) {
            is ModelLoadPlan.UnknownModel -> return ChatLoadPlan.UnknownModel
            is ModelLoadPlan.NoEngine -> return ChatLoadPlan.NoEngine(plan.model)
            is ModelLoadPlan.Resolved -> plan
        }

        val settings = settingsStore.settings.value
        val model = resolved.model

        // Tools are fixed for the life of a loaded model either way -- the prompt section is in the
        // system prompt, and the native declarations are baked into the conversation at creation. A
        // model that cannot do tool calling is given neither: the prompt section costs several
        // hundred tokens on every turn, wasted on a model that will never emit a call.
        val toolsEnabled = settings.appFunctionsEnabled && model.canCallTools

        // Which mechanism this engine gets is the strategy's business, not this planner's -- it
        // asks for a prompt section and a set of declarations and uses whatever comes back. Exactly
        // one of the two is ever non-empty: a model told about its tools twice, in two different
        // formats, is a model that invents a third.
        val strategy = ToolCallingStrategy.forEngine(resolved.engine.descriptor)
        val toolsUnavailableReason = when {
            !settings.appFunctionsEnabled -> null
            !model.canCallTools ->
                "${model.name} cannot run app functions -- it is too small, or its family was " +
                    "not trained for tool calling. Try FunctionGemma 270M, Gemma 4, or Qwen 2.5 1.5B."
            else -> null
        }

        // Web search is a tool like any other, but opt-in: offered only when a Tavily key is set.
        val webAccessEnabled = toolsEnabled && !settings.tavilyApiKey.isNullOrBlank()
        val definitions =
            if (toolsEnabled) appFunctions.definitions(webAccessEnabled) else emptyList()

        // The tool section is chat's alone; the reasoning directive is app-wide and applied by the
        // builder. Both are fixed for the life of the loaded model, since both live in the system
        // prompt -- toggling either setting mid-chat cannot reach back into a loaded conversation.
        val systemPrompt = SystemPromptBuilder.build(
            base = ChatPrompts.SYSTEM_PROMPT,
            thinkingEnabled = settings.thinkingEnabled,
            strategy.systemPromptSection(definitions),
        )

        return ChatLoadPlan.Ready(
            resolved = resolved,
            toolsEnabled = toolsEnabled,
            toolsUnavailableReason = toolsUnavailableReason,
            systemPrompt = systemPrompt,
            nativeTools = strategy.declarations(definitions),
        )
    }
}
