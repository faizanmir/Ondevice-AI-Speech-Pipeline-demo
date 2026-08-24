package com.example.aiagenttestapp.stt

import com.example.aiagenttestapp.data.SliceWindow
import com.example.aiagenttestapp.data.notes.SttBackend

/**
 * Where to cut a recording so no single piece is longer than the recogniser can attend to.
 *
 * Pure arithmetic over a `FloatArray`, deliberately kept away from any model. It used to live on
 * [SpeechRecognizer], which was fine while sherpa-onnx was the only transcriber -- but a Gemma
 * transcription needs the same cuts and has no business loading an ONNX session to get them. Both
 * backends now ask this, and a unit test can too.
 *
 * Every offline speech model has a length past which it stops listening rather than failing loudly,
 * which is the worst possible shape for a bug: the transcript covers the first half-minute and then
 * simply stops, or trails off into repeated garbage, and nothing reports an error. The caps below
 * exist to keep every piece under that line.
 */
object AudioSegmenter {

    /**
     * Length limits for one backend, in samples at [AudioRecorder.SAMPLE_RATE].
     *
     * [target] is where a cut is *preferred*; [max] is the hard cap. The gap between them is the
     * window the quiet-point search gets to work in, so a cut can wait for a pause instead of
     * landing mid-word.
     */
    data class Limits(val target: Int, val max: Int)

    /**
     * sherpa-onnx: SenseVoice and Whisper are both trained on utterances of roughly half a minute --
     * Whisper's encoder ignores anything past 30 s outright, SenseVoice degrades into repetition.
     * 28 s leaves headroom under that wall; 20 s as the target means most cuts land at a natural
     * pause well before the cap.
     *
     * Parakeet does not belong here and has [PARAKEET] instead.
     */
    val ONNX = Limits(
        target = AudioRecorder.SAMPLE_RATE * 20,
        max = AudioRecorder.SAMPLE_RATE * 28,
    )

    /**
     * Parakeet: the only recogniser here whose cap is set by device memory rather than by the model
     * refusing to listen.
     *
     * The model has no 30-second wall. NVIDIA advertises 24 minutes in one pass, and the checkpoint
     * backs that up -- `self_attention_model: rel_pos` with `att_context_size: [-1, -1]`, full
     * attention over the whole clip. That is also exactly why 24 minutes is not available here: the
     * figure is quoted for an A100 with 80 GB, and full attention costs memory as the *square* of
     * the clip length.
     *
     * The arithmetic, since it decides the number. The encoder emits one frame per 80 ms (10 ms hop,
     * subsampling factor 8), and each of its 24 layers builds an 8-head attention matrix over those
     * frames -- 8 x T x T x 4 bytes live at once:
     *
     * ```
     *    28 s ->   350 frames ->   4 MB      120 s -> 1500 frames ->  72 MB
     *    60 s ->   750 frames ->  18 MB      300 s -> 3750 frames -> 450 MB
     *                                         24 min -> 18000 frames -> 10 GB
     * ```
     *
     * On top of a model that already sits at about 1.2 GB resident, and on a device that may also be
     * holding a language model for the summary. 120 s keeps the transient under 100 MB, which is
     * affordable; a few minutes is not, and 24 is not a candidate at any point.
     *
     * A second ceiling sits above that one and is worth knowing before anyone raises this: the
     * exported positional embedding is `pos_emb_max_len: 5000`, so the ONNX graph physically cannot
     * attend past 5000 frames -- 400 s -- however much memory the device has. The real long-form
     * path is not a larger number here; it is re-exporting the model with local attention
     * (`change_attention_model("rel_pos_local_attn", ...)`, which is where NVIDIA's "3 hours" figure
     * comes from), which makes memory linear in the clip length. sherpa-onnx does not publish such
     * an export.
     *
     * This is the default rather than the only value: [SliceWindow] offers 30 s to 240 s from the
     * benchmark screen, because where the useful window actually sits is the open question Parakeet
     * was carried here to answer. Overshooting costs an allocation failure inside the native
     * session, not a truncated transcript, so the range is bounded rather than free.
     */
    val PARAKEET = parakeetLimits(SliceWindow.DEFAULT.seconds)

    /**
     * Parakeet's limits for a window of [seconds].
     *
     * The target sits at three quarters of the cap throughout, so the quiet-point search always gets
     * a quarter of the window to find a real pause in. Deriving it rather than listing both numbers
     * per option is what stops a new [SliceWindow] entry arriving with a target nobody thought
     * about -- a target too close to the cap gives the search nowhere to look and every cut lands
     * mid-word.
     */
    fun parakeetLimits(seconds: Int): Limits = Limits(
        target = AudioRecorder.SAMPLE_RATE * seconds * 3 / 4,
        max = AudioRecorder.SAMPLE_RATE * seconds,
    )

    /**
     * LiteRT-LM: noticeably more conservative than [ONNX], and not as a matter of taste.
     *
     * Gemma's audio encoder is documented for clips of about 30 s, but the runtime enforces its
     * limit with a native assertion (`audio_matrix.cols() <= max_audio_seq_length`). A failed
     * assertion aborts the process -- it is not an exception, so there is nothing to catch and
     * nothing to report; the app simply dies mid-recording. Overshooting the ONNX cap costs a
     * truncated transcript, overshooting this one costs the user their note, so the margin here is
     * deliberately wide. Raise it only against measurements from a real device.
     */
    val GEMMA = Limits(
        target = AudioRecorder.SAMPLE_RATE * 18,
        max = AudioRecorder.SAMPLE_RATE * 24,
    )

    /**
     * Android's own recogniser: the conservative option, because nothing here is measured.
     *
     * Unlike [ONNX] and [GEMMA], whose caps come from a documented model limit and a native
     * assertion respectively, no published limit applies to a segmented session and no device
     * measurement backs a larger number. The asymmetry of being wrong decides it: too short costs a
     * few extra round trips through a system service, while too long risks a service that truncates
     * silently -- losing speech with nothing in the log to say so, which is the failure this
     * codebase has already paid for once. Raise it only against a real device.
     */
    val PLATFORM = Limits(
        target = AudioRecorder.SAMPLE_RATE * 15,
        max = AudioRecorder.SAMPLE_RATE * 20,
    )

    /**
     * The limits one sherpa model runs under, or null when it has none.
     *
     * The single place the sherpa caps are chosen, because they now differ *within* the ONNX
     * backend rather than only between backends: SenseVoice and Whisper are held at [ONNX] by a
     * 30-second wall in the model, Parakeet at [PARAKEET] by device memory, and the streaming
     * Zipformer by nothing at all. Both [capFor] and [OnnxTranscriber] read this rather than
     * naming a constant, so the two cannot drift apart -- see [capFor] for why they are asked
     * separately in the first place.
     *
     * Null means genuinely unlimited, not unknown. See [StreamingTranscriber.maxSliceSamples].
     *
     * [window] is deliberately ignored by every branch but Parakeet's. SenseVoice and Whisper are
     * held by a wall in the model rather than by a preference, and honouring a 240 s setting for
     * them would produce silently truncated transcripts -- the exact failure the caps exist to
     * prevent. A setting left behind from a Parakeet run must not be able to do that.
     */
    fun limitsFor(
        kind: SpeechEngineKind,
        window: SliceWindow = SliceWindow.DEFAULT,
    ): Limits? = when (kind) {
        SpeechEngineKind.SENSE_VOICE, SpeechEngineKind.WHISPER -> ONNX
        SpeechEngineKind.NEMO_TRANSDUCER -> parakeetLimits(window.seconds)
        SpeechEngineKind.STREAMING_ZIPFORMER -> null
    }

    /**
     * The slice cap a backend will report, answerable before any transcriber exists.
     *
     * Deliberately duplicates what each [Transcriber] returns as `maxSliceSamples`: the worker
     * wants to run the VAD while the transcriber is still loading -- on the Gemma path that load is
     * multi-gigabyte -- and the VAD needs this number first, because Silero is told the cap so it
     * splits an unbroken run of speech at its own quietest point. The two must stay in agreement;
     * a value that drifted from the transcriber's would not crash, but the regions it produced
     * would split speech to the wrong grain. [limitsFor] is how they are kept in agreement now that
     * the ONNX backend no longer has one answer.
     *
     * [speechModelKind] is read only on the ONNX backend; the other two resolve their model later
     * and have one cap each regardless.
     */
    fun capFor(
        backend: SttBackend,
        speechModelKind: SpeechEngineKind,
        window: SliceWindow = SliceWindow.DEFAULT,
    ): Int = when (backend) {
        SttBackend.GEMMA -> GEMMA.max
        SttBackend.PLATFORM -> PLATFORM.max
        // A streaming transducer consumes audio frame by frame and genuinely has no cap; see
        // [StreamingTranscriber.maxSliceSamples] for why Int.MAX_VALUE is the honest answer.
        SttBackend.ONNX -> limitsFor(speechModelKind, window)?.max ?: Int.MAX_VALUE
    }

    /** ~30 ms energy frame, the resolution the quietest-point search works at. */
    private val CUT_FRAME_SAMPLES = AudioRecorder.SAMPLE_RATE * 30 / 1000

    /**
     * Splits [samples] into inclusive ranges, each at most [Limits.max] long and ending on the
     * quietest frame available -- a pause, so segment edges fall between words rather than through
     * them.
     */
    fun segmentBounds(samples: FloatArray, limits: Limits): List<IntRange> {
        val bounds = mutableListOf<IntRange>()
        var start = 0
        while (start < samples.size) {
            if (samples.size - start <= limits.max) {
                bounds += start..samples.lastIndex
                break
            }
            val cut = quietestCutBetween(
                samples = samples,
                from = start,
                until = minOf(start + limits.max, samples.size),
                limits = limits,
            )
            bounds += start until cut
            start = cut
        }
        return bounds
    }

    /**
     * The sample index of the quietest ~30 ms frame in `[from, until)`, preferring a cut no earlier
     * than [Limits.target] past [from] so most segments run near their useful length.
     *
     * Exposed on its own because the slicer needs it: when spoken markers and speaker turns decide
     * where boundaries go, an over-long slice between two of them still has to be split somewhere
     * sensible, and "at the quietest moment" is that somewhere. Passing it in as a function keeps
     * the slicing rules in [com.example.aiagenttestapp.data.notes.SpokenMarkers] pure and testable
     * while the acoustics stay here.
     */
    fun quietestCutBetween(
        samples: FloatArray,
        from: Int,
        until: Int,
        limits: Limits,
    ): Int {
        val hardEnd = until.coerceIn(0, samples.size)
        // Never search past the end, and never insist on a target the window is too short to reach.
        val searchStart = (from + limits.target).coerceAtMost(hardEnd)

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
}
