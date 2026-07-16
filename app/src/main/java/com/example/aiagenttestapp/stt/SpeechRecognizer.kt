package com.example.aiagenttestapp.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Speech-to-text with sherpa-onnx, running entirely on the device.
 *
 * Loads whichever model Settings selected -- SenseVoice or Whisper, both *offline*
 * (non-streaming) recognisers: the whole recording is transcribed once the user stops talking,
 * rather than word-by-word as they speak. That is the right trade here. A streaming model has to
 * commit to each word before hearing the next one, so it cannot revise -- and this transcript is
 * going to be read, edited, and summarised, where accuracy matters far more than seeing words
 * appear live.
 *
 * The models are loaded from the filesystem, not from assets: they are hundreds of MB, and baking
 * them into the APK would multiply its size for a feature not everyone uses. They are downloaded
 * on demand instead, like the language models.
 */
class SpeechRecognizer {

    private var recognizer: OfflineRecognizer? = null

    /**
     * Which [SpeechModel] is currently loaded, or null. Callers compare this against the selected
     * model's id before transcribing, so switching the model in Settings takes effect on the next
     * recording instead of requiring an app restart.
     */
    @Volatile
    var loadedModelId: String? = null
        private set

    val isLoaded: Boolean get() = loadedModelId != null

    /** Loads the ASR model. Seconds-long and allocation-heavy; never call this on the main thread. */
    suspend fun load(paths: SpeechModelPaths) = withContext(Dispatchers.IO) {
        release()

        val modelConfig = when (paths.kind) {
            SpeechEngineKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = requirePath(paths.model, "model"),
                    // Punctuation, capitalisation and numerals. Without it the transcript comes
                    // back as one unpunctuated lowercase stream, which is unpleasant to edit and
                    // gives the summarising model nothing to work with.
                    useInverseTextNormalization = true,
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = recommendedThreadCount(),
                modelType = "sense_voice",
                debug = false,
            )

            SpeechEngineKind.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = requirePath(paths.encoder, "encoder"),
                    decoder = requirePath(paths.decoder, "decoder"),
                    // Empty = detect the language from the audio. The whole reason Whisper is
                    // offered is notes in whatever language the user happens to speak.
                    language = "",
                    task = "transcribe",
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = recommendedThreadCount(),
                modelType = "whisper",
                debug = false,
            )
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioRecorder.SAMPLE_RATE,
                featureDim = 80,
            ),
            modelConfig = modelConfig,
        )

        // assetManager is null: the model lives on the filesystem, not in the APK.
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        loadedModelId = paths.id
        Log.i(TAG, "ASR model loaded: ${paths.id}")
    }

    /**
     * Transcribes [samples] -- the whole recording, 16 kHz mono, -1..1.
     *
     * sherpa-onnx streams are single-use: one stream per utterance, decoded once. Reusing one across
     * recordings would append the new audio to the old and transcribe both together.
     */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val active = recognizer ?: error("The speech model is not loaded")
        if (samples.isEmpty()) return@withContext ""

        val stream = active.createStream()
        try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        runCatching { recognizer?.release() }
            .onFailure { Log.w(TAG, "releasing the recogniser failed", it) }
        recognizer = null
        loadedModelId = null
    }

    /**
     * Leave the little cores alone. Same reasoning as llama.cpp: on a big.LITTLE phone, saturating
     * every core drags the batch down to the speed of the slowest one.
     */
    private fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)

    private fun requirePath(file: File?, role: String): String =
        file?.absolutePath ?: error("Speech model is missing its $role file")

    private companion object {
        const val TAG = "SpeechRecognizer"
    }
}
