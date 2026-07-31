package com.example.aiagenttestapp.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A finished transcription: the text, plus the language the model detected in the audio. */
data class Transcription(
    val text: String,
    /** Lowercase ISO 639 code ("de", "en", "yue"), or null when the model reported none. */
    val language: String?,
)

/**
 * One transcribed slice of a recording, together with the sample range it came from.
 *
 * The range is kept because it is the only thing that can tie text back to the audio timeline:
 * neither Whisper nor SenseVoice reports word timestamps through sherpa-onnx, so once slices are
 * joined into one string there is no way to recover which part of the recording a given word came
 * from. Speaker attribution and spoken markers both depend on not losing that.
 */
data class SegmentTranscription(
    val range: IntRange,
    val text: String,
    val language: String?,
)

/** Joins segment texts into one transcript, skipping the blanks so silence adds no double spaces. */
internal fun joinSegments(segments: List<SegmentTranscription>): String = segments
    .asSequence()
    .map { it.text.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" ")
    .trim()

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
     * Serialises everything that touches [recognizer]: loading, releasing and decoding.
     *
     * This used to rest on call ordering -- the record screen cancelled *and joined* its live command
     * detector before starting the full-buffer pass, so only one decode was ever in flight. That
     * argument stopped holding once transcription moved into a background worker: the worker can be
     * transcribing one note while the user is back on the record screen starting the next, and two
     * concurrent `decode` calls on one recogniser corrupt each other's output. Worse, releasing the
     * model while a decode is running frees native memory out from under it.
     *
     * A lock rather than a second recogniser instance: two loaded copies of Whisper Small is ~750 MB
     * of native memory, which is more than the phones this targets can spare.
     */
    private val decodeLock = Mutex()

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
    suspend fun load(paths: SpeechModelPaths) = decodeLock.withLock {
        loadLocked(paths)
    }

    private suspend fun loadLocked(paths: SpeechModelPaths) = withContext(Dispatchers.IO) {
        releaseLocked()

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
     * Both models detect the spoken language themselves -- SenseVoice always, among its five;
     * Whisper because it is loaded with `language = ""` -- and the result carries it out, so
     * downstream work (the summary, the saved note) can follow the language the user spoke.
     *
     * sherpa-onnx streams are single-use: one stream per utterance, decoded once. Reusing one across
     * recordings would append the new audio to the old and transcribe both together.
     */
    suspend fun transcribe(samples: FloatArray): Transcription = decodeLock.withLock {
        decodeOnce(samples)
    }

    private suspend fun decodeOnce(samples: FloatArray): Transcription = withContext(Dispatchers.IO) {
        val active = recognizer ?: error("The speech model is not loaded")
        if (samples.isEmpty()) return@withContext Transcription("", null)

        val stream = active.createStream()
        try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            active.decode(stream)
            val result = active.getResult(stream)
            Transcription(result.text.trim(), normalizeLanguage(result.lang))
        } finally {
            stream.release()
        }
    }

    /**
     * Transcribes a whole recording of any length.
     *
     * [transcribe] hands the audio to the model in one shot, which is only correct up to the ~30 s
     * these offline models can actually attend to. Past that, Whisper's encoder silently keeps just
     * the first 30 seconds and SenseVoice degrades into repeated garbage -- the "it transcribes the
     * start and then stops" bug on a note of any real length. This splits the audio into
     * sub-30-second segments, cut at the quietest point near each boundary so a word is rarely
     * sliced in two, transcribes each, and joins them.
     *
     * [onProgress] is invoked after every segment with the running transcript and a 0..1 fraction,
     * so the caller can show the text arriving instead of a spinner over a long silence.
     */
    suspend fun transcribeLong(
        samples: FloatArray,
        onProgress: (suspend (partial: String, fraction: Float) -> Unit)? = null,
    ): Transcription {
        val pieces = transcribeSegments(samples, segmentBounds(samples)) { done, total, joined ->
            onProgress?.invoke(joined, done.toFloat() / total)
        }
        return Transcription(
            text = joinSegments(pieces),
            // The first segment that carries a language wins; the rest only echo it.
            language = pieces.firstNotNullOfOrNull { it.language },
        )
    }

    /**
     * Transcribes exactly the ranges it is given, one decode each, and returns them separately.
     *
     * The per-range result is the point: callers that know *why* a boundary is where it is need the
     * text on each side kept apart. Speaker-turn boundaries need it so each turn can be attributed to
     * a person, and spoken-marker boundaries need it so the text inside a "non-conformity" tag is
     * exactly the audio between the two spoken markers. Both would be destroyed by joining first and
     * trying to split the string afterwards -- neither Whisper nor SenseVoice returns word
     * timestamps in sherpa-onnx, so there is nothing to align a character offset against.
     *
     * The whole pass holds [decodeLock], so a model swap cannot land halfway through a recording.
     */
    suspend fun transcribeSegments(
        samples: FloatArray,
        bounds: List<IntRange>,
        onProgress: (suspend (done: Int, total: Int, joined: String) -> Unit)? = null,
    ): List<SegmentTranscription> = decodeLock.withLock {
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SegmentTranscription>()

            bounds.forEachIndexed { index, range ->
                currentCoroutineContext().ensureActive()

                // Defensive clamp: a caller computing boundaries from diarisation timings in seconds
                // can round a range one sample past the buffer, and copyOfRange throws on that.
                val from = range.first.coerceIn(0, samples.size)
                val to = (range.last + 1).coerceIn(from, samples.size)

                val piece = if (to > from) {
                    decodeOnce(samples.copyOfRange(from, to))
                } else {
                    Transcription("", null)
                }

                results += SegmentTranscription(
                    range = from until to,
                    text = piece.text,
                    language = piece.language,
                )
                onProgress?.invoke(index + 1, bounds.size, joinSegments(results))
            }

            results
        }
    }

    /**
     * Splits [samples] into inclusive ranges, each at most [MAX_SEGMENT_SAMPLES] long and ending on
     * the quietest frame between the target length and the hard cap -- a pause, so segment edges
     * fall between words rather than through them.
     */
    fun segmentBounds(samples: FloatArray): List<IntRange> {
        val bounds = mutableListOf<IntRange>()
        var start = 0
        while (start < samples.size) {
            if (samples.size - start <= MAX_SEGMENT_SAMPLES) {
                bounds += start..samples.lastIndex
                break
            }
            val cut = quietestCutBetween(samples, start, minOf(start + MAX_SEGMENT_SAMPLES, samples.size))
            bounds += start until cut
            start = cut
        }
        return bounds
    }

    /**
     * The sample index of the quietest ~30 ms frame in `[from, until)`, preferring a cut no earlier
     * than [TARGET_SEGMENT_SAMPLES] past [from] so most segments run near their useful length.
     *
     * Public because the slicer needs it: when spoken markers and speaker turns decide where the
     * boundaries go, an over-long slice between two of them still has to be split somewhere sensible,
     * and "at the quietest moment" is that somewhere. Passing it in as a function keeps the slicing
     * rules pure and testable while the acoustics stay here.
     */
    fun quietestCutBetween(samples: FloatArray, from: Int, until: Int): Int {
        val hardEnd = until.coerceIn(0, samples.size)
        // Never search past the end, and never insist on a target the window is too short to reach.
        val searchStart = (from + TARGET_SEGMENT_SAMPLES).coerceAtMost(hardEnd)

        var quietest = Double.MAX_VALUE
        var cutAt = hardEnd
        var i = searchStart
        while (i + CUT_FRAME_SAMPLES <= hardEnd) {
            var energy = 0.0
            for (j in i until i + CUT_FRAME_SAMPLES) energy += (samples[j] * samples[j]).toDouble()
            if (energy < quietest) {
                quietest = energy
                cutAt = i + CUT_FRAME_SAMPLES / 2
            }
            i += CUT_FRAME_SAMPLES
        }
        return cutAt.coerceIn(from + 1, hardEnd.coerceAtLeast(from + 1))
    }

    /** SenseVoice reports a token like `<|en|>`, Whisper a bare `en`; both become a plain code. */
    private fun normalizeLanguage(raw: String?): String? {
        val code = raw.orEmpty().trim()
            .removePrefix("<|").removeSuffix("|>")
            .trim().lowercase()
        return code.takeIf { it.isNotEmpty() && it.all(Char::isLetter) }
    }

    /**
     * Frees the native model.
     *
     * Suspending, and it takes [decodeLock], because this is shared: the record screen releases on
     * `onCleared()` while a background transcription worker may still be mid-decode on the very same
     * recogniser. Freeing the native memory under a running decode takes the process down, and a
     * plain `fun release()` gives the caller no way to wait.
     */
    suspend fun release() = decodeLock.withLock { releaseLocked() }

    private fun releaseLocked() {
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

    companion object {
        private const val TAG = "SpeechRecognizer"

        /**
         * Both SenseVoice and Whisper are trained on utterances of roughly half a minute -- Whisper's
         * encoder ignores anything past 30 s outright, SenseVoice degrades into repeated garbage.
         * Long recordings are therefore transcribed in segments no larger than [MAX_SEGMENT_SAMPLES].
         * 28 s leaves headroom under the 30 s wall; [TARGET_SEGMENT_SAMPLES] is 20 s so most cuts land
         * at a natural pause well before the cap.
         */
        val TARGET_SEGMENT_SAMPLES = AudioRecorder.SAMPLE_RATE * 20
        val MAX_SEGMENT_SAMPLES = AudioRecorder.SAMPLE_RATE * 28

        /** ~30 ms energy frame used to find the quietest cut point between segments. */
        private val CUT_FRAME_SAMPLES = AudioRecorder.SAMPLE_RATE * 30 / 1000
    }
}
