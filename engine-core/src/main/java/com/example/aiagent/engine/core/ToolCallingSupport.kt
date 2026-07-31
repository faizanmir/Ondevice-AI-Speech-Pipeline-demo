package com.example.aiagent.engine.core

/**
 * Whether a model can plausibly drive the app through tool calling.
 *
 * Engine-agnostic, and shared for that reason: this asks about the *model*, not the runtime. A
 * family that was never trained to emit a function call will not start because the runtime has a
 * tool API, and one that was will manage either mechanism.
 *
 * Two things have to be true, and they are independent:
 *
 *  1. **The family was trained for tool use.** Emitting a well-formed function call on demand is a
 *     learned behaviour, not an emergent one. Qwen 2.5, Llama 3.2, Gemma 3/4 and Phi-4 were all
 *     trained for it; SmolLM and TinyLlama were not, and no amount of prompting fixes that -- they
 *     answer in prose about how to open Settings instead of opening it.
 *  2. **It is big enough** -- but the bar is deliberately low. The floor used to be 1.4B on the
 *     theory that smaller models fumble too often to be worth offering; in practice the protocol
 *     already absorbs a fumble (a malformed call is rejected by the parser and the model simply
 *     answers in prose), so locking every sub-1.5B phone-sized model out of app functions cost
 *     more than the occasional bad call did. The floor now only excludes the truly tiny generics,
 *     where calls fail more often than they succeed.
 *
 * ## The honest caveat
 *
 * [MIN_PARAMS_BILLIONS] is taken from what the model cards claim, not from measuring these
 * particular models against this particular JSON protocol. It is a filter to stop the app promising
 * a feature that visibly does nothing on a 135M generic -- not a guarantee that everything above
 * the line works perfectly. A small model WILL fumble calls more often than a 3B one; what it gets
 * is the chance to try.
 */
object ToolCallingSupport {

    /** Below this, even tool-trained generics fail more calls than they land. */
    const val MIN_PARAMS_BILLIONS = 0.5

    /** Families with function calling in their post-training. */
    private val TOOL_TRAINED = listOf(
        "qwen",
        "llama",
        "gemma",
        "phi",
        "mistral",
        "ministral",
        "hermes",
        "command-r",
    )

    /**
     * Families that will not do it regardless of size.
     *
     * The reasoning distills (DeepSeek-R1 and friends) are the subtle one: they are big enough and
     * built on a tool-trained base, but they are tuned to think out loud. They wrap everything in a
     * chain of thought and explain the call they are about to make instead of just making it, which
     * the parser cannot use.
     */
    private val NOT_TOOL_TRAINED = listOf(
        "smollm",
        "tinyllama",
        "deepseek-r1",
        "-r1-",
        "distill",
    )

    /** Fine-tuned specifically for on-device function calling, so size does not disqualify them. */
    private val PURPOSE_BUILT = listOf("functiongemma")

    fun infer(name: String, id: String, paramsBillions: Double): Boolean {
        val haystack = "$name $id".lowercase()

        if (PURPOSE_BUILT.any { it in haystack }) return true
        if (NOT_TOOL_TRAINED.any { it in haystack }) return false
        if (paramsBillions < MIN_PARAMS_BILLIONS) return false

        return TOOL_TRAINED.any { it in haystack }
    }
}
