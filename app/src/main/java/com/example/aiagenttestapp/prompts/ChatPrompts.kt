package com.example.aiagenttestapp.prompts

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
 * in llama.cpp's `ToolCallingProtocol.systemPromptSection`, which is only present when tools
 * actually are -- and on LiteRT-LM not even then, since its tools are declared to the runtime
 * rather than described in the prompt.
 */
object ChatPrompts {
    const val SYSTEM_PROMPT =
        "You are a helpful assistant running offline on the user's phone. " +
            "Be concise and accurate."

    /**
     * Asks the model to write the rolling summary a chat is reopened with.
     *
     * Addressed to the model as notes to itself rather than as a report for the user, because that
     * is what it is: the summary is never shown, it is prefixed to the system prompt of the next
     * session so a long conversation can be continued without re-sending every turn.
     */
    const val SUMMARISE_PROMPT =
        "Summarise our conversation so far in 3 to 5 sentences, capturing the key facts, " +
            "questions, decisions and anything I asked you to remember. Write it as concise " +
            "notes to yourself so you can continue the conversation later. Use only what was " +
            "actually discussed."
}
