package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.ParamBudget

/**
 * The context window a model actually gets on *this* phone.
 *
 * A model's advertised length is a ceiling on quality, never a promise about memory. Gemma 4 E2B's
 * card says 32k; at ~24 KiB of KV per token per billion parameters that is well over a gigabyte of
 * cache for a 2B model, before any weights. The advertised number and the affordable number are
 * different questions, and only the second one can be loaded.
 *
 * Applied to *every* model, curated or added. It used to run only on the HuggingFace path, which
 * left the built-in catalogue's hand-typed numbers going into a [com.example.aiagent.engine.core.LoadRequest]
 * verbatim -- and those are the numbers most likely to be wrong, because nothing checks a constant
 * against the device it will run on. Capping everything also makes an over-stated ceiling harmless:
 * it is trimmed here, while an under-stated one silently costs the user context for the life of the
 * entry.
 */
object DeviceContextWindow {

    /** Fallback when a model advertises none, and the window a tight device is still given. */
    const val MIN_CONTEXT = 1024

    /** The estimate runs high, so the result is rounded *down* to a whole block. */
    private const val CONTEXT_GRANULARITY = 256

    /**
     * [advertised], capped by the largest KV cache this device's budget can hold for a model of
     * this size and weight.
     *
     * Sized against the CPU deliberately -- the most pessimistic accelerator for every engine,
     * since it keeps the weights fully resident and so leaves the least room for KV. A model that
     * genuinely will not fit at all is caught separately by `ModelFitEvaluator`, which reports it
     * as EXCEEDS_MEMORY rather than quietly handing back a tiny window.
     */
    fun cap(
        advertised: Int,
        weightsBytes: Long,
        paramsBillions: Double,
        engine: EngineId,
        device: DeviceMemoryProfile,
    ): Int {
        val deviceMax = ParamBudget.maxRunnableContext(
            budgetBytes = device.modelRamBudgetBytes,
            weightsBytes = weightsBytes,
            paramsBillions = paramsBillions,
            engine = engine,
            accelerator = Accelerator.CPU,
        )
        val capped = advertised.coerceAtMost(deviceMax.coerceAtLeast(MIN_CONTEXT))
        return (capped / CONTEXT_GRANULARITY) * CONTEXT_GRANULARITY
    }
}
