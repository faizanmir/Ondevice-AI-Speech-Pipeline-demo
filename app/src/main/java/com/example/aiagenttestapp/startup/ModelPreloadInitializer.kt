package com.example.aiagenttestapp.startup

import android.content.Context
import androidx.startup.Initializer
import com.example.aiagenttestapp.AIAgentApplication

/**
 * App Startup component that warms the default model into memory.
 *
 * Driven manually from [AIAgentApplication.onCreate] via `AppInitializer`, NOT automatically: App
 * Startup's automatic initializers run from a ContentProvider *before* `Application.onCreate`, which
 * is before the [com.example.aiagenttestapp.AppContainer] -- and the engine instances it owns --
 * exist. Running it from `onCreate` lets the warm-up target the very engines the chat screen will
 * later reuse, which is the whole point; auto-initialising it would only warm a throwaway engine.
 *
 * Because it is never auto-initialised, it is intentionally absent from the manifest's
 * `InitializationProvider` metadata.
 */
class ModelPreloadInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val application = context.applicationContext as AIAgentApplication
        application.container.modelResidency.preloadActiveModel(application.container)
    }

    /** No other initializer has to run first: the container is already built by the time we run. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
