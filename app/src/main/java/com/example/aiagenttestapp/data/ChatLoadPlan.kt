package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.ToolCallingProtocol
import com.example.aiagenttestapp.functions.AppFunctions
import com.example.aiagenttestapp.util.Reasoning
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
/**
 * The chat system prompt. Fixed in code, not a setting.
 *
 * It was an editable text field, which made the one piece of text present on every single turn the
 * one piece nobody could budget for: [ContextWindow][com.example.aiagent.engine.core.ContextWindow]
 * sizes history against it, so a pasted essay silently cost the user their conversation memory, and
 * an emptied box left small models with no framing at all. Neither failure was visible in the UI.
 *
 * Written tight on purpose -- every token here is paid on every turn of every chat, and is charged
 * twice over, once in prefill and again as history the window can no longer hold. It says only what
 * the model cannot work out for itself: that it is offline on a phone (so it does not promise to
 * look things up or send anything), and what register to answer in. Anything about *tools* belongs
 * in [ToolCallingProtocol.systemPromptSection], which is only present when tools actually are.
 */
object ChatPrompts {
    const val SYSTEM_PROMPT =
        "You are a helpful assistant running offline on the user's phone. " +
            "Be concise and accurate."
}

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
         * are on. A resumed chat folds a rolling summary in on top of this before loading.
         */
        val systemPrompt: String,
    ) : ChatLoadPlan {

        // Read-through to the shared plan, so the chat screen reads the same as it always did.
        val model get() = resolved.model
        val engine get() = resolved.engine
        val accelerator get() = resolved.accelerator
        val engineId get() = resolved.engineId
        val engineName get() = resolved.engineName
        val downloaded get() = resolved.downloaded

        /**
         * The load a brand-new chat performs: empty history, no summary. Two equal requests describe
         * two identical resident models, which is exactly what the warm handoff checks.
         */
        fun freshLoadRequest(): LoadRequest =
            resolved.baseLoadRequest().copy(systemPrompt = systemPrompt)
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
) {

    fun plan(modelId: String): ChatLoadPlan {
        val resolved = when (val plan = modelLoadPlanner.plan(modelId)) {
            is ModelLoadPlan.UnknownModel -> return ChatLoadPlan.UnknownModel
            is ModelLoadPlan.NoEngine -> return ChatLoadPlan.NoEngine(plan.model)
            is ModelLoadPlan.Resolved -> plan
        }

        val settings = settingsStore.settings.value
        val model = resolved.model

        // Tools go into the system prompt, so they are fixed for the life of a loaded model. A model
        // that cannot do tool calling is not given the tool section at all -- it is several hundred
        // system-prompt tokens on every turn, wasted on a model that will never emit a call.
        val toolsEnabled = settings.appFunctionsEnabled && model.canCallTools
        val toolsUnavailableReason = when {
            !settings.appFunctionsEnabled -> null
            !model.canCallTools ->
                "${model.name} cannot run app functions -- it is too small, or its family was " +
                    "not trained for tool calling. Try FunctionGemma 270M, Gemma 4, or Qwen 2.5 1.5B."
            else -> null
        }

        // Web search is a tool like any other, but opt-in: offered only when a Tavily key is set.
        val webAccessEnabled = toolsEnabled && !settings.tavilyApiKey.isNullOrBlank()

        val systemPrompt = buildString {
            append(ChatPrompts.SYSTEM_PROMPT)
            // Off asks reasoning models to answer without a <think> block. Fixed for the loaded
            // model, like the tool section, since both live in the system prompt.
            if (!settings.thinkingEnabled) {
                append("\n\n")
                append(Reasoning.NO_THINK_DIRECTIVE)
            }
            if (toolsEnabled) {
                ToolCallingProtocol.systemPromptSection(
                    AppFunctions.definitionsFor(webAccessEnabled),
                )?.let {
                    append("\n\n")
                    append(it)
                }
            }
        }

        return ChatLoadPlan.Ready(
            resolved = resolved,
            toolsEnabled = toolsEnabled,
            toolsUnavailableReason = toolsUnavailableReason,
            systemPrompt = systemPrompt,
        )
    }
}
