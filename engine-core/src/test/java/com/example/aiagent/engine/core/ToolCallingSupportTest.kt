package com.example.aiagent.engine.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallingSupportTest {

    private fun infer(name: String, params: Double, id: String = name.lowercase()) =
        ToolCallingSupport.infer(name, id, params)

    @Test
    fun `tool-trained families above the size floor are supported`() {
        assertTrue(infer("Gemma 4 E2B", 2.0))
        assertTrue(infer("Qwen 2.5 1.5B", 1.5))
        assertTrue(infer("Qwen 2.5 3B", 3.09))
        assertTrue(infer("Phi-4 Mini", 3.8))
    }

    /**
     * The floor is deliberately permissive: phone-sized tool-trained models fumble more often, but
     * the protocol absorbs a bad call, and shutting them out of app functions entirely proved the
     * worse trade. These are exactly the models the old 1.4B floor used to exclude.
     */
    @Test
    fun `small tool-trained models are supported down to the floor`() {
        assertTrue(infer("Qwen 2.5 0.5B", 0.5))
        assertTrue(infer("Qwen 3 0.6B", 0.6))
        assertTrue(infer("Gemma 3 1B", 1.0))
        assertTrue(infer("Llama 3.2 1B", 1.24))
    }

    @Test
    fun `families never trained for tools are never supported`() {
        // No size makes these work. SmolLM will happily explain how to open Settings instead of
        // opening it, which is the failure this check exists to prevent.
        assertFalse(infer("SmolLM2 360M", 0.36))
        assertFalse(infer("SmolLM3 3B", 3.0))
        assertFalse(infer("TinyLlama 1.1B", 1.1))
    }

    @Test
    fun `models below the size floor are not supported even in a tool-trained family`() {
        // Plain Gemma 3 270M is a tool-trained *family* at a size where calls mostly fail --
        // unlike FunctionGemma below, which earns its size exemption by being purpose-built.
        assertFalse(infer("Gemma 3 270M", 0.27))
        assertFalse(infer("Qwen 2 0.35B", 0.35))
    }

    /**
     * The reasoning distills are the subtle case: big enough, and built on a tool-trained base, but
     * tuned to think out loud. They narrate the call rather than emitting it.
     */
    @Test
    fun `reasoning distills are excluded despite being large enough`() {
        assertFalse(infer("DeepSeek R1 Distill 1.5B", 1.5))
        assertFalse(infer("DeepSeek-R1-Distill-Qwen-7B", 7.0))
    }

    /**
     * FunctionGemma is the whole reason this is not just a size check. It is a 270M model that
     * beats models ten times its size at the one thing it was built for, so the size floor must not
     * disqualify it.
     */
    @Test
    fun `FunctionGemma is supported despite being tiny`() {
        assertTrue(infer("FunctionGemma 270M", 0.268))
        assertTrue(
            ToolCallingSupport.infer(
                name = "functiongemma-270m-it-GGUF",
                id = "hf:unsloth/functiongemma-270m-it-GGUF:functiongemma-270m-it-Q8_0.gguf",
                paramsBillions = 0.268,
            ),
        )
    }

    /** A model added from HuggingFace is judged by its repo id, which is all we have. */
    @Test
    fun `hugging face ids are matched too`() {
        assertTrue(
            ToolCallingSupport.infer(
                name = "Qwen2.5-3B-Instruct-GGUF",
                id = "hf:Qwen/Qwen2.5-3B-Instruct-GGUF:qwen2.5-3b-instruct-q4_k_m.gguf",
                paramsBillions = 3.09,
            ),
        )
        assertFalse(
            ToolCallingSupport.infer(
                name = "SmolLM2-135M-Instruct-GGUF",
                id = "hf:unsloth/SmolLM2-135M-Instruct-GGUF:SmolLM2-135M-Instruct-Q4_K_M.gguf",
                paramsBillions = 0.134,
            ),
        )
    }

    @Test
    fun `an unknown family is not assumed to support tools`() {
        // Defaulting to "yes" would offer app functions on a model that silently ignores them.
        assertFalse(infer("SomeNewModel 7B", 7.0))
    }
}
