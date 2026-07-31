package com.example.aiagent.engine.core

import kotlinx.serialization.Serializable

/** On-disk container format of a model. Determines which engines can load it. */
@Serializable
enum class ModelFormat(
    val extension: String,
    val label: String,
    /**
     * The model is owned and stored by the operating system, not by this app. There is no file to
     * download, nothing on our disk to delete, and "is it downloaded" is a question for the OS
     * service at load time rather than for [java.io.File.exists].
     */
    val systemManaged: Boolean = false,
) {
    /** Google's current on-device format, loaded by LiteRT-LM. */
    LITERTLM(".litertlm", "LiteRT-LM"),

    /** llama.cpp's format. Huge community catalogue. */
    GGUF(".gguf", "GGUF"),

    /**
     * Alibaba MNN's exported-LLM format. Unlike the single-file formats above, an MNN model is a
     * *directory* of files (config.json, llm.mnn, llm.mnn.weight, tokenizer.txt, ...) whose entry
     * point is config.json -- see [ModelSpec.files].
     */
    MNN(".mnn", "MNN"),

    /**
     * Not a file at all: Gemini Nano lives inside Android's AICore system service, which
     * downloads, updates and runs it on the OS's behalf. The extension exists only so this enum
     * stays uniform; it never matches anything on disk.
     */
    AICORE(".aicore", "AICore", systemManaged = true),
}

/**
 * Weight quantization, with the *effective* bytes-per-parameter each one costs in practice.
 *
 * These are deliberately not the textbook values (4 bits = 0.5 bytes). A real quantized file never
 * hits the textbook number: per-block scales and zero-points add overhead, and the embedding and
 * output-projection tensors are usually left at higher precision. Gemma 3 1B at int4 ships as
 * 557 MB, not the 500 MB the textbook predicts.
 *
 * These are only ever used when the real file size is unknown -- to invert a RAM budget into a
 * parameter count ([ParamBudget.maxRunnableParams]), where there is no file to measure. Whenever a
 * concrete file exists, its actual size is used instead, because the error here is not constant:
 * a 0.5B model's `q4_k_m` build costs ~0.78 bytes/param, while a 7B model's costs ~0.60, purely
 * because the un-quantized embedding table is a much bigger share of a small model.
 */
@Serializable
enum class Quantization(val bitsPerWeight: Int, val bytesPerWeight: Double, val label: String) {
    Q2(2, 0.45, "2-bit"),
    Q3(3, 0.55, "3-bit"),
    Q4(4, 0.65, "4-bit"),
    Q5(5, 0.80, "5-bit"),
    Q6(6, 0.95, "6-bit"),
    Q8(8, 1.15, "8-bit"),
    F16(16, 2.05, "16-bit"),
    F32(32, 4.05, "32-bit"),

    /** Mixed 2/4/8-bit, as used by the Gemma 4 LiteRT-LM builds. */
    MIXED(4, 0.75, "mixed"),
    ;

    companion object {
        /**
         * Reads the quantization out of a model filename, which is where -- and only where -- it is
         * recorded for an arbitrary community build. GGUF convention is `..-Q4_K_M.gguf`;
         * LiteRT-LM's is `.._q8_ekv4096.litertlm`.
         */
        fun fromFileName(fileName: String): Quantization? {
            val name = fileName.lowercase()
            // Longest tokens first: "q4_k_m" must win over a bare "q4", and "f32" must not be
            // mistaken for "f16" by a sloppy prefix match.
            return when {
                name.contains("q2_k") || name.contains("-q2") || name.contains("_q2") -> Q2
                name.contains("q3_k") || name.contains("-q3") || name.contains("_q3") -> Q3
                name.contains("q4_k") || name.contains("q4_0") || name.contains("q4_1") ||
                    name.contains("int4") || name.contains("-q4") || name.contains("_q4") -> Q4
                name.contains("q5_k") || name.contains("q5_0") || name.contains("q5_1") ||
                    name.contains("-q5") || name.contains("_q5") -> Q5
                name.contains("q6_k") || name.contains("-q6") || name.contains("_q6") -> Q6
                name.contains("q8_0") || name.contains("int8") ||
                    name.contains("-q8") || name.contains("_q8") -> Q8
                name.contains("f32") || name.contains("fp32") -> F32
                name.contains("f16") || name.contains("fp16") || name.contains("bf16") -> F16
                name.contains("mixed") -> MIXED
                else -> null
            }
        }
    }
}

/** Compute unit an engine can run inference on. */
@Serializable
enum class Accelerator(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU"),
}

/**
 * One file of a multi-file model. [relativePath] is where it lives under the app's models
 * directory, and deliberately includes the model's own subdirectory ("qwen3-0.6b-mnn/llm.mnn") so
 * two models never collide on generic names like config.json.
 */
@Serializable
data class ModelFile(
    val url: String,
    val relativePath: String,
    val sizeBytes: Long,
)

/**
 * One downloadable model in the catalogue.
 *
 * [minDeviceMemoryGb] is a *hand-curated* tier, not something derived from [sizeBytes]. That is
 * deliberate: across Google's own allowlist the required-RAM-to-file-size ratio ranges from 2.2x
 * to 20.8x, because the tier really encodes "is this class of phone capable of running this at an
 * acceptable speed", which is not a property of the file. Compare it against
 * [DeviceMemoryProfile.advertisedRamBytes], never against total RAM -- see that field's docs.
 */
@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    val vendor: String,
    /** Effective parameter count in billions. For MatFormer models (Gemma 4/3n) this is the
     *  effective, not the raw, count -- E2B raw-weighs more than 2B but activates ~2B. */
    val paramsBillions: Double,
    val quantization: Quantization,
    val format: ModelFormat,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Max KV-cache length. Prompt + response must fit inside this. */
    val contextTokens: Int,
    /** 0 means "no curated tier" -- true of anything the user added from HuggingFace. */
    val minDeviceMemoryGb: Int,
    val accelerators: Set<Accelerator>,
    val multimodal: Boolean = false,
    val license: String,
    val description: String,
    /** True for models the user added themselves, as opposed to the built-in catalogue. */
    val isCustom: Boolean = false,
    /** HuggingFace repo this came from, e.g. "Qwen/Qwen2.5-3B-Instruct-GGUF". Null for built-ins. */
    val repoId: String? = null,
    /**
     * Whether this model can drive the app through tool calling.
     *
     * Null means "work it out from the family and the size" ([ToolCallingSupport]), which is the
     * only option for a model the user added from HuggingFace. The built-in catalogue states it
     * explicitly, because a curated entry can be checked against the model card rather than guessed
     * from its name.
     */
    val supportsToolCalling: Boolean? = null,
    /**
     * The HuggingFace repo is gated, so downloading needs a signed-in account.
     *
     * These are still shown in the catalogue rather than hidden -- a user searching for "gemma" and
     * getting an inexplicably short list is worse than one who is told why a model is locked and
     * how to unlock it.
     */
    val requiresAuth: Boolean = false,
    /**
     * Every file of a *multi-file* model (MNN models are a directory, not a file). Empty for the
     * single-file formats, whose one file is already described by [downloadUrl]/[fileName]/
     * [sizeBytes]. When set, [fileName] must be the entry-point file the engine loads (MNN's
     * config.json), [sizeBytes] the total across all files, and every [ModelFile.relativePath]
     * must live under one directory named after the model.
     */
    val files: List<ModelFile> = emptyList(),
    /**
     * When the user added this model to their catalogue, in epoch millis. 0 for the built-in
     * catalogue (which has no meaningful "added" date) and for custom models saved before this was
     * tracked -- the "newest first" sort treats both as oldest. Stamped by the custom-model store
     * when a model is added.
     */
    val addedAtMillis: Long = 0L,
) {

    /** Can this model actually run app functions? */
    val canCallTools: Boolean
        get() = supportsToolCalling ?: ToolCallingSupport.infer(name, id, paramsBillions)
    val sizeGb: Double get() = sizeBytes / BYTES_PER_GB

    /** The OS owns this model (AICore); the app's download/delete machinery does not apply. */
    val systemManaged: Boolean get() = format.systemManaged

    /**
     * Everything that must be on disk for this model to load, single- and multi-file models seen
     * through one lens so the download pipeline never branches on format.
     */
    val allFiles: List<ModelFile>
        get() = files.ifEmpty { listOf(ModelFile(downloadUrl, fileName, sizeBytes)) }

    /** "1.5B", "270M" */
    val paramsLabel: String
        get() = if (paramsBillions < 1.0) {
            "${(paramsBillions * 1000).toInt()}M"
        } else {
            val s = String.format("%.1f", paramsBillions).removeSuffix(".0")
            "${s}B"
        }

    companion object {
        const val BYTES_PER_GB: Double = 1_073_741_824.0
    }
}
