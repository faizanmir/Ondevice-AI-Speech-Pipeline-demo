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
     * [expectedSpeakers] is the user's answer to "how many people are in this recording". When they
     * know, fixing the cluster count is a materially better instruction than any similarity threshold:
     * threshold-based clustering has to guess how many groups exist, and on short or noisy recordings it
     * habitually splits one person in two. Zero or negative means "work it out".
     */
    fun load(
        segmentationModel: File,
        embeddingModel: File,
        expectedSpeakers: Int = 0,
        threadCount: Int = recommendedThreadCount(),
    ) {
        release()

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
            clustering = if (expectedSpeakers > 0) {
                FastClusteringConfig(numClusters = expectedSpeakers, threshold = 0f)
            } else {
                // sherpa's own default. Only consulted when the cluster count is unknown.
                FastClusteringConfig(numClusters = -1, threshold = 0.5f)
            },
            // Sub-200 ms of speech or silence is not a turn, it is a breath.
            minDurationOn = 0.2f,
            minDurationOff = 0.5f,
        )

        diarization = OfflineSpeakerDiarization(assetManager = null, config = config)
        Log.i(TAG, "diarizer loaded, expectedSpeakers=$expectedSpeakers")
    }

    /**
     * Diarises a whole recording, reporting progress in 0..1.
     *
     * Returns an empty list when the model found nothing to attribute, which the caller treats as
     * "carry on without speaker labels" rather than as an error.
     */
    fun diarize(
        samples: FloatArray,
        onProgress: ((Float) -> Unit)? = null,
    ): List<DiarizedSegment> {
        val active = diarization ?: return emptyList()
        if (samples.isEmpty()) return emptyList()

        val rate = AudioRecorder.SAMPLE_RATE

        return try {
            val segments = if (onProgress != null) {
                active.processWithCallback(
                    samples = samples,
                    callback = { processed, total, _ ->
                        if (total > 0) onProgress(processed.toFloat() / total)
                        0 // non-zero would ask sherpa to stop
                    },
                    arg = 0,
                )
            } else {
                active.process(samples)
            }

            segments
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
