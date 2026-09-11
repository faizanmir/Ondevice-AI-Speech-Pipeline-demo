package com.example.aiagenttestapp.stt

import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the speech pipeline needs from its host's settings.
 *
 * The pipeline used to read `SettingsStore` directly -- five classes reaching into the app's own
 * preference store for one or two values each. That is the single thing that stops this code being
 * lifted out as a library: a caller would have to adopt this app's settings model whole, including
 * the twenty fields that have nothing to do with speech.
 *
 * So the dependency is inverted. The pipeline states what it needs, the host maps its own settings
 * onto it (`AppSettings.toSttConfig()`), and a caller with no settings store at all can pass the
 * defaults. Every value here has one, and the defaults are the app's current behaviour.
 *
 * Handed around as a [StateFlow] rather than a snapshot because the values are read *late* -- at the
 * moment a recogniser session is opened, not when the object was constructed. That timing is
 * load-bearing: changing the execution provider in Settings has to affect the next model load
 * without the app rebuilding the object graph, which is what a plain snapshot would require.
 */
data class SttConfig(
    /** Which ONNX Runtime execution provider the sherpa-onnx sessions run on. */
    val onnxProvider: OnnxProvider = OnnxProvider.DEFAULT,

    /** The chosen recogniser, by [SpeechModel.id]. Null selects the default. */
    val speechModelId: String? = null,

    /** How long one slice of audio may be before the recogniser is asked to transcribe it. */
    val sliceWindow: SliceWindow = SliceWindow.DEFAULT,

    /** Which implementation answers "who spoke when". */
    val diarizationEngine: DiarizationEngine = DiarizationEngine.DEFAULT,

    /** How fast audio is fed to the system recogniser, on the platform backend. */
    val platformFeedPace: PlatformFeedPace = PlatformFeedPace.DEFAULT,

    /** How much audio goes into each write on that same path. */
    val platformFeedChunk: PlatformFeedChunk = PlatformFeedChunk.DEFAULT,

    /** The BCP-47 tag the system recogniser is asked for, or null for the device default. */
    val platformLanguage: String? = null,

    /** Whether silence is detected and skipped before transcription. */
    val vadEnabled: Boolean = true,

    /** Whether the keyword spotter listens for spoken markers while recording. */
    val keywordMarkersEnabled: Boolean = false,

    /** Threads for the speech models. 0 = automatic. */
    val threadCount: Int = 0,

    /** How long a stretch of a recording is diarised on its own, in minutes; 0 = all at once. */
    val diarizeChunkMinutes: Int = 5,
) {
    companion object {
        /**
         * What a caller that has no settings of its own gets. Also the object every default in this
         * class adds up to, so a host that maps only the fields it cares about still gets sane
         * behaviour for the rest.
         */
        val DEFAULT = SttConfig()
    }
}
