package com.example.aiagent.engine.core

import kotlin.math.roundToLong

/**
 * Turns device RAM into a parameter budget, and a model's parameter count back into a RAM cost.
 *
 * The model:
 *
 *     peak RAM = weights x residency x activation_factor  +  KV cache  +  runtime overhead
 *
 *   weights   = params x bytesPerWeight        (see [Quantization] -- includes quant overhead)
 *   residency = share of weights actually resident (see [weightResidency]); mmap'd weights and
 *               weights that live in GPU memory do not count against the app's RAM
 *   KV cache  = context x bytes-per-token, and bytes-per-token scales with model width, which in
 *               turn scales roughly linearly with parameter count for models in this size class
 *   overhead  = tokenizer, graph, framework allocations -- roughly constant
 *
 * Every term is linear in `params`, so it inverts in closed form: given a RAM budget we can solve
 * directly for the largest model that fits. That inversion is [maxRunnableParams], which is what
 * lets the app tell a user "your 8 GB phone runs up to ~3.9B parameters at 4-bit" instead of making
 * them work it out from a list of file sizes.
 *
 * These are estimates. Where the catalogue carries a hand-curated [ModelSpec.minDeviceMemoryGb],
 * that wins -- see [ModelFitEvaluator].
 */
object ParamBudget {

    private const val MB = 1024L * 1024L
    private const val BILLION = 1_000_000_000.0

    /** Tokenizer, execution graph, JNI buffers, framework. Roughly independent of model size. */
    const val RUNTIME_OVERHEAD_BYTES: Long = 250 * MB

    /** Transient prefill/decode scratch, as a multiple of resident weights. */
    const val ACTIVATION_FACTOR: Double = 1.15

    /**
     * KV-cache cost: ~24 KiB per token, per billion parameters.
     *
     * Derived from the real geometry of this size class rather than assumed. Gemma 3 1B
     * (26 layers, 1 KV head, head_dim 256, fp16) costs 2 x 26 x 1 x 256 x 2 = 26 KB/token;
     * Qwen 2.5 1.5B (28 layers, 2 KV heads, head_dim 128) costs 28 KB/token for 1.5x the
     * parameters. Grouped-query attention is what keeps this from exploding.
     */
    const val KV_BYTES_PER_TOKEN_PER_BILLION: Double = 24.0 * 1024.0

    /**
     * Fraction of a model's weights that occupy app-visible RAM on a given engine+accelerator.
     *
     * This is why file size is a bad proxy for RAM. Gemma-4-E2B is a 2.58 GB file that peaks at
     * 676 MB on the GPU -- LiteRT-LM memory-maps the 1.12 GB embedding table instead of heap-loading
     * it, and pushes the rest into GPU memory, which is not charged to the process. Assuming
     * residency 1.0 would tell an 8 GB user the model does not fit when in fact it fits four times
     * over.
     */
    fun weightResidency(engine: EngineId, accelerator: Accelerator): Double = when (engine) {
        // Weights live in GPU memory; embeddings are mmap'd from disk.
        EngineId.LITE_RT_LM -> when (accelerator) {
            Accelerator.GPU -> 0.35
            Accelerator.NPU -> 0.45
            Accelerator.CPU -> 0.70
        }
        // llama.cpp mmaps the GGUF, but CPU inference touches every weight each forward pass, so
        // the pages stay resident and it bills at close to full file size.
        EngineId.LLAMA_CPP -> when (accelerator) {
            Accelerator.GPU -> 0.60
            else -> 1.00
        }
    }

    /**
     * Peak RAM for a model whose file we can actually measure.
     *
     * [weightsBytes] is the real file size, and using it rather than `params x bytesPerWeight` is
     * the single biggest accuracy win available here. The bytes-per-parameter of a quantization is
     * not a constant: `q4_k_m` costs ~0.78 bytes/param on a 0.5B model but ~0.60 on a 7B one,
     * because the embedding table stays at high precision and dominates a small model. Estimating
     * from parameters would therefore be wrong by 20-30% in a direction that varies with model
     * size -- while the file size is simply a fact.
     *
     * [paramsBillions] is still needed, but only to size the KV cache, which scales with the
     * model's width rather than with its file.
     */
    fun estimatePeakRamBytes(
        weightsBytes: Long,
        paramsBillions: Double,
        contextTokens: Int,
        engine: EngineId,
        accelerator: Accelerator,
    ): Long {
        val residency = weightResidency(engine, accelerator)
        val residentWeights = weightsBytes * residency
        val kvCache = contextTokens * KV_BYTES_PER_TOKEN_PER_BILLION * paramsBillions
        return (residentWeights * ACTIVATION_FACTOR + kvCache).roundToLong() + RUNTIME_OVERHEAD_BYTES
    }

    /**
     * What a model of [paramsBillions] would weigh on disk, when there is no actual file to look
     * at. Only for hypotheticals -- prefer the real size whenever one exists.
     */
    fun estimateWeightsBytes(paramsBillions: Double, quantization: Quantization): Long =
        (paramsBillions * BILLION * quantization.bytesPerWeight).roundToLong()

    /**
     * The inverse: largest model, in billions of parameters, that fits in [budgetBytes].
     *
     * Solves `budget = p*(bpw*residency*activation + ctx*kvPerB) + overhead` for `p`.
     */
    fun maxRunnableParams(
        budgetBytes: Long,
        quantization: Quantization,
        contextTokens: Int,
        engine: EngineId,
        accelerator: Accelerator,
    ): Double {
        val usable = budgetBytes - RUNTIME_OVERHEAD_BYTES
        if (usable <= 0) return 0.0

        val residency = weightResidency(engine, accelerator)
        // Bytes consumed per billion parameters, at this context length.
        val bytesPerBillion =
            BILLION * quantization.bytesPerWeight * residency * ACTIVATION_FACTOR +
                contextTokens * KV_BYTES_PER_TOKEN_PER_BILLION

        if (bytesPerBillion <= 0) return 0.0
        return (usable / bytesPerBillion).coerceAtLeast(0.0)
    }

    /** Convenience: the headline figure for the catalogue header. */
    fun maxRunnableParams(
        device: DeviceMemoryProfile,
        quantization: Quantization = Quantization.Q4,
        contextTokens: Int = 4096,
        engine: EngineId = EngineId.LITE_RT_LM,
        accelerator: Accelerator = Accelerator.CPU,
    ): Double = maxRunnableParams(
        budgetBytes = device.modelRamBudgetBytes,
        quantization = quantization,
        contextTokens = contextTokens,
        engine = engine,
        accelerator = accelerator,
    )

    /**
     * The mirror of [maxRunnableParams], solved for context instead of params: the largest KV-cache
     * length, in tokens, a model of this exact size can be given before its peak RAM would exceed
     * [budgetBytes]. Solves the same `peak = residentWeights*activation + ctx*kvPerToken + overhead`
     * for `ctx`.
     *
     * This is what lets the app size a model's context window to the device -- honouring the length
     * a GGUF advertises when the KV cache fits, trimming it when it does not -- instead of clamping
     * every model to one flat constant.
     */
    fun maxRunnableContext(
        budgetBytes: Long,
        weightsBytes: Long,
        paramsBillions: Double,
        engine: EngineId,
        accelerator: Accelerator,
    ): Int {
        val residentWeights = weightsBytes * weightResidency(engine, accelerator)
        // Everything except the KV cache is paid out of the budget first; the remainder buys context.
        val kvBudget = budgetBytes - RUNTIME_OVERHEAD_BYTES -
            (residentWeights * ACTIVATION_FACTOR).roundToLong()
        val bytesPerToken = KV_BYTES_PER_TOKEN_PER_BILLION * paramsBillions
        if (kvBudget <= 0 || bytesPerToken <= 0) return 0
        return (kvBudget / bytesPerToken).toInt()
    }
}
