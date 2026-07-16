package com.example.aiagent.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFitEvaluatorTest {

    private fun gb(value: Double): Long = (value * 1_073_741_824.0).toLong()

    private fun device(
        totalGb: Double,
        advertisedGb: Int,
        freeStorageGb: Double = 64.0,
        abis: List<String> = listOf("arm64-v8a"),
    ) = DeviceMemoryProfile(
        advertisedRamBytes = gb(advertisedGb.toDouble()),
        totalRamBytes = gb(totalGb),
        availableRamBytes = gb(totalGb / 2),
        lowMemoryThresholdBytes = gb(0.2),
        isLowRamDevice = false,
        freeStorageBytes = gb(freeStorageGb),
        socModel = "TEST",
        supportedAbis = abis,
    )

    private val liteRtLm = EngineDescriptor(
        id = EngineId.LITE_RT_LM,
        displayName = "LiteRT-LM",
        vendor = "Google",
        supportedFormats = setOf(ModelFormat.LITERTLM),
        supportedAccelerators = setOf(Accelerator.CPU, Accelerator.GPU),
        supportsVision = true,
        blurb = "",
    )

    private val llamaCpp = EngineDescriptor(
        id = EngineId.LLAMA_CPP,
        displayName = "llama.cpp",
        vendor = "ggml",
        supportedFormats = setOf(ModelFormat.GGUF),
        supportedAccelerators = setOf(Accelerator.CPU),
        supportsVision = false,
        blurb = "",
    )

    private fun model(
        params: Double,
        minRamGb: Int,
        sizeBytes: Long = 1_000_000_000L,
        format: ModelFormat = ModelFormat.LITERTLM,
        quant: Quantization = Quantization.Q4,
    ) = ModelSpec(
        id = "test",
        name = "Test",
        vendor = "Test",
        paramsBillions = params,
        quantization = quant,
        format = format,
        downloadUrl = "https://example.com/model",
        fileName = "model${format.extension}",
        sizeBytes = sizeBytes,
        contextTokens = 4096,
        minDeviceMemoryGb = minRamGb,
        accelerators = setOf(Accelerator.CPU, Accelerator.GPU),
        license = "Apache-2.0",
        description = "",
    )

    /**
     * The bug this whole design exists to prevent.
     *
     * An 8 GB phone reports ~7.4 GB of totalMem after the kernel carveout. A model tiered at
     * "needs 8 GB" must still be offered to it -- gating on totalMem instead of the marketed figure
     * would reject every 8 GB phone on the market.
     */
    @Test
    fun `an 8gb phone can run a model tiered at 8gb`() {
        val fit = ModelFitEvaluator.evaluate(
            model = model(params = 2.0, minRamGb = 8),
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = device(totalGb = 7.4, advertisedGb = 8),
        )

        assertTrue("8 GB phone was wrongly denied an 8 GB model: ${fit.reason}", fit.canRun)
    }

    @Test
    fun `a 6gb phone is refused a model tiered at 8gb`() {
        val fit = ModelFitEvaluator.evaluate(
            model = model(params = 4.0, minRamGb = 8),
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = device(totalGb = 5.5, advertisedGb = 6),
        )

        assertEquals(FitVerdict.EXCEEDS_MEMORY, fit.verdict)
        assertFalse(fit.canRun)
        // It can still be downloaded -- the user may free memory, or switch engine.
        assertTrue(fit.canDownload)
    }

    @Test
    fun `meeting the tier exactly reads as tight, not comfortable`() {
        val fit = ModelFitEvaluator.evaluate(
            model = model(params = 1.0, minRamGb = 8),
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = device(totalGb = 7.4, advertisedGb = 8),
        )

        assertEquals(FitVerdict.TIGHT, fit.verdict)
    }

    @Test
    fun `no disk space blocks the download outright`() {
        val fit = ModelFitEvaluator.evaluate(
            model = model(params = 2.0, minRamGb = 4, sizeBytes = gb(3.0)),
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = device(totalGb = 7.4, advertisedGb = 8, freeStorageGb = 1.0),
        )

        assertEquals(FitVerdict.INSUFFICIENT_STORAGE, fit.verdict)
        // The one case where downloading really is pointless.
        assertFalse(fit.canDownload)
    }

    /**
     * A model already on disk must not be judged on free space.
     *
     * Otherwise a model the user downloaded happily runs until the disk later fills up, at which
     * point the card flips to "No space" and refuses to open -- even though the file is right
     * there and nothing about the model changed. Free space gates *fetching*, not running.
     */
    @Test
    fun `a downloaded model ignores free storage`() {
        val fullDisk = device(totalGb = 7.4, advertisedGb = 8, freeStorageGb = 0.1)
        val spec = model(params = 1.0, minRamGb = 4, sizeBytes = gb(2.0))

        val notYetDownloaded = ModelFitEvaluator.evaluate(
            model = spec,
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = fullDisk,
            isDownloaded = false,
        )
        assertEquals(FitVerdict.INSUFFICIENT_STORAGE, notYetDownloaded.verdict)

        val alreadyDownloaded = ModelFitEvaluator.evaluate(
            model = spec,
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = fullDisk,
            isDownloaded = true,
        )
        assertTrue(
            "a downloaded model should still run on a full disk: ${alreadyDownloaded.reason}",
            alreadyDownloaded.canRun,
        )
    }

    @Test
    fun `an engine that cannot read the format reports unsupported`() {
        val fit = ModelFitEvaluator.evaluate(
            // A GGUF handed to LiteRT-LM.
            model = model(params = 1.0, minRamGb = 4, format = ModelFormat.GGUF),
            engine = liteRtLm,
            accelerator = Accelerator.CPU,
            device = device(totalGb = 7.4, advertisedGb = 8),
        )

        assertEquals(FitVerdict.UNSUPPORTED, fit.verdict)
        assertFalse(fit.canDownload)
    }

    @Test
    fun `switching engine can change the verdict for the same model`() {
        // The same 3B model, same phone. llama.cpp keeps every weight resident on the CPU;
        // LiteRT-LM pushes them to the GPU. That difference is exactly what the engine picker is
        // for, and the fit badge has to move with it or the picker is a lie.
        val device = device(totalGb = 5.5, advertisedGb = 6)

        val onLiteRtGpu = ModelFitEvaluator.evaluate(
            model = model(params = 3.0, minRamGb = 6),
            engine = liteRtLm,
            accelerator = Accelerator.GPU,
            device = device,
        )
        val onLlamaCpu = ModelFitEvaluator.evaluate(
            model = model(params = 3.0, minRamGb = 6, format = ModelFormat.GGUF),
            engine = llamaCpp,
            accelerator = Accelerator.CPU,
            device = device,
        )

        assertTrue(onLiteRtGpu.estimatedPeakRamBytes < onLlamaCpu.estimatedPeakRamBytes)
    }

    @Test
    fun `a 32-bit device is unsupported`() {
        val fit = ModelFitEvaluator.evaluate(
            model = model(params = 1.0, minRamGb = 4),
            engine = liteRtLm,
            accelerator = Accelerator.CPU,
            device = device(totalGb = 3.6, advertisedGb = 4, abis = listOf("armeabi-v7a")),
        )

        assertEquals(FitVerdict.UNSUPPORTED, fit.verdict)
    }

    @Test
    fun `evaluateBest picks the most favourable accelerator`() {
        val device = device(totalGb = 7.4, advertisedGb = 8)
        val spec = model(params = 4.0, minRamGb = 8)

        val best = ModelFitEvaluator.evaluateBest(spec, liteRtLm, device)
        val onCpu = ModelFitEvaluator.evaluate(spec, liteRtLm, Accelerator.CPU, device)

        // The catalogue should show what the user gets when the app picks for them, which is the
        // GPU -- so the headline verdict must be at least as good as the CPU-only one.
        assertTrue(best.verdict.ordinal >= onCpu.verdict.ordinal)
    }
}
