package com.example.aiagenttestapp.stt

import android.util.Log
import com.example.aiagenttestapp.data.SettingsStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
    /**
     * Where each word sits in the audio, when the model says.
     *
     * Empty for every backend but Parakeet, and defaulted so that stays a detail of this file: the
     * transcript itself is what almost every caller wants, and a nullable list would push a
     * "did this model have timestamps?" branch into all of them.
     */
    val words: List<TimedWord> = emptyList(),
)

/**
 * One transcribed slice of a recording, together with the sample range it came from.
 *
 * The range is kept because it is the only thing that can tie text back to the audio timeline. Of
 * the models offered, only Parakeet returns word timestamps through sherpa-onnx, and nothing reads
 * them: the pipeline is written against ranges because Whisper, SenseVoice and Gemma have nothing
 * to offer instead, so once slices are joined into one string there is no way to recover which part
 * of the recording a given word came from. Speaker attribution and spoken markers both depend on
 * not losing that.
 */
data class SegmentTranscription(
    val range: IntRange,
    val text: String,
    val language: String?,
    /** In *recording* coordinates, not the slice's -- see [SpeechRecognizer.transcribeSegments]. */
    val words: List<TimedWord> = emptyList(),
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
 * Loads whichever model Settings selected -- SenseVoice, Whisper or Parakeet, all *offline*
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
class SpeechRecognizer(private val settings: SettingsStore) {

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

    /**
     * Threads the loaded session was built with, so a caller that needs a different share can tell
     * that reloading is necessary. A session's thread count is fixed at construction, so a warm
     * recogniser silently keeps whatever the last caller asked for -- which would have made
     * [ThreadBudget] apply only to the first run after a cold start.
     */
    var loadedThreadCount: Int? = null
        private set

    val isLoaded: Boolean get() = loadedModelId != null

    /**
     * Loads the ASR model. Seconds-long and allocation-heavy; never call this on the main thread.
     *
     * @param threadCount how many threads the session may use. Defaulted rather than required,
     * because most callers are the only heavy thing running; diarisation passes a share from
     * [ThreadBudget] because it runs this beside the diarisation models on the same cores.
     */
    suspend fun load(
        paths: SpeechModelPaths,
        threadCount: Int = recommendedThreadCount(),
    ) = decodeLock.withLock {
        loadLocked(paths, threadCount)
    }

    private suspend fun loadLocked(
        paths: SpeechModelPaths,
        threadCount: Int = recommendedThreadCount(),
    ) = withContext(Dispatchers.IO) {
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
                numThreads = threadCount,
                provider = settings.settings.value.onnxProvider.slug,
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
                    // Off by default, and leaving it off is what made Whisper look like a model with
                    // no timestamps at all. sherpa computes a time per token from the decoder's
                    // cross-attention (OpenAI's DTW method) only when asked; unasked, it returns an
                    // empty array that reads exactly like "this model cannot do that".
                    enableTokenTimestamps = true,
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = threadCount,
                provider = settings.settings.value.onnxProvider.slug,
                modelType = "whisper",
                debug = false,
            )

            SpeechEngineKind.NEMO_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = requirePath(paths.encoder, "encoder"),
                    decoder = requirePath(paths.decoder, "decoder"),
                    joiner = requirePath(paths.joiner, "joiner"),
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = threadCount,
                provider = settings.settings.value.onnxProvider.slug,
                // Named, not left empty for sherpa to work out. It can -- but only by opening the
                // encoder to read its metadata, which is a second load of a 650 MB file, and its own
                // log calls that path "Invalid model_type ... trying to load the model to get its
                // type". The value has to be exactly this one: "transducer" is also accepted and
                // selects the icefall implementation, which fails on these files.
                modelType = "nemo_transducer",
                // No language field to set. Unlike Whisper above, this model identifies the language
                // internally and never says which it chose; see the note on [Transcription.language].
                debug = false,
            )

            // Refused rather than quietly handled. A streaming transducer is not a slow offline
            // model -- it is fed audio as it arrives and has its own recogniser, [StreamingRecognizer].
            // Routing one here would mean the caller believes it is streaming when it is not, and the
            // symptom of that is a transcript that arrives at the end anyway, with no error to
            // explain why.
            SpeechEngineKind.STREAMING_ZIPFORMER -> error(
                "${paths.id} is a streaming model; load it with StreamingRecognizer, not this one",
            )
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioRecorder.SAMPLE_RATE,
                // 80 is right for Whisper and SenseVoice and wrong for Parakeet, which is trained on
                // 128 mel bins -- and it is still correct to pass it. sherpa's NeMo recogniser
                // overwrites feature_dim from the model's own metadata as it constructs (along with
                // the normalisation type and the librosa-compatible filterbank), so a number set
                // here is ignored on that path. Changing it to 128 would silently break the two
                // families that do read it.
                featureDim = 80,
            ),
            modelConfig = modelConfig,
        )

        // assetManager is null: the model lives on the filesystem, not in the APK.
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        loadedModelId = paths.id
        loadedThreadCount = threadCount
        Log.i(TAG, "ASR model loaded: ${paths.id}")
    }

    /**
     * Transcribes [samples] -- the whole recording, 16 kHz mono, -1..1.
     *
     * Two of the three models report the language they heard -- SenseVoice always, among its five;
     * Whisper because it is loaded with `language = ""` -- and the result carries it out, so
     * downstream work (the summary, the saved note) can follow the language the user spoke. Parakeet
     * detects it too and has nowhere to put it: sherpa's transducer result has no `lang` field, so
     * that path returns null and callers fall back to whatever they do for an unknown language.
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
            Transcription(
                text = result.text.trim(),
                language = normalizeLanguage(result.lang),
                // Empty for the families that report none, which costs nothing: the arrays are
                // parallel, so a model with no timestamps yields no words rather than words at
                // time zero.
                words = TimedWords.fromTokens(
                    tokens = result.tokens?.toList().orEmpty(),
                    timestamps = result.timestamps ?: FloatArray(0),
                    clipEndSeconds = samples.size.toFloat() / AudioRecorder.SAMPLE_RATE,
                ),
            )
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
     * trying to split the string afterwards -- of the offered models only Parakeet returns word
     * timestamps in sherpa-onnx, so on every other one there is nothing to align a character offset
     * against.
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

                // Defensive clamp: a caller computing boundaries in seconds can round a range one
                // sample past the buffer, and copyOfRange throws on that.
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
                    // Into recording coordinates here and nowhere else. Each slice is decoded on its
                    // own and reports times from its own zero, so without this every slice's words
                    // would claim to be at the start of the recording -- and the CLAUDE.md warning
                    // about mixing window and recording coordinates is exactly this trap, one
                    // abstraction up from the array indexing that has already caused a crash here.
                    words = TimedWords.offsetBySamples(
                        words = piece.words,
                        offsetSamples = from,
                        sampleRate = AudioRecorder.SAMPLE_RATE,
                    ),
                )
                onProgress?.invoke(index + 1, bounds.size, joinSegments(results))
            }

            results
        }
    }

    /** Where this recogniser's segment boundaries go. See [AudioSegmenter] for the arithmetic. */
    fun segmentBounds(samples: FloatArray): List<IntRange> =
        AudioSegmenter.segmentBounds(samples, AudioSegmenter.ONNX)

    /**
     * SenseVoice reports a token like `<|en|>`, Whisper a bare `en`; both become a plain code.
     * Parakeet reports an empty string, which falls out of here as null -- the same answer, and the
     * right one, since it never said.
     */
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
        loadedThreadCount = null
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
