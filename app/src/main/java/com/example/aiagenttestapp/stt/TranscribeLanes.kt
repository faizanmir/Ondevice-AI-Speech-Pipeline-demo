package com.example.aiagenttestapp.stt

import android.app.ActivityManager
import android.content.Context

/**
 * Decides whether transcription gets its second decoding lane, and the thread split if it does.
 *
 * Shared between [com.example.aiagenttestapp.data.speakers.DiarizeWorker] and the diarise screen's
 * pre-warm on purpose: the pre-warm loads the recogniser with the thread share the worker will ask
 * for, and if the two computed it differently the "warm" model would be reloaded the moment the run
 * starts -- paying the load twice and calling it an optimisation.
 */
object TranscribeLanes {

    /**
     * Free memory required, beyond the low-memory threshold, before transcription builds a second
     * recogniser. Sized for the largest model offered (Whisper Small is roughly 750 MB resident)
     * plus margin, not for the usual case: the gate cannot know a model's resident size without
     * loading it, and erring small here is how a run gets killed instead of slowed.
     */
    const val SECOND_RECOGNIZER_HEADROOM_BYTES = 1_500L * 1024 * 1024

    /**
     * The per-lane thread shares: two entries when the second lane is granted, one otherwise.
     *
     * [hasWarmLane] short-circuits the memory gate, because a lane already warm in memory costs
     * nothing to use -- the gate exists to stop a *fresh* allocation under pressure, not to refuse
     * memory that is already spent and would go to waste refused.
     */
    fun laneThreads(
        context: Context,
        transcribeThreads: Int,
        hasWarmLane: (threads: Int) -> Boolean = { false },
    ): List<Int> {
        if (transcribeThreads < 2) return listOf(transcribeThreads.coerceAtLeast(1))
        val split = ThreadBudget.share(transcribeThreads, 2)
        val granted = hasWarmLane(split[1]) || roomForSecondRecognizer(context)
        return if (granted) split else listOf(transcribeThreads)
    }

    /**
     * Whether a second transcribe recogniser fits in memory right now.
     *
     * The gate is deliberately blunt -- free memory above the system's own low-memory threshold --
     * and deliberately generous, because what it guards against is not a slow run but the
     * low-memory killer taking the process and the run with it. Refusing the lane costs seconds;
     * being killed costs the whole recording's work.
     */
    fun roomForSecondRecognizer(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem - info.threshold >= SECOND_RECOGNIZER_HEADROOM_BYTES
    }
}
