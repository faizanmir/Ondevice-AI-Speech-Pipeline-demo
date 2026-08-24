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
 *
 * ## Do not switch back to the "-mobile" KWS bundle
 *
 * It aborts the process. On device (2026-08-13, Lenovo TB336FU) a few seconds into any recording:
 *
 *     Ort::Exception: Reshape node '/downsample/Reshape_1'
 *     Input shape:{17,1,128}, requested shape:{8,2,1,128}
 *
 * Its encoder is handed an odd frame count where the downsample needs an even one. That arrives as a
 * C++ exception and libc++abi turns it straight into SIGABRT, so the `runCatching` in [accept] cannot
 * contain it -- the app dies and takes the in-progress recording with it.
 *
 * The fix was the bundle, and nothing else. Ruled out on device first, all reproducing identically:
 * resetting inside the decode loop, an unset `OnlineModelConfig.modelType`, the int8 encoder, and
 * sherpa-onnx 1.13.5. The standard `gigaspeech-3.3M` archive works where `-mobile` does not, even
 * though the two declare the same `model_type=zipformer2` and `decode_chunk_len=32` -- only their
 * encoder graphs differ (their decoders and tokens are byte-identical). Nothing in the metadata
 * predicts it, which is why `AudioModelCatalog.KEYWORDS` names the working archive explicitly.
 */
class KeywordDetector {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /**
     * End of the last marker accepted this recording, in samples. The floor every later hit has to
     * clear.
     *
     * Kept because the model's own timestamps cannot be trusted to move forward: they restart after
     * `reset(stream)`, which the spotter calls every time a keyword fires. Without a floor the
     * second marker in a recording reports a time from before the first.
     */
    private var lastAcceptedEnd = 0

    /**
     * Whether the stream has been reset since it was created -- i.e. whether a keyword has already
     * fired this recording.
     *
     * Once it has, the model's token timestamps are not used again. They restart from zero after
     * `reset(stream)` and then count forward from the wrong base, which a floor cannot repair: it
     * catches a marker that goes *backwards* but not one that is merely early. Measured on device
     * with four phrases spoken about eight seconds apart, the second hit reported 6.4 s for audio
     * spoken at 12 s -- forward of the previous marker, so plausible by every test, and wrong by
     * five seconds. The feed position has no such problem: it is the caller's own count of audio
     * handed over, so it cannot drift.
     */
    private var hasReset = false

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
                // Pinned to CPU, and deliberately left out of the provider setting the transcription
                // path honours. Two reasons, and the second is the one that decides it. This does not
                // run during transcription at all -- it runs live, while the user is recording -- so
                // it contributes nothing to the number that setting exists to measure. And a provider
                // that mishandles a tensor here does not return an error: sherpa has been seen to
                // abort the process outright on a shape mismatch in this model, which during a
                // recording costs the user the walkthrough they are in the middle of. There is
                // nothing to win here and a recording to lose.
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
        lastAcceptedEnd = 0
        hasReset = false
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

            // Decode to exhaustion, THEN read the result, THEN reset -- the order sherpa's own
            // streaming samples use, with the reset outside the decode loop rather than inside it.
            //
            // Honest note on what this does and does not buy. Resetting mid-loop is not the
            // documented usage and is worth not doing; but it is NOT the cause of the abort this
            // detector currently hits on device (see the class docs), which reproduces with this
            // ordering too. The cost of the safer order is at most one keyword per chunk -- ~100 ms
            // of audio, and nobody says two marker phrases in a tenth of a second.
            while (active.isReady(activeStream)) {
                active.decode(activeStream)
            }

            val fired = mutableListOf<SpottedKeyword>()
            val result = active.getResult(activeStream)
            if (result.keyword.isNotEmpty()) {
                fired += offsetsFor(result.keyword, result.timestamps)
                // Without this the same keyword can never fire twice in one recording -- and opening
                // three non-conformities in a walkthrough is the normal case, not the exotic one.
                // It is also what invalidates the model's timestamps from here on: see [hasReset].
                active.reset(activeStream)
                hasReset = true
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

        val plausible = !hasReset &&
            first != null && last != null &&
            first >= 0f && last >= first &&
            (last * rate) <= position + rate && // one second of slack for decoder lookahead
            // ...and after everything already heard. This is the check that was missing, and its
            // absence was not theoretical: on device a recording produced ACTION_START at 10.4 s
            // followed by ACTION_END at 3.8 s, both reported as trustworthy, because every other
            // test above passes for a timestamp that is merely *early*. The frame counter restarts
            // after `reset(stream)` -- which is exactly what the class docs said was undocumented
            // and worth checking. A marker placed before the one it follows does not just misplace
            // itself; it inverts the span, and SpokenMarkers.pair then reads the end as orphaned and
            // lets the start run to the end of the recording, swallowing the whole note.
            (first * rate) >= lastAcceptedEnd

        if (plausible) {
            val start = (first!! * rate).toInt().coerceAtLeast(lastAcceptedEnd)
            val end = (last!! * rate).toInt().coerceIn(start, position.toInt())
            lastAcceptedEnd = end
            return SpottedKeyword(
                id = keyword,
                startSample = start,
                endSample = end,
                timestamped = true,
            )
        }

        Log.w(
            TAG,
            "keyword $keyword had unusable timestamps (${timestamps?.joinToString()}); " +
                "using the feed position instead",
        )
        // The feed position is monotonic by construction, so this branch cannot go backwards -- but
        // the backdated start still has to clear the last hit, or two phrases spoken close together
        // would overlap.
        val end = position.toInt().coerceAtLeast(lastAcceptedEnd)
        val start = (end - TYPICAL_PHRASE_SAMPLES).coerceIn(lastAcceptedEnd, end)
        lastAcceptedEnd = end
        return SpottedKeyword(
            id = keyword,
            startSample = start,
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
        lastAcceptedEnd = 0
        hasReset = false
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
