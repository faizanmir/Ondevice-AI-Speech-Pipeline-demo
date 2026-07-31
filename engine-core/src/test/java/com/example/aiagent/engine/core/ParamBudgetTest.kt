package com.example.aiagent.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory maths is the product. If it is wrong in the optimistic direction the app promises a
 * model that then gets killed on load; if it is wrong the other way it hides models that would have
 * run fine. Both are silent failures in the field, so they get pinned down here.
 */
class ParamBudgetTest {

    private fun gb(value: Double): Long = (value * 1_073_741_824.0).toLong()

    private fun device(totalGb: Double, advertisedGb: Int) = DeviceMemoryProfile(
        advertisedRamBytes = gb(advertisedGb.toDouble()),
        totalRamBytes = gb(totalGb),
        availableRamBytes = gb(totalGb / 2),
        lowMemoryThresholdBytes = gb(0.2),
        isLowRamDevice = false,
        freeStorageBytes = gb(64.0),
        socModel = "TEST",
        supportedAbis = listOf("arm64-v8a"),
    )

    /** An 8 GB phone reports ~7.4 GB of kernel-visible RAM after the carveout. */
    private val eightGbPhone = device(totalGb = 7.4, advertisedGb = 8)

    @Test
    fun `estimate and inverse agree`() {
        // The two directions must be consistent, or the headline the user reads ("up to 3.9B")
        // would contradict the per-model verdict they get when they tap one.
        val budget = eightGbPhone.modelRamBudgetBytes

        val maxParams = ParamBudget.maxRunnableParams(
            budgetBytes = budget,
            quantization = Quantization.Q4,
            contextTokens = 4096,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        val peakAtMax = ParamBudget.estimatePeakRamBytes(
            weightsBytes = ParamBudget.estimateWeightsBytes(maxParams, Quantization.Q4),
            paramsBillions = maxParams,
            contextTokens = 4096,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        // Round-trips to the budget, give or take rounding.
        assertEquals(budget.toDouble(), peakAtMax.toDouble(), gb(0.01).toDouble())
    }

    @Test
    fun `a model just over the budget does not fit`() {
        val budget = eightGbPhone.modelRamBudgetBytes
        val maxParams = ParamBudget.maxRunnableParams(
            budgetBytes = budget,
            quantization = Quantization.Q4,
            contextTokens = 4096,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        val overBudget = ParamBudget.estimatePeakRamBytes(
            weightsBytes = ParamBudget.estimateWeightsBytes(maxParams * 1.1, Quantization.Q4),
            paramsBillions = maxParams * 1.1,
            contextTokens = 4096,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        assertTrue("10% more parameters must exceed the budget", overBudget > budget)
    }

    @Test
    fun `lower precision buys more parameters`() {
        val budget = eightGbPhone.modelRamBudgetBytes

        fun maxAt(q: Quantization) = ParamBudget.maxRunnableParams(
            budgetBytes = budget,
            quantization = q,
            contextTokens = 4096,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        // This ordering is the entire premise of the precision chips on the catalogue header.
        assertTrue(maxAt(Quantization.Q4) > maxAt(Quantization.Q8))
        assertTrue(maxAt(Quantization.Q8) > maxAt(Quantization.F16))
    }

    @Test
    fun `an 8gb phone lands in the 3 to 5 billion range at 4-bit`() {
        // Sanity anchor against reality: an 8 GB phone runs a 4-bit 3B comfortably and cannot run a
        // 4-bit 7B. If a refactor moves this number outside that range, the model is wrong, not the
        // test.
        val maxParams = ParamBudget.maxRunnableParams(
            device = eightGbPhone,
            quantization = Quantization.Q4,
            contextTokens = 4096,
            engine = EngineId.LITE_RT_LM,
            accelerator = Accelerator.CPU,
        )

        assertTrue("expected 3B-5B, got ${"%.2f".format(maxParams)}B", maxParams in 3.0..5.0)
    }

    @Test
    fun `gpu residency lets the gpu run bigger models than the cpu`() {
        val budget = eightGbPhone.modelRamBudgetBytes

        val onGpu = ParamBudget.maxRunnableParams(
            budgetBytes = budget,
            quantization = Quantization.Q4,
            contextTokens = 4096,
            engine = EngineId.LITE_RT_LM,
            accelerator = Accelerator.GPU,
        )
        val onCpu = ParamBudget.maxRunnableParams(
            budgetBytes = budget,
            quantization = Quantization.Q4,
            contextTokens = 4096,
            engine = EngineId.LITE_RT_LM,
            accelerator = Accelerator.CPU,
        )

        // LiteRT-LM pushes weights into GPU memory and mmaps embeddings, so far less is charged to
        // the process. This is why Gemma-4-E2B peaks at ~676 MB on the GPU despite a 2.6 GB file.
        assertTrue("GPU ($onGpu B) should beat CPU ($onCpu B)", onGpu > onCpu)
    }

    @Test
    fun `a longer context costs parameters`() {
        val budget = eightGbPhone.modelRamBudgetBytes

        fun maxAt(ctx: Int) = ParamBudget.maxRunnableParams(
            budgetBytes = budget,
            quantization = Quantization.Q4,
            contextTokens = ctx,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        // The KV cache is not free, and at long contexts it is a real share of the budget.
        assertTrue(maxAt(1024) > maxAt(8192))
    }

    /**
     * Locks in the calibration. These are the numbers a real user reads off the catalogue header,
     * and they are cross-checked against Google's own device tiers: a 6 GB phone is the entry point
     * for a ~1.5B model and an 8 GB phone for a ~2B multimodal one, so a ladder that puts 6 GB at
     * 2B and 8 GB at 3B is in the right place. If a change to the RAM model moves these, it is
     * making a claim about real hardware and should have to say so out loud.
     */
    @Test
    fun `the parameter ladder across RAM tiers is plausible`() {
        val ladder = listOf(
            // kernel-visible GB, marketed GB, expected range
            Triple(3.6, 4, 0.6..1.3),
            Triple(5.5, 6, 1.6..2.5),
            Triple(7.4, 8, 2.7..3.8),
            Triple(11.1, 12, 4.6..6.2),
        )

        ladder.forEach { (totalGb, advertisedGb, expected) ->
            val maxParams = ParamBudget.maxRunnableParams(
                device = device(totalGb, advertisedGb),
                quantization = Quantization.Q4,
                contextTokens = 4096,
                engine = EngineId.LITE_RT_LM,
                accelerator = Accelerator.CPU,
            )
            assertTrue(
                "${advertisedGb}GB phone -> ${"%.2f".format(maxParams)}B, expected $expected",
                maxParams in expected,
            )
        }
    }

    @Test
    fun `a tiny budget yields no runnable model rather than a negative one`() {
        val maxParams = ParamBudget.maxRunnableParams(
            budgetBytes = gb(0.1),
            quantization = Quantization.Q4,
            contextTokens = 4096,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )
        // The runtime overhead alone exceeds the budget. Must clamp, not go negative and render as
        // "-0.3B parameters".
        assertEquals(0.0, maxParams, 0.0001)
    }

    @Test
    fun `the context inverse round-trips to the budget`() {
        // maxRunnableContext solves peak == budget for ctx, so putting that ctx back through
        // estimatePeakRamBytes must land on the budget without going over it -- the same
        // consistency the params direction guarantees, since this is what sizes the real KV cache.
        val budget = eightGbPhone.modelRamBudgetBytes
        val params = 1.5
        val weights = ParamBudget.estimateWeightsBytes(params, Quantization.Q4)

        val maxCtx = ParamBudget.maxRunnableContext(
            budgetBytes = budget,
            weightsBytes = weights,
            paramsBillions = params,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        val peakAtMax = ParamBudget.estimatePeakRamBytes(
            weightsBytes = weights,
            paramsBillions = params,
            contextTokens = maxCtx,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        assertTrue("peak $peakAtMax must not exceed budget $budget", peakAtMax <= budget)
        assertEquals(budget.toDouble(), peakAtMax.toDouble(), gb(0.01).toDouble())
    }

    @Test
    fun `a roomier device affords a longer context for the same model`() {
        val params = 1.5
        val weights = ParamBudget.estimateWeightsBytes(params, Quantization.Q4)

        fun ctxOn(dev: DeviceMemoryProfile) = ParamBudget.maxRunnableContext(
            budgetBytes = dev.modelRamBudgetBytes,
            weightsBytes = weights,
            paramsBillions = params,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )

        // More RAM left over after the weights is more room for KV, which is the whole point.
        assertTrue(ctxOn(device(totalGb = 11.1, advertisedGb = 12)) > ctxOn(eightGbPhone))
    }

    @Test
    fun `aicore context is unbounded because its cache is out of process`() {
        // AICore hosts the KV cache in the system service, so the app must not shrink it to fit a
        // budget it does not pay -- the sentinel says "do not cap".
        val ctx = ParamBudget.maxRunnableContext(
            budgetBytes = gb(0.1),
            weightsBytes = gb(2.0),
            paramsBillions = 3.0,
            engine = EngineId.AICORE,
            accelerator = Accelerator.CPU,
        )
        assertEquals(Int.MAX_VALUE, ctx)
    }

    @Test
    fun `weights that already blow the budget yield no context rather than a negative one`() {
        val ctx = ParamBudget.maxRunnableContext(
            budgetBytes = gb(0.5),
            weightsBytes = gb(4.0), // the weights alone exceed the budget
            paramsBillions = 7.0,
            engine = EngineId.LLAMA_CPP,
            accelerator = Accelerator.CPU,
        )
        assertEquals(0, ctx)
    }
}
