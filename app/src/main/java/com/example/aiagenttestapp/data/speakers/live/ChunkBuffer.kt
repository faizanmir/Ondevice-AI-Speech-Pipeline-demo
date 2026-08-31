package com.example.aiagenttestapp.data.speakers.live

/**
 * The samples of the chunk being built, kept only from where that chunk starts.
 *
 * The batch worker holds the whole recording in memory because clustering needs it. A live session
 * never needs more than the chunk in progress -- at most [capacity] samples plus one block -- so
 * memory is bounded however long the session runs. Positions are in **recording samples**; the
 * buffer keeps track of where in the recording its first sample sits.
 *
 * Two ways to read, and the difference is the bug this class exists to pin down: [peek] copies a
 * range out and leaves the buffer as it was; [take] copies the range out and **drops everything
 * before its end**, because that audio now belongs to a chunk that has been handed on. The cap path
 * needs to look at the audio to find a quiet frame *and then* take the chunk up to that frame -- two
 * reads of overlapping ranges. Using [take] for the first of them shifted the buffer's start past
 * the chunk's start, and the second read indexed 45 seconds before the array began.
 */
class ChunkBuffer(capacity: Int) {
    private var start = 0
    private var data = FloatArray(capacity)
    private var length = 0

    /** The recording position of the first buffered sample. */
    val startSample: Int get() = start

    /** The recording position just past the last buffered sample. */
    val endSample: Int get() = start + length

    fun append(at: Int, block: FloatArray) {
        if (length == 0) start = at
        require(at == start + length) { "block at $at does not continue the buffer, which ends at ${start + length}" }
        if (length + block.size > data.size) data = data.copyOf((length + block.size) * 2)
        block.copyInto(data, length)
        length += block.size
    }

    /** A copy of `[from, until)` in recording samples; the buffer is unchanged. */
    fun peek(from: Int, until: Int): FloatArray {
        check(from >= start && until <= start + length) {
            "peek [$from, $until) is outside the buffer [$start, ${start + length})"
        }
        return data.copyOfRange(from - start, until - start)
    }

    /** A copy of `[from, until)` in recording samples; everything before [until] is then dropped. */
    fun take(from: Int, until: Int): FloatArray {
        val out = peek(from, until)
        val keep = length - (until - start)
        if (keep > 0) data.copyInto(data, 0, until - start, length)
        length = keep.coerceAtLeast(0)
        start = until
        return out
    }
}
