package com.example.aiagenttestapp.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/** One stretch of audio attributed to one diarisation cluster, in samples. */
data class DiarizedSegment(
    val startSample: Int,
    val endSample: Int,
    val cluster: Int,
)

internal data class SpeakerDiarizationPolicy(
    val numClusters: Int,
    val threshold: Float,
    val minDurationOn: Float,
    val minDurationOff: Float,
)

/**
 * Resolves the app's speaker-count instruction into sherpa's clustering convention.
 *
 * A positive count is explicit user evidence and stays a hard instruction. Unknown counts use
 * threshold clustering. Keeping this decision pure makes it possible to pin the values that affect
 * model output without loading native models in a JVM test.
 */
internal fun speakerDiarizationPolicy(expectedSpeakers: Int) = SpeakerDiarizationPolicy(
    numClusters = expectedSpeakers.takeIf { it > 0 } ?: -1,
    threshold = if (expectedSpeakers > 0) 0f else 0.5f,
    minDurationOn = 0.2f,
    minDurationOff = 0.5f,
)

/**
 * Works out who spoke when, using pyannote segmentation plus speaker-embedding clustering.
 *
 * Diarisation answers "how many people, and which stretches belong to each" -- it does *not* know any
 * names. Naming is a second step: embed each cluster's audio and look it up among enrolled voices. The
 * two are separate because they fail separately, and a recording where nobody is recognised is still
 * far more useful split by speaker than run together.
 *
 * Held only for the length of one recording and released immediately. It carries its own copy of the
 * embedding model internally, so keeping it alive alongside the embedder used for naming would mean two
 * copies of a 29 MB model resident at once for no reason.
 */
class SpeakerDiarizer {

    private var diarization: OfflineSpeakerDiarization? = null

    val isLoaded: Boolean get() = diarization != null

    /**
     * Loads the models.
     *
     * [expectedSpeakers] fixes the cluster count. Zero or negative means "work it out". A positive
     * value is deliberately honoured whether or not enrolled voices exist: enrolment can name a
     * cluster, but it cannot recover a real speaker that clustering merged away.
     *
     * This used to claim that fixing the count was a materially better instruction than any
     * similarity threshold. Measurement said otherwise: sherpa cuts the dendrogram at exactly k
     * (`cutree_k` in `fast-clustering.cc`, which ignores [FastClusteringConfig.threshold] entirely),
     * so k is a hard budget rather than a hint. On a two-person recording it spent one of its two
     * slots on a 0.3-second fragment and handed the entire conversation to one voice. Threshold
     * clustering over-segments instead, which is the harmless direction whenever something
     * downstream can merge -- and speaker naming merges by matching each cluster to an enrolled
     * voice on its own.
     *
     * This makes the caller responsible for only passing a count it actually knows. It is not a hint:
     * sherpa cuts the dendrogram at exactly that number.
     */
    fun load(
        segmentationModel: File,
        embeddingModel: File,
        expectedSpeakers: Int = 0,
        threadCount: Int = recommendedThreadCount(),
    ) {
        release()

        val policy = speakerDiarizationPolicy(expectedSpeakers)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = segmentationModel.absolutePath,
                ),
                numThreads = threadCount,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel.absolutePath,
                numThreads = threadCount,
                debug = false,
                provider = "cpu",
            ),
            // threshold is unread when numClusters is positive; carrying both values from one policy
            // keeps the native configuration and its JVM-testable contract together.
            clustering = FastClusteringConfig(
                numClusters = policy.numClusters,
                threshold = policy.threshold,
            ),
            // Sherpa's own default, and what its Android sample uses. This used to say 0.3 was
            // the default and set it there; the default is 0.2, so the app was quietly stricter than
            // the pipeline it borrows -- which discards short speech the diariser would otherwise
            // have placed. Small clusters are dealt with after clustering instead, where the
            // decision can be made on what the fragment sounds like rather than how long it is.
            minDurationOn = policy.minDurationOn,
            minDurationOff = policy.minDurationOff,
        )

        diarization = OfflineSpeakerDiarization(assetManager = null, config = config)
        Log.i(TAG, "diarizer loaded, expectedSpeakers=$expectedSpeakers")
    }

    /**
     * Diarises a whole recording.
     *
     * Returns an empty list when the model found nothing to attribute, which the caller treats as
     * "carry on without speaker labels" rather than as an error.
     *
     * **No progress callback, and it is not an oversight.** sherpa offers `processWithCallback`, and
     * calling it aborts the process on this toolchain:
     *
     * ```
     * JNI DETECTED ERROR IN APPLICATION: JNI FindClass called with pending exception
     * java.lang.NoSuchMethodError: no non-static method
     * "SpeakerDiarizer$$ExternalSyntheticLambda0.invoke(IIJ)Ljava/lang/Integer;"
     * ```
     *
     * The native side looks the callback up by an *unerased* signature -- three primitives in, a
     * boxed `Integer` out. A Kotlin lambda used to compile to a class carrying exactly that method,
     * but D8 desugars it to a synthetic that implements only the erased
     * `invoke(Object, Object, Object): Object`, so the lookup misses. It is a `FindClass` with a
     * pending exception, which is an abort rather than a `NoSuchMethodError` anyone can catch -- the
     * recording dies with the process.
     *
     * The same lesson as the keyword spotter's null handle, one layer along: these bindings are
     * generated against assumptions the app's own build does not have to share, so anything that
     * hands sherpa a Kotlin object to call back into gets verified on a device before it is trusted.
     */
    fun diarize(samples: FloatArray): List<DiarizedSegment> {
        val active = diarization ?: return emptyList()
        if (samples.isEmpty()) return emptyList()

        val rate = AudioRecorder.SAMPLE_RATE

        return try {
            active.process(samples)
                .map { segment ->
                    DiarizedSegment(
                        // Sherpa reports seconds; clamped because rounding can land a hair past the end.
                        startSample = (segment.start * rate).toInt().coerceIn(0, samples.size),
                        endSample = (segment.end * rate).toInt().coerceIn(0, samples.size),
                        cluster = segment.speaker,
                    )
                }
                .filter { it.endSample > it.startSample }
                .sortedBy { it.startSample }
                .also { turns ->
                    // How the turns divide between clusters, which is the one thing that separates
                    // "the clustering put everything in one group" from "the clustering was fine and
                    // the alignment lost it". Forcing two speakers on a two-person recording
                    // collapsed the whole transcript onto one name, and the cluster totals say which
                    // half of the pipeline did it.
                    turns.groupBy { it.cluster }
                        .toSortedMap()
                        .forEach { (cluster, its) ->
                            val seconds = its.sumOf { it.endSample - it.startSample } /
                                AudioRecorder.SAMPLE_RATE.toDouble()
                            Log.i(
                                TAG,
                                "cluster %d: %d turns, %.1fs, first at %.1fs".format(
                                    cluster,
                                    its.size,
                                    seconds,
                                    its.first().startSample / AudioRecorder.SAMPLE_RATE.toDouble(),
                                ),
                            )
                        }
                }
        } catch (e: Exception) {
            Log.w(TAG, "diarisation failed; carrying on without speaker labels", e)
            emptyList()
        }
    }

    fun release() {
        runCatching { diarization?.release() }
            .onFailure { Log.w(TAG, "releasing the diarizer failed", it) }
        diarization = null
    }

    private fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)

    private companion object {
        const val TAG = "SpeakerDiarizer"
    }
}
