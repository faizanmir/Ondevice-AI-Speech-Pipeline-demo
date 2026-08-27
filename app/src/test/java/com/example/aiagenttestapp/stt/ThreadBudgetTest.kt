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
    fun `a heavy recogniser gives transcription the extra thread`() {
        // CAM++ embedding (diarise 6) against Parakeet (transcribe 8): transcription is the bigger
        // job, so it takes the odd thread. This is the split the app was tuned to before a cheaper
        // recogniser existed.
        val split = ThreadBudget.concurrent(
            cores = 8, weights = ThreadBudget.Weights(diarise = 6, transcribe = 8),
        )

        assertEquals(3, split.diarise)
        assertEquals(4, split.transcribe)
        assertEquals(7, split.total)
    }

    @Test
    fun `a light recogniser hands the extra thread back to diarisation`() {
        // CAM++ embedding (diarise 6) against FastConformer (transcribe 4): transcription is now the
        // small job, so the split flips -- the regression the old embedder-only presets could not
        // see, because they assumed Parakeet.
        val split = ThreadBudget.concurrent(
            cores = 8, weights = ThreadBudget.Weights(diarise = 6, transcribe = 4),
        )

        assertEquals(4, split.diarise)
        assertEquals(3, split.transcribe)
        assertEquals(7, split.total)
    }

    /**
     * The whole reason the weights are a pair: the same embedder against a heavy versus a light
     * recogniser lands the odd thread on opposite branches.
     */
    @Test
    fun `swapping the recogniser moves who gets the extra thread`() {
        val heavy = ThreadBudget.concurrent(
            cores = 8, weights = ThreadBudget.Weights(diarise = 6, transcribe = 8),
        )
        val light = ThreadBudget.concurrent(
            cores = 8, weights = ThreadBudget.Weights(diarise = 6, transcribe = 4),
        )

        assertTrue(heavy.transcribe > heavy.diarise)
        assertTrue(light.diarise > light.transcribe)
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
