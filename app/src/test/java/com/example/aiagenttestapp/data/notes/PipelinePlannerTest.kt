package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerEdge
import com.example.aiagenttestapp.functions.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers which ranges may be transcribed while a recording is still running.
 *
 * The whole value of pipelining rests on one property: a range handed out here must have the same
 * bounds when the final pass slices the finished recording. If it does not, the early decode is a
 * cache miss and the work is thrown away — so the tests that matter are the ones proving the planner
 * *withholds* things, not the ones proving it offers them.
 *
 * The frontier has its own invariants since silence-skipping arrived: it must move past settled
 * audio that produced no ranges (or the caller re-reads it every pass forever), and it must never
 * claim audio the final slice still owns.
 */
class PipelinePlannerTest {

    private val rate = 16_000
    private val cap = rate * 28

    /** Deterministic, and honours its window — the real cut function's contract. */
    private val cutAtCap: (Int, Int) -> Int = { from, until -> minOf(from + cap, until) }

    private fun marker(kind: MarkerKind, edge: MarkerEdge, atSeconds: Double) = SpokenMarker(
        kind = kind,
        edge = edge,
        startSample = (atSeconds * rate).toInt(),
        endSample = ((atSeconds + 1.0) * rate).toInt(),
    )

    private fun plan(
        watermark: Int,
        capturedTotal: Int,
        markers: List<SpokenMarker> = emptyList(),
        speechRegions: List<IntRange>? = null,
    ) = PipelinePlanner.readyRanges(
        windowStart = watermark,
        capturedTotal = capturedTotal,
        markers = markers,
        excludedRanges = emptyList(),
        speechRegions = speechRegions,
        maxSliceSamples = cap,
        cutLongSlice = cutAtCap,
    )

    @Test
    fun `nothing is offered before there is enough settled audio`() {
        assertTrue(plan(watermark = 0, capturedTotal = rate).ranges.isEmpty())
        assertTrue(plan(watermark = 0, capturedTotal = rate * 2).ranges.isEmpty())
    }

    /** With nothing settled there is nothing to advance past either. */
    @Test
    fun `an empty plan leaves the frontier at the watermark`() {
        assertEquals(0, plan(watermark = 0, capturedTotal = rate).frontier)
        val late = rate * 199
        assertEquals(late, plan(watermark = late, capturedTotal = rate * 200).frontier)
    }

    /**
     * The core withholding rule. Two minutes captured is four whole slices plus a partial one, and
     * the partial one's far edge is wherever recording happens to have reached.
     */
    @Test
    fun `the slice still being recorded is never offered`() {
        val captured = rate * 120
        val ranges = plan(watermark = 0, capturedTotal = captured).ranges

        assertTrue("some slices should be ready", ranges.isNotEmpty())
        val last = ranges.last()
        assertTrue(
            "the newest audio must be left alone",
            last.last < captured - PipelinePlanner.TAIL_SAMPLES,
        )
    }

    @Test
    fun `every offered range sits behind the tail margin`() {
        val captured = rate * 200
        plan(watermark = 0, capturedTotal = captured).ranges.forEach { r ->
            assertTrue(
                "range ending at ${r.last} is inside the tail",
                r.last < captured - PipelinePlanner.TAIL_SAMPLES,
            )
        }
    }

    /** The frontier is subject to the same withholding as the ranges: the last slice is not settled. */
    @Test
    fun `the frontier never claims the tail or the slice still being recorded`() {
        val captured = rate * 200
        val plan = plan(watermark = 0, capturedTotal = captured)

        assertTrue(plan.frontier <= captured - PipelinePlanner.TAIL_SAMPLES)
        assertTrue("the frontier must cover every offered range", plan.ranges.all { it.last < plan.frontier })
    }

    @Test
    fun `ranges never overlap and never go behind the watermark`() {
        val ranges = plan(watermark = rate * 60, capturedTotal = rate * 200).ranges

        assertTrue(ranges.all { it.first >= rate * 60 })
        ranges.zipWithNext { a, b ->
            assertTrue("ranges must not overlap", a.last < b.first)
        }
    }

    /**
     * The property everything else depends on: a range offered early must survive the final pass
     * unchanged, or the early decode is wasted. Slicing the finished recording must reproduce the
     * same bounds.
     */
    @Test
    fun `offered ranges reappear identically when the finished recording is sliced`() {
        val markers = listOf(
            marker(MarkerKind.NonConformity, MarkerEdge.Start, 20.0),
            marker(MarkerKind.NonConformity, MarkerEdge.End, 50.0),
        )
        val duringRecording =
            plan(watermark = 0, capturedTotal = rate * 150, markers = markers).ranges
        assertTrue(duringRecording.isNotEmpty())

        // The same recording, finished and longer, sliced the way the worker slices it.
        val finalRanges = SpokenMarkers.slice(
            totalSamples = rate * 300,
            markers = markers,
            maxSliceSamples = cap,
            cutLongSlice = cutAtCap,
        ).filter { it.isSpoken }.map { it.range }.toSet()

        duringRecording.forEach { early ->
            assertTrue(
                "range $early was decoded early but is not a slice of the finished recording",
                early in finalRanges,
            )
        }
    }

    /**
     * The same guarantee with a live VAD watching: regions may only decide *which* settled slices
     * are offered, never where their edges lie. A region that moved an edge would make every
     * pre-decode a checkpoint miss — the exact failure the planner exists to prevent.
     */
    @Test
    fun `speech regions filter the offer but never move a boundary`() {
        val captured = rate * 150
        val without = plan(watermark = 0, capturedTotal = captured).ranges.toSet()
        val with = plan(
            watermark = 0,
            capturedTotal = captured,
            // Speech only in the first minute; the rest of the settled audio is ruled silence.
            speechRegions = listOf(0 until rate * 60),
        ).ranges

        assertTrue("regions should suppress something here", with.size < without.size)
        with.forEach { r ->
            assertTrue("range $r exists only in the region-aware plan", r in without)
        }
    }

    /**
     * A silent slice whose far edge is a *marker* is fully settled — both its edges are boundaries
     * the final pass will reproduce — so the frontier moves past it even though nothing is offered.
     */
    @Test
    fun `silence bounded by a marker advances the frontier without being offered`() {
        val watermark = rate * 60
        val plan = plan(
            watermark = watermark,
            capturedTotal = rate * 200,
            markers = listOf(marker(MarkerKind.NonConformity, MarkerEdge.Start, 100.0)),
            // All detected speech lies outside the settled window: nothing here to decode.
            speechRegions = listOf(0 until rate * 10),
        )

        assertTrue("nothing should be offered from ruled silence", plan.ranges.isEmpty())
        assertTrue("the frontier must pass the silence up to the marker", plan.frontier > rate * 100)
    }

    /**
     * Unbroken silence contains no real boundary, and a made-up one would never match the final
     * pass's slicing — so the frontier must *not* advance into it. (The caller's pass stays cheap
     * anyway: nothing spoken is planned, so nothing is read or decoded.)
     */
    @Test
    fun `unbroken silence does not advance the frontier`() {
        val watermark = rate * 60
        val plan = plan(
            watermark = watermark,
            capturedTotal = rate * 200,
            speechRegions = listOf(0 until rate * 10),
        )

        assertTrue(plan.ranges.isEmpty())
        assertEquals(watermark, plan.frontier)
    }

    /**
     * Regions are clamped to the window, not filtered out of it: one straddling the window edge
     * still marks its overlap as speech. Filtered, the straddled slice would read as silent and a
     * sentence crossing a pass boundary would be skipped.
     */
    @Test
    fun `a region straddling the window edge keeps its slice spoken`() {
        val watermark = rate * 60
        val plan = plan(
            watermark = watermark,
            capturedTotal = rate * 200,
            speechRegions = listOf(rate * 50 until rate * 70),
        )

        assertTrue(plan.ranges.isNotEmpty())
        assertEquals(watermark, plan.ranges.first().first)
    }

    /**
     * A marker that fires after a pipelined pass adds edges only after the audio that pass covered,
     * so the ranges it already handed out stay valid.
     */
    @Test
    fun `a marker heard later cannot invalidate what was already offered`() {
        val early = plan(watermark = 0, capturedTotal = rate * 100).ranges

        val laterMarkers = listOf(marker(MarkerKind.Action, MarkerEdge.Start, 200.0))
        val finalRanges = SpokenMarkers.slice(
            totalSamples = rate * 400,
            markers = laterMarkers,
            maxSliceSamples = cap,
            cutLongSlice = cutAtCap,
        ).filter { it.isSpoken }.map { it.range }.toSet()

        early.forEach { r ->
            assertTrue("range $r stopped being valid once a later marker fired", r in finalRanges)
        }
    }

    /**
     * Regression. The planner slices a *window*, and the cut function it is handed can only index
     * that window — so every position it asks about must be window-relative and non-negative. An
     * earlier version sliced the whole prefix from zero while the caller held only the newest
     * stretch, and the negative index reached into the array and killed the app mid-recording.
     */
    @Test
    fun `every cut position offered is inside the window the caller holds`() {
        val windowStart = rate * 90
        val captured = rate * 200
        val windowLength = captured - PipelinePlanner.TAIL_SAMPLES - windowStart

        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        PipelinePlanner.readyRanges(
            windowStart = windowStart,
            capturedTotal = captured,
            markers = emptyList(),
            excludedRanges = emptyList(),
            speechRegions = null,
            maxSliceSamples = cap,
            cutLongSlice = { from, until ->
                lowest = minOf(lowest, from)
                highest = maxOf(highest, until)
                minOf(from + cap, until)
            },
        )

        assertTrue("a cut was requested before the window began: $lowest", lowest >= 0)
        assertTrue("a cut was requested past the window end: $highest", highest <= windowLength)
    }

    /** And what comes back is in recording coordinates, not window coordinates. */
    @Test
    fun `ranges are returned in recording coordinates`() {
        val windowStart = rate * 90
        val ranges = plan(watermark = windowStart, capturedTotal = rate * 200).ranges

        assertTrue(ranges.isNotEmpty())
        assertTrue(
            "ranges must be absolute, so never before the window start",
            ranges.all { it.first >= windowStart },
        )
    }

    @Test
    fun `a watermark past the settled audio yields nothing`() {
        val plan = plan(watermark = rate * 199, capturedTotal = rate * 200)
        assertEquals(emptyList<IntRange>(), plan.ranges)
        assertEquals(rate * 199, plan.frontier)
    }
}
