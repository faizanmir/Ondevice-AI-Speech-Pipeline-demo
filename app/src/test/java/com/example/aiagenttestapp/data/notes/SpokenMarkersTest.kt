package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerEdge
import com.example.aiagenttestapp.functions.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule in [SpokenMarkers] is a decision about someone not speaking in well-formed brackets.
 * Those are the cases that are miserable to reproduce on a phone and trivial to pin down here.
 */
class SpokenMarkersTest {

    private val rate = 16_000
    private val total = rate * 60 // a one-minute recording

    private fun marker(
        kind: MarkerKind,
        edge: MarkerEdge,
        atSeconds: Double,
        lengthSeconds: Double = 1.5,
    ) = SpokenMarker(
        kind = kind,
        edge = edge,
        startSample = (atSeconds * rate).toInt(),
        endSample = ((atSeconds + lengthSeconds) * rate).toInt(),
    )

    private fun ncStart(at: Double) = marker(MarkerKind.NonConformity, MarkerEdge.Start, at)
    private fun ncEnd(at: Double) = marker(MarkerKind.NonConformity, MarkerEdge.End, at)
    private fun actionStart(at: Double) = marker(MarkerKind.Action, MarkerEdge.Start, at)
    private fun actionEnd(at: Double) = marker(MarkerKind.Action, MarkerEdge.End, at)

    // -------- pairing --------

    @Test
    fun `a start and an end become one span covering only the content between them`() {
        val spans = SpokenMarkers.pair(listOf(ncStart(5.0), ncEnd(12.0)), total)

        assertEquals(1, spans.size)
        // Content starts where the start phrase *ended* and stops where the end phrase *began*, so
        // neither trigger phrase is inside the span.
        assertEquals((6.5 * rate).toInt(), spans[0].range.first)
        assertEquals((12.0 * rate).toInt() - 1, spans[0].range.last)
        assertEquals(MarkerKind.NonConformity, spans[0].kind)
    }

    @Test
    fun `three spans in sequence all survive`() {
        val spans = SpokenMarkers.pair(
            listOf(
                ncStart(5.0), ncEnd(10.0),
                ncStart(15.0), ncEnd(20.0),
                ncStart(25.0), ncEnd(30.0),
            ),
            total,
        )

        // The old wall-clock cooldown would have swallowed repeats of the same phrase; opening three
        // non-conformities in one walkthrough is the normal case, not the exotic one.
        assertEquals(3, spans.size)
    }

    @Test
    fun `a second start without an end closes the first span rather than losing it`() {
        val spans = SpokenMarkers.pair(listOf(ncStart(5.0), ncStart(20.0), ncEnd(30.0)), total)

        assertEquals(2, spans.size)
        assertEquals((6.5 * rate).toInt(), spans[0].range.first)
        assertEquals((20.0 * rate).toInt() - 1, spans[0].range.last)
        assertEquals((21.5 * rate).toInt(), spans[1].range.first)
    }

    @Test
    fun `an end with nothing open is ignored`() {
        assertEquals(emptyList<MarkerSpan>(), SpokenMarkers.pair(listOf(ncEnd(5.0)), total))
    }

    @Test
    fun `an unclosed start runs to the end of the recording`() {
        val spans = SpokenMarkers.pair(listOf(ncStart(50.0)), total)

        assertEquals(1, spans.size)
        assertEquals(total - 1, spans[0].range.last)
    }

    @Test
    fun `non-conformities and actions overlap independently`() {
        val spans = SpokenMarkers.pair(
            listOf(ncStart(5.0), actionStart(8.0), ncEnd(12.0), actionEnd(16.0)),
            total,
        )

        assertEquals(2, spans.size)
        val nc = spans.first { it.kind == MarkerKind.NonConformity }
        val action = spans.first { it.kind == MarkerKind.Action }
        // Genuinely overlapping: audio between 9.5s and 12s belongs to both.
        assertTrue(nc.range.last > action.range.first)
    }

    @Test
    fun `an empty span is dropped`() {
        // "start non conformity, end non conformity" with nothing said between tags nothing.
        val spans = SpokenMarkers.pair(
            listOf(ncStart(5.0), ncEnd(6.5)),
            total,
        )
        assertEquals(emptyList<MarkerSpan>(), spans)
    }

    @Test
    fun `markers given out of order are sorted before pairing`() {
        val spans = SpokenMarkers.pair(listOf(ncEnd(12.0), ncStart(5.0)), total)

        assertEquals(1, spans.size)
        assertEquals((6.5 * rate).toInt(), spans[0].range.first)
    }

    // -------- slicing --------

    /** Cuts exactly at the cap, so slice boundaries in tests are predictable. */
    private val cutAtCap: (Int, Int) -> Int = { from, _ -> from + rate * 28 }

    @Test
    fun `slices cover the whole recording without gaps or overlaps`() {
        val slices = SpokenMarkers.slice(
            totalSamples = total,
            markers = listOf(ncStart(5.0), ncEnd(12.0)),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertEquals(0, slices.first().range.first)
        assertEquals(total - 1, slices.last().range.last)
        slices.zipWithNext { a, b ->
            assertEquals("slices must be contiguous", a.range.last + 1, b.range.first)
        }
    }

    @Test
    fun `marker phrases get their own slices and are flagged for dropping`() {
        val start = ncStart(5.0)
        val slices = SpokenMarkers.slice(
            totalSamples = total,
            markers = listOf(start, ncEnd(12.0)),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        val phrase = slices.first { it.range.first == start.startSample }
        assertTrue(phrase.isTriggerPhrase)
        assertEquals(start.endSample - 1, phrase.range.last)
        // A marker phrase carries no tag -- it is removed, not tagged.
        assertEquals(emptySet<MarkerKind>(), phrase.tags)

        // Exactly two phrase slices for two markers.
        assertEquals(2, slices.count { it.isTriggerPhrase })
    }

    @Test
    fun `content between markers carries the tag`() {
        val slices = SpokenMarkers.slice(
            totalSamples = total,
            markers = listOf(ncStart(5.0), ncEnd(12.0)),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        val tagged = slices.filter { MarkerKind.NonConformity in it.tags }
        assertTrue(tagged.isNotEmpty())
        assertTrue(tagged.none { it.isTriggerPhrase })
        // The tagged region is exactly the content between the two phrases.
        assertEquals((6.5 * rate).toInt(), tagged.first().range.first)
        assertEquals((12.0 * rate).toInt() - 1, tagged.last().range.last)
    }

    @Test
    fun `speaker turns become slice boundaries and carry their cluster`() {
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 30,
            turns = listOf(
                SpeakerTurn(0 until rate * 10, cluster = 0),
                SpeakerTurn(rate * 10 until rate * 30, cluster = 1),
            ),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertEquals(listOf(0, 1), slices.map { it.cluster })
        assertEquals(rate * 10 - 1, slices[0].range.last)
    }

    @Test
    fun `a long slice is split and every piece keeps its tag and speaker`() {
        val start = ncStart(1.0) // unclosed, so its tag runs to the end of the recording
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 90,
            markers = listOf(start),
            turns = listOf(SpeakerTurn(0 until rate * 90, cluster = 3)),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertTrue("no slice may exceed the cap", slices.all { it.range.count() <= rate * 28 })

        // The ~88 s of content after the marker must have been split into several pieces, and every
        // one of them has to keep both the tag and the speaker -- losing either on a split is how a
        // long non-conformity would end up half-tagged or attributed to nobody.
        val content = slices.filter { it.range.first >= start.endSample }
        assertTrue("content should have been split", content.size > 1)
        assertTrue(content.all { MarkerKind.NonConformity in it.tags })
        assertTrue(content.all { it.cluster == 3 })
        assertTrue(content.none { it.isTriggerPhrase })

        // The one second spoken before the marker is legitimately untagged.
        val beforeMarker = slices.first()
        assertEquals(emptySet<MarkerKind>(), beforeMarker.tags)
        assertEquals(3, beforeMarker.cluster)
    }

    @Test
    fun `a cut function that makes no progress cannot loop forever`() {
        // A silent recording can make a quietest-cut search return the same point every time.
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 120,
            maxSliceSamples = rate * 28,
            cutLongSlice = { from, _ -> from },
        )

        assertTrue(slices.isNotEmpty())
        assertTrue(slices.all { it.range.count() <= rate * 28 })
        assertEquals(rate * 120 - 1, slices.last().range.last)
    }

    @Test
    fun `an empty recording slices to nothing`() {
        assertEquals(
            emptyList<TranscriptSlice>(),
            SpokenMarkers.slice(
                totalSamples = 0,
                maxSliceSamples = rate * 28,
                cutLongSlice = cutAtCap,
            ),
        )
    }

    @Test
    fun `markers beyond the recording length are clamped rather than crashing`() {
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 10,
            markers = listOf(marker(MarkerKind.Action, MarkerEdge.Start, 9.5, lengthSeconds = 5.0)),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertEquals(rate * 10 - 1, slices.last().range.last)
        assertFalse(slices.any { it.range.last >= rate * 10 })
    }
}
