package com.example.aiagenttestapp.stt

import com.example.aiagenttestapp.data.SliceWindow

/**
 * Turns ranges of a recording into text. The one thing the two speech backends have in common.
 *
 * Everything downstream of transcription -- marker slicing, checkpointed resume, speaker attribution,
 * the transcript markup, the summary -- is written against sample ranges and the text found in them,
 * and none of it cares which model produced that text. So the backends meet here and nowhere else:
 * [NoteTranscribeWorker][com.example.aiagenttestapp.data.notes.NoteTranscribeWorker] picks an
 * implementation once and runs the same pipeline either way.
 *
 * The two differ in what they can promise, and the interface is deliberately shaped around the
 * weaker of them:
 *
 *  - [OnnxTranscriber] reports the language it heard on two of its three models; [GemmaTranscriber]
 *    returns null, because a language model has no equivalent of a detected-language field and
 *    asking for one in the prompt would put a machine-readable tag in a human-readable transcript.
 *    Parakeet returns null for a third reason again -- it detects the language and sherpa-onnx has
 *    no field to hand it back in. Three unrelated causes, one nullable column.
 *  - Their length limits differ by backend, so [maxSliceSamples] and [quietestCutBetween] come from
 *    the transcriber rather than from a shared constant. A slicer that used the ONNX cap on a Gemma
 *    run would hand the runtime clips it aborts the process over.
 */
interface Transcriber {

    /** Longest slice this backend can be given in one go. See [AudioSegmenter] for why they differ. */
    val maxSliceSamples: Int

    /**
     * Transcribes exactly [ranges], one decode each, and returns them separately.
     *
     * Separate results are the point rather than a convenience: callers that know *why* a boundary
     * is where it is -- a speaker change, a spoken marker -- need the text on each side kept apart,
     * and no backend here returns word timestamps that would let a joined string be split again
     * afterwards. (Parakeet and Whisper both can -- see [SpeechEngineKind.reportsWordTimings] -- but
     * the interface still promises only what all of them can keep, and Gemma cannot.)
     */
    suspend fun transcribe(samples: FloatArray, ranges: List<IntRange>): List<SegmentTranscription>

    /** Where to cut a slice that is longer than [maxSliceSamples]. */
    fun quietestCutBetween(samples: FloatArray, from: Int, until: Int): Int

    /**
     * Frees whatever this backend holds. Suspending because both hold native memory that may have a
     * decode in flight, and neither can be freed from under one.
     */
    suspend fun release()
}

/**
 * Transcribes with sherpa-onnx -- the original path, unchanged, behind the new interface.
 *
 * A thin adapter rather than [SpeechRecognizer] implementing [Transcriber] directly. The recogniser
 * is an app-lifetime singleton shared with chat dictation and the live command detector, which do not
 * want a [Transcriber]'s per-run semantics; and the model-selection step below (load whatever
 * Settings currently points at) belongs to a transcription run rather than to the recogniser.
 *
 * [release] deliberately does nothing. The recogniser outlives any one run and is released by
 * whoever owns it -- freeing a shared native model at the end of a background job would pull it out
 * from under a dictation happening on another screen.
 */
class OnnxTranscriber(
    private val recognizer: SpeechRecognizer,
    private val models: SpeechModelRepository,
    /**
     * How long a slice this run may be given, for the one model that has a say in it.
     *
     * Passed in rather than read from Settings here, and pinned by the caller before the run starts,
     * for the reason every other run parameter is: a recording whose window changed halfway through
     * would compute the second half of its cuts differently from the first, and every slice after
     * the change would miss the checkpoint the pre-decode pass wrote under the old one.
     */
    private val window: SliceWindow = SliceWindow.DEFAULT,
) : Transcriber {

    /**
     * The selected model's limits, not the backend's -- they stopped being the same thing when
     * Parakeet arrived, and this adapter serves three models whose caps come from three different
     * places: a wall in the model, a user-chosen [window], and nothing at all.
     *
     * The fallback is unreachable rather than lenient: [AudioSegmenter.limitsFor] answers null only
     * for a streaming model, and one of those never reaches this class -- `TranscriptionRun` routes
     * it to [StreamingTranscriber], and [SpeechRecognizer.load] refuses it outright. Falling back to
     * the tightest cap in that impossible case keeps a wrong slice short rather than unbounded.
     */
    private val limits: AudioSegmenter.Limits
        get() = AudioSegmenter.limitsFor(models.selected.kind, window) ?: AudioSegmenter.ONNX

    override val maxSliceSamples: Int get() = limits.max

    override suspend fun transcribe(
        samples: FloatArray,
        ranges: List<IntRange>,
    ): List<SegmentTranscription> {
        // Reload when the Settings choice changed, not only when nothing is loaded yet -- switching
        // model in Settings should take effect on the next recording, not on the next app start.
        val paths = models.selectedPaths()
        if (recognizer.loadedModelId != paths.id) recognizer.load(paths)

        return recognizer.transcribeSegments(samples, ranges)
    }

    override fun quietestCutBetween(samples: FloatArray, from: Int, until: Int): Int =
        AudioSegmenter.quietestCutBetween(samples, from, until, limits)

    override suspend fun release() = Unit
}

/**
 * Transcribes with a streaming transducer, feeding each range through as a continuous stream.
 *
 * The interesting part is [maxSliceSamples]. Every other backend reports a real limit because a real
 * limit exists: Whisper truncates at 29.5 s, LiteRT-LM rejects or aborts past its own. A transducer
 * has none -- it consumes audio frame by frame with cached state, so an hour is as decodable as a
 * second. Reporting [Int.MAX_VALUE] is not a shortcut; it is the honest answer, and it changes what
 * slicing *means* on this path. Slices still happen, but only where a spoken marker or a speaker turn
 * puts one, which is where they carry meaning. No cut is ever made merely to fit a model.
 *
 * That also removes this backend from the blast radius of the cut-window bug entirely: with no
 * length cap, [com.example.aiagenttestapp.data.notes.SpokenMarkers] never calls the splitter at all.
 */
class StreamingTranscriber(
    private val recognizer: StreamingRecognizer,
    private val models: SpeechModelRepository,
    /**
     * Restores the capitals and full stops a transducer does not emit. Optional: when the bundle is
     * absent this is a no-op and the transcript is merely uppercase, which is a worse note but still
     * a note.
     */
    private val punctuator: Punctuator? = null,
) : Transcriber {

    override val maxSliceSamples: Int get() = Int.MAX_VALUE

    override suspend fun transcribe(
        samples: FloatArray,
        ranges: List<IntRange>,
    ): List<SegmentTranscription> {
        val paths = models.selectedPaths()
        if (recognizer.loadedModelId != paths.id) recognizer.load(paths)

        return ranges.map { range ->
            val from = range.first.coerceIn(0, samples.size)
            val to = (range.last + 1).coerceIn(from, samples.size)

            // A fresh stream per range. The ranges are deliberately separated -- a marker boundary or
            // a speaker change sits between them -- so letting decoder state run across one would
            // blur exactly the edge the caller went to the trouble of finding.
            recognizer.reset()

            val raw = if (to > from) {
                feedInChunks(samples, from, to)
                recognizer.finish()
            } else {
                ""
            }

            // Punctuated per slice rather than once over the joined transcript, because the slices
            // are handed on separately -- each carries its own tags and speaker -- and there is no
            // joined string to punctuate at this point.
            val text = punctuator?.punctuate(raw) ?: raw

            SegmentTranscription(
                range = from until to,
                text = text,
                // This model is trained on English alone and reports no detection of its own, so
                // there is nothing honest to put here. See the interface docs.
                language = null,
            )
        }
    }

    /**
     * Hands the range over in capture-sized pieces rather than one array.
     *
     * The recogniser is perfectly happy with a single large call, but feeding it the way live audio
     * arrives keeps this path and the record screen's live path exercising the same code, so a bug in
     * chunk handling cannot hide in whichever one is tested less.
     */
    private suspend fun feedInChunks(samples: FloatArray, from: Int, until: Int) {
        var offset = from
        while (offset < until) {
            val end = minOf(offset + FEED_CHUNK_SAMPLES, until)
            recognizer.accept(samples.copyOfRange(offset, end))
            offset = end
        }
    }

    /** Never reached: [maxSliceSamples] means the slicer has no reason to ask for a cut. */
    override fun quietestCutBetween(samples: FloatArray, from: Int, until: Int): Int =
        AudioSegmenter.quietestCutBetween(samples, from, until, AudioSegmenter.ONNX)

    /**
     * Releases the stream but not the model, matching [OnnxTranscriber]: the recogniser is shared
     * with the record screen's live transcript and outlives any one transcription run.
     */
    override suspend fun release() = recognizer.reset()

    private companion object {
        /** ~200 ms, close to what the recorder emits. */
        const val FEED_CHUNK_SAMPLES = AudioRecorder.SAMPLE_RATE / 5
    }
}
