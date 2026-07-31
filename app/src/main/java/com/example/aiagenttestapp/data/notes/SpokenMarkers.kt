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

/** One speaker's uninterrupted stretch of a recording, as diarisation reported it. */
data class SpeakerTurn(
    val range: IntRange,
    /** Diarisation's cluster index. Resolved to a name or "Speaker 2" later. */
    val cluster: Int,
)

/**
 * One slice of the recording, ready to be handed to the recogniser (or dropped).
 *
 * Slices are the single currency between everything that decides *where* boundaries go -- spoken
 * markers, speaker turns, the recogniser's ~30 s attention limit -- and the code that actually
 * transcribes. Each carries what is true of it, so nothing downstream has to re-derive it from text.
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
    /** Diarisation cluster this slice belongs to, when speaker identification ran. */
    val cluster: Int? = null,
)

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
     * Boundaries come from three places and all three have to hold at once:
     *  - **Marker phrases** get their own slices, so they can be dropped rather than transcribed.
     *  - **Speaker turns**, when diarisation ran, so each slice belongs to exactly one person and its
     *    text can be attributed without needing word timestamps the recognisers do not provide.
     *  - **The recogniser's attention limit**, because Whisper ignores everything past ~30 s.
     *
     * [cutLongSlice] is injected rather than imported so this stays testable: the real implementation
     * looks for the quietest frame near the target length, and a test can pass a deterministic stub.
     */
    fun slice(
        totalSamples: Int,
        markers: List<SpokenMarker> = emptyList(),
        turns: List<SpeakerTurn> = emptyList(),
        /**
         * Extra stretches of audio to leave untranscribed -- spoken *commands* ("stop recording"),
         * which are aimed at the app rather than at the note and should no more appear in it than a
         * marker phrase should.
         */
        excludedRanges: List<IntRange> = emptyList(),
        maxSliceSamples: Int,
        cutLongSlice: (from: Int, until: Int) -> Int,
    ): List<TranscriptSlice> {
        if (totalSamples <= 0) return emptyList()

        val spans = pair(markers, totalSamples)

        val triggers = markers.map { it.startSample until it.endSample } + excludedRanges

        // Every point where something changes. A sorted, de-duplicated set of edges is far easier to
        // reason about -- and to test -- than trying to walk three overlapping structures at once.
        val edges = sortedSetOf(0, totalSamples)
        triggers.forEach { trigger ->
            edges += trigger.first.coerceIn(0, totalSamples)
            edges += (trigger.last + 1).coerceIn(0, totalSamples)
        }
        turns.forEach { turn ->
            edges += turn.range.first.coerceIn(0, totalSamples)
            edges += (turn.range.last + 1).coerceIn(0, totalSamples)
        }

        val slices = mutableListOf<TranscriptSlice>()
        val points = edges.toList()

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
                cluster = turns.firstOrNull { it.range.first <= from && it.range.last >= until - 1 }
                    ?.cluster,
            )

            // A trigger phrase is a second or two and never needs splitting; content might run for
            // minutes if nobody paused and no speaker changed.
            if (slice.isTriggerPhrase || slice.range.count() <= maxSliceSamples) {
                slices += slice
            } else {
                slices += splitLong(slice, maxSliceSamples, cutLongSlice)
            }
        }

        return slices
    }

    /** Repeatedly cuts a too-long slice, preserving its tags and speaker on every piece. */
    private fun splitLong(
        slice: TranscriptSlice,
        maxSliceSamples: Int,
        cutLongSlice: (from: Int, until: Int) -> Int,
    ): List<TranscriptSlice> {
        val pieces = mutableListOf<TranscriptSlice>()
        var start = slice.range.first
        val end = slice.range.last + 1

        while (end - start > maxSliceSamples) {
            val cut = cutLongSlice(start, end).coerceIn(start + 1, end)
            // A stub or a pathological signal could return no forward progress; forcing the cap
            // guarantees termination rather than looping forever on a silent recording.
            val safeCut = if (cut <= start) start + maxSliceSamples else cut
            pieces += slice.copy(range = start until minOf(safeCut, end))
            start = minOf(safeCut, end)
        }

        if (end > start) pieces += slice.copy(range = start until end)
        return pieces
    }
}
