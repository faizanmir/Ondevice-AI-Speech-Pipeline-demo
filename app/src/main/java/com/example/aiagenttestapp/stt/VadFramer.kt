package com.example.aiagenttestapp.stt

/**
 * Reassembles arbitrary-size audio chunks into the fixed-size frames Silero requires.
 *
 * The capture loop emits ~1600-sample chunks and Silero's frame is 512 samples -- not a multiple,
 * so a straight hand-off would either feed the model a wrong-size window or drop the 64-sample
 * remainder of every chunk. Dropping is the insidious one: Silero's running position *is* the
 * recording's position (that identity is what makes its regions line up with the checkpoint), and a
 * position that drifts 64 samples per chunk has every region boundary wrong within seconds, with
 * nothing anywhere failing. The keyword spotter beside this never has the problem because sherpa's
 * online stream buffers internally; the Silero binding does not, so this does.
 *
 * Pure arithmetic over arrays, kept out of [SpeechActivityDetector] so a JVM test can pin the
 * reassembly without the native model.
 */
internal class VadFramer(private val frameSamples: Int) {

    private val carry = FloatArray(frameSamples)
    private var carried = 0

    /**
     * Feeds [chunk], emitting every complete frame it fills, in order. Each emitted frame is a
     * fresh copy -- the carry buffer is reused the moment the callback returns.
     */
    fun accept(chunk: FloatArray, emit: (FloatArray) -> Unit) {
        var index = 0
        while (index < chunk.size) {
            val take = minOf(frameSamples - carried, chunk.size - index)
            chunk.copyInto(
                carry,
                destinationOffset = carried,
                startIndex = index,
                endIndex = index + take,
            )
            carried += take
            index += take

            if (carried == frameSamples) {
                emit(carry.copyOf())
                carried = 0
            }
        }
    }

    /**
     * The final partial frame, or null when the chunks divided evenly. Resets the framer, so an
     * instance can be reused for the next stream.
     */
    fun flush(): FloatArray? {
        if (carried == 0) return null
        val tail = carry.copyOf(carried)
        carried = 0
        return tail
    }
}
