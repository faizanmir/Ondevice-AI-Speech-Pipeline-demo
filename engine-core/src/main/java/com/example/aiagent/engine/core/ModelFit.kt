package com.example.aiagent.engine.core

/**
 * Whether a model can run on this device. Ordered worst-to-best so `maxOf`/`minOf` compose.
 */
enum class FitVerdict {
    /** No engine on this build can load the format, or the ABI is wrong. */
    UNSUPPORTED,

    /** Not enough free disk to even download it. */
    INSUFFICIENT_STORAGE,

    /** Would very likely be killed by the low-memory killer. Downloadable, but blocked from running. */
    EXCEEDS_MEMORY,

    /** Fits, but with little headroom. Expect slow first tokens and possible pressure under multitasking. */
    TIGHT,

    /** Runs with room to spare. */
    COMFORTABLE,
    ;

    val canRun: Boolean get() = this == TIGHT || this == COMFORTABLE
}

data class ModelFit(
    val verdict: FitVerdict,
    /** One short sentence, safe to show in the UI verbatim. */
    val reason: String,
    val estimatedPeakRamBytes: Long,
    val ramBudgetBytes: Long,
    val deviceRamGb: Int,
    val requiredRamGb: Int,
    val engine: EngineId,
    val accelerator: Accelerator,
) {
    val canRun: Boolean get() = verdict.canRun

    /**
     * Downloading is allowed even when the model will not run: the user may be about to free
     * memory, switch to a lighter engine, or simply want it staged. Only a genuine lack of disk
     * space blocks the download itself.
     */
    val canDownload: Boolean get() = verdict != FitVerdict.INSUFFICIENT_STORAGE &&
        verdict != FitVerdict.UNSUPPORTED

    /** 0f..1f -- share of the RAM budget this model would consume. Drives the meter in the UI. */
    val budgetUsedFraction: Float
        get() = if (ramBudgetBytes <= 0) 1f
        else (estimatedPeakRamBytes.toFloat() / ramBudgetBytes.toFloat()).coerceIn(0f, 1f)
}

object ModelFitEvaluator {

    /** Below this share of the RAM budget, a model is comfortable rather than tight. */
    private const val COMFORTABLE_THRESHOLD = 0.75

    /**
     * Decides whether [model] runs on [device] under [engine].
     *
     * Two independent checks, and the more pessimistic wins:
     *
     *  1. The curated tier ([ModelSpec.minDeviceMemoryGb]) against the device's *marketed* RAM.
     *     Hand-picked per model, and the authority when present -- it captures things a formula
     *     cannot, like a 270M model still needing a 6 GB-class phone to be usable at all.
     *  2. The computed peak ([ParamBudget.estimatePeakRamBytes]) against the device's RAM budget.
     *     This is what generalises: it covers custom GGUF files the catalogue has never seen, and
     *     it is what makes the verdict move when the user switches engine or accelerator.
     */
    fun evaluate(
        model: ModelSpec,
        engine: EngineDescriptor,
        accelerator: Accelerator,
        device: DeviceMemoryProfile,
        /**
         * Whether the file is already on disk. When it is, the free-space check is skipped:
         * space that would be needed to *fetch* a model says nothing about whether an
         * already-downloaded one can run, and checking it anyway makes a model the user has in
         * hand report "no space" the moment the disk later fills up.
         */
        isDownloaded: Boolean = false,
    ): ModelFit {
        val peak = ParamBudget.estimatePeakRamBytes(
            // The file size is a measured fact; deriving it from the parameter count would inject
            // a 20-30% error that varies with model size. See ParamBudget.estimatePeakRamBytes.
            weightsBytes = model.sizeBytes,
            paramsBillions = model.paramsBillions,
            contextTokens = model.contextTokens,
            engine = engine.id,
            accelerator = accelerator,
        )
        val budget = device.modelRamBudgetBytes

        fun fit(verdict: FitVerdict, reason: String) = ModelFit(
            verdict = verdict,
            reason = reason,
            estimatedPeakRamBytes = peak,
            ramBudgetBytes = budget,
            deviceRamGb = device.advertisedRamGb,
            requiredRamGb = model.minDeviceMemoryGb,
            engine = engine.id,
            accelerator = accelerator,
        )

        if (!engine.canLoad(model.format)) {
            return fit(
                FitVerdict.UNSUPPORTED,
                "${engine.displayName} cannot load ${model.format.label} files",
            )
        }
        if (!device.is64Bit) {
            return fit(FitVerdict.UNSUPPORTED, "Requires a 64-bit device")
        }

        // 5% slack so a download does not land the user on a completely full disk.
        val storageNeeded = (model.sizeBytes * 1.05).toLong()
        if (!isDownloaded && device.freeStorageBytes < storageNeeded) {
            val short = (storageNeeded - device.freeStorageBytes) / ModelSpec.BYTES_PER_GB
            return fit(
                FitVerdict.INSUFFICIENT_STORAGE,
                "Needs %.1f GB more free storage".format(short),
            )
        }

        // Check 1: the curated tier, against MARKETED ram.
        //
        // Skipped entirely when there is no curated tier (0), which is the case for anything the
        // user added from HuggingFace themselves. Nobody has hand-vetted those on real hardware, so
        // the formula below is the only honest authority for them.
        val hasCuratedTier = model.minDeviceMemoryGb > 0
        if (hasCuratedTier && device.advertisedRamGb < model.minDeviceMemoryGb) {
            return fit(
                FitVerdict.EXCEEDS_MEMORY,
                "Needs a ${model.minDeviceMemoryGb} GB device; this one has ${device.advertisedRamGb} GB",
            )
        }

        // Check 2: the computed peak, against the byte-accurate budget.
        if (peak > budget) {
            return fit(
                FitVerdict.EXCEEDS_MEMORY,
                "Needs ~%.1f GB at run time, more than the %.1f GB this device can spare".format(
                    peak / ModelSpec.BYTES_PER_GB,
                    budget / ModelSpec.BYTES_PER_GB,
                ),
            )
        }

        val tightByTier = hasCuratedTier && device.advertisedRamGb == model.minDeviceMemoryGb
        val tightByBudget = peak > budget * COMFORTABLE_THRESHOLD
        return if (tightByTier || tightByBudget) {
            fit(
                FitVerdict.TIGHT,
                "Fits, but with little headroom -- close other apps before running",
            )
        } else {
            fit(
                FitVerdict.COMFORTABLE,
                "Runs comfortably on this device",
            )
        }
    }

    /**
     * Best fit across every accelerator the engine and model share. The catalogue shows the most
     * optimistic honest verdict, because that is the one the user gets if they let the app pick.
     */
    fun evaluateBest(
        model: ModelSpec,
        engine: EngineDescriptor,
        device: DeviceMemoryProfile,
        isDownloaded: Boolean = false,
    ): ModelFit {
        val shared = engine.supportedAccelerators.filter { it in model.accelerators }
            .ifEmpty { listOf(Accelerator.CPU) }
        return shared
            .map { evaluate(model, engine, it, device, isDownloaded) }
            .maxByOrNull { it.verdict.ordinal }!!
    }
}
