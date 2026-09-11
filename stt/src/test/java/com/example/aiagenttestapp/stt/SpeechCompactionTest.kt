package com.example.aiagenttestapp.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two coordinate systems against each other.
 *
 * Compaction is only worth having if a sample index means the same moment of speech before and
 * after it, so nearly every test here is a round trip. The one that is not is the splice test,
 * which pins the decision the whole design rests on.
 */
class SpeechCompactionTest {

    /** A ramp, so every sample says where it came from and a misplaced copy is visible. */
    private fun ramp(size: Int) = FloatArray(size) { it.toFloat() }

    @Test
    fun `speech with no silence is left exactly as it was`() {
        val samples = ramp(1_000)
        val compacted = CompactedAudio.of(samples, listOf(0..999))

        assertArrayEquals(samples, compacted.samples, 0f)
        assertEquals(0f, compacted.removedFraction, 0.0001f)
        assertEquals(0, compacted.toOriginal(0))
        assertEquals(999, compacted.toOriginal(999))
    }

    @Test
    fun `silence between two regions is dropped and the map still points home`() {
        val samples = ramp(1_000)
        // 0..199 speech, 200..799 silence, 800..999 speech.
        val compacted = CompactedAudio.of(samples, listOf(0..199, 800..999))

        assertEquals(400, compacted.samples.size)
        assertEquals(0.6f, compacted.removedFraction, 0.0001f)

        // Every kept sample still carries the value it had in the recording.
        assertEquals(0f, compacted.samples[0], 0f)
        assertEquals(199f, compacted.samples[199], 0f)
        assertEquals(800f, compacted.samples[200], 0f)
        assertEquals(999f, compacted.samples[399], 0f)

        assertEquals(0, compacted.toOriginal(0))
        assertEquals(199, compacted.toOriginal(199))
        assertEquals(800, compacted.toOriginal(200))
        assertEquals(999, compacted.toOriginal(399))
    }

    @Test
    fun `a turn spanning a splice comes back as two ranges, not one across the gap`() {
        // The decision the design rests on. In compacted space this is one continuous stretch of
        // one voice; in the recording it is two, with removed silence between. Reporting it as one
        // range would claim the speaker held the floor through audio dropped for having no speech
        // in it -- and any word the recogniser found in that silence would be handed to them.
        val samples = ramp(1_000)
        val compacted = CompactedAudio.of(samples, listOf(0..199, 800..999))

        val expanded = compacted.toOriginal(150..250)

        assertEquals(2, expanded.size)
        assertEquals(150..199, expanded[0])
        assertEquals(800..850, expanded[1])
        // The dropped silence is covered by neither, which is what leaves alignment free to treat
        // it as a gap and refuse to guess.
        assertTrue(expanded.none { 400 in it })
    }

    @Test
    fun `a range inside one piece stays one range`() {
        val compacted = CompactedAudio.of(ramp(1_000), listOf(0..199, 800..999))
        assertEquals(listOf(20..40), compacted.toOriginal(20..40))
    }

    @Test
    fun `a detector that found nothing returns the recording rather than deleting it`() {
        // A VAD that finds no speech is far more likely to have failed than to have been handed a
        // silent meeting, and from here the two are indistinguishable. Compacting to an empty array
        // would lose the recording.
        val samples = ramp(1_000)
        val compacted = CompactedAudio.of(samples, emptyList())

        assertArrayEquals(samples, compacted.samples, 0f)
        assertEquals(0f, compacted.removedFraction, 0.0001f)
        assertEquals(500, compacted.toOriginal(500))
    }

    @Test
    fun `overlapping and unsorted regions cannot produce disagreeing offsets`() {
        val samples = ramp(1_000)
        // Same speech described badly: out of order, overlapping, and one running past the end.
        val compacted = CompactedAudio.of(samples, listOf(800..1_400, 0..199, 100..250))

        // 0..250 and 800..999 survive as two pieces, not four overlapping ones.
        assertEquals(2, compacted.pieces.size)
        assertEquals(251 + 200, compacted.samples.size)
        assertEquals(0, compacted.toOriginal(0))
        assertEquals(250, compacted.toOriginal(250))
        assertEquals(800, compacted.toOriginal(251))
        assertEquals(999, compacted.toOriginal(450))
    }

    @Test
    fun `every compacted sample maps back inside a region that was kept`() {
        val samples = ramp(4_000)
        val regions = listOf(120..900, 1_500..1_560, 2_000..3_780)
        val compacted = CompactedAudio.of(samples, regions)

        for (index in compacted.samples.indices) {
            val original = compacted.toOriginal(index)
            assertTrue(
                "compacted $index mapped to $original, which no region covers",
                regions.any { original in it },
            )
            // And the value at that index is the value the recording holds there.
            assertEquals(original.toFloat(), compacted.samples[index], 0f)
        }
    }

    @Test
    fun `an hour of meeting with dead air fits where the raw recording would not`() {
        // The reason this class exists, in the units the ceiling is stated in: DiarizeWorker holds
        // the whole recording as floats, 3.8 MB a minute, and names a streaming diarisation API as
        // the way past that -- one sherpa-onnx does not have.
        val minute = 16_000 * 60
        val hour = minute * 60
        // 40% dead air, as a meeting rather than a read script: speech in ten-minute stretches.
        val regions = (0 until 6).map { block ->
            val from = block * 10 * minute
            from until (from + 6 * minute)
        }
        val compacted = CompactedAudio.of(FloatArray(hour), regions)

        assertEquals(0.4f, compacted.removedFraction, 0.001f)
        val megabytes = compacted.samples.size * 4 / (1024 * 1024)
        assertTrue("compacted to $megabytes MB, expected under 150", megabytes < 150)
    }
}
