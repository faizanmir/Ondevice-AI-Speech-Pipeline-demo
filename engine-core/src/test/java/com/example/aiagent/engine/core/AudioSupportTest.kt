package com.example.aiagent.engine.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inference exists because a curated `audioInput` flag only ever covers the built-in catalogue,
 * and most models arrive from HuggingFace instead. The first case below is the one that sent the
 * record screen into insisting a device had no model that could listen while the model sat on disk.
 */
class AudioSupportTest {

    @Test
    fun `recognises a gemma 4 E2B added from huggingface`() {
        // Taken verbatim from custom_models.json on the device that reported the bug.
        assertTrue(
            AudioSupport.infer(
                name = "gemma-4-E2B-it-litert-lm",
                id = "hf:litert-community/gemma-4-E2B-it-litert-lm:gemma-4-E2B-it.litertlm",
                repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            ),
        )
    }

    @Test
    fun `recognises the gemma 3n line`() {
        assertTrue(AudioSupport.infer("Gemma 3n E4B", "gemma-3n-e4b-it", "google/gemma-3n-E4B-it"))
        assertTrue(AudioSupport.infer("gemma3n", "gemma3n-int4", null))
    }

    @Test
    fun `recognises a matformer size in any of the three identifiers`() {
        assertTrue(AudioSupport.infer("mystery", "gemma-4-e4b", null))
        assertTrue(AudioSupport.infer("mystery", "local-file", "litert-community/gemma-4-E2B-it"))
    }

    @Test
    fun `rejects gemma builds with no audio encoder`() {
        // Same family name, no MatFormer size -- these genuinely cannot hear.
        assertFalse(AudioSupport.infer("Gemma 3 1B", "gemma3-1b-it", "litert-community/Gemma3-1B-IT"))
        assertFalse(
            AudioSupport.infer(
                "FunctionGemma 270M",
                "functiongemma-270m-gguf",
                "unsloth/functiongemma-270m-it-GGUF",
            ),
        )
    }

    @Test
    fun `rejects the ordinary text models in the catalogue`() {
        assertFalse(AudioSupport.infer("Qwen 2.5 1.5B", "qwen2.5-1.5b-instruct", null))
        assertFalse(
            AudioSupport.infer("Llama-3.2-1B", "hf:litert-community/Llama-3.2-1B:x.litertlm", null),
        )
        assertFalse(AudioSupport.infer("DeepSeek R1 Distill 1.5B", "deepseek-r1-distill", null))
    }

    @Test
    fun `an explicit answer always wins over the guess`() {
        // A curated entry states its capability; inference must not be consulted at all.
        val stated = spec(id = "qwen2.5-1.5b-instruct", name = "Qwen 2.5 1.5B", audioInput = true)
        assertTrue(stated.hearsAudio)

        val denied = spec(id = "gemma-4-e2b-it", name = "Gemma 4 E2B", audioInput = false)
        assertFalse(denied.hearsAudio)
    }

    @Test
    fun `an unstated capability falls through to the guess`() {
        assertTrue(spec(id = "hf:x/gemma-4-E2B", name = "gemma-4-E2B", audioInput = null).hearsAudio)
        assertFalse(spec(id = "hf:x/qwen", name = "qwen", audioInput = null).hearsAudio)
    }

    private fun spec(id: String, name: String, audioInput: Boolean?) = ModelSpec(
        id = id,
        name = name,
        vendor = "test",
        paramsBillions = 2.0,
        quantization = Quantization.Q4,
        format = ModelFormat.LITERTLM,
        downloadUrl = "https://example.invalid/model",
        fileName = "model.litertlm",
        sizeBytes = 1,
        contextTokens = 4096,
        minDeviceMemoryGb = 0,
        accelerators = setOf(Accelerator.CPU),
        audioInput = audioInput,
        license = "test",
        description = "test",
    )
}
