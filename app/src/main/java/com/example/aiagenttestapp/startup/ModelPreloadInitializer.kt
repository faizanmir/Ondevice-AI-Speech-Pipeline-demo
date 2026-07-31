package com.example.aiagenttestapp.startup

import android.content.Context
import androidx.startup.Initializer
import com.example.aiagenttestapp.data.ModelResidency
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * App Startup component that warms the default model into memory.
 *
 * Driven manually from the splash screen's ViewModel via `AppInitializer`, NOT automatically: App
 * Startup's automatic initializers run from a ContentProvider *before* `Application.onCreate`, which
 * is before Hilt's singleton graph -- and the engine instances it owns -- exist. Running it from the splash lets the warm-up target the very engines the chat screen will
 * later reuse, which is the whole point; auto-initialising it would only warm a throwaway engine.
 * `AppInitializer` memoises the component per process, so however often the splash is recreated,
 * the preload runs once.
 *
 * Because it is never auto-initialised, it is intentionally absent from the manifest's
 * `InitializationProvider` metadata.
 */
class ModelPreloadInitializer : Initializer<Unit> {

    /** Startup instantiates this class itself, so the graph is reached through an entry point. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PreloadEntryPoint {
        fun modelResidency(): ModelResidency
    }

    override fun create(context: Context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, PreloadEntryPoint::class.java)
            .modelResidency()
            .preloadActiveModel()
    }

    /** No other initializer has to run first: the graph is already built by the time we run. */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
