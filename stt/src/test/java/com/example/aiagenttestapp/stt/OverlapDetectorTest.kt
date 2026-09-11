package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hearing two people at once.
 *
 * The hysteresis is the part worth pinning: a plain threshold on the model's activity curve produces
 * a burst of one-frame regions wherever it wobbles across the line, and a transcript peppered with
 * `(overlap)` at every hesitation is worse than one that never says it at all.
 */
class OverlapDetectorTest {

    private val hop = 0.1f

    /**
     * Builds a track from per-speaker activity curves. `cls` and `margin` are irrelevant here --
     * overlap is read from [PyannoteSegmenter.FrameTrack.act] alone.
     */
    private fun track(vararg curves: FloatArray): PyannoteSegmenter.FrameTrack {
        val frames = curves.first().size
        val act = Array(PyannoteSegmenter.SPEAKERS_PER_WINDOW) { speaker ->
            curves.getOrElse(speaker) { FloatArray(frames) }
        }
        return PyannoteSegmenter.FrameTrack(
            cls = IntArray(frames),
            margin = FloatArray(frames) { 1f },
            act = act,
            hopSec = hop,
        )
    }

    private fun flat(value: Float, frames: Int) = FloatArray(frames) { value }

    private fun ramp(vararg values: Float) = values

    @Test
    fun `one speaker alone is not an overlap`() {
        val regions = OverlapDetector.detect(track(flat(0.9f, 40), flat(0.0f, 40)), 0f)

        assertTrue(regions.isEmpty())
    }

    @Test
    fun `two speakers active together are one region`() {
        val regions = OverlapDetector.detect(track(flat(0.9f, 40), flat(0.9f, 40)), 0f)

        assertEquals(1, regions.size)
        assertEquals(0f, regions[0].startSec, 1e-4f)
        assertEquals(4f, regions[0].endSec, 1e-4f)
    }

    @Test
    fun `regions are reported in recording time, not window time`() {
        val regions = OverlapDetector.detect(track(flat(0.9f, 20), flat(0.9f, 20)), windowStartSec = 30f)

        assertEquals(1, regions.size)
        assertEquals(30f, regions[0].startSec, 1e-4f)
        assertEquals(32f, regions[0].endSec, 1e-4f)
    }

    /**
     * Once the model has committed to someone talking it takes a clearer retraction to stop, so a
     * dip to 0.25 -- below [OverlapDetector.ONSET] but above [OverlapDetector.OFFSET] -- does not
     * end the run.
     */
    @Test
    fun `a dip between onset and offset does not break a region in two`() {
        val second = ramp(
            0.9f, 0.9f, 0.9f, 0.9f, 0.9f,
            0.25f, 0.25f,
            0.9f, 0.9f, 0.9f, 0.9f, 0.9f,
        )
        val regions = OverlapDetector.detect(track(flat(0.9f, second.size), second), 0f)

        assertEquals(1, regions.size)
    }

    @Test
    fun `a speaker who never reaches onset is not active at all`() {
        val regions = OverlapDetector.detect(track(flat(0.9f, 40), flat(0.25f, 40)), 0f)

        assertTrue(regions.isEmpty())
    }

    /** Below about 150 ms there is no word in it, so it is the model's uncertainty, not a person. */
    @Test
    fun `a blip too short to hold a word is dropped`() {
        val second = ramp(0f, 0.9f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val regions = OverlapDetector.detect(track(flat(0.9f, second.size), second), 0f)

        assertTrue(regions.isEmpty())
    }

    @Test
    fun `three speakers at once is still one region`() {
        val regions = OverlapDetector.detect(
            track(flat(0.9f, 30), flat(0.9f, 30), flat(0.9f, 30)), 0f,
        )

        assertEquals(1, regions.size)
    }

    // ---------------------------------------------------------------- accumulate

    /** A contested stretch routinely spans the seam between two windows. */
    @Test
    fun `regions either side of a window seam join into one`() {
        val accumulated = mutableListOf(OverlapDetector.Region(8f, 10f))

        OverlapDetector.accumulate(accumulated, listOf(OverlapDetector.Region(10f, 12f)))

        assertEquals(1, accumulated.size)
        assertEquals(8f, accumulated[0].startSec, 1e-4f)
        assertEquals(12f, accumulated[0].endSec, 1e-4f)
    }

    @Test
    fun `regions a long way apart stay separate`() {
        val accumulated = mutableListOf(OverlapDetector.Region(0f, 2f))

        OverlapDetector.accumulate(accumulated, listOf(OverlapDetector.Region(60f, 62f)))

        assertEquals(2, accumulated.size)
    }

    @Test
    fun `accumulating into an empty list keeps what it is given`() {
        val accumulated = mutableListOf<OverlapDetector.Region>()

        OverlapDetector.accumulate(accumulated, listOf(OverlapDetector.Region(1f, 2f)))

        assertEquals(1, accumulated.size)
    }

    // ---------------------------------------------------------------- intersects

    @Test
    fun `a word inside a contested stretch intersects it`() {
        val regions = listOf(OverlapDetector.Region(5f, 9f))

        assertTrue(OverlapDetector.intersects(regions, 6f, 7f))
        assertTrue(OverlapDetector.intersects(regions, 4f, 6f))
        assertFalse(OverlapDetector.intersects(regions, 9f, 10f))
        assertFalse(OverlapDetector.intersects(regions, 0f, 5f))
    }
}
