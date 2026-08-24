package com.example.aiagenttestapp.ui.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The run row's clock, pinned because its failure mode is a number that reads as a different number.
 *
 * A run that took an hour and a half rendered as "94:07" before this: not obviously wrong, not
 * flagged anywhere, and wrong by a factor of sixty to anyone scanning a column of durations to
 * decide which settings to keep. Benchmarks here already run past half an hour on the slower device,
 * and a long clip at the larger slice windows can pass an hour outright.
 */
class DurationFormatTest {

    @Test
    fun `renders minutes and seconds below an hour`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("1:00", formatDuration(60_000))
        // The two real measurements from docs/stt-benchmark.html, so the format is pinned against
        // numbers someone has actually read off this screen.
        assertEquals("7:53", formatDuration(473_000))
        assertEquals("34:23", formatDuration(2_063_000))
    }

    @Test
    fun `grows an hours field rather than counting past sixty minutes`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:34:07", formatDuration(5_647_000))
        assertEquals("2:05:00", formatDuration(7_500_000))
    }

    @Test
    fun `never renders a negative clock`() {
        // Reachable from the running row, which subtracts an enqueue stamp from the current time:
        // a device whose clock steps backwards mid-run would otherwise produce "-1:-3".
        assertEquals("0:00", formatDuration(-5_000))
    }
}
