package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.stt.DiarizedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarizationChunksTest {

    private val rate = 16_000

    private fun seconds(n: Int) = n * rate

    @Test
    fun `a recording too short to split is one chunk covering all of it`() {
        val total = seconds(120)

        val chunks = DiarizationChunks.plan(total, rate)

        assertEquals(listOf(DiarizationChunk(0, total)), chunks)
    }

    @Test
    fun `a recording is only split when the tail left over is itself usable`() {
        // target + min is the threshold; just under it must stay whole rather than leave a stub
        // with too little voice to cluster.
        val threshold = DiarizationChunks.TARGET_SECONDS + DiarizationChunks.MIN_SECONDS
        assertEquals(1, DiarizationChunks.plan(seconds(threshold - 1), rate).size)
        assertEquals(2, DiarizationChunks.plan(seconds(threshold + 1), rate).size)
    }

    /**
     * Pinned because the value is the outcome of a measurement, not a preference: smaller chunks
     * speed diarisation up and slow folding and naming down, and 300 is where that trade settled.
     */
    @Test
    fun `the chunk target is five minutes`() {
        assertEquals(300, DiarizationChunks.TARGET_SECONDS)
    }

    @Test
    fun `a twenty minute recording splits into four chunks`() {
        assertEquals(4, DiarizationChunks.plan(seconds(1236), rate).size)
    }

    @Test
    fun `chunks are contiguous and cover the whole recording`() {
        val total = seconds(1236)          // the 20 minute audit recording

        val chunks = DiarizationChunks.plan(total, rate)

        assertEquals(0, chunks.first().startSample)
        assertEquals(total, chunks.last().endSample)
        chunks.zipWithNext { a, b -> assertEquals(a.endSample, b.startSample) }
    }

    @Test
    fun `no chunk is shorter than the minimum`() {
        val min = seconds(DiarizationChunks.MIN_SECONDS)

        for (durationSeconds in listOf(196, 300, 451, 900, 1236, 1686)) {
            val chunks = DiarizationChunks.plan(seconds(durationSeconds), rate)
            chunks.forEach {
                assertTrue(
                    "chunk of ${it.length} samples in a ${durationSeconds}s recording",
                    it.length >= min,
                )
            }
        }
    }

    @Test
    fun `a cut lands on a nearby splice rather than mid word`() {
        val total = seconds(600)
        val splice = seconds(158)          // 8s past the ideal 150s cut, inside the snap window

        val chunks = DiarizationChunks.plan(
            total, rate, spliceBoundaries = listOf(splice), targetSeconds = 150,
        )

        assertEquals(splice, chunks.first().endSample)
    }

    @Test
    fun `a splice too far from the ideal cut is ignored`() {
        val total = seconds(600)
        val splice = seconds(100)          // 50s early, well outside the 20s snap window

        val chunks = DiarizationChunks.plan(
            total, rate, spliceBoundaries = listOf(splice), targetSeconds = 150,
        )

        assertEquals(seconds(150), chunks.first().endSample)
    }

    @Test
    fun `the closest of several splices wins`() {
        val total = seconds(600)
        val splices = listOf(seconds(136), seconds(147), seconds(163))

        val chunks = DiarizationChunks.plan(
            total, rate, spliceBoundaries = splices, targetSeconds = 150,
        )

        assertEquals(seconds(147), chunks.first().endSample)
    }

    @Test
    fun `an empty recording plans nothing`() {
        assertEquals(emptyList<DiarizationChunk>(), DiarizationChunks.plan(0, rate))
    }

    @Test
    fun `cluster ids from a later chunk never collide with an earlier one`() {
        val first = listOf(seg(0, 100, 0), seg(100, 200, 1))
        val second = listOf(seg(0, 100, 0), seg(100, 200, 1))

        val (shiftedFirst, afterFirst) = DiarizationChunks.namespaced(first, 0)
        val (shiftedSecond, _) = DiarizationChunks.namespaced(second, afterFirst)

        assertEquals(listOf(0, 1), shiftedFirst.map { it.cluster })
        assertEquals(listOf(2, 3), shiftedSecond.map { it.cluster })
        assertTrue(
            (shiftedFirst.map { it.cluster } intersect shiftedSecond.map { it.cluster }.toSet())
                .isEmpty(),
        )
    }

    @Test
    fun `namespacing an empty chunk leaves the next free id alone`() {
        val (turns, next) = DiarizationChunks.namespaced(emptyList(), 7)

        assertEquals(emptyList<DiarizedSegment>(), turns)
        assertEquals(7, next)
    }

    @Test
    fun `chunk local turns come back in compacted coordinates`() {
        val chunk = DiarizationChunk(seconds(150), seconds(300))
        val turns = listOf(seg(0, seconds(4), 0), seg(seconds(10), seconds(12), 1))

        val moved = DiarizationChunks.toCompacted(turns, chunk)

        assertEquals(seconds(150), moved[0].startSample)
        assertEquals(seconds(154), moved[0].endSample)
        assertEquals(seconds(160), moved[1].startSample)
        assertEquals(seconds(162), moved[1].endSample)
    }

    private fun seg(start: Int, end: Int, cluster: Int) =
        DiarizedSegment(startSample = start, endSample = end, cluster = cluster)
}
