package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.TimedWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryRefinementTest {

    private val rate = 16_000
    private val a = floatArrayOf(1f, 0f)
    private val b = floatArrayOf(0f, 1f)
    private fun s(seconds: Float) = (seconds * rate).toInt()

    /** A word every half second across the recording. */
    private val words = (0 until 40).map { TimedWord("w$it", it * 0.5f, it * 0.5f + 0.5f) }

    /**
     * A fake embedder for a recording in which A really speaks until [trueBoundary] and B after: a
     * range's voiceprint is the two voices mixed by how much of the range each occupies.
     */
    private fun embedderWithBoundaryAt(trueBoundary: Float, calls: MutableList<IntRange> = mutableListOf()): suspend (IntRange) -> FloatArray? =
        { range ->
            calls += range
            val from = range.first / rate.toFloat()
            val until = (range.last + 1) / rate.toFloat()
            val bShare = ((until - minOf(maxOf(trueBoundary, from), until)) / (until - from)).coerceIn(0f, 1f)
            val v = floatArrayOf(1f - bShare, bShare)
            val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1])
            floatArrayOf(v[0] / n, v[1] / n)
        }

    private suspend fun refine(turns: List<DiarizedSegment>, embed: suspend (IntRange) -> FloatArray?) =
        BoundaryRefinement.refine(
            turns = turns, words = words, sampleRate = rate,
            identity = { if (it == 0) "A" else "B" },
            centroid = { if (it == 0) a else b },
            embed = embed,
        )

    @Test
    fun `a boundary four seconds late is pulled back to the voice change`() = runBlocking {
        // The diariser says A until 10 s; A actually stopped at 6 s.
        val turns = listOf(DiarizedSegment(0, s(10f), 0), DiarizedSegment(s(10f), s(20f), 1))
        val result = refine(turns, embedderWithBoundaryAt(6f))
        assertEquals(1, result.moves.size)
        val moved = result.turns.sortedBy { it.startSample }
        val boundary = moved[0].endSample / rate.toFloat()
        // Pieces are cut at word starts about 1.2 s apart, so the landing is within a piece of 6 s
        // -- and never later than the true change, which is the whole point.
        assertTrue("boundary $boundary", boundary in 4.8f..6.0f)
        assertEquals(moved[0].endSample, moved[1].startSample)
    }

    @Test
    fun `a correct boundary costs one probe and does not move`() = runBlocking {
        val turns = listOf(DiarizedSegment(0, s(10f), 0), DiarizedSegment(s(10f), s(20f), 1))
        val calls = mutableListOf<IntRange>()
        val result = refine(turns, embedderWithBoundaryAt(10f, calls))
        assertEquals(0, result.moves.size)
        assertEquals(1, result.handOvers)
        assertEquals(1, calls.size)
        assertEquals(turns, result.turns)
    }

    @Test
    fun `the boundary never moves further back than MAX_SHIFT and A keeps MIN_KEEP`() = runBlocking {
        // The whole of A's ten seconds "sounds like B": still only six seconds may move.
        val turns = listOf(DiarizedSegment(0, s(10f), 0), DiarizedSegment(s(10f), s(20f), 1))
        val result = refine(turns) { b }
        val boundary = result.turns.sortedBy { it.startSample }[0].endSample / rate.toFloat()
        assertTrue("boundary $boundary", boundary >= 10f - BoundaryRefinement.MAX_SHIFT_SECONDS - 0.01f)
        assertTrue(boundary >= BoundaryRefinement.MIN_KEEP_SECONDS)
    }

    @Test
    fun `two clusters that name the same person are not a hand-over`() = runBlocking {
        val turns = listOf(DiarizedSegment(0, s(10f), 0), DiarizedSegment(s(10f), s(20f), 1))
        val result = BoundaryRefinement.refine(
            turns = turns, words = words, sampleRate = rate,
            identity = { "same person" }, centroid = { if (it == 0) a else b },
            embed = embedderWithBoundaryAt(6f),
        )
        assertEquals(0, result.handOvers)
        assertEquals(0, result.embeddings)
    }

    @Test
    fun `an uncertain tail leaves the boundary where it was`() = runBlocking {
        // Everything sounds equally like both: under the margin, so nothing is sure enough to move.
        val turns = listOf(DiarizedSegment(0, s(10f), 0), DiarizedSegment(s(10f), s(20f), 1))
        val result = refine(turns) { floatArrayOf(0.7071f, 0.7071f) }
        assertEquals(0, result.moves.size)
        assertEquals(turns, result.turns)
    }

    @Test
    fun `a turn with no words to cut at is left alone`() = runBlocking {
        val turns = listOf(DiarizedSegment(0, s(10f), 0), DiarizedSegment(s(10f), s(20f), 1))
        val result = BoundaryRefinement.refine(
            turns = turns, words = emptyList(), sampleRate = rate,
            identity = { if (it == 0) "A" else "B" }, centroid = { if (it == 0) a else b },
            embed = embedderWithBoundaryAt(6f),
        )
        assertEquals(turns, result.turns)
    }
}
