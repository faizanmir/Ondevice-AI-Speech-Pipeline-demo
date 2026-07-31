package com.example.aiagenttestapp.util

/**
 * Interprets a reasoning model's `<think>...</think>` output.
 *
 * Detection is by the tags alone, so a non-reasoning model's output passes through untouched. The
 * chat shows the reasoning in a collapsible section ([split]); the note summary hides it outright
 * ([stripThinking]) -- a summary is meant to be the distilled result, not the model's scratch work.
 */
object Reasoning {

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    /**
     * System-prompt directive that asks a reasoning model to skip its `<think>` block, for when the
     * user has turned thinking off. Pairs Qwen3's `/no_think` soft switch with a plain instruction
     * for families that do not recognise it. Best effort: a model with no thinking mode just answers
     * as usual, and an empty `<think></think>` some still emit is hidden by [stripThinking] anyway.
     */
    const val NO_THINK_DIRECTIVE =
        "/no_think\nAnswer directly and concisely. Do not include a <think> reasoning section."

    /** The reasoning block (null when absent) and the answer that follows it. */
    data class Split(val thinking: String?, val answer: String)

    /**
     * Splits output into its reasoning block and the answer. Handles the answer-only case (no tags),
     * a closed block (thinking + answer), and an unclosed block mid-stream (all thinking, no answer
     * yet). Tolerant of the common variant where the chat template emits the opening `<think>` for
     * the model, so the output carries only the closing tag.
     */
    fun split(text: String): Split {
        val close = text.indexOf(CLOSE)
        if (close >= 0) {
            var thinking = text.substring(0, close)
            val open = thinking.indexOf(OPEN)
            if (open >= 0) thinking = thinking.substring(open + OPEN.length)
            val answer = text.substring(close + CLOSE.length)
            return Split(thinking.trim().ifEmpty { null }, answer.trim())
        }
        val open = text.indexOf(OPEN)
        if (open >= 0) {
            // The block opened and has not closed yet: everything after the tag is still reasoning.
            val thinking = text.substring(open + OPEN.length)
            return Split(thinking.trim().ifEmpty { null }, "")
        }
        return Split(null, text)
    }

    /** Just the answer, with any `<think>` reasoning removed. Empty while a block is still open. */
    fun stripThinking(text: String): String = split(text).answer

    private val BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Removes reasoning as aggressively as possible, for a chat with thinking turned off: any
     * complete `<think>...</think>` block, then any *stray* unpaired tag a model still emits. Qwen3's
     * chat template prefills the opening `<think>` for the model, so with `/no_think` the output can
     * carry only a closing `</think>` -- which [split] would mistake the answer for reasoning. This
     * keeps whatever text is left as the answer, so "reasoning off" never renders a thinking card.
     *
     * Use only when reasoning is off. With it on there may be real reasoning before a lone `</think>`,
     * and dropping just the tag would fuse the reasoning onto the answer -- [stripThinking] is right
     * there.
     */
    fun stripAllThinking(text: String): String =
        text.replace(BLOCK, "")
            .replace(OPEN, "")
            .replace(CLOSE, "")
            .trim()
}
