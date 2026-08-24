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

    /**
     * Cuts exactly at the cap, so slice boundaries in tests are predictable.
     *
     * Honours `until` rather than discarding it. An earlier version returned `from + cap`
     * unconditionally, which quietly hard-coded the very behaviour the production cut function was
     * failing to provide: the real one is free to answer anywhere up to `until`, so a stub that
     * ignores `until` cannot fail no matter how far past the cap the caller's window reaches. The
     * cap assertions below were passing against a recording whose slices ran to 150 s on a device.
     */
    private val cutAtCap: (Int, Int) -> Int = { from, until -> minOf(from + rate * 28, until) }

    /**
     * The adversarial stub: always answers with the far end of whatever window it is given.
     *
     * This is what the quiet-point search really does when the whole recording is one long pause,
     * and it is the shape that caught the missing bound in [SpokenMarkers.slice]. Any slicing that
     * relies on the cut function to be reasonable will exceed the cap under this.
     */
    private val cutAtFarEnd: (Int, Int) -> Int = { _, until -> until }

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
    fun `a long slice is split and every piece keeps its tag`() {
        val start = ncStart(1.0) // unclosed, so its tag runs to the end of the recording
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 90,
            markers = listOf(start),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertTrue("no slice may exceed the cap", slices.all { it.range.count() <= rate * 28 })

        // The ~88 s of content after the marker must have been split into several pieces, and every
        // one of them has to keep the tag -- losing it on a split is how a long non-conformity would
        // end up half-tagged.
        val content = slices.filter { it.range.first >= start.endSample }
        assertTrue("content should have been split", content.size > 1)
        assertTrue(content.all { MarkerKind.NonConformity in it.tags })
        assertTrue(content.none { it.isTriggerPhrase })

        // The one second spoken before the marker is legitimately untagged.
        assertEquals(emptySet<MarkerKind>(), slices.first().tags)
    }

    @Test
    fun `a cut function that answers at the far end still cannot exceed the cap`() {
        // Regression: the search window handed to the cut function used to be the whole remaining
        // slice rather than the cap, so a cut function answering at the far end of its window --
        // exactly what the quiet-point search does across a long pause -- produced one enormous
        // piece and the loop terminated. On a device this yielded 150 s slices against a 24 s cap
        // and the transcription failed with "Input token ids are too long: 5018 >= 4096".
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 600,
            maxSliceSamples = rate * 24,
            cutLongSlice = cutAtFarEnd,
        )

        assertTrue("no slice may exceed the cap", slices.all { it.range.count() <= rate * 24 })
        // Ten minutes against a 24 s cap cannot come back as a handful of pieces.
        assertTrue("600 s must split into many pieces", slices.size >= 600 / 24)
        // And the recording still has to be covered exactly once, end to end.
        assertEquals(0, slices.first().range.first)
        assertEquals(rate * 600 - 1, slices.last().range.last)
        slices.zipWithNext { a, b ->
            assertEquals("slices must be contiguous", a.range.last + 1, b.range.first)
        }
    }

    /**
     * A streaming transducer has no length limit, so it reports one near [Int.MAX_VALUE]. Slices must
     * then fall only where a marker or a speaker turn puts one — never to fit the model — and the
     * arithmetic that computes the cut window must not overflow on the way past.
     */
    @Test
    fun `a backend with no length limit is never split for length`() {
        val start = ncStart(5.0)
        val end = ncEnd(12.0)
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 3600, // an hour
            markers = listOf(start, end),
            maxSliceSamples = Int.MAX_VALUE,
            cutLongSlice = { _, _ -> error("a limitless backend must never ask for a cut") },
        )

        // Exactly the boundaries the markers imply: before, the two phrases, the content between
        // them, and after. An hour of audio adds none of its own.
        assertEquals(5, slices.size)
        assertEquals(0, slices.first().range.first)
        assertEquals(rate * 3600 - 1, slices.last().range.last)
        slices.zipWithNext { a, b ->
            assertEquals("slices must be contiguous", a.range.last + 1, b.range.first)
        }
        assertTrue(
            "the tagged content between the markers is one unbroken slice",
            slices.count { MarkerKind.NonConformity in it.tags && !it.isTriggerPhrase } == 1,
        )
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

    // -------- voice activity --------

    @Test
    fun `no speech regions means everything is transcribed`() {
        // Null is "the VAD did not run", which has to behave exactly as it did before there was one.
        val slices = SpokenMarkers.slice(
            totalSamples = total,
            speechRegions = null,
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertTrue(slices.all { it.isSpoken })
    }

    @Test
    fun `slices outside every speech region are marked silent`() {
        val slices = SpokenMarkers.slice(
            totalSamples = total,
            markers = listOf(ncStart(40.0), ncEnd(50.0)),
            speechRegions = listOf(0 until rate * 10),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertTrue("something must survive", slices.any { it.isSpoken })
        assertTrue(
            "a slice well past the speech must be silent",
            slices.filter { it.range.first >= rate * 20 }.all { !it.isSpoken },
        )
    }

    @Test
    fun `a slice overlapping a speech region is kept whole`() {
        // Half in, half out. Transcribed, because the alternative is cutting off the word that
        // straddles the boundary -- and being wrong this way costs one wasted decode.
        //
        // The excluded range is only here to force a boundary at 10 s so there are two slices to
        // judge; the speech region deliberately straddles it.
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 20,
            excludedRanges = listOf(rate * 10 until rate * 10 + 1),
            speechRegions = listOf(rate * 9 until rate * 11),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertTrue(
            "both content slices touch the speech region",
            slices.filter { !it.isTriggerPhrase }.all { it.isSpoken },
        )
    }

    @Test
    fun `a trigger phrase in a speech region stays a trigger phrase`() {
        // Marker phrases are speech -- the VAD hears them. They must still be dropped as commands
        // rather than promoted to content by having been detected.
        val start = ncStart(5.0)
        val slices = SpokenMarkers.slice(
            totalSamples = total,
            markers = listOf(start, ncEnd(12.0)),
            speechRegions = listOf(0 until total),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        val phrase = slices.first { it.range.first == start.startSample }
        assertTrue(phrase.isTriggerPhrase)
        assertFalse(phrase.isSpoken)
    }

    @Test
    fun `a long stretch with no speech at all is not split into pieces`() {
        // The marker at 6 s ends the first segment, so everything after it is one long stretch the
        // VAD heard nothing in. Nothing downstream will decode it, so splitting it into four
        // twenty-eight-second pieces only makes work for the filter.
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 120,
            markers = listOf(marker(MarkerKind.Action, MarkerEdge.Start, 6.0)),
            speechRegions = listOf(0 until rate * 5),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        val tail = slices.last()
        assertEquals(rate * 120 - 1, tail.range.last)
        assertTrue("the silent tail should be one piece", tail.range.count() > rate * 28)
        assertFalse(tail.isSpoken)
    }

    @Test
    fun `a long stretch is split so only the part with speech is transcribed`() {
        // The opposite case, and the one that makes the split necessary: someone speaks for the
        // first few seconds and then says nothing for two minutes. The slice as a whole contains
        // speech, so it cannot be dropped -- but only its first piece is worth decoding.
        val slices = SpokenMarkers.slice(
            totalSamples = rate * 120,
            speechRegions = listOf(0 until rate * 5),
            maxSliceSamples = rate * 28,
            cutLongSlice = cutAtCap,
        )

        assertEquals(1, slices.count { it.isSpoken })
        assertEquals(0, slices.first { it.isSpoken }.range.first)
    }
}
