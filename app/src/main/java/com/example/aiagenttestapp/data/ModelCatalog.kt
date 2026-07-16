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

        // ---- AICore -- the model Android itself carries ---------------------------------------

        /**
         * The exception to everything this file says above: not downloadable, not on HuggingFace,
         * not even a file. Gemini Nano is delivered and run by the AICore system service, so
         * `downloadUrl` is empty, `sizeBytes` is zero (nothing lands on *our* disk) and the
         * catalogue treats it as permanently downloaded. Whether the device actually supports it
         * is only knowable by asking AICore, which the engine does at load time.
         *
         * `paramsBillions` is the publicly documented Nano-2 figure; what actually runs varies by
         * device generation (Pixel 10 ships a newer build) and Google does not publish those sizes.
         */
        ModelSpec(
            id = "gemini-nano-aicore",
            name = "Gemini Nano",
            vendor = "Google",
            paramsBillions = 3.25,
            quantization = Quantization.MIXED,
            format = ModelFormat.AICORE,
            downloadUrl = "",
            fileName = "gemini-nano.aicore",
            sizeBytes = 0L,
            contextTokens = 4096,
            minDeviceMemoryGb = 0,
            accelerators = setOf(Accelerator.NPU),
            license = "Proprietary (Google)",
            description = "The Gemini built into Android itself. Runs inside the AICore system " +
                "service on the NPU -- nothing to download, and nearly no memory taken from the " +
                "app. Needs a recent flagship: Pixel 9 or newer, Galaxy S25 or newer.",
            supportsToolCalling = false,
        ),

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
         * Google's own FunctionGemma, as opposed to the community GGUF rebuild further down.
         *
         * Same lineage, but this is the LiteRT-LM build Google ships to phones, and it is the
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

        // ---- GGUF (llama.cpp) -- CPU only, but the widest catalogue anywhere ------------------

        /**
         * The exception to the tool-calling size floor, and the reason app functions are
         * usable on a cheap phone at all.
         *
         * FunctionGemma is a separate model, not a mode of Gemma: a 270M checkpoint Google
         * fine-tuned specifically to emit function calls. It is a poor conversationalist -- do not
         * ask it to write an essay -- but at 278 MB it will run on anything, and it does the one
         * thing it was built for far better than models ten times its size.
         *
         * Google's own repositories for it are HuggingFace-gated; this is an ungated community
         * rebuild of the same weights, which is the only reason it can be in a no-sign-in catalogue.
         *
         * Q8 rather than Q4 on purpose. Quantisation error is proportionally far more damaging to a
         * 270M model, and the whole value here is emitting *exactly* the right JSON -- the 100 MB
         * saved by going to 4-bit is not worth trading that away.
         */
        ModelSpec(
            id = "functiongemma-270m-gguf",
            name = "FunctionGemma 270M",
            vendor = "Google (community build)",
            paramsBillions = 0.268,
            quantization = Quantization.Q8,
            format = ModelFormat.GGUF,
            downloadUrl = "$HF/unsloth/functiongemma-270m-it-GGUF/resolve/main/functiongemma-270m-it-Q8_0.gguf?download=true",
            fileName = "functiongemma-270m-it-Q8_0.gguf",
            sizeBytes = 291_558_624L,
            contextTokens = 4096,
            minDeviceMemoryGb = 3,
            accelerators = setOf(Accelerator.CPU),
            license = "Gemma Terms of Use",
            description = "Built for one job: controlling apps. Tiny, runs on any phone, and the " +
                "most reliable model here at app functions -- but it is not a chat model, so " +
                "expect little from ordinary conversation.",
            supportsToolCalling = true,
        ),

        ModelSpec(
            id = "qwen2.5-0.5b-instruct-gguf",
            name = "Qwen 2.5 0.5B",
            vendor = "Alibaba",
            paramsBillions = 0.5,
            quantization = Quantization.Q4,
            format = ModelFormat.GGUF,
            downloadUrl = "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491_400_032L,
            contextTokens = 4096,
            minDeviceMemoryGb = 4,
            accelerators = setOf(Accelerator.CPU),
            license = "Apache-2.0",
            description = "Tiny and quick. Good for summarising and rewriting; it will struggle " +
                "with anything that needs real reasoning.",
        ),

        ModelSpec(
            id = "smollm2-360m-instruct-gguf",
            name = "SmolLM2 360M",
            vendor = "HuggingFace",
            paramsBillions = 0.36,
            quantization = Quantization.Q8,
            format = ModelFormat.GGUF,
            downloadUrl = "$HF/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf?download=true",
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            sizeBytes = 386_404_992L,
            contextTokens = 2048,
            minDeviceMemoryGb = 3,
            accelerators = setOf(Accelerator.CPU),
            license = "Apache-2.0",
            description = "About as small as a useful chat model gets. Runs on almost anything, " +
                "including phones that cannot load anything else here.",
        ),

        ModelSpec(
            id = "llama-3.2-1b-instruct-gguf",
            name = "Llama 3.2 1B",
            vendor = "Meta (community build)",
            paramsBillions = 1.24,
            quantization = Quantization.Q4,
            format = ModelFormat.GGUF,
            // Meta's own repo is gated; this community requant of the same weights is not.
            downloadUrl = "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            contextTokens = 4096,
            minDeviceMemoryGb = 4,
            accelerators = setOf(Accelerator.CPU),
            license = "Llama 3.2 Community License",
            description = "Meta's small Llama. Conversational and well-behaved. Reached here " +
                "through a community rebuild, because Meta's own repository requires sign-in.",
        ),

        ModelSpec(
            id = "qwen2.5-3b-instruct-gguf",
            name = "Qwen 2.5 3B",
            vendor = "Alibaba",
            paramsBillions = 3.09,
            quantization = Quantization.Q4,
            format = ModelFormat.GGUF,
            downloadUrl = "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            contextTokens = 4096,
            minDeviceMemoryGb = 8,
            accelerators = setOf(Accelerator.CPU),
            license = "Qwen Research License",
            description = "The largest model here that still runs at a readable speed on the CPU. " +
                "Clearly sharper than the 1.5B models.",
        ),

        // ---- MNN -- Alibaba's engine, CPU-tuned; one model is a *directory* of files ------------
        //
        // These are Alibaba's own exports (the `taobao-mnn` org on HuggingFace), all ungated.
        // Unlike the formats above, an MNN model is several files sharing a directory; `fileName`
        // is the config.json entry point the engine loads, and `files` lists everything that must
        // be downloaded. Sizes are the Hub's authoritative per-file sizes.

        ModelSpec(
            id = "qwen3-0.6b-mnn",
            name = "Qwen 3 0.6B",
            vendor = "Alibaba",
            paramsBillions = 0.6,
            quantization = Quantization.Q4,
            format = ModelFormat.MNN,
            downloadUrl = "$HF/taobao-mnn/Qwen3-0.6B-MNN/resolve/main/config.json?download=true",
            fileName = "qwen3-0.6b-mnn/config.json",
            sizeBytes = 454_470_710L,
            contextTokens = 4096,
            minDeviceMemoryGb = 4,
            accelerators = setOf(Accelerator.CPU),
            license = "Apache-2.0",
            description = "The newest small Qwen, on Alibaba's own runtime. Can think step by " +
                "step before answering. Small enough for almost any phone, and quick on the " +
                "CPU thanks to MNN's phone-first kernels.",
            files = listOf(
                mnnFile("Qwen3-0.6B-MNN", "qwen3-0.6b-mnn", "config.json", 403L),
                mnnFile("Qwen3-0.6B-MNN", "qwen3-0.6b-mnn", "llm.mnn", 461_520L),
                mnnFile("Qwen3-0.6B-MNN", "qwen3-0.6b-mnn", "llm.mnn.weight", 450_810_338L),
                mnnFile("Qwen3-0.6B-MNN", "qwen3-0.6b-mnn", "llm_config.json", 4_880L),
                mnnFile("Qwen3-0.6B-MNN", "qwen3-0.6b-mnn", "tokenizer.txt", 3_193_569L),
            ),
        ),

        ModelSpec(
            id = "qwen2.5-1.5b-instruct-mnn",
            name = "Qwen 2.5 1.5B (MNN)",
            vendor = "Alibaba",
            paramsBillions = 1.5,
            quantization = Quantization.Q4,
            format = ModelFormat.MNN,
            downloadUrl = "$HF/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/config.json?download=true",
            fileName = "qwen2.5-1.5b-instruct-mnn/config.json",
            sizeBytes = 879_481_306L,
            contextTokens = 4096,
            minDeviceMemoryGb = 6,
            accelerators = setOf(Accelerator.CPU),
            license = "Apache-2.0",
            description = "The same strong all-rounder as the LiteRT-LM build above, exported by " +
                "Alibaba for its own MNN runtime -- at 4-bit it is roughly half the download.",
            files = listOf(
                mnnFile("Qwen2.5-1.5B-Instruct-MNN", "qwen2.5-1.5b-instruct-mnn", "config.json", 159L),
                mnnFile("Qwen2.5-1.5B-Instruct-MNN", "qwen2.5-1.5b-instruct-mnn", "llm.mnn", 1_145_128L),
                mnnFile("Qwen2.5-1.5B-Instruct-MNN", "qwen2.5-1.5b-instruct-mnn", "llm.mnn.json", 6_650_652L),
                mnnFile("Qwen2.5-1.5B-Instruct-MNN", "qwen2.5-1.5b-instruct-mnn", "llm.mnn.weight", 868_491_506L),
                mnnFile("Qwen2.5-1.5B-Instruct-MNN", "qwen2.5-1.5b-instruct-mnn", "llm_config.json", 384L),
                mnnFile("Qwen2.5-1.5B-Instruct-MNN", "qwen2.5-1.5b-instruct-mnn", "tokenizer.txt", 3_193_477L),
            ),
        ),
    )

    /** One file of an MNN model: `taobao-mnn/[repo]` on the Hub, `models/[dir]/[name]` on disk. */
    private fun mnnFile(repo: String, dir: String, name: String, sizeBytes: Long) = ModelFile(
        url = "$HF/taobao-mnn/$repo/resolve/main/$name?download=true",
        relativePath = "$dir/$name",
        sizeBytes = sizeBytes,
    )

    fun byId(id: String): ModelSpec? = builtIn.firstOrNull { it.id == id }
}
