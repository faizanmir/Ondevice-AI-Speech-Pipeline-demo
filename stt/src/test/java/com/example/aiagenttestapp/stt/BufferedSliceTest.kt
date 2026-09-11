package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides which words a buffered slice keeps.
 *
 * Buffered inference decodes each slice with its neighbours' audio attached, so the same word is
 * seen by two decodes and exactly one of them must keep it. The rule is "keep a word that *starts*
 * inside this slice's core", and the property that matters is not that any single slice looks right
 * -- it is that the slices **partition** the words: every word kept once, none kept twice, none
 * dropped by both sides.
 *
 * That property is what these pin. It is checked here rather than through [SpeechRecognizer] because
 * the rule is arithmetic over timestamps and the recogniser needs a 670 MB model to say anything.
 */
class BufferedSliceTest {

    private val rate = 16_000

    /** The rule as [SpeechRecognizer.decodeRange] applies it, over words already in recording time. */
    private fun keep(
        words: List<TimedWord>,
        coreFrom: Int,
        coreTo: Int,
        totalSamples: Int,
    ): List<String> {
        val coreStart = coreFrom / rate.toFloat()
        val coreEnd = coreTo / rate.toFloat()
        val lastSlice = coreTo >= totalSamples
        return words
            .filter { it.startSeconds >= coreStart && (lastSlice || it.startSeconds < coreEnd) }
            .map { it.text }
    }

    private fun word(text: String, at: Float) =
        TimedWord(text = text, startSeconds = at, endSeconds = at + 0.3f)

    /** Every word the padded decode saw, in recording time. */
    private val heard = listOf(
        word("before", 8.0f),      // the previous slice's
        word("boundary", 9.9f),    // just before this core starts
        word("inside", 12.0f),
        word("also", 15.0f),
        word("after", 20.4f),      // the next slice's
    )

    private val total = 40 * rate

    @Test
    fun `a slice keeps only the words starting inside its core`() {
        val kept = keep(heard, coreFrom = 10 * rate, coreTo = 20 * rate, totalSamples = total)

        assertEquals(listOf("inside", "also"), kept)
    }

    /**
     * The property the whole design rests on: run the rule over consecutive cores and every word is
     * accounted for exactly once.
     */
    @Test
    fun `consecutive slices partition the words between them`() {
        val cores = listOf(0 to 10, 10 to 20, 20 to 30, 30 to 40)

        val kept = cores.flatMap { (from, to) ->
            keep(heard, from * rate, to * rate, total)
        }

        assertEquals(heard.map { it.text }, kept)
        assertEquals("no word may be kept twice", kept.size, kept.distinct().size)
    }

    /** A word exactly on a boundary belongs to the slice it starts, not the one it ends in. */
    @Test
    fun `a word starting exactly on the boundary goes to the later slice`() {
        val onBoundary = listOf(word("edge", 10.0f))

        assertEquals(emptyList<String>(), keep(onBoundary, 0, 10 * rate, total))
        assertEquals(listOf("edge"), keep(onBoundary, 10 * rate, 20 * rate, total))
    }

    /**
     * The last slice has nobody to hand its tail to. Without this the final words of a recording
     * would be decoded and then discarded by the only slice that could keep them.
     */
    @Test
    fun `the last slice keeps everything past its core`() {
        val tail = listOf(word("penultimate", 36.0f), word("final", 39.5f))

        val kept = keep(tail, coreFrom = 35 * rate, coreTo = total, totalSamples = total)

        assertEquals(listOf("penultimate", "final"), kept)
    }

    /** A slice the padded decode produced nothing for is empty rather than wrong. */
    @Test
    fun `a slice with no words of its own keeps none`() {
        val kept = keep(heard, coreFrom = 30 * rate, coreTo = 35 * rate, totalSamples = total)

        assertEquals(emptyList<String>(), kept)
    }

    /** Only the backends that report word timings can use context at all. */
    @Test
    fun `context is only available where word timings are`() {
        assertEquals(true, SpeechEngineKind.NEMO_TRANSDUCER.reportsWordTimings)
        assertEquals(true, SpeechEngineKind.WHISPER.reportsWordTimings)
        assertEquals(false, SpeechEngineKind.SENSE_VOICE.reportsWordTimings)
    }
}
