package com.example.aiagent.engine.core

/**
 * Enforces the two generation bounds that no on-device runtime exposes uniformly -- a hard cap on
 * the number of output tokens, and stop strings that end the reply -- so every engine behaves the
 * same regardless of what its native sampler happens to support.
 *
 * An engine feeds each decoded chunk to [push] and emits whatever it returns; once [isDone] is true
 * it stops decoding. Stop strings are matched across chunk boundaries, so the guard holds back the
 * final few characters of the stream (up to the longest stop string minus one) until it has seen
 * enough to rule out a match. [drain] releases that held-back tail when the engine ends on its own.
 *
 * A chunk counts as one token for the cap. That is exact for the pull-loop engines (one token per
 * [push]) and an approximation for the streamed-chunk engines -- the same approximation their token
 * counts already make.
 */
class OutputGuard(
    private val maxTokens: Int,
    stopSequences: List<String>,
) {
    private val stops = stopSequences.filter { it.isNotEmpty() }
    private val holdBack = (stops.maxOfOrNull { it.length } ?: 1) - 1
    private val held = StringBuilder()
    private var tokens = 0
    private var finished = false

    /** True once a stop string was hit or the token cap was reached; the engine must then stop. */
    val isDone: Boolean get() = finished

    /** Feeds one decoded chunk and returns the text to emit for it (may be empty, or trimmed). */
    fun push(chunk: String): String {
        if (finished) return ""
        tokens++
        val capHit = maxTokens in 1..tokens

        if (stops.isEmpty()) {
            if (capHit) finished = true
            return chunk
        }

        held.append(chunk)

        val cut = earliestStop()
        if (cut >= 0) {
            val out = held.substring(0, cut)
            held.setLength(0)
            finished = true
            return out
        }

        if (capHit) {
            val out = held.toString()
            held.setLength(0)
            finished = true
            return out
        }

        // Emit everything except the tail that could still be the start of a stop string next chunk.
        val safe = (held.length - holdBack).coerceAtLeast(0)
        val out = held.substring(0, safe)
        held.delete(0, safe)
        return out
    }

    /** The held-back tail, released when the engine stops on its own (end-of-sequence). */
    fun drain(): String {
        if (finished) return ""
        val out = held.toString()
        held.setLength(0)
        return out
    }

    /** Index of the earliest stop string in [held], or -1 when none is present. */
    private fun earliestStop(): Int {
        var best = -1
        for (s in stops) {
            val i = held.indexOf(s)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }
}
