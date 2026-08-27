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
     * How the budget is divided, as a ratio of the two branches' relative cost.
     *
     * A pair rather than one number because which branch is the long pole depends on *both* models,
     * and they pull in opposite directions:
     *
     *  - [diarise] scales with the embedding model. CAM++ compares voices about 2.8x faster than
     *    ERes2Net-base, so it makes the diarisation branch cheaper.
     *  - [transcribe] scales with the recogniser. Parakeet 0.6B was the bigger job by far (114.7s
     *    against a 62.9s diarisation branch on a 20:36 recording), which is why the split used to
     *    lean toward transcription; FastConformer does the same work in ~39s, which hands the lead
     *    back to diarisation.
     *
     * So the weights are no longer two fixed presets keyed on the embedder -- that quietly assumed
     * Parakeet and split the wrong way once a cheaper recogniser existed. Each model now declares
     * its own relative cost ([com.example.aiagenttestapp.stt.SpeechModel.transcribeWeight],
     * [com.example.aiagenttestapp.data.audiomodels.AudioModelBundle.diariseWeight]) and the caller
     * pairs them here. The numbers are relative core-seconds on the same scale, meant to be
     * re-measured, not derived.
     */
    data class Weights(val diarise: Int, val transcribe: Int)

    /**
     * Splits the machine between two branches that run at the same time.
     *
     * One core is held back rather than handed out. The coroutine dispatcher, WorkManager and the
     * UI all need somewhere to run, and a run that saturates every core makes the progress bar
     * stutter for no measured gain.
     *
     * The weights default to even; every real caller passes a pair built from the two selected
     * models, and only the invariant tests lean on the default.
     */
    fun concurrent(
        cores: Int = Runtime.getRuntime().availableProcessors(),
        weights: Weights = Weights(diarise = 1, transcribe = 1),
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
