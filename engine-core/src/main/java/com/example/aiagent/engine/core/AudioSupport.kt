package com.example.aiagent.engine.core

/**
 * Whether a model has an audio encoder, worked out from its name when nobody has said.
 *
 * The same shape as [ToolCallingSupport], and for the same reason: the built-in catalogue can state
 * a capability outright because a curated entry was checked against its model card, but a model the
 * user added from HuggingFace arrives with nothing but a repo id and a filename. Without inference
 * that model is assumed text-only forever -- which is how a device with Gemma 4 E2B sitting on disk
 * came to be told it had no model that could listen.
 *
 * This asks about the *model*, not the runtime. Whether the engine holding it can actually be handed
 * audio is [EngineDescriptor.supportsAudioInput], a separate question with a separate answer: a GGUF
 * build of an audio-capable model is correctly identified here and correctly rejected there.
 *
 * ## Which way to be wrong
 *
 * Answering "yes" wrongly offers transcription with a model that cannot hear. The runtime rejects
 * the audio, the worker records the failure on the note, and the recording survives to be retried on
 * the speech model -- annoying, visible, recoverable. Answering "no" wrongly hides the feature with
 * no explanation and no way to reach it. So the families below are matched on the markers that
 * actually distinguish an audio build, and anything unrecognised gets a no.
 */
object AudioSupport {

    /**
     * The audio-capable on-device families, as name fragments.
     *
     * Gemma's MatFormer builds are the ones that carry the USM audio encoder, and the "E" sizes are
     * what identify them -- `gemma-3n-E2B`, `gemma-4-E4B`. A plain `gemma-3-1b` is the same family
     * name and has no audio at all, which is why the size marker is required rather than optional.
     */
    private val MATFORMER_SIZES = listOf("e2b", "e4b")

    /** Families whose whole line carries an audio encoder, size markers or not. */
    private val ALWAYS_AUDIO = listOf(
        "gemma-3n",
        "gemma3n",
        "qwen2-audio",
        "qwen2.5-omni",
        "phi-4-multimodal",
    )

    /**
     * Best guess from whatever identifiers a model carries.
     *
     * All three are consulted because which one holds the useful text varies with how the model was
     * added: a HuggingFace entry puts the family in [repoId] and often in [name], while a renamed
     * local file may only reveal it in [id].
     */
    fun infer(name: String, id: String, repoId: String? = null): Boolean {
        val text = listOfNotNull(name, id, repoId).joinToString(" ").lowercase()

        if (ALWAYS_AUDIO.any { it in text }) return true

        // Gemma's audio builds, which need both the family and a MatFormer size to be certain.
        val isGemma = "gemma" in text
        return isGemma && MATFORMER_SIZES.any { it in text }
    }
}
