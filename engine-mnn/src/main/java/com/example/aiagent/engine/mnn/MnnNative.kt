package com.example.aiagent.engine.mnn

import android.util.Log

/**
 * Raw JNI surface of MNN's LLM engine. Everything here maps 1:1 onto a function in `mnn_jni.cpp`.
 *
 * Kotlin `object` members compile to instance methods on the singleton, which is what the JNI
 * signatures in the C++ file expect (`JNIEnv*, jobject`).
 *
 * Nothing outside this package should touch these -- [MnnEngine] is the supported entry point.
 */
internal object MnnNative {

    /** Mirrors the error codes at the top of mnn_jni.cpp. */
    const val ERR_DECODE = -1
    const val ERR_CONTEXT_FULL = -2
    const val ERR_NO_SESSION = -3

    /** Null when the native library is absent (MNN excluded from the build, or wrong ABI). */
    val loadError: String? by lazy {
        if (!BuildConfig.MNN_ENABLED) {
            "MNN was excluded from this build (enableMnn=false)"
        } else {
            try {
                System.loadLibrary("mnnjni")
                null
            } catch (t: UnsatisfiedLinkError) {
                Log.e("MnnNative", "failed to load libmnnjni.so", t)
                "MNN native library could not be loaded on this device (${t.message})"
            }
        }
    }

    /**
     * Returns an opaque session handle, or 0 on failure. [configPath] is the model directory's
     * config.json. There is no seed parameter: MNN's sampler config has none, so generation on
     * this engine is never reproducible.
     */
    external fun nativeCreateSession(
        configPath: String,
        nCtx: Int,
        nThreads: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        cacheDir: String,
    ): Long

    /** Seeds the native transcript: system prompt plus restored history. Call once, after create. */
    external fun nativeSeedHistory(handle: Long, roles: Array<String>, contents: Array<String>)

    /** Templating + prefill. Returns the tokens prefilled this turn, or a negative ERR_* code. */
    external fun nativeIngestPrompt(handle: Long, prompt: String): Int

    /** One decode step. Null ends the stream; "" means "partial UTF-8, keep going". */
    external fun nativeNextToken(handle: Long): String?

    external fun nativeCancel(handle: Long)
    external fun nativeResetContext(handle: Long)
    external fun nativeContextUsed(handle: Long): Int

    /** MNN's own decode throughput for the last turn, tokens/second; 0 when unknown. */
    external fun nativeDecodeTokensPerSecond(handle: Long): Double

    external fun nativeFreeSession(handle: Long)
}
