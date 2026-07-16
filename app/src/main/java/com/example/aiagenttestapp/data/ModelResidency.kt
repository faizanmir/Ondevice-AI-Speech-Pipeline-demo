package com.example.aiagenttestapp.data

import android.util.Log
import com.example.aiagent.engine.core.InferenceEngine
import com.example.aiagent.engine.core.LoadRequest
import com.example.aiagent.engine.core.ModelFitEvaluator
import com.example.aiagenttestapp.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the default (active) model resident in its engine for the whole session, so opening its chat
 * is instant after the first load rather than paying a multi-second load each time.
 *
 * It is warmed once at startup ([preloadActiveModel]) and thereafter handed to every chat through
 * [open]. A fresh chat whose load matches the resident model reuses it with a cheap conversation
 * reset; a resumed conversation, a different model, or changed settings loads normally and becomes
 * the new resident. Nothing unloads when a chat closes -- that is the whole point -- so an engine
 * holds one model in memory continuously.
 *
 * The safety valve is [releaseIfIdle]: under real memory pressure, and only while no chat is on
 * screen ([attach]/[detach] track that), the resident model is unloaded so a multi-GB model cannot
 * starve the rest of the app. The next chat then pays one load to bring it back.
 */
class ModelResidency {

    /** Serialises load / reset / unload / the on-close summary so they never overlap on the engine. */
    private val mutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The engine currently holding a model, or null when nothing is resident. */
    @Volatile
    private var resident: InferenceEngine? = null

    /**
     * The request [resident] was loaded with when it was loaded for a *fresh* chat, or null when it
     * holds a resumed conversation. A fresh chat is reused only when its request equals this.
     */
    @Volatile
    private var residentFreshRequest: LoadRequest? = null

    /** The startup warm-up, so the first [open] waits for it rather than racing it into a second load. */
    @Volatile
    private var preloadJob: Job? = null

    /** Live chat view-models using the engine. While > 0, a chat is on screen -- do not release. */
    private var attached = 0

    // ---- Startup warm-up ----------------------------------------------------------------------

    /** Kicks off the background warm-load of the active model. Non-blocking; call once, at startup. */
    fun preloadActiveModel(container: AppContainer) {
        try {
            val modelId = container.settingsStore.settings.value.activeModelId ?: return
            val plan = planChatLoad(container, modelId) as? ChatLoadPlan.Resolved ?: return
            if (!plan.downloaded) return

            // Never risk taking the process down at startup for a model that would not fit anyway.
            val fit = ModelFitEvaluator.evaluate(
                model = plan.model,
                engine = plan.engine.descriptor,
                accelerator = plan.accelerator,
                device = container.deviceMemory,
                isDownloaded = true,
            )
            if (!fit.canRun) return

            val request = plan.freshLoadRequest()
            preloadJob = scope.launch {
                runCatching { loadResident(plan.engine, request, freshRequest = request) }
                    .onFailure { Log.w(TAG, "model preload failed", it) }
            }
        } catch (e: Exception) {
            // Preloading is a pure optimisation; a chat can always load on demand. Never break startup.
            Log.w(TAG, "model preload skipped", e)
        }
    }

    // ---- Chat open ----------------------------------------------------------------------------

    /**
     * Readies [plan]'s engine for a chat and returns it, resident afterwards either way:
     *
     *  - [reuseWhenResident] and the engine already holds exactly this model and config -> a cheap
     *    [InferenceEngine.resetConversation], no load at all (the instant path).
     *  - otherwise -> a full [InferenceEngine.load] of [request].
     *
     * Pass `reuseWhenResident = true` only for a fresh chat, whose [request] must be the plan's
     * [ChatLoadPlan.Resolved.freshLoadRequest]; a resumed chat carries restored history and must load.
     */
    suspend fun open(
        plan: ChatLoadPlan.Resolved,
        request: LoadRequest,
        reuseWhenResident: Boolean,
    ): InferenceEngine {
        // Let a startup warm-up finish first, so we reset onto its result instead of loading again.
        preloadJob?.join()

        return mutex.withLock {
            val engine = plan.engine
            val canReset = reuseWhenResident &&
                resident === engine &&
                residentFreshRequest == request &&
                engine.loadedModelPath == request.modelPath
            if (canReset) {
                engine.resetConversation()
                return@withLock engine
            }
            loadResident(engine, request, freshRequest = if (reuseWhenResident) request else null)
            engine
        }
    }

    /** Caller must hold [mutex]. Loads [request] and records it as resident. */
    private suspend fun loadResident(
        engine: InferenceEngine,
        request: LoadRequest,
        freshRequest: LoadRequest?,
    ) {
        try {
            engine.load(request)
            resident = engine
            residentFreshRequest = freshRequest
        } catch (t: Throwable) {
            // A failed load leaves the engine unloaded; do not keep a dangling resident pointer.
            resident = null
            residentFreshRequest = null
            throw t
        }
    }

    /**
     * Runs [block] with exclusive access to the engine, so no [open] can reset or reload it midway.
     * Used for the on-close summary, which generates on the still-resident model.
     */
    suspend fun runExclusive(block: suspend () -> Unit) = mutex.withLock { block() }

    // ---- Attachment (whether a chat is on screen) ---------------------------------------------

    @Synchronized
    fun attach() {
        attached++
    }

    @Synchronized
    fun detach() {
        if (attached > 0) attached--
    }

    @Synchronized
    private fun isAttached(): Boolean = attached > 0

    // ---- Memory pressure ----------------------------------------------------------------------

    /** Frees the resident model on a background thread when memory is tight. Safe to call anytime. */
    fun onMemoryPressure() {
        scope.launch { releaseIfIdle() }
    }

    /** Unloads the resident model, but only while no chat is using it. */
    private suspend fun releaseIfIdle() {
        if (isAttached()) return
        mutex.withLock {
            // Re-check under the lock: a chat may have attached while we waited for it.
            if (isAttached()) return@withLock
            resident?.let { runCatching { it.unload() } }
            resident = null
            residentFreshRequest = null
        }
    }

    private companion object {
        const val TAG = "ModelResidency"
    }
}
