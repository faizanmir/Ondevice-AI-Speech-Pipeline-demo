package com.example.aiagenttestapp.bridge

import android.util.Log
import com.example.aiagent.llm.ModelResidency
import com.example.aiagenttestapp.stt.LlmTranscriberFactory
import com.example.aiagenttestapp.stt.Transcriber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This app's answer to "can a language model transcribe here?".
 *
 * The one class that knows both worlds, which is exactly why it lives on this side of the seam.
 * Everything under `stt/` sees only [LlmTranscriberFactory]; everything under `llm/` sees only its
 * own model hosting. The knowledge that *this* app can transcribe with its resident multimodal
 * model, and how to pick which one, stops here.
 *
 * Never returns null: this app does have an LLM backend, so a failure to open one is a fact the
 * user needs told ("nothing downloaded", "this phone cannot run it") rather than a silent fallback
 * to a different transcriber. Null is reserved for a host that has no LLM backend at all.
 */
@Singleton
class GemmaTranscriberFactory @Inject constructor(
    private val planner: SttLoadPlanner,
    private val residency: ModelResidency,
) : LlmTranscriberFactory {

    override suspend fun open(preferredModelId: String?): Transcriber =
        when (val plan = planner.plan(preferredModelId)) {
            is SttModelPlan.Unavailable -> error(plan.reason)
            is SttModelPlan.Ready -> {
                Log.i(TAG, "transcribing with ${plan.modelName}")
                planner.open(plan)
            }
        }

    /**
     * The model is shared with chat and the audit pipeline, so this only unloads when nothing else
     * is holding it -- which is residency's own rule, not something a transcription run can judge.
     */
    override suspend fun releaseIfIdle() = residency.releaseIfIdle()

    private companion object {
        const val TAG = "GemmaTranscriberFactory"
    }
}
