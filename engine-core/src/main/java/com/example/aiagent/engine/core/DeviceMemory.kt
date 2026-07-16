package com.example.aiagent.engine.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.core.content.getSystemService

/**
 * What this device can physically offer an LLM.
 *
 * The distinction between [advertisedRamBytes] and [totalRamBytes] is the single most important
 * thing in this file, and getting it wrong silently breaks the whole app -- see their docs.
 */
data class DeviceMemoryProfile(
    /**
     * RAM the phone was *marketed* with: exactly 8.00 GB on an "8 GB" phone.
     *
     * Compare curated tiers ([ModelSpec.minDeviceMemoryGb]) against THIS, never against
     * [totalRamBytes]. The kernel and GPU carve out a reserve before Android ever sees the RAM,
     * so an 8 GB phone reports ~7.4 GB of `totalMem`. Gating a "needs 8 GB" model on `totalMem`
     * therefore rejects every 8 GB phone on the market -- the exact bug this field exists to avoid.
     */
    val advertisedRamBytes: Long,

    /** Kernel-visible RAM, post-carveout. Use for byte-accurate budget math, not tier comparisons. */
    val totalRamBytes: Long,

    /** Free right now. Volatile -- depends on what else the user has open, so never gate a
     *  catalogue browsed *before* download on this. Only meaningful at load time. */
    val availableRamBytes: Long,

    /** Below this, the system starts killing background processes. */
    val lowMemoryThresholdBytes: Long,

    val isLowRamDevice: Boolean,
    val freeStorageBytes: Long,
    /** e.g. "SM8750". Null below API 31. Used to pick NPU-compiled model variants. */
    val socModel: String?,
    val supportedAbis: List<String>,
) {
    /** Marketed RAM in GB, rounded to the nearest whole GB (phones ship in 4/6/8/12/16 tiers). */
    val advertisedRamGb: Int
        get() = Math.round(advertisedRamBytes / ModelSpec.BYTES_PER_GB).toInt()

    val is64Bit: Boolean get() = supportedAbis.any { it.contains("64") }

    /**
     * Bytes this app can realistically hand to a model without the low-memory killer taking an
     * interest.
     *
     * Not total RAM: Android itself, the launcher, and whatever the user has in the background all
     * have to survive too. [USABLE_FRACTION] is the share a foreground app can actually hold onto,
     * and [SYSTEM_RESERVE_BYTES] is a flat floor on top so low-RAM devices don't get an absurdly
     * optimistic budget.
     *
     * [USABLE_FRACTION] is calibrated against Google's published allowlist tiers rather than
     * guessed. Those tiers say an 8 GB phone is the entry point for Gemma-4-E2B, which peaks around
     * 1.7 GB, and a 6 GB phone the entry point for Qwen-1.5B-q8 at roughly 1.5 GB. Both imply a
     * usable budget near 2 GB on an 8 GB device -- about 35% of what the kernel reports, not the
     * ~55% a naive reading of "free memory" suggests. Setting this too high is the dangerous
     * direction: it makes the app promise a model that the LMK then kills on load.
     */
    val modelRamBudgetBytes: Long
        get() = ((totalRamBytes * USABLE_FRACTION).toLong() - SYSTEM_RESERVE_BYTES)
            .coerceAtLeast(0L)

    companion object {
        /** See [modelRamBudgetBytes] -- back-solved from Google's own device tiers. */
        const val USABLE_FRACTION = 0.35
        const val SYSTEM_RESERVE_BYTES = 512L * 1024 * 1024
    }
}

object DeviceMemoryProbe {

    fun read(context: Context): DeviceMemoryProfile {
        val am = context.getSystemService<ActivityManager>()
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

        // advertisedMem landed in API 34. Below that the best available answer is totalMem, which
        // under-reports by the kernel carveout; round it up to the nearest common RAM tier so that
        // pre-34 devices are not unfairly locked out of models sized for their actual hardware.
        val advertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.advertisedMem
        } else {
            roundUpToRamTier(info.totalMem)
        }

        val stat = StatFs(context.filesDir.absolutePath)
        val freeStorage = stat.availableBlocksLong * stat.blockSizeLong

        return DeviceMemoryProfile(
            advertisedRamBytes = advertised,
            totalRamBytes = info.totalMem,
            availableRamBytes = info.availMem,
            lowMemoryThresholdBytes = info.threshold,
            isLowRamDevice = am?.isLowRamDevice == true,
            freeStorageBytes = freeStorage,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    /**
     * Snaps kernel-visible RAM up to the nearest tier phones are actually sold in. A 7.4 GB
     * `totalMem` reading is an 8 GB phone; 5.6 GB is a 6 GB phone. Only used pre-API-34, where
     * the OS will not tell us the marketed figure directly.
     */
    private fun roundUpToRamTier(totalMemBytes: Long): Long {
        val gb = totalMemBytes / ModelSpec.BYTES_PER_GB
        val tier = RAM_TIERS_GB.firstOrNull { it >= gb - TIER_TOLERANCE_GB }
            ?: RAM_TIERS_GB.last()
        return (tier * ModelSpec.BYTES_PER_GB).toLong()
    }

    private val RAM_TIERS_GB = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32)

    /** How far below a tier a reading can sit and still be that tier. The carveout is ~5-8%. */
    private const val TIER_TOLERANCE_GB = 0.75
}
