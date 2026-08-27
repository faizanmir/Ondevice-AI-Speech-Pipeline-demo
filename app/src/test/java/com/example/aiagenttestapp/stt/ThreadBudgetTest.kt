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
    fun `few fast cores cap the budget below the core count`() {
        // Eight cores but only two run near the top clock (a big.LITTLE part): the budget stops
        // spilling ONNX threads onto the slow companions -- two fast plus the headroom, not cores-1.
        val split = ThreadBudget.concurrent(
            cores = 8, fastCores = 2,
            weights = ThreadBudget.Weights(diarise = 6, transcribe = 4),
        )
        assertEquals(4, split.total)
        assertEquals(2, split.diarise)
        assertEquals(2, split.transcribe)
    }

    @Test
    fun `enough fast cores leave the budget at cores minus one`() {
        // Six fast cores clears cores-1, so nothing is held back: the many-core devices are unchanged.
        val split = ThreadBudget.concurrent(
            cores = 8, fastCores = 6,
            weights = ThreadBudget.Weights(diarise = 6, transcribe = 4),
        )
        assertEquals(7, split.total)
    }

    @Test
    fun `fast-core detection counts cores near the top clock`() {
        // MT8755-shaped: six at 2.0 GHz against two at 2.6 GHz -> two fast.
        val mtk = longArrayOf(
            2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_600_000, 2_600_000,
        )
        assertEquals(2, ThreadBudget.detectFastCores(cores = 8) { mtk[it] })

        // Snapdragon-shaped: 3.2 + 3.0x3 + 2.8x2 + 2.0x2 -> only the 2.0 GHz pair drops out.
        val sd = longArrayOf(
            3_206_400, 3_014_400, 3_014_400, 3_014_400, 2_803_200, 2_803_200, 2_016_000, 2_016_000,
        )
        assertEquals(6, ThreadBudget.detectFastCores(cores = 8) { sd[it] })
    }

    @Test
    fun `unreadable topology falls back to all cores`() {
        // No /sys to read: assume every core is fast, which restores the old cores-1 budget.
        assertEquals(8, ThreadBudget.detectFastCores(cores = 8) { null })
    }

    @Test
    fun `a stage running alone still leaves one core free`() {
        assertEquals(7, ThreadBudget.exclusive(cores = 8))
        assertEquals(3, ThreadBudget.exclusive(cores = 4))
        assertEquals(1, ThreadBudget.exclusive(cores = 1))
    }
}
