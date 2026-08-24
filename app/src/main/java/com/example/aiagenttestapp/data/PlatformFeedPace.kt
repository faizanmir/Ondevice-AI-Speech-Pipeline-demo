package com.example.aiagenttestapp.data

/**
 * How fast recorded audio is handed to the system recogniser, as a multiple of real time.
 *
 * A user-visible setting for the same reason [OnnxProvider] is: the right answer has to be
 * measured, not reasoned about, and the measuring has to happen on a real device. The feed is
 * paced at all because an unpaced write made the service silently discard whole sentences from
 * the middle of a clip -- 57.5% word error rate against 7.8% for Whisper on the same audio, with
 * nothing anywhere reporting a problem. Every *paced* rate tested was complete, so the open
 * question is only how fast pacing can go -- and one variable is still confounded: the failing
 * case was a single write of the whole clip, so chunked writes with no delay at all have never
 * been tested. [NO_DELAY] exists to answer exactly that.
 *
 * A second party scoring the same audio with the same engine reports **7.9%** where this app's
 * 16x runs give 26%, and their protocol feeds at [X1]. Their claim and ours agree in direction --
 * too fast silently drops audio -- but we only ever compared 8x against 16x, which agreed with each
 * other and so told us nothing about whether both were already past the cliff. [X1] is what tests
 * that.
 *
 * The failure mode this experiments against is **silent**: an over-fast feed does not error, it
 * returns a shorter transcript. Judge a rate by the per-slice `segments=` log line and a WER run
 * against a reference recording, never by whether it "worked". [X16] stays the default because it
 * is the fastest rate verified complete on device; anything faster is an experiment the user has
 * opted into.
 */
enum class PlatformFeedPace(
    /** Stored in Settings; never rename a slug, it is what an installed device wrote. */
    val slug: String,
    val label: String,
    val hint: String,
    /** Multiple of real time. [Double.POSITIVE_INFINITY] = chunked writes with no delay. */
    val multiplier: Double,
) {
    X1(
        slug = "1x",
        label = "1× (realtime)",
        hint = "Realtime, as the shared scoring protocol requires. Slowest by far — a 21-minute " +
            "clip takes 21 minutes — and the only rate a second party's baseline was measured at. " +
            "Use it for any number meant to be compared with theirs.",
        multiplier = 1.0,
    ),

    X8(
        slug = "8x",
        label = "8×",
        hint = "The rate verified complete over a full-length recording. The safe fallback if a " +
            "faster one ever comes back short.",
        multiplier = 8.0,
    ),

    X16(
        slug = "16x",
        label = "16×",
        hint = "The default: the fastest rate verified complete on device.",
        multiplier = 16.0,
    ),

    X32(
        slug = "32x",
        label = "32×",
        hint = "Untested beyond 16×. Compare the transcript against a known recording — a feed " +
            "that is too fast drops sentences without reporting anything.",
        multiplier = 32.0,
    ),

    NO_DELAY(
        slug = "nodelay",
        label = "No delay",
        hint = "Chunked writes with no pacing at all — the fastest possible hand-off, and the " +
            "one variant never yet measured. Verify nothing is missing before trusting it.",
        multiplier = Double.POSITIVE_INFINITY,
    ),

    ;

    companion object {

        val DEFAULT = X16

        /** Resolves a stored slug, falling back to [DEFAULT] -- same reasoning as [OnnxProvider]. */
        fun fromSlug(slug: String?): PlatformFeedPace =
            entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}
