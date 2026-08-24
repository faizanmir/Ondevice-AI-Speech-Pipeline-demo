package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerEdge
import com.example.aiagenttestapp.functions.MarkerKind

/**
 * A marker the user spoke, located in the recording's own sample timeline.
 *
 * [startSample] and [endSample] bracket the *phrase itself* ("start non conformity"), not the content
 * it introduces. Keeping the phrase's extent -- rather than just a point -- is what lets the
 * transcriber cut it out of the audio entirely, so the trigger words never reach the note and no
 * string-stripping pass is needed afterwards.
 */
data class SpokenMarker(
    val kind: MarkerKind,
    val edge: MarkerEdge,
    val startSample: Int,
    val endSample: Int,
)

/**
 * A resolved tag: the stretch of recording between a start marker and its end marker.
 *
 * [range] covers only the content. The marker phrases sit immediately outside it on either side.
 */
data class MarkerSpan(
    val kind: MarkerKind,
    val range: IntRange,
)

/**
 * One slice of the recording, ready to be handed to the recogniser (or dropped).
 *
 * Slices are the single currency between everything that decides *where* boundaries go -- spoken
 * markers and the recogniser's ~30 s attention limit -- and the code that actually transcribes. Each
 * carries what is true of it, so nothing downstream has to re-derive it from text.
 */
data class TranscriptSlice(
    val range: IntRange,
    /** Tags whose span covers this slice. A slice can be inside both a non-conformity and an action. */
    val tags: Set<MarkerKind> = emptySet(),
    /**
     * True when this slice is a phrase the user spoke *at* the app rather than into the note -- a
     * marker like "start non conformity", or a command like "stop recording". Never transcribed, which
     * is how trigger words stay out of the note without any string-stripping pass afterwards.
     */
    val isTriggerPhrase: Boolean = false,
    /**
     * True when voice-activity detection found no speech anywhere in this slice.
     *
     * Kept apart from [isTriggerPhrase] rather than folded into it, because the two are different
     * kinds of claim. A trigger phrase is *known* not to be content -- the user said "stop
     * recording" and we know exactly where. This is a guess by a model that can be wrong, and one
     * whose mistakes delete speech invisibly. Keeping them distinct means the confidence is visible
     * at every point that reads a slice.
     */
    val isSilence: Boolean = false,
) {
    /** Whether this slice is worth handing to a recogniser. The only test callers should apply. */
    val isSpoken: Boolean get() = !isTriggerPhrase && !isSilence
}

/**
 * Turns spoken markers into tagged spans, and the recording into slices that respect them.
 *
 * Pure, and deliberately so: every rule here is a decision about ambiguous human behaviour -- someone
 * who forgets to close a tag, or closes one they never opened -- and those are exactly the cases that
 * are miserable to reproduce on a device and trivial to pin down in a unit test.
 */
object SpokenMarkers {

    /**
     * Pairs markers into spans.
     *
     * The rules, all of which come from people not speaking in well-formed brackets:
     *
     * | Situation | Behaviour |
     * |---|---|
     * | `start X` … `end X` | span covers the audio between them |
     * | `start X` while X is already open | close the previous span here, open a new one |
     * | `end X` with nothing open | ignored |
     * | `start X` never closed | runs to the end of the recording |
     *
     * Non-conformities and actions are tracked independently, so they may overlap or nest freely and a
     * stretch of audio can belong to both. Forcing them to be mutually exclusive would mean silently
     * dropping one of two things the user explicitly asked for.
     */
    fun pair(markers: List<SpokenMarker>, totalSamples: Int): List<MarkerSpan> {
        val spans = mutableListOf<MarkerSpan>()
        val open = mutableMapOf<MarkerKind, SpokenMarker>()

        for (marker in markers.sortedBy { it.startSample }) {
            when (marker.edge) {
                MarkerEdge.Start -> {
                    // A second "start" without an "end" means the close was missed; end the previous
                    // span where this one begins rather than throwing the first away.
                    open.remove(marker.kind)?.let { previous ->
                        spans.addSpan(marker.kind, previous.endSample, marker.startSample)
                    }
                    open[marker.kind] = marker
                }

                MarkerEdge.End -> {
                    val opened = open.remove(marker.kind) ?: continue
                    spans.addSpan(marker.kind, opened.endSample, marker.startSample)
                }
            }
        }

        // Anything still open runs to the end of the recording. The user said "start non conformity"
        // and meant it; ending the note is as good a close as any.
        for ((kind, opened) in open) {
            spans.addSpan(kind, opened.endSample, totalSamples)
        }

        return spans.sortedBy { it.range.first }
    }

    /** Adds a span unless it is empty -- "start X end X" with nothing said between tags nothing. */
    private fun MutableList<MarkerSpan>.addSpan(kind: MarkerKind, from: Int, until: Int) {
        if (until > from) add(MarkerSpan(kind, from until until))
    }

    /**
     * Cuts [totalSamples] of recording into slices that honour every boundary that matters.
     *
     * Boundaries come from two places and both have to hold at once:
     *  - **Marker phrases** get their own slices, so they can be dropped rather than transcribed.
     *  - **The recogniser's attention limit**, because Whisper ignores everything past ~30 s.
     *
     * [speechRegions] does not add boundaries -- it marks slices that fall in the gaps as
     * [TranscriptSlice.isSilence] so they are never decoded. Deliberately not a third source of
     * cuts: a VAD boundary is a guess, and letting a guess split a slice would put a cut inside a
     * marker span that the other two rules had already placed correctly.
     *
     * [cutLongSlice] is injected rather than imported so this stays testable: the real implementation
     * looks for the quietest frame near the target length, and a test can pass a deterministic stub.
     */
    fun slice(
        totalSamples: Int,
        markers: List<SpokenMarker> = emptyList(),
        /**
         * Extra stretches of audio to leave untranscribed -- spoken *commands* ("stop recording"),
         * which are aimed at the app rather than at the note and should no more appear in it than a
         * marker phrase should.
         */
        excludedRanges: List<IntRange> = emptyList(),
        /**
         * Where speech was actually detected, or null when it was not looked for -- which means
         * "assume speech throughout", the behaviour before there was a VAD. Null rather than an
         * empty list on purpose: an empty list is indistinguishable from "the VAD found nothing",
         * and treating that as "skip the entire recording" would silently save an empty note.
         */
        speechRegions: List<IntRange>? = null,
        maxSliceSamples: Int,
        cutLongSlice: (from: Int, until: Int) -> Int,
    ): List<TranscriptSlice> {
        if (totalSamples <= 0) return emptyList()

        val spans = pair(markers, totalSamples)

        val triggers = markers.map { it.startSample until it.endSample } + excludedRanges

        // Every point where something changes. A sorted, de-duplicated set of edges is far easier to
        // reason about -- and to test -- than trying to walk overlapping structures at once.
        val edges = sortedSetOf(0, totalSamples)
        triggers.forEach { trigger ->
            edges += trigger.first.coerceIn(0, totalSamples)
            edges += (trigger.last + 1).coerceIn(0, totalSamples)
        }

        val slices = mutableListOf<TranscriptSlice>()
        val points = edges.toList()

        // Any overlap at all counts as speech. A slice half in a speech region is transcribed whole,
        // because the alternative is cutting off the word that straddles the boundary -- and the
        // cost of being wrong this way is one wasted decode.
        fun hasSpeech(range: IntRange): Boolean =
            speechRegions == null || speechRegions.any { it.first <= range.last && it.last >= range.first }

        for (i in 0 until points.size - 1) {
            val from = points[i]
            val until = points[i + 1]
            if (until <= from) continue

            val isTrigger = triggers.any { it.first <= from && it.last >= until - 1 }

            val slice = TranscriptSlice(
                range = from until until,
                tags = if (isTrigger) {
                    emptySet()
                } else {
                    spans.filter { it.range.first <= from && it.range.last >= until - 1 }
                        .map { it.kind }
                        .toSet()
                },
                isTriggerPhrase = isTrigger,
                isSilence = !isTrigger && !hasSpeech(from until until),
            )

            // Only slices that will actually be decoded need splitting. A trigger phrase is a second
            // or two, and a stretch with no speech anywhere in it is dropped whole -- splitting
            // either would just make more slices for the filter downstream to throw away. Content
            // might run for minutes if nobody paused.
            if (!slice.isSpoken || slice.range.count() <= maxSliceSamples) {
                slices += slice
            } else {
                // Re-asked per piece rather than inherited. A slice is only silent when the *whole*
                // of it is, so a minute-long stretch where someone spoke for the first two seconds
                // arrives here marked as speech -- and every piece after the first would inherit
                // that and be decoded for nothing.
                slices += splitLong(slice, maxSliceSamples, cutLongSlice)
                    .map { piece -> piece.copy(isSilence = !hasSpeech(piece.range)) }
            }
        }

        return slices
    }

    /** Repeatedly cuts a too-long slice, preserving its tags on every piece. */
    private fun splitLong(
        slice: TranscriptSlice,
        maxSliceSamples: Int,
        cutLongSlice: (from: Int, until: Int) -> Int,
    ): List<TranscriptSlice> {
        val pieces = mutableListOf<TranscriptSlice>()
        var start = slice.range.first
        val end = slice.range.last + 1

        while (end - start > maxSliceSamples) {
            // The search window stops at the cap, never at the end of the slice. Handing the whole
            // remaining stretch to the quiet-point search lets it answer with the quietest moment
            // *anywhere* in it -- on a ten-minute recording that is routinely a pause two minutes
            // away, and the piece it produces is then far past the cap the caller asked for. Every
            // caller of this pays for that in a different currency: sherpa truncates, and LiteRT-LM
            // either rejects the clip or trips a native assertion that takes the process with it.
            //
            // Added as a Long: a backend with no length limit reports one near [Int.MAX_VALUE]
            // (a streaming transducer consumes audio frame by frame and genuinely has no cap), and
            // `start + maxSliceSamples` in Int would wrap to a negative window. The loop above never
            // enters in that case, but a window computed by overflow is not something to leave
            // sitting one edit away from being reachable.
            val capped = (start.toLong() + maxSliceSamples).coerceAtMost(end.toLong()).toInt()
            val window = maxOf(capped, start + 1)
            val cut = cutLongSlice(start, window).coerceIn(start + 1, window)
            // A stub or a pathological signal could return no forward progress; forcing the cap
            // guarantees termination rather than looping forever on a silent recording.
            val safeCut = if (cut <= start) window else cut
            pieces += slice.copy(range = start until minOf(safeCut, end))
            start = minOf(safeCut, end)
        }

        if (end > start) pieces += slice.copy(range = start until end)
        return pieces
    }
}
