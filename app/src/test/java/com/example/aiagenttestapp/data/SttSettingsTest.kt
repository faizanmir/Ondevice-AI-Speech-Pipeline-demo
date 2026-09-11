package com.example.aiagenttestapp.data

import com.example.aiagenttestapp.stt.DiarizationEngine
import com.example.aiagenttestapp.stt.OnnxProvider
import com.example.aiagenttestapp.stt.PlatformFeedChunk
import com.example.aiagenttestapp.stt.PlatformFeedPace
import com.example.aiagenttestapp.stt.SliceWindow
import com.example.aiagenttestapp.stt.SttConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The seam between the app's settings and the speech pipeline's own configuration.
 *
 * Worth a test because the failure mode is quiet: `toSttConfig` is a field-by-field copy, and a
 * speech setting added to `AppSettings` but forgotten here would leave the pipeline silently running
 * on the default while the Settings screen showed the user's choice. Nothing would error.
 */
class SttSettingsTest {

    @Test
    fun `every speech setting reaches the pipeline`() {
        val settings = AppSettings(
            onnxProvider = OnnxProvider.entries.last(),
            speechModelId = "parakeet-v3",
            sliceWindow = SliceWindow.entries.last(),
            diarizationEngine = DiarizationEngine.entries.last(),
            platformFeedPace = PlatformFeedPace.entries.last(),
            platformFeedChunk = PlatformFeedChunk.entries.last(),
            platformLanguage = "de-DE",
            vadEnabled = false,
            keywordMarkersEnabled = true,
            threadCount = 6,
            diarizeChunkMinutes = 11,
        )

        val config = settings.toSttConfig()

        assertEquals(settings.onnxProvider, config.onnxProvider)
        assertEquals(settings.speechModelId, config.speechModelId)
        assertEquals(settings.sliceWindow, config.sliceWindow)
        assertEquals(settings.diarizationEngine, config.diarizationEngine)
        assertEquals(settings.platformFeedPace, config.platformFeedPace)
        assertEquals(settings.platformFeedChunk, config.platformFeedChunk)
        assertEquals(settings.platformLanguage, config.platformLanguage)
        assertEquals(settings.vadEnabled, config.vadEnabled)
        assertEquals(settings.keywordMarkersEnabled, config.keywordMarkersEnabled)
        assertEquals(settings.threadCount, config.threadCount)
        assertEquals(settings.diarizeChunkMinutes, config.diarizeChunkMinutes)
    }

    /**
     * A caller with no settings store gets the same behaviour the app had. If these ever diverge,
     * the library's defaults are lying about what this app actually ships with.
     */
    @Test
    fun `the pipeline's own defaults match the app's defaults`() {
        assertEquals(AppSettings().toSttConfig(), SttConfig.DEFAULT)
    }
}
