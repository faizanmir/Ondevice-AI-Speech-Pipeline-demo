package com.example.aiagenttestapp.stt

import android.util.Log
import com.example.aiagenttestapp.functions.SpokenKeywords
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** The files a keyword-spotting model needs, resolved to absolute paths. */
data class KeywordModelPaths(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    /**
     * Where the keyword list is written. Generated on device from [SpokenKeywords] rather than
     * downloaded with the model -- see [KeywordDetector.load] for why the spotter insists on a file.
     */
    val keywords: File,
)

/**
 * A keyword the spotter heard, and where in the recording it was.
 *
 * The sample offsets are the whole reason this type is richer than "a keyword fired". They become cut
 * points for the transcriber: the audio between the end of a *start* marker and the beginning of an
 * *end* marker is exactly the tagged span, so each marker lands on a segment boundary by construction
 * and the marker words themselves are never transcribed into the note.
 */
data class SpottedKeyword(
    val id: String,
    /** Sample offset where the spoken phrase began. */
    val startSample: Int,
    /** Sample offset where it ended. */
    val endSample: Int,
    /**
     * True when the model's own token timestamps were used, false when the offsets were derived from
     * how much audio had been fed. See [KeywordDetector.offsetsFor] -- the fallback exists because
     * whether timestamps stay absolute across `reset()` is not something the Kotlin API documents.
     */
    val timestamped: Boolean,
)

/**
 * Spots fixed spoken phrases in a live recording, using sherpa-onnx's streaming keyword spotter.
 *
 * This replaces a much more expensive approach. Spoken commands used to be found by re-transcribing
 * the last four seconds of audio with the *full* ASR model every 1.8 seconds -- on Whisper Small, by
 * far the heaviest thing happening while recording, and it re-decoded the same words in two or three
 * consecutive overlapping windows. A purpose-built 3.3 M-parameter transducer consumes each chunk once
 * as it arrives, at a small fraction of the cost, and reports frame-level timestamps that the
 * re-transcription approach could never provide.
 *
 * English only: no German keyword model exists upstream. `SpokenKeywords.spokenPhrases` covers other
 * languages by scanning the finished transcript instead.
 */
class KeywordDetector {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /**
     * Where in the recording the last accepted chunk ended.
     *
     * Supplied by the caller rather than counted here. The detector can finish loading *after* a
     * recording has already started -- the model is loaded off the main thread when the screen opens --
     * and an internal counter would then be behind the capture buffer by however much audio it missed,
     * silently placing every marker too early. The caller always knows the true offset.
     */
    private var position = 0L

    val isLoaded: Boolean get() = spotter != null

    /**
     * Loads the model. Allocation-heavy; never call from the main thread.
     *
     * Throws rather than returning a broken detector, because the alternative is not a broken
     * detector but a dead process: the native spotter validates its config before constructing
     * anything and returns a null handle when it rejects it, while the Kotlin binding stores that
     * null without looking at it. The next call into the spotter then dereferences it -- SIGSEGV
     * inside `createStream`, which no `runCatching` at any level can turn back into a recording that
     * simply has no spoken markers. So every precondition the native side checks is checked here
     * first, where failing is an ordinary exception.
     */
    fun load(paths: KeywordModelPaths) {
        release()

        mapOf(
            "encoder" to paths.encoder,
            "decoder" to paths.decoder,
            "joiner" to paths.joiner,
            "tokens" to paths.tokens,
        ).forEach { (part, file) ->
            check(file.isFile && file.length() > 0L) {
                "keyword model $part is missing or empty at ${file.absolutePath}"
            }
        }

        // The keyword list has to reach the spotter as a *file*. Handing keywords to createStream()
        // instead reads like it should be enough -- and does work, per stream -- but the spotter
        // refuses to be constructed at all unless the config names a keywords file or carries a
        // keywords buffer, and the Kotlin binding exposes only the file. It is rewritten on every
        // load, so SpokenKeywords stays the single copy and this file cannot drift away from it.
        paths.keywords.writeText(SpokenKeywords.keywordsSpec() + "\n")
        check(paths.keywords.length() > 0L) {
            "could not write the keyword list to ${paths.keywords.absolutePath}"
        }

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(
                sampleRate = AudioRecorder.SAMPLE_RATE,
                featureDim = 80,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.encoder.absolutePath,
                    decoder = paths.decoder.absolutePath,
                    joiner = paths.joiner.absolutePath,
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = 1,
                provider = "cpu",
            ),
            keywordsFile = paths.keywords.absolutePath,
            // sherpa's defaults. The per-keyword `#threshold` overrides in SpokenKeywords tighten the
            // handful of phrases that are short enough to collide with ordinary speech.
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        )

        val created = KeywordSpotter(assetManager = null, config = config)
        // Streams take their keywords from the config, so no argument here.
        val createdStream = created.createStream()
        if (createdStream.ptr == 0L) {
            // The one native failure this binding does surface. Cheap to check, and the alternative
            // is the same segfault one call later.
            runCatching { created.release() }
            error("the keyword spotter would not open a stream")
        }

        stream = createdStream
        spotter = created
        position = 0
        Log.i(TAG, "keyword spotter loaded with ${SpokenKeywords.entries.size} keywords")
    }

    /**
     * Feeds one chunk of microphone audio and returns whatever fired inside it.
     *
     * Usually empty -- it is called ~10 times a second for the whole recording. Never throws: a
     * keyword spotter failing must not take a recording down with it, so a native error switches the
     * detector off and lets the recording carry on without spoken markers.
     */
    fun accept(samples: FloatArray, chunkStartSample: Long): List<SpottedKeyword> {
        val active = spotter ?: return emptyList()
        val activeStream = stream ?: return emptyList()
        if (samples.isEmpty()) return emptyList()

        return try {
            activeStream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            position = chunkStartSample + samples.size

            val fired = mutableListOf<SpottedKeyword>()
            while (active.isReady(activeStream)) {
                active.decode(activeStream)

                val result = active.getResult(activeStream)
                if (result.keyword.isEmpty()) continue

                fired += offsetsFor(result.keyword, result.timestamps)
                // Without this the same keyword can never fire twice in one recording -- and opening
                // three non-conformities in a walkthrough is the normal case, not the exotic one.
                active.reset(activeStream)
            }
            fired
        } catch (e: Exception) {
            Log.w(TAG, "keyword spotting failed; carrying on without it", e)
            release()
            emptyList()
        }
    }

    /**
     * Turns a result's token timestamps into sample offsets, falling back to the feed position.
     *
     * The model reports each token's time in seconds from the start of the stream. Those are used when
     * they are plausible -- monotonic, non-negative, and not claiming to be from audio we have not fed
     * yet. They are not simply trusted because `reset()` is documented to clear decoder state without
     * saying what happens to the frame counter, and a timestamp that silently restarts at zero after
     * the first keyword would place every later marker at the beginning of the recording. That failure
     * would be invisible in the chip and destructive in the transcript.
     *
     * The fallback assumes the phrase ended about where the feed is now, which is true to within the
     * decoder's lookahead, and backdates the start by a typical phrase length.
     */
    private fun offsetsFor(keyword: String, timestamps: FloatArray?): SpottedKeyword {
        val rate = AudioRecorder.SAMPLE_RATE
        val first = timestamps?.firstOrNull()
        val last = timestamps?.lastOrNull()

        val plausible = first != null && last != null &&
            first >= 0f && last >= first &&
            (last * rate) <= position + rate // one second of slack for decoder lookahead

        if (plausible) {
            return SpottedKeyword(
                id = keyword,
                startSample = (first!! * rate).toInt().coerceAtLeast(0),
                endSample = (last!! * rate).toInt().coerceIn(0, position.toInt()),
                timestamped = true,
            )
        }

        Log.w(
            TAG,
            "keyword $keyword had unusable timestamps (${timestamps?.joinToString()}); " +
                "using the feed position instead",
        )
        val end = position.toInt()
        return SpottedKeyword(
            id = keyword,
            startSample = (end - TYPICAL_PHRASE_SAMPLES).coerceAtLeast(0),
            endSample = end,
            timestamped = false,
        )
    }

    /** Forgets decoder state and the sample counter. Call when a new recording starts. */
    fun reset() {
        val active = spotter ?: return
        val activeStream = stream ?: return
        runCatching { active.reset(activeStream) }
        position = 0
    }

    fun release() {
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
            .onFailure { Log.w(TAG, "releasing the keyword spotter failed", it) }
        stream = null
        spotter = null
        position = 0
    }

    private companion object {
        const val TAG = "KeywordDetector"

        /** ~1.6 s: about how long "start non conformity" takes to say. Only used by the fallback. */
        val TYPICAL_PHRASE_SAMPLES = AudioRecorder.SAMPLE_RATE * 16 / 10
    }
}
