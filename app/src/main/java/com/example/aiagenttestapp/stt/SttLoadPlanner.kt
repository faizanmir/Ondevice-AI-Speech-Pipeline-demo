package com.example.aiagenttestapp.stt

import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelLoadPlan
import com.example.aiagenttestapp.data.ModelLoadPlanner
import com.example.aiagenttestapp.data.ModelRepository
import com.example.aiagenttestapp.data.ModelResidency
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.prompts.SttPrompts
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which model, if any, can transcribe on this device -- in transcription's own terms.
 *
 * The same shape as [com.example.aiagenttestapp.data.audit.AuditModelPlan]: the shared resolution
 * (engine, accelerator, file, thread count) is [ModelLoadPlanner]'s job and is identical whoever
 * asks, so this narrows its sealed result down to the two states the record screen actually has --
 * it can transcribe, or here is the sentence to show the user explaining why not.
 */
sealed interface SttModelPlan {

    /** [reason] is user-facing and appears on the record screen. */
    data class Unavailable(val reason: String) : SttModelPlan

    data class Ready(
        val modelName: String,
        val resolved: ModelLoadPlan.Resolved,
    ) : SttModelPlan {

        /**
         * The load a transcription run performs.
         *
         * Three things differ from a chat load of the same model, and each is the point of having a
         * separate request: the transcription system prompt, a temperature pinned near zero because
         * this is a faithful read rather than a creative task, and an output cap so a model that
         * starts looping on a hard slice stops at a few hundred tokens instead of decoding until the
         * context window fills.
         *
         * Because the request differs, switching between chatting with a model and transcribing with
         * it reloads it. That is correct -- a transcriber that inherited a chat's system prompt and
         * temperature would answer the audio rather than write it down.
         */
        fun loadRequest(): LoadRequest {
            val base = resolved.baseLoadRequest()
            return base.copy(
                // Without this the model loads happily and then rejects the first clip: LiteRT-LM
                // builds its audio executor from the engine config, not on demand.
                audioInput = true,
                systemPrompt = SttPrompts.SYSTEM_PROMPT,
                sampling = base.sampling.copy(
                    temperature = SttPrompts.TEMPERATURE,
                    maxOutputTokens = SttPrompts.MAX_CHUNK_TOKENS,
                ),
            )
        }
    }
}

/**
 * Picks the model that transcribes, and opens it.
 *
 * Two conditions have to hold at once and they are genuinely separate questions: the *model* needs
 * an audio encoder ([ModelSpec.audioInput]) and the *engine* holding it needs a way to be given
 * audio ([com.example.aiagent.engine.core.EngineDescriptor.supportsAudioInput]). A GGUF Gemma build
 * running on llama.cpp satisfies the first and not the second.
 *
 * Preference order matches [RecordViewModel.pickSummariser][com.example.aiagenttestapp.ui.notes]:
 * the model chosen in Settings when it qualifies, then the largest that does. The Settings choice
 * winning first is not only deference -- it is also the model most likely to be resident already, so
 * transcription starts without a multi-second load.
 */
@Singleton
class SttLoadPlanner @Inject constructor(
    private val models: ModelDirectory,
    private val modelLoadPlanner: ModelLoadPlanner,
    private val modelRepository: ModelRepository,
    private val modelResidency: ModelResidency,
    private val engines: EngineRegistry,
    private val settingsStore: SettingsStore,
    private val deviceMemory: DeviceMemoryProfile,
) {

    /**
     * Resolves the best available model, or the reason there is none.
     *
     * Cheap enough to call from a ViewModel's init: it reads the catalogue, checks file sizes on
     * disk and does the fit arithmetic, but loads nothing.
     *
     * [preferredId] wins over the Settings choice when it is still usable. That is how a resumed
     * transcription finishes on the model it started with -- the checkpoint recorded which one that
     * was -- rather than switching mid-recording because Settings changed in between.
     */
    fun plan(preferredId: String? = null): SttModelPlan {
        val candidates = models.snapshot().filter { it.hearsAudio }
        if (candidates.isEmpty()) {
            return SttModelPlan.Unavailable("No model in your catalogue can listen to audio.")
        }

        val usable = candidates.filter(::canTranscribe)
        if (usable.isEmpty()) {
            // Split the two reasons apart, because they need opposite things from the user:
            // downloading a model, or accepting that this phone cannot run the ones they have.
            val downloaded = candidates.any { modelRepository.isDownloaded(it) }
            return if (downloaded) {
                SttModelPlan.Unavailable(
                    "This device does not have enough memory to transcribe with " +
                        candidates.joinToString(" or ") { it.name } + ".",
                )
            } else {
                SttModelPlan.Unavailable(
                    "Download " + candidates.joinToString(" or ") { it.name } +
                        " from the model catalogue to transcribe without a speech model.",
                )
            }
        }

        val chosen = preferredId?.let { id -> usable.firstOrNull { it.id == id } }
            ?: settingsStore.settings.value.activeModelId
                ?.let { id -> usable.firstOrNull { it.id == id } }
            ?: usable.maxByOrNull { it.paramsBillions }
            ?: return SttModelPlan.Unavailable("No audio-capable model is usable on this device.")

        return when (val plan = modelLoadPlanner.plan(chosen.id)) {
            is ModelLoadPlan.UnknownModel ->
                SttModelPlan.Unavailable("No model with this id is installed.")

            is ModelLoadPlan.NoEngine ->
                SttModelPlan.Unavailable("No engine in this build can load ${plan.model.name}.")

            is ModelLoadPlan.Resolved ->
                SttModelPlan.Ready(modelName = plan.model.name, resolved = plan)
        }
    }

    /** On disk, loadable by an engine that accepts audio, and small enough to run here. */
    private fun canTranscribe(model: ModelSpec): Boolean {
        if (!modelRepository.isDownloaded(model)) return false

        val engine = engines.defaultFor(model)?.descriptor ?: return false
        if (!engine.supportsAudioInput) return false

        return ModelFitEvaluator
            .evaluateBest(model, engine, deviceMemory, isDownloaded = true)
            .canRun
    }

    /**
     * Opens the model a plan describes and returns a transcriber over it.
     *
     * Through [ModelResidency] rather than [InferenceEngine.load] directly, so a transcription run
     * shares the process-wide resident model with chat and the audit pipeline instead of loading a
     * second multi-gigabyte copy alongside one that is already in memory. Sharing is also why the
     * transcriber is built here: it needs the residency as well as the engine, and a caller that
     * assembled the two itself could get that pairing wrong.
     */
    suspend fun open(plan: SttModelPlan.Ready): Transcriber {
        val engine = modelResidency.open(plan.resolved, plan.loadRequest(), reuseWhenResident = true)
        return GemmaTranscriber(engine, modelResidency, plan.modelName)
    }
}
