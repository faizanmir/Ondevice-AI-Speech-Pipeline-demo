package com.example.aiagenttestapp

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.example.aiagenttestapp.data.ModelResidency

@HiltAndroidApp
class AIAgentApplication : Application(), Configuration.Provider {

    /**
     * Lets WorkManager build workers through Hilt, so a worker declares the dependencies it needs in
     * its constructor instead of reaching back to the Application for a container.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var modelResidency: ModelResidency

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // The default model's warm-up is NOT kicked off here: the splash screen drives it (via
        // ModelPreloadInitializer) as part of its model-pathway initialization, alongside the
        // permission flow, so startup work is visible in one place instead of split across two.
    }

    /**
     * The resident model can be gigabytes, so hand it back under real memory pressure -- foreground
     * low/critical, or the system reclaiming from us in the background. [ModelResidency] only acts on
     * this while no chat is on screen, so an open chat is never pulled out from under the user, and a
     * routine app-switch (UI_HIDDEN) is deliberately left alone so the warm model survives it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val underPressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (underPressure) modelResidency.onMemoryPressure()
    }

}
