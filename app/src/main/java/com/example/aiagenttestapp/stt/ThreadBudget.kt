package com.example.aiagenttestapp.stt

import kotlin.math.roundToInt

/**
 * How CPU is divided between the two branches of a diarisation run.
 *
 * **The problem this replaces.** Both branches sized their own pool from `availableProcessors()`
 * with no knowledge of the other, and both capped at 4. On an eight-core device that is 4 + 4 ONNX
 * threads, plus the coroutine dispatcher's own pool, all on eight cores. Measured mid-run: the
 * process held 590-615% of 800%, spread across thirteen threads at 7-93% each, with none saturating
 * and roughly two cores' worth lost to scheduling. Threads were descheduling each other rather than
 * owning a core.
 *
 * **What the split is worth, honestly.** The measured work ratio is close to even -- 244.2s of
 * diarisation against 190.9s of transcription, both on four threads -- so proportional allocation
 * lands near the 4/4 the app already had. The gain is expected to come from asking for *fewer*
 * threads in total than there are cores, leaving the dispatcher and the UI somewhere to run, rather
 * than from the ratio. Whether that is worth more than the thread it gives up is an empirical
 * question, which is why the weights are constants rather than a formula: they are meant to be
 * moved and re-measured.
 *
 * The total work is what bounds this. 1740 thread-seconds over eight cores is 218s if the machine
 * stayed saturated and both branches finished together; the run being tuned took 245s. So there is
 * about 11% on the table here, not a multiple.
 */
object ThreadBudget {

    /** Threads for each branch. Never zero, and never more than the budget between them. */
    data class Split(val diarise: Int, val transcribe: Int) {
        val total: Int get() = diarise + transcribe
    }

    /**
     * How the budget is divided, as a ratio.
     *
     * A pair rather than one number because the right split depends on which embedding model is
     * running, and the two measured configurations point in opposite directions. Both are recorded
     * so that changing the default embedder does not quietly leave the budget calibrated for a
     * model nobody is using.
     */
    data class Weights(val diarise: Int, val transcribe: Int) {
        companion object {
            /**
             * With CAM++ embedding. Measured on a 20:36 recording: the diarisation branch took
             * 73.7s on four threads (295 core-seconds) against transcription's 114.7s on three
             * (344). Transcription is the bigger job and had fewer threads, which cost about 16
             * seconds of wall clock.
             */
            val FAST_EMBEDDER = Weights(diarise = 3, transcribe = 4)

            /**
             * With ERes2Net-base embedding, which is roughly 2.8x slower to compare voices. Same
             * recording: 244.2s on four threads (977 core-seconds) against 190.9s on four (764).
             * Diarisation dominates, so the ratio is the other way round.
             */
            val SLOW_EMBEDDER = Weights(diarise = 5, transcribe = 4)
        }
    }

    /**
     * Splits the machine between two branches that run at the same time.
     *
     * One core is held back rather than handed out. The coroutine dispatcher, WorkManager and the
     * UI all need somewhere to run, and a run that saturates every core makes the progress bar
     * stutter for no measured gain.
     */
    fun concurrent(
        cores: Int = Runtime.getRuntime().availableProcessors(),
        weights: Weights = Weights.FAST_EMBEDDER,
    ): Split {
        // Two is the floor: below it there is nothing to split and both branches get one thread.
        val budget = (cores - 1).coerceAtLeast(2)
        val total = (weights.diarise + weights.transcribe).coerceAtLeast(1)
        val diarise = ((budget.toDouble() * weights.diarise) / total)
            .roundToInt()
            .coerceIn(1, budget - 1)
        return Split(diarise = diarise, transcribe = budget - diarise)
    }

    /**
     * The whole budget, for a stage with nothing running beside it.
     *
     * Still one short of the core count, for the same reason [concurrent] holds one back.
     */
    fun exclusive(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        (cores - 1).coerceAtLeast(1)
}
