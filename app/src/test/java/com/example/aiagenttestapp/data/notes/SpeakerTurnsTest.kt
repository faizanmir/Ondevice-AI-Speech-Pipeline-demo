package com.example.aiagenttestapp.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn building is where the accuracy/speed trade is actually made, so these tests pin the trade down:
 * what gets merged, what loses its identity, and the one place merging is refused.
 */
class SpeakerTurnsTest {

    private val rate = 16_000
    private val floor = rate * 4 // the 4-second absorption floor

    private fun seg(fromSec: Double, untilSec: Double, cluster: Int) =
        DiarizedRange((fromSec * rate).toInt(), (untilSec * rate).toInt(), cluster)

    @Test
    fun `turns tile the recording with no gaps`() {
        val turns = SpeakerTurns.build(
            segments = listOf(seg(1.0, 10.0, 0), seg(12.0, 20.0, 1)),
            totalSamples = rate * 30,
            minTurnSamples = floor,
        )

        assertEquals(0, turns.first().range.first)
        assertEquals(rate * 30 - 1, turns.last().range.last)
        turns.zipWithNext { a, b ->
            assertEquals("turns must be contiguous", a.range.last + 1, b.range.first)
        }
    }

    @Test
    fun `adjacent turns from the same speaker merge`() {
        val turns = SpeakerTurns.build(
            // Diarisation splits on every pause; three segments, one person.
            segments = listOf(seg(0.0, 10.0, 0), seg(11.0, 20.0, 0), seg(21.0, 30.0, 0)),
            totalSamples = rate * 30,
            minTurnSamples = floor,
        )

        assertEquals(1, turns.size)
        assertEquals(0, turns[0].cluster)
    }

    @Test
    fun `two long alternating speakers are both kept`() {
        val turns = SpeakerTurns.build(
            segments = listOf(seg(0.0, 20.0, 0), seg(20.0, 40.0, 1), seg(40.0, 60.0, 0)),
            totalSamples = rate * 60,
            minTurnSamples = floor,
        )

        assertEquals(listOf(0, 1, 0), turns.map { it.cluster })
    }

    @Test
    fun `a short interjection is absorbed into the longer neighbour`() {
        val turns = SpeakerTurns.build(
            // Alice talks for 30s, Bob says "yeah" for 0.8s, Alice continues for 30s.
            segments = listOf(seg(0.0, 30.0, 0), seg(30.0, 30.8, 1), seg(30.8, 60.0, 0)),
            totalSamples = rate * 60,
            minTurnSamples = floor,
        )

        // This is the documented cost of the floor: Bob's interjection is attributed to Alice.
        assertEquals(1, turns.size)
        assertEquals(0, turns[0].cluster)
    }

    @Test
    fun `a run of short turns all get absorbed`() {
        val turns = SpeakerTurns.build(
            segments = listOf(
                seg(0.0, 30.0, 0),
                seg(30.0, 31.0, 1),
                seg(31.0, 32.0, 2),
                seg(32.0, 33.0, 1),
                seg(33.0, 60.0, 0),
            ),
            totalSamples = rate * 60,
            minTurnSamples = floor,
        )

        // Shortest-first absorption has to converge, not stall partway with short turns left over.
        assertTrue("no turn should remain under the floor", turns.all { it.range.count() >= floor })
    }

    @Test
    fun `absorption is refused across a marker boundary`() {
        val markerEdge = rate * 30

        val turns = SpeakerTurns.build(
            // A 2s turn by speaker 1 begins exactly where a spoken marker phrase ended.
            segments = listOf(seg(0.0, 30.0, 0), seg(30.0, 32.0, 1), seg(32.0, 60.0, 0)),
            totalSamples = rate * 60,
            minTurnSamples = floor,
            protectedBoundaries = setOf(markerEdge),
        )

        // Without the protection this would collapse to one turn, as the interjection test shows. Inside
        // a tagged span, who is speaking is the whole point, so the short turn keeps its identity.
        assertTrue("the short turn must survive", turns.any { it.cluster == 1 })
        assertEquals(markerEdge, turns.first { it.cluster == 1 }.range.first)
    }

    @Test
    fun `same-speaker turns are not merged across a marker boundary either`() {
        val markerEdge = rate * 20

        val turns = SpeakerTurns.build(
            segments = listOf(seg(0.0, 20.0, 0), seg(20.0, 40.0, 0)),
            totalSamples = rate * 40,
            minTurnSamples = floor,
            protectedBoundaries = setOf(markerEdge),
        )

        // The slicer would cut here anyway; keeping the turns apart means the tagged span's ownership is
        // decided by the turn that actually covers it.
        assertEquals(2, turns.size)
        assertEquals(markerEdge, turns[1].range.first)
    }

    @Test
    fun `a single speaker collapses to one turn covering everything`() {
        val turns = SpeakerTurns.build(
            segments = listOf(seg(2.0, 55.0, 0)),
            totalSamples = rate * 60,
            minTurnSamples = floor,
        )

        assertEquals(1, turns.size)
        assertEquals(0, turns[0].range.first)
        assertEquals(rate * 60 - 1, turns[0].range.last)
    }

    @Test
    fun `no segments yields no turns`() {
        assertEquals(
            emptyList<SpeakerTurn>(),
            SpeakerTurns.build(emptyList(), rate * 30, floor),
        )
    }

    @Test
    fun `segments given out of order are sorted`() {
        val turns = SpeakerTurns.build(
            segments = listOf(seg(20.0, 40.0, 1), seg(0.0, 20.0, 0)),
            totalSamples = rate * 40,
            minTurnSamples = floor,
        )

        assertEquals(listOf(0, 1), turns.map { it.cluster })
    }

    @Test
    fun `a recording shorter than the floor still produces one turn`() {
        val turns = SpeakerTurns.build(
            segments = listOf(seg(0.0, 2.0, 0)),
            totalSamples = rate * 2,
            minTurnSamples = floor,
        )

        // Nothing to absorb into, so the single short turn stands rather than vanishing.
        assertEquals(1, turns.size)
        assertEquals(rate * 2 - 1, turns[0].range.last)
    }

    @Test
    fun `segments beyond the recording length are clamped`() {
        val turns = SpeakerTurns.build(
            segments = listOf(seg(0.0, 10.0, 0), seg(9.0, 40.0, 1)),
            totalSamples = rate * 12,
            minTurnSamples = floor,
        )

        assertTrue(turns.all { it.range.last < rate * 12 })
        assertEquals(rate * 12 - 1, turns.last().range.last)
    }
}
