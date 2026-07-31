package com.example.aiagenttestapp.data.audit

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
         * The context this run uses -- the model's, capped to what the audit actually needs. Read by
         * every caller that sizes anything, so the load, the chunking and the budgets cannot disagree
         * about how big the window is.
         */
        val contextTokens: Int = AuditChunker.auditContextTokens(resolved.model.contextTokens)

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
                // Deliberately not the model's full window: see AuditChunker.auditContextTokens. The
                // cost is that a model made resident for chat is loaded with a different request and
                // so cannot be reused here -- switching between chat and audit reloads it.
                contextTokens = contextTokens,
                // No reasoning directive of any kind: extraction needs the model's thinking, and
                // suppressing it measurably cost findings. See AuditPrompts.fixedPromptTokens.
                //
                // Quick mode gets its own system prompt because it is asking for something else --
                // report what is here, rather than judge what is wrong. Note this makes the two
                // modes' load requests differ, so switching between them reloads the model; that is
                // correct, since a quick run must not inherit an audit-shaped framing of the task.
                systemPrompt = when (mode) {
                    AuditMode.DETAILED -> AuditPrompts.SYSTEM_PROMPT
                    AuditMode.QUICK -> AuditPrompts.QUICK_SYSTEM_PROMPT
                },
                sampling = base.sampling.copy(temperature = AuditPrompts.EXTRACTION_TEMPERATURE),
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

                // A context that cannot hold the audit prompt plus a usable slice of transcript is
                // refused here rather than absorbed downstream. AuditChunker's floor would otherwise
                // keep handing out chunks the window cannot fit, and every turn of the run would
                // overflow -- producing a finished-looking report from sections the model only ever
                // saw the tail of. Measured against RICH, the same worst case AuditQueue reserves
                // for, so the gate and the chunk sizing can never disagree.
                AuditChunker.auditContextTokens(plan.model.contextTokens) < MIN_CONTEXT_TOKENS ->
                    AuditModelPlan.Unavailable(
                        "${plan.model.name} has too small a context window to audit with " +
                            "(${plan.model.contextTokens} tokens; about $MIN_CONTEXT_TOKENS are needed).",
                    )

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

    private companion object {
        /** Derived from the prompts, so editing them moves the bar rather than invalidating it. */
        val MIN_CONTEXT_TOKENS =
            AuditChunker.minimumContextTokens(AuditPrompts.fixedPromptTokens())
    }
}
