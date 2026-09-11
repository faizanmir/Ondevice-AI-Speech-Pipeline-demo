package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.ModelFile
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.Quantization

/**
 * The models this app can download and run.
 *
 * Every entry here is **ungated**: downloadable from HuggingFace with no token and no licence
 * click-through. That is a deliberate constraint, and it excludes some obvious names -- Gemma 3 1B,
 * Gemma 2, and every Llama build are behind an HF gate and would 401 a token-less client. The one
 * happy surprise is that Gemma 4 E2B/E4B are ungated *and* Apache-2.0, so the best multimodal
 * models in the catalogue are also the easiest to ship.
 *
 * Sizes are HuggingFace's authoritative `x-linked-size` (the LFS object size), not estimates.
 *
 * `minDeviceMemoryGb` is hand-curated, not computed from `sizeBytes` -- see [ModelSpec] for why a
 * multiplier cannot work here. The values follow Google's own allowlist tiers where the model
 * appears in it, and are interpolated conservatively where it does not.
 */
object ModelCatalog {

    private const val HF = "https://huggingface.co"

    val builtIn: List<ModelSpec> = listOf(

        // ---- LiteRT-LM (.litertlm) -- GPU accelerated, memory-mapped weights -----------------

        ModelSpec(
            id = "gemma-4-e2b-it",
            name = "Gemma 4 E2B",
            vendor = "Google",
            paramsBillions = 2.0,
            quantization = Quantization.MIXED,
            format = ModelFormat.LITERTLM,
            downloadUrl = "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            contextTokens = 4096,
            minDeviceMemoryGb = 8,
            accelerators = setOf(Accelerator.GPU, Accelerator.CPU),
            multimodal = true,
            audioInput = true,
            license = "Apache-2.0",
            description = "Google's newest on-device model. Understands images and audio as well " +
                "as text. Memory-maps its embeddings, so it runs in far less RAM than its file " +
                "size suggests -- about 700 MB on the GPU.",
        ),

        ModelSpec(
            id = "gemma-4-e4b-it",
            name = "Gemma 4 E4B",
            vendor = "Google",
            paramsBillions = 4.0,
            quantization = Quantization.MIXED,
            format = ModelFormat.LITERTLM,
            downloadUrl = "$HF/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            contextTokens = 4096,
            minDeviceMemoryGb = 12,
            accelerators = setOf(Accelerator.GPU, Accelerator.CPU),
            multimodal = true,
            audioInput = true,
            license = "Apache-2.0",
            description = "The larger Gemma 4. Noticeably stronger reasoning than E2B, and still " +
                "multimodal, but it wants a flagship phone.",
        ),

        ModelSpec(
            id = "qwen2.5-1.5b-instruct",
            name = "Qwen 2.5 1.5B",
            vendor = "Alibaba",
            paramsBillions = 1.5,
            quantization = Quantization.Q8,
            format = ModelFormat.LITERTLM,
            downloadUrl = "$HF/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
            fileName = "Qwen2.5-1.5B-Instruct_q8_ekv4096.litertlm",
            sizeBytes = 1_597_931_520L,
            contextTokens = 4096,
            minDeviceMemoryGb = 6,
            accelerators = setOf(Accelerator.GPU, Accelerator.CPU),
            license = "Apache-2.0",
            description = "A strong all-rounder for its size, and unusually good at code and " +
                "languages other than English.",
        ),

        ModelSpec(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek R1 Distill 1.5B",
            vendor = "DeepSeek",
            paramsBillions = 1.5,
            quantization = Quantization.Q8,
            format = ModelFormat.LITERTLM,
            downloadUrl = "$HF/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_q8_ekv4096.litertlm",
            sizeBytes = 1_833_451_520L,
            contextTokens = 4096,
            minDeviceMemoryGb = 6,
            accelerators = setOf(Accelerator.GPU, Accelerator.CPU),
            license = "MIT",
            description = "Thinks step by step before answering. Slower to reach a first word, " +
                "but much better at maths and logic puzzles than its size implies.",
        ),

        // ---- Gated: need a HuggingFace sign-in ------------------------------------------------
        //
        // Shown to everyone, downloadable only when signed in. Both are `gated: auto`, which means
        // accepting the licence on huggingface.co is instant -- no waiting for a human to approve.

        /**
         * Google's own FunctionGemma.
         *
         * This is the LiteRT-LM build Google ships to phones, and it is the
         * `mobile-actions` fine-tune -- trained on exactly this kind of task, driving a phone UI.
         * It is the better of the two if the user is willing to sign in.
         */
        ModelSpec(
            id = "functiongemma-270m-mobile-actions",
            name = "FunctionGemma 270M (official)",
            vendor = "Google",
            paramsBillions = 0.268,
            quantization = Quantization.Q8,
            format = ModelFormat.LITERTLM,
            downloadUrl = "$HF/litert-community/functiongemma-270m-ft-mobile-actions/resolve/main/mobile_actions_q8_ekv1024.litertlm?download=true",
            fileName = "functiongemma-270m-mobile-actions_q8_ekv1024.litertlm",
            sizeBytes = 288_964_608L,
            contextTokens = 1024,
            minDeviceMemoryGb = 4,
            accelerators = setOf(Accelerator.CPU),
            license = "Gemma Terms of Use",
            description = "Google's official build of FunctionGemma, fine-tuned for driving phone " +
                "UIs. The most capable app-function model here, and it runs on anything.",
            supportsToolCalling = true,
            requiresAuth = true,
        ),

        ModelSpec(
            id = "gemma3-1b-it",
            name = "Gemma 3 1B",
            vendor = "Google",
            paramsBillions = 1.0,
            quantization = Quantization.Q4,
            format = ModelFormat.LITERTLM,
            downloadUrl = "$HF/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm?download=true",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_417_280L,
            contextTokens = 4096,
            minDeviceMemoryGb = 4,
            accelerators = setOf(Accelerator.GPU, Accelerator.CPU),
            license = "Gemma Terms of Use",
            description = "Small, fast, and genuinely good for its size. GPU accelerated. The best " +
                "everyday chat model that will run on a mid-range phone.",
            requiresAuth = true,
        ),
    )

    fun byId(id: String): ModelSpec? = builtIn.firstOrNull { it.id == id }
}
