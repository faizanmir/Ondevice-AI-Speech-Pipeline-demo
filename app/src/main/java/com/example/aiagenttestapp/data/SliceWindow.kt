package com.example.aiagenttestapp.data

/**
 * How long one slice of audio may be before the recogniser is asked to transcribe it.
 *
 * A setting rather than a constant because it is the one knob on the speech path whose best value
 * is genuinely unknown, and because the model it applies to was carried specifically for its
 * ability to move it. SenseVoice and Whisper have a wall at 29.5 s -- sherpa-onnx truncates past it
 * and reports nothing -- so for them there is no experiment to run and this is ignored. Parakeet
 * has no wall, and where its slices should be cut is a trade nobody here has measured:
 *
 *  - **Longer should be more accurate.** Every cut is a place the model loses the words either side
 *    of it, and the codebase has the receipts -- `Wareneingang` became `wahren Eingang`, `Kammer
 *    vier` became `Kamera`. Fewer cuts is fewer chances to do that.
 *  - **Longer is not obviously faster.** Parakeet attends over the whole slice at once, so its
 *    attention cost grows with the *square* of the length while everything else grows linearly.
 *    Somewhere above these values the quadratic term overtakes the saving from fewer encoder passes,
 *    and only a device can say where.
 *  - **Longer costs more when a process dies.** A slice is the transcription checkpoint's unit of
 *    resume, so a killed job loses up to one slice of work. At 240 s that is four minutes.
 *  - **Longer delays pre-decoding.** Slices are decoded during the recording as they settle, and a
 *    slice does not settle until it is complete -- so a long window pushes work back past the stop
 *    button, which is the wait the pipeline exists to remove.
 *
 * Change one thing and run it again, the way [PlatformFeedPace] says to. The numbers that matter are
 * the WER on the benchmark screen and the `decoded ...s of audio in ...s` line in the log; a
 * transcript that reads well is not evidence.
 *
 * The memory column below is the attention tensor Parakeet allocates for one slice: the encoder
 * emits a frame per 80 ms and each of its 24 layers builds an 8-head matrix over them, so 8 x T x T
 * x 4 bytes are live at once. It sits on top of ~1.2 GB of resident model, on a device that may also
 * be holding a language model for the summary.
 */
enum class SliceWindow(
    /** Stored in Settings; never rename a slug, it is what an installed device wrote. */
    val slug: String,
    val label: String,
    val hint: String,
    /** The hard cap for one slice. The preferred cut sits at three quarters of it. */
    val seconds: Int,
) {
    S30(
        slug = "30s",
        label = "30 s",
        hint = "Roughly what Whisper is forced to. Here as the control: run it to see what " +
            "Parakeet scores under the same slicing the other models get, before changing anything.",
        seconds = 30,
    ),

    S60(
        slug = "60s",
        label = "60 s",
        hint = "Twice the wall the other models live behind, for about 18 MB of attention. The " +
            "conservative step up.",
        seconds = 60,
    ),

    S120(
        slug = "120s",
        label = "120 s",
        hint = "The default: 72 MB of attention, a quarter of the encoder passes a 30 s window " +
            "costs, and still under half the length the exported model can physically attend to.",
        seconds = 120,
    ),

    S180(
        slug = "180s",
        label = "180 s",
        hint = "162 MB of attention. Past anything measured -- check a run completes and scores " +
            "before trusting it on a real recording.",
        seconds = 180,
    ),

    S240(
        slug = "240s",
        label = "240 s",
        hint = "288 MB of attention for one slice, and the largest offered. Not the model's " +
            "ceiling -- that is 400 s, fixed by the exported positional embedding -- but the point " +
            "past which an allocation failure inside the native session is a realistic way for a " +
            "run to end.",
        seconds = 240,
    ),

    ;

    companion object {

        /**
         * 120 s, and it is a judgement rather than a measurement: it is the largest window whose
         * attention cost stays under 100 MB, which is what the phones this targets can spare
         * without competing with a resident language model.
         */
        val DEFAULT = S120

        /** Resolves a stored slug, falling back to [DEFAULT] -- same reasoning as [OnnxProvider]. */
        fun fromSlug(slug: String?): SliceWindow = entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}
