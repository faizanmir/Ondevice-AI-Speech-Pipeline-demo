package com.example.aiagenttestapp.prompts

/**
 * What the app says to a reasoning model about its `<think>` block.
 *
 * Separate from [com.example.aiagenttestapp.util.Reasoning], which *reads* those blocks back out of
 * the output. The two are opposite ends of the same feature and are used by different layers -- the
 * directive by whoever builds a system prompt, the parsing by whoever renders a reply -- so the
 * text lives here with the other prompts and the parser stays where the rendering is.
 */
object ReasoningPrompts {

    /**
     * Asks a reasoning model to skip its `<think>` block, for when the user has turned thinking off.
     *
     * Pairs Qwen3's `/no_think` soft switch with a plain instruction for families that do not
     * recognise it. Best effort: a model with no thinking mode just answers as usual, and the empty
     * `<think></think>` some still emit is hidden by
     * [Reasoning.stripAllThinking][com.example.aiagenttestapp.util.Reasoning.stripAllThinking]
     * anyway.
     */
    const val NO_THINK_DIRECTIVE =
        "/no_think\nAnswer directly and concisely. Do not include a <think> reasoning section."
}
