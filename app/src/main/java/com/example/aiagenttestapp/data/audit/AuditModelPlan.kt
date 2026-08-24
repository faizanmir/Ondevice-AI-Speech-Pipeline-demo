package com.example.aiagenttestapp.data.audit

import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagenttestapp.prompts.audit.AuditExtractionPrompts
import com.example.aiagenttestapp.prompts.audit.AuditPromptBudget
import com.example.aiagenttestapp.prompts.audit.AuditSystemPrompts
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagenttestapp.data.ModelLoadPlan
import com.example.aiagenttestapp.data.ModelLoadPlanner
import com.example.aiagenttestapp.data.ModelResidency
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the audit pipeline loads a model, in audit's own terms.
 *
 * The underlying resolution ([planModelLoad]) is shared with chat -- engine choice, accelerator,
 * file location and thread count are the same question whoever is asking -- but its result carries
 * chat concerns audit has no use for (tool availability, the chat system prompt) and forces every
 * caller to narrow a sealed type and then re-check `downloaded` by hand. Both audit call sites were
 * doing that, differently.
 *
 * This collapses it to the two states audit actually has: it can run, or it cannot and here is the
 * reason to show the user. Everything audit-specific -- the audit system prompt, the pinned
 * extraction temperature, the prompt profile -- is decided here, once.
 */
sealed interface AuditModelPlan {

    /** The model cannot be audited with right now. [reason] is user-facing. */
    data class Unavailable(val reason: String) : AuditModelPlan

    data class Ready(
        val modelName: String,
        val engineName: String,
        /** Rich or lean prompt, decided by whether this engine reuses a shared prompt prefix. */
        val profile: AuditPromptProfile,
        /**
         * The shared plan, needed only to hand back to [com.example.aiagenttestapp.data.ModelResidency].
         * Deliberately the one place audit still touches it, rather than every call site.
         */
        val resolved: ModelLoadPlan.Resolved,
    ) : AuditModelPlan {

        /**
         * The context this run uses: the model's own, whole. Read by every caller that sizes
         * anything, so the load, the chunking and the budgets cannot disagree about how big the
         * window is.
         */
        val contextTokens: Int = resolved.contextTokens

        /**
         * The load an audit run performs: the audit system prompt, and the extraction temperature
         * pinned regardless of the user's chat setting -- audit is a faithful read, not a creative
         * task.
         *
         * Built the same way for both audit callers (the queue screen warming the model, and the
         * drain worker running it) so the two requests are equal and the warm handoff can reuse a
         * model one of them already made resident.
         */
        fun loadRequest(mode: AuditMode = AuditMode.DETAILED): LoadRequest {
            val base = resolved.baseLoadRequest()
            return base.copy(
                // The model's full window -- the same value chat loads with, since the audit-only
                // clamp is gone. The request still differs from chat's by system prompt and
                // temperature, so switching between the two reloads the model regardless.
                contextTokens = contextTokens,
                // No reasoning directive of any kind: extraction needs the model's thinking, and
                // suppressing it measurably cost findings. See AuditPromptBudget.fixedPromptTokens.
                //
                // Quick mode gets its own system prompt because it is asking for something else --
                // report what is here, rather than judge what is wrong. Note this makes the two
                // modes' load requests differ, so switching between them reloads the model; that is
                // correct, since a quick run must not inherit an audit-shaped framing of the task.
                systemPrompt = when (mode) {
                    AuditMode.DETAILED -> AuditSystemPrompts.SYSTEM_PROMPT
                    AuditMode.QUICK -> AuditSystemPrompts.QUICK_SYSTEM_PROMPT
                },
                // Temperature pinned for the read, and the seed pinned so the read repeats. A chat
                // draws a fresh seed every load, which is right for a chat and wrong for a document
                // that will be read again: the same section graded two ways on two runs is a coin
                // toss nobody can defend. A seed the user already fixed is left alone -- see
                // AuditExtractionPrompts.EXTRACTION_SEED.
                sampling = base.sampling.copy(
                    temperature = AuditExtractionPrompts.EXTRACTION_TEMPERATURE,
                    seed = base.sampling.seed.takeIf { it != SamplingParams.SEED_RANDOM }
                        ?: AuditExtractionPrompts.EXTRACTION_SEED,
                ),
            )
        }
    }
}

/**
 * Resolves a model for an audit run, with audit's own failure messages, and opens it.
 *
 * A type rather than a free function taking the whole container, so the two audit callers -- the
 * queue screen warming the model and the drain worker running it -- share one injected instance and
 * cannot drift apart on which engine or which request an audit uses.
 */
@Singleton
class AuditLoadPlanner @Inject constructor(
    private val modelLoadPlanner: ModelLoadPlanner,
    private val modelResidency: ModelResidency,
) {

    fun plan(modelId: String): AuditModelPlan =
        when (val plan = modelLoadPlanner.plan(modelId)) {
            is ModelLoadPlan.UnknownModel ->
                AuditModelPlan.Unavailable("No model with this id is installed.")

            is ModelLoadPlan.NoEngine ->
                AuditModelPlan.Unavailable("No engine in this build can load ${plan.model.name}.")

            is ModelLoadPlan.Resolved -> when {
                !plan.downloaded -> AuditModelPlan.Unavailable("${plan.model.name} is not downloaded.")

                // No minimum-context gate. There used to be one, refusing any model whose window
                // could not hold the RICH preamble plus a reply plus a floor-sized chunk (~3,300
                // tokens), on the grounds that smaller windows overflow every turn and produce a
                // finished-looking report from sections the model only saw the tail of. That is
                // still true, and it is now the user's call rather than this planner's: a degraded
                // audit beats no audit, and the gate was firing on models that run perfectly well.
                // AuditChunker.chunkCharBudget carries the floor that keeps such a run going.
                else -> AuditModelPlan.Ready(
                    modelName = plan.model.name,
                    engineName = plan.engineName,
                    profile = AuditPromptProfile.forEngine(plan.engineId),
                    resolved = plan,
                )
            }
        }

    /** Opens the model a plan describes, keeping the residency handling in one place. */
    suspend fun open(
        plan: AuditModelPlan.Ready,
        mode: AuditMode = AuditMode.DETAILED,
    ): InferenceEngine =
        modelResidency.open(plan.resolved, plan.loadRequest(mode), reuseWhenResident = true)
}
