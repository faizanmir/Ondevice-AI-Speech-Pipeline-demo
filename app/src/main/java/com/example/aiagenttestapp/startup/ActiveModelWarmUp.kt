package com.example.aiagenttestapp.startup

import android.util.Log
import com.example.aiagenttestapp.data.ChatLoadPlan
import com.example.aiagenttestapp.data.ChatLoadPlanner
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagent.llm.WarmUp
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Decides what to warm into memory at startup: this app's policy, kept out of `ModelResidency`.
 *
 * Residency knows how to hold an engine loaded; it has no business knowing that this app warms
 * *the model a fresh chat would open*, which is a product decision about which screen matters most.
 * Splitting the two is what lets residency ship as part of a model-hosting library while this stays
 * here with the chat it serves.
 *
 * Deliberately the **chat** plan. The warmed request must be byte-identical to the one
 * `ChatViewModel` builds -- system prompt included -- or the resident model will not match when the
 * chat opens and the whole warm-up is paid twice for nothing.
 *
 * `Provider`, not direct references: this is constructed as part of the singleton graph, and taking
 * the chat planner lazily keeps startup out of the chat graph's construction order.
 */
@Singleton
class ActiveModelWarmUp @Inject constructor(
    private val chatLoadPlanner: Provider<ChatLoadPlanner>,
    private val settingsStore: Provider<SettingsStore>,
) {

    /** The warm-up to run, or null when there is nothing worth warming. */
    fun plan(): WarmUp? = try {
        val modelId = settingsStore.get().settings.value.activeModelId
        val plan = modelId?.let { chatLoadPlanner.get().plan(it) } as? ChatLoadPlan.Ready
        when {
            plan == null -> null
            !plan.downloaded -> null
            else -> WarmUp(
                engine = plan.engine,
                request = plan.freshLoadRequest(),
                model = plan.model,
                accelerator = plan.accelerator,
            )
        }
    } catch (e: Exception) {
        // Warming is a pure optimisation; a chat can always load on demand. Never break startup.
        Log.w(TAG, "model preload skipped", e)
        null
    }

    private companion object {
        const val TAG = "ActiveModelWarmUp"
    }
}
