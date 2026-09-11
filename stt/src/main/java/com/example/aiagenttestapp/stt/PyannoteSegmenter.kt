package com.example.aiagenttestapp.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs pyannote segmentation-3.0 directly, keeping the per-frame output sherpa throws away.
 *
 * ## Why this exists rather than calling sherpa
 *
 * sherpa runs this same model inside `OfflineSpeakerDiarization`, and its Java bindings expose only
 * that one sealed entry point: audio in, finished segments out. The model's actual output is richer
 * and is discarded on the way. For every frame -- one per ~17 ms -- it emits a distribution over
 * seven **powerset** classes, which are the possible *combinations* of up to three window-local
 * speakers:
 *
 * ```
 *   index 0 = {}        nobody
 *   index 1 = {0}       speaker 0 alone        index 4 = {0,1}   two at once
 *   index 2 = {1}       speaker 1 alone        index 5 = {0,2}
 *   index 3 = {2}       speaker 2 alone        index 6 = {1,2}
 * ```
 *
 * Three things downstream need what survives only if that distribution is kept:
 *
 *  - **[FrameTrack.act]** -- a per-speaker activity curve, the summed probability mass of every
 *    class containing that speaker. Overlapping speech is a *value* here, not something to be
 *    inferred later from segments that no longer overlap.
 *  - **[FrameTrack.margin]** -- top-class probability minus second. The model's own confidence,
 *    which is what lets word attribution weight a frame by how sure the model was rather than
 *    counting every frame the same.
 *  - **[FrameTrack.cls]** -- the argmax per frame, which is all sherpa keeps and all [segments]
 *    needs. Reproduced here so both paths cut turns the same way.
 *
 * ## What "window-local" means, and why it is not a limitation
 *
 * The model attends to one window at a time and numbers the speakers it hears *within that window*:
 * speaker 0 in one window has no relationship to speaker 0 in the next. That is why this class stops
 * at segments and never mentions identity -- turning window-local indices into people is clustering's
 * job, and it happens over embeddings in [SpeakerClustering], not here.
 *
 * Verified against sherpa's own segment output on the same audio: boundaries agree to within roughly
 * one frame (17 ms), which is the check that this reimplements the pipeline rather than diverging
 * from it.
 *
 * Not thread-safe: one instance runs one window at a time. Diarisation lanes each hold their own.
 */
class PyannoteSegmenter(
    model: File,
    threadCount: Int = 2,
) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = env.createSession(
        model.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(threadCount) },
    )

    /**
     * One window's decoded frames.
     *
     * @property cls per-frame argmax powerset class; index into [POWERSET].
     * @property margin per-frame top-minus-second probability, in 0..1. The model's confidence.
     * @property act `act[speaker][frame]`: summed probability of every class containing that
     *   window-local speaker. This is the multilabel view, and the only one that can show two people
     *   talking at once.
     * @property hopSec seconds of audio per frame, derived from the window rather than assumed --
     *   the model's stride is a property of the checkpoint.
     */
    class FrameTrack(
        val cls: IntArray,
        val margin: FloatArray,
        val act: Array<FloatArray>,
        val hopSec: Float,
    )

    /** A stretch where one window-local speaker was active, in seconds from the window's start. */
    data class LocalSegment(val startSec: Float, val endSec: Float, val local: Int)

    /**
     * Runs the model over one window and decodes every frame.
     *
     * Softmax is computed here rather than taken from the model because the checkpoint emits
     * logits, and it is done in the max-subtracted form so a confident frame cannot overflow
     * `exp` -- the values reaching this are unbounded.
     */
    fun frames(samples: FloatArray): FrameTrack {
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(samples),
            longArrayOf(1, 1, samples.size.toLong()),
        ).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                val frameCount = logits.size
                val cls = IntArray(frameCount)
                val margin = FloatArray(frameCount)
                val act = Array(SPEAKERS_PER_WINDOW) { FloatArray(frameCount) }

                for (frame in 0 until frameCount) {
                    val row = logits[frame]

                    var max = row[0]
                    for (v in row) if (v > max) max = v

                    var total = 0f
                    val exp = FloatArray(row.size)
                    for (i in row.indices) {
                        exp[i] = kotlin.math.exp((row[i] - max).toDouble()).toFloat()
                        total += exp[i]
                    }

                    // Top and second in the same pass that accumulates the activity curves, so a
                    // frame is walked once. `best`/`second` are probabilities, `bestIndex` the class.
                    var best = -1f
                    var second = -1f
                    var bestIndex = 0
                    for (i in exp.indices) {
                        val p = exp[i] / total
                        if (p > best) {
                            second = best
                            best = p
                            bestIndex = i
                        } else if (p > second) {
                            second = p
                        }
                        for (speaker in POWERSET[i]) act[speaker][frame] += p
                    }

                    cls[frame] = bestIndex
                    margin[frame] = best - second
                }

                return FrameTrack(
                    cls = cls,
                    margin = margin,
                    act = act,
                    hopSec = samples.size / frameCount.toFloat() / AudioRecorder.SAMPLE_RATE,
                )
            }
        }
    }

    /**
     * Turns decoded frames into per-speaker segments, with sherpa's own smoothing.
     *
     * Two passes that matter and are easy to get backwards: gaps shorter than [minOffSec] are
     * *closed* first, then runs shorter than [minOnSec] are dropped. Closing first is what stops a
     * single uncertain frame mid-sentence from splitting one turn into two that are then both thrown
     * away for being short.
     *
     * The defaults match what this app already passes sherpa (`minDurationOn` 0.2 / `minDurationOff`
     * 0.5) rather than pyannote's published numbers, so switching engines does not silently change
     * how short a turn has to be to survive.
     */
    fun segments(
        track: FrameTrack,
        minOnSec: Float = 0.2f,
        minOffSec: Float = 0.5f,
    ): List<LocalSegment> {
        val frameCount = track.cls.size
        val out = ArrayList<LocalSegment>()

        for (speaker in 0 until SPEAKERS_PER_WINDOW) {
            val runs = ArrayList<FloatArray>()
            var onAt = -1
            for (frame in 0 until frameCount) {
                val active = POWERSET[track.cls[frame]].contains(speaker)
                if (active && onAt < 0) onAt = frame
                if (!active && onAt >= 0) {
                    runs += floatArrayOf(onAt * track.hopSec, frame * track.hopSec)
                    onAt = -1
                }
            }
            if (onAt >= 0) runs += floatArrayOf(onAt * track.hopSec, frameCount * track.hopSec)

            val merged = ArrayList<FloatArray>()
            var current: FloatArray? = null
            for (run in runs) {
                val open = current
                if (open != null && run[0] - open[1] < minOffSec) {
                    open[1] = maxOf(open[1], run[1])
                } else {
                    if (open != null) merged += open
                    current = run.copyOf()
                }
            }
            current?.let { merged += it }

            for (m in merged) {
                if (m[1] - m[0] >= minOnSec) out += LocalSegment(m[0], m[1], speaker)
            }
        }

        return out.sortedBy { it.startSec }
    }

    fun release() {
        runCatching { session.close() }
    }

    companion object {

        /**
         * The powerset decoding, in the checkpoint's own class order.
         *
         * Not a convention this app is free to choose -- it is baked into the exported model's final
         * layer, and reordering it silently attributes speech to the wrong window-local speaker.
         */
        val POWERSET: Array<IntArray> = arrayOf(
            intArrayOf(),
            intArrayOf(0),
            intArrayOf(1),
            intArrayOf(2),
            intArrayOf(0, 1),
            intArrayOf(0, 2),
            intArrayOf(1, 2),
        )

        /** Speakers the model can distinguish inside one window. A property of the checkpoint. */
        const val SPEAKERS_PER_WINDOW = 3

        /** The exported graph's single input. */
        private const val INPUT_NAME = "x"
    }
}
