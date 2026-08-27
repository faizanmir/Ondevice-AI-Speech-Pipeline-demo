package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadBudgetTest {

    @Test
    fun `a core is held back for the dispatcher and the UI`() {
        // The whole point: the two branches together must ask for less than the machine has, or
        // they deschedule each other and the progress bar stutters.
        for (cores in 4..16) {
            assertTrue(
                "cores=$cores asked for ${ThreadBudget.concurrent(cores).total}",
                ThreadBudget.concurrent(cores).total < cores,
            )
        }
    }

    @Test
    fun `with the fast embedder an eight core device gives transcription the extra thread`() {
        val split = ThreadBudget.concurrent(cores = 8, weights = ThreadBudget.Weights.FAST_EMBEDDER)

        assertEquals(3, split.diarise)
        assertEquals(4, split.transcribe)
        assertEquals(7, split.total)
    }

    @Test
    fun `with the slow embedder the extra thread goes the other way`() {
        val split = ThreadBudget.concurrent(cores = 8, weights = ThreadBudget.Weights.SLOW_EMBEDDER)

        assertEquals(4, split.diarise)
        assertEquals(3, split.transcribe)
        assertEquals(7, split.total)
    }

    /**
     * The whole reason the weights are a pair: a budget calibrated for one embedding model and
     * applied to the other lands on the wrong side, which measured about 16 seconds of wall clock.
     */
    @Test
    fun `the two calibrations disagree about who gets the extra thread`() {
        val fast = ThreadBudget.concurrent(cores = 8, weights = ThreadBudget.Weights.FAST_EMBEDDER)
        val slow = ThreadBudget.concurrent(cores = 8, weights = ThreadBudget.Weights.SLOW_EMBEDDER)

        assertTrue(fast.transcribe > fast.diarise)
        assertTrue(slow.diarise > slow.transcribe)
    }

    @Test
    fun `neither branch is ever starved to zero`() {
        for (cores in 1..16) {
            val split = ThreadBudget.concurrent(cores)
            assertTrue("cores=$cores diarise=${split.diarise}", split.diarise >= 1)
            assertTrue("cores=$cores transcribe=${split.transcribe}", split.transcribe >= 1)
        }
    }

    @Test
    fun `a single core machine still runs both branches`() {
        val split = ThreadBudget.concurrent(cores = 1)

        assertEquals(1, split.diarise)
        assertEquals(1, split.transcribe)
    }

    @Test
    fun `weights move the split, so they can be re-measured`() {
        val evenly = ThreadBudget.concurrent(
            cores = 9, weights = ThreadBudget.Weights(diarise = 1, transcribe = 1),
        )
        assertEquals(4, evenly.diarise)
        assertEquals(4, evenly.transcribe)

        val lopsided = ThreadBudget.concurrent(
            cores = 9, weights = ThreadBudget.Weights(diarise = 3, transcribe = 1),
        )
        assertEquals(6, lopsided.diarise)
        assertEquals(2, lopsided.transcribe)
    }

    @Test
    fun `a stage running alone still leaves one core free`() {
        assertEquals(7, ThreadBudget.exclusive(cores = 8))
        assertEquals(3, ThreadBudget.exclusive(cores = 4))
        assertEquals(1, ThreadBudget.exclusive(cores = 1))
    }
}
