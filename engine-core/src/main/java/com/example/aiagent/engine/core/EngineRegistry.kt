package com.example.aiagent.engine.core

/**
 * The set of inference backends compiled into this build.
 *
 * The app talks to engines only through this registry, so adding a backend (ONNX Runtime GenAI,
 * ExecuTorch, a vendor SDK) means implementing [InferenceEngine] and adding it here -- no change
 * to the chat layer or the UI.
 */
class EngineRegistry(private val engines: List<InferenceEngine>) {

    val all: List<InferenceEngine> get() = engines

    /** Engines that can actually run right now, in registration order. */
    fun available(): List<InferenceEngine> =
        engines.filter { it.availability() is EngineAvailability.Available }

    operator fun get(id: EngineId): InferenceEngine? = engines.firstOrNull { it.descriptor.id == id }

    /** Engines that can load [format] and are usable on this device. */
    fun availableFor(format: ModelFormat): List<InferenceEngine> =
        available().filter { it.descriptor.canLoad(format) }

    /**
     * The engine the app should use for [model] absent an explicit user choice: the first
     * registered engine that can load the format. Null when the format has no usable engine --
     * which happens when llama.cpp was excluded from the build and the model is GGUF.
     */
    fun defaultFor(model: ModelSpec): InferenceEngine? = availableFor(model.format).firstOrNull()
}
