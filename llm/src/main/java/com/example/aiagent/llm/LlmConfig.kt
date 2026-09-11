package com.example.aiagent.llm

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.SamplingParams

/**
 * Everything model loading needs from its host's settings.
 *
 * The same inversion [com.example.aiagenttestapp.stt.SttConfig] does for speech, for the same
 * reason: `ModelLoadPlanner` read `SettingsStore` directly, which is exactly the dependency that
 * stops model hosting being lifted out. Four values, all with defaults, so a caller with no settings
 * store gets working behaviour rather than a construction puzzle.
 */
data class LlmConfig(
    /** Null = let the app pick the first engine that can load the chosen model. */
    val preferredEngine: EngineId? = null,
    val preferredAccelerator: Accelerator = Accelerator.GPU,
    /** What engines actually receive -- already resolved through the reproducible-output flag. */
    val sampling: SamplingParams = SamplingParams(),
    /** CPU decode threads. 0 = automatic. */
    val threadCount: Int = 0,
) {
    companion object {
        val DEFAULT = LlmConfig()
    }
}

/**
 * Where an engine may write and read on this device.
 *
 * Both are host facts, not library ones: the cache directory is the app's, and the native library
 * directory is where the *host APK* unpacked its `.so` files. LiteRT-LM's NPU backend dlopen()s
 * vendor libraries out of it, so a library that guessed would break on any host laid out
 * differently.
 */
data class LlmPaths(
    val cacheDir: String,
    val nativeLibraryDir: String,
)

/**
 * How the planner asks for the current configuration.
 *
 * A supplier rather than a value, because the read is *late*: a plan is built when a chat opens, and
 * it must see the accelerator the user picked a moment ago without anything rebuilding the object
 * graph. A `StateFlow` would say the same thing, but the planner never observes -- it only ever
 * reads once per plan -- so this is the smaller honest contract, and it costs a caller one lambda.
 */
fun interface LlmConfigSource {
    fun current(): LlmConfig
}
