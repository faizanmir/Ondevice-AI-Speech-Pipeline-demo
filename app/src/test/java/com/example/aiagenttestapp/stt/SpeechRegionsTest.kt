package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules decide which parts of a recording are never transcribed, and a mistake in that
 * direction is invisible: the sentence is simply not in the transcript, with no gap to notice and no
 * way to get it back. So the cases that matter most are the ones proving speech survives.
 */
class SpeechRegionsTest {

    private val rate = AudioRecorder.SAMPLE_RATE
    private val total = rate * 60

    /** Fixed, small values so the arithmetic in each case can be checked by hand. */
    private val pad = rate / 10
    private val gap = rate

    private fun resolve(
        detected: List<IntRange>,
        protectedRanges: List<IntRange> = emptyList(),
        totalSamples: Int = total,
    ) = SpeechRegions.resolve(
        detected = detected,
        totalSamples = totalSamples,
        protectedRanges = protectedRanges,
        padSamples = pad,
        minGapSamples = gap,
    )

    // -------- the fallbacks that must never produce an empty note --------

    @Test
    fun `finding nothing means transcribe everything`() {
        // The VAD failing and the recording being silent are indistinguishable from here, and both
        // are better served by the old behaviour than by an empty note.
        assertNull(resolve(emptyList()))
    }

    @Test
    fun `speech everywhere means no restriction`() {
        assertNull(resolve(listOf(0 until total)))
    }

    @Test
    fun `an empty recording means no restriction`() {
        assertNull(resolve(listOf(0 until 100), totalSamples = 0))
    }

    // -------- padding and merging --------

    @Test
    fun `pads both ends of a region`() {
        val regions = resolve(listOf(rate * 10 until rate * 20))

        assertEquals(listOf(rate * 10 - pad until rate * 20 + pad), regions)
    }

    @Test
    fun `never pads past the recording`() {
        val regions = resolve(listOf(0 until rate))

        assertEquals(0, regions!!.first().first)
    }

    @Test
    fun `merges regions separated by a short pause`() {
        // Someone drawing breath mid-sentence. Split, each half would be decoded with no idea what
        // the other said.
        val regions = resolve(
            listOf(rate * 10 until rate * 12, rate * 12 + rate / 2 until rate * 15),
        )

        assertEquals(1, regions!!.size)
        assertEquals(rate * 10 - pad until rate * 15 + pad, regions.single())
    }

    @Test
    fun `keeps regions separated by a real silence apart`() {
        val regions = resolve(
            listOf(rate * 10 until rate * 12, rate * 30 until rate * 35),
        )

        assertEquals(2, regions!!.size)
    }

    @Test
    fun `merges overlapping regions`() {
        val regions = resolve(listOf(rate * 10 until rate * 20, rate * 15 until rate * 25))

        assertEquals(listOf(rate * 10 - pad until rate * 25 + pad), regions)
    }

    // -------- what the user marked wins --------

    @Test
    fun `keeps a marked span the vad heard nothing in`() {
        // A spoken marker is the strongest signal in the recording that this part matters. If the
        // VAD and the user disagree, the user is right.
        val marked = rate * 40 until rate * 45
        val regions = resolve(listOf(rate * 10 until rate * 12), protectedRanges = listOf(marked))

        assertTrue(
            "the marked span must be covered, got $regions",
            regions!!.any { it.first <= marked.first && it.last >= marked.last },
        )
    }

    @Test
    fun `clamps a marked span that runs past the recording`() {
        val regions = resolve(
            listOf(rate until rate * 2),
            protectedRanges = listOf(rate * 55 until rate * 90),
        )

        assertTrue("no region may run past the end", regions!!.all { it.last < total })
    }

    // -------- provisional regions for a recording still in progress --------

    /**
     * A live VAD only reports a region when it closes, so mid-recording the audio it has not ruled
     * on must arrive claimed as speech -- the pipeline would otherwise skip a sentence still being
     * spoken and pay for it after stop.
     */
    @Test
    fun `unruled audio is claimed as speech`() {
        val provisional = SpeechRegions.provisional(
            settled = listOf(0 until rate * 10),
            classifiedUpTo = rate * 20,
            totalSamples = rate * 60,
        )

        assertTrue(
            "the unruled tail must be covered, got $provisional",
            provisional.any { it.first <= rate * 20 && it.last >= rate * 60 - 1 },
        )
    }

    /** The stretch that *was* ruled on and holds no region really is silence, and stays skippable. */
    @Test
    fun `ruled silence is not claimed`() {
        val provisional = SpeechRegions.provisional(
            settled = listOf(0 until rate * 10),
            classifiedUpTo = rate * 30,
            totalSamples = rate * 60,
        )

        val ruledSilence = rate * 15
        assertTrue(
            "audio ruled silent must stay outside every region",
            provisional.none { ruledSilence in it },
        )
    }

    @Test
    fun `a fully classified recording adds nothing`() {
        val settled = listOf(0 until rate * 10)
        assertEquals(
            settled,
            SpeechRegions.provisional(settled, classifiedUpTo = rate * 60, totalSamples = rate * 60),
        )
    }

    // How a resolved region is then applied to slices is SpokenMarkers' job, and is tested there --
    // there is deliberately only one implementation of "does this slice overlap any speech".
}
