package com.example.aiagent.engine.core

/**
 * The set of inference backends compiled into this build.
 *
 * The app talks to engines only through this registry, so adding a backend (ONNX Runtime GenAI,
 * ExecuTorch, a vendor SDK) means implementing [InferenceEngine] and adding it here -- no change
 * to the chat layer or the UI.
 */
class EngineRegistry(private val engines: List<InferenceEngine>) {

    init {
        // An engine that advertises native tools but cannot be handed a runner would be given
        // tools to declare, then fail every call the model made -- at run time, with a generic
        // message. Caught here instead, when the app builds its registry, naming the engine.
        val unrunnable = engines.filter {
            it.descriptor.supportsNativeTools && it !is NativeToolEngine
        }
        require(unrunnable.isEmpty()) {
            "${unrunnable.joinToString { it.descriptor.displayName }} declares native tool " +
                "support but does not implement NativeToolEngine, so its tool calls could never run"
        }

        // The same trap one modality over, and a worse one to debug: an engine that advertises audio
        // but cannot be given any would be picked to transcribe a recording, hear nothing, and
        // return whatever a model says when asked to transcribe silence -- a plausible-looking
        // transcript of an empty room. Caught here rather than in a voice note.
        val deaf = engines.filter {
            it.descriptor.supportsAudioInput && it !is AudioInputEngine
        }
        require(deaf.isEmpty()) {
            "${deaf.joinToString { it.descriptor.displayName }} declares audio input but does not " +
                "implement AudioInputEngine, so audio handed to it would be silently dropped"
        }
    }

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
     * registered engine that can load the format *and reports itself usable on this device*.
     *
     * Null when there is no such engine. That used to mean a GGUF model in a build with llama.cpp
     * excluded; with one engine and one format left it means the device itself cannot run it --
     * [availability] returned something other than [EngineAvailability.Available]. Still a real
     * outcome, and still one the caller has to render.
     */
    fun defaultFor(model: ModelSpec): InferenceEngine? = availableFor(model.format).firstOrNull()
}
