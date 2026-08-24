package com.example.aiagenttestapp.data

/**
 * How much audio goes into each write when handing a recording to the system recogniser.
 *
 * The second half of the feed experiment, and the reason it is a setting rather than a constant is
 * that [PlatformFeedPace] on its own cannot settle what actually fixed the silent-drop failure.
 *
 * The evidence, all measured on device against a known recording: a single `write()` of the whole
 * clip made the service discard whole sentences from the *middle* -- 57.5% word error rate against
 * 7.8% for Whisper on the same audio, no error reported anywhere. Chunked-and-paced writes were
 * complete at every rate tried. But those two cases differ in *two* ways at once, so "pacing fixed
 * it" was never established -- the write size may have been doing the work all along. Pace and chunk
 * are separate settings so that they can be varied one at a time:
 *
 * | chunk      | paced                       | no delay                    |
 * |------------|-----------------------------|-----------------------------|
 * | 200 ms     | verified complete           | the untested cell           |
 * | whole clip | the original 57.5% failure  | *identical to the left*     |
 *
 * Three configurations, not four: with one chunk there is no second write to delay before, so
 * [WHOLE_CLIP] ignores [PlatformFeedPace] entirely. The confound is therefore settled by comparing
 * 200 ms + no delay against whole clip -- if the first is complete and the second drops sentences,
 * write size was doing the work; if both drop, the delay was.
 *
 * [WHOLE_CLIP] is the one that reproduces that failure on demand, which is the only way to be sure
 * a future change actually fixed it rather than moved it. Note what it does *not* do: a pipe holds
 * 64 KB, so a 20-second slice still reaches the service in kernel-sized instalments. It removes
 * this app from the decision; it does not deliver the clip atomically.
 *
 * Judge any change here the way [PlatformFeedPace] says to -- by the per-slice `segments=` log line
 * and a WER run, never by whether it "worked". The failure is silent by construction.
 */
enum class PlatformFeedChunk(
    /** Stored in Settings; never rename a slug, it is what an installed device wrote. */
    val slug: String,
    val label: String,
    val hint: String,
    /** Milliseconds of audio per write, or [Int.MAX_VALUE] for one write of the whole slice. */
    val millis: Int,
) {
    MS50(
        slug = "50ms",
        label = "50 ms",
        hint = "The smallest writes offered — 1.6 KB each. Closest to how audio arrives live, and " +
            "the most work per slice.",
        millis = 50,
    ),

    MS100(
        slug = "100ms",
        label = "100 ms",
        hint = "Half the default. Untested; useful only if 200 ms ever comes back short.",
        millis = 100,
    ),

    MS200(
        slug = "200ms",
        label = "200 ms",
        hint = "The default: 6.4 KB a write, the size every complete transcript so far was fed at.",
        millis = 200,
    ),

    MS500(
        slug = "500ms",
        label = "500 ms",
        hint = "Larger than anything verified. Check the transcript against a known recording " +
            "before trusting it.",
        millis = 500,
    ),

    WHOLE_CLIP(
        slug = "whole",
        label = "Whole slice",
        hint = "One write per slice — the shape that dropped sentences at 57.5% WER. Here to " +
            "reproduce that failure deliberately, not to transcribe with. The feed rate has no " +
            "effect on this setting: one write, nothing to pace.",
        millis = Int.MAX_VALUE,
    ),

    ;

    companion object {

        val DEFAULT = MS200

        /** Resolves a stored slug, falling back to [DEFAULT] -- same reasoning as [OnnxProvider]. */
        fun fromSlug(slug: String?): PlatformFeedChunk =
            entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}
