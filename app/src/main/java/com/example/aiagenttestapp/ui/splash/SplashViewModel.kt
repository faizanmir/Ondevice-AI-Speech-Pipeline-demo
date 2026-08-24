package com.example.aiagenttestapp.ui.splash

import androidx.lifecycle.viewModelScope
import androidx.startup.AppInitializer
import com.example.aiagenttestapp.startup.ModelPreloadInitializer
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.aiagenttestapp.data.ModelDirectory
import com.example.aiagenttestapp.data.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class SplashUiState(
    /** True once model storage is verified and the active model's warm-up has been kicked off. */
    val modelPathwayReady: Boolean = false,
    /** How many catalogue models are already on disk -- shown as a status line on the splash. */
    val downloadedModelCount: Int = 0,
) : UiState

sealed interface SplashIntent : UiIntent {
    /** Begins the startup work. Self-dispatched, and idempotent, so it can only ever run once. */
    data object Start : SplashIntent
}

/**
 * Drives the splash screen's startup work: verifying the on-disk model store and starting the
 * warm-load of the active model. The permission flow lives in the screen, not here -- the system
 * permission dialogs need an Activity, which a ViewModel must never hold.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val application: android.app.Application,
    private val models: ModelDirectory,
    private val modelRepository: ModelRepository,
) : MviViewModel<SplashUiState, SplashIntent, Nothing>(SplashUiState()) {

    private var startJob: Job? = null

    init {
        // Nothing else can send this one: the splash's work is not something the user asks for, it
        // is what the screen exists to do. Routing it through an intent anyway keeps the single
        // entry point honest -- there is still no path into this ViewModel that is not an intent.
        onIntent(SplashIntent.Start)
    }

    override fun reduce(intent: SplashIntent) = when (intent) {
        SplashIntent.Start -> start()
    }

    private fun start() {
        if (startJob != null) return
        startJob = viewModelScope.launch {
            // Model storage: the models directory was created when the container was built;
            // scanning what is on disk here settles every catalogue entry's downloaded state
            // before the first real screen renders.
            val downloaded = withContext(Dispatchers.IO) {
                models.snapshot().count { modelRepository.isDownloaded(it) }
            }

            // Warm the active model into its engine, off the main thread. App Startup memoises the
            // component per process, so a recreated splash can never start a second, racing preload.
            AppInitializer.getInstance(application)
                .initializeComponent(ModelPreloadInitializer::class.java)

            setState {
                copy(modelPathwayReady = true, downloadedModelCount = downloaded)
            }
        }
    }
}
