package com.example.aiagent.engine.llamacpp

import android.util.Log

/**
 * Raw JNI surface of llama.cpp. Everything here maps 1:1 onto a function in `llama_jni.cpp`.
 *
 * Kotlin `object` members compile to instance methods on the singleton, which is what the JNI
 * signatures in the C++ file expect (`JNIEnv*, jobject`).
 *
 * Nothing outside this package should touch these -- [LlamaCppEngine] is the supported entry point.
 */
internal object LlamaNative {

    /** Mirrors the error codes at the top of llama_jni.cpp. */
    const val ERR_DECODE = -1
    const val ERR_CONTEXT_FULL = -2
    const val ERR_NO_SESSION = -3

    /** Null when the native library is absent (llama.cpp excluded from the build, or wrong ABI). */
    val loadError: String? by lazy {
        if (!BuildConfig.LLAMA_CPP_ENABLED) {
            "llama.cpp was excluded from this build (enableLlamaCpp=false)"
        } else {
            try {
                System.loadLibrary("llamajni")
                nativeBackendInit()
                null
            } catch (t: UnsatisfiedLinkError) {
                Log.e("LlamaNative", "failed to load libllamajni.so", t)
                "llama.cpp native library could not be loaded on this device (${t.message})"
            }
        }
    }

    external fun nativeBackendInit()

    /** Returns an opaque session handle, or 0 on failure. */
    external fun nativeCreateSession(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int,
    ): Long

    /** Renders [roles]/[contents] through the GGUF's own chat template. Null if it has none. */
    external fun nativeFormatChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /** Prefill. Returns the prompt's token count, or one of the negative ERR_* codes. */
    external fun nativeIngestPrompt(handle: Long, prompt: String): Int

    /** One decode step. Null ends the stream; "" means "partial UTF-8, keep going". */
    external fun nativeNextToken(handle: Long): String?

    external fun nativeCancel(handle: Long)
    external fun nativeResetContext(handle: Long)

    /** Ends the turn but keeps the KV cache, so the next prompt can reuse its shared prefix. */
    external fun nativeResetTurnKeepCache(handle: Long)
    external fun nativeContextUsed(handle: Long): Int
    external fun nativeContextSize(handle: Long): Int
    external fun nativeFreeSession(handle: Long)
    external fun nativeSystemInfo(): String

    /** Name of an offload-capable GPU (e.g. "Adreno (TM) 750"), or null if there is none. */
    external fun nativeGpuDeviceName(): String?

    /**
     * The GPU llama.cpp can actually use, or null.
     *
     * Cached: enumerating Vulkan devices spins up the loader and is not something to do on every
     * recomposition. Null covers both "built without Vulkan" and "built with Vulkan but this device
     * has no usable driver" -- from the app's point of view those are the same thing.
     */
    val gpuDeviceName: String? by lazy {
        if (!BuildConfig.VULKAN_COMPILED_IN || loadError != null) {
            null
        } else {
            runCatching { nativeGpuDeviceName() }.getOrNull()
        }
    }
}
