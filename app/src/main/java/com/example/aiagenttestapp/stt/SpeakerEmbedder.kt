package com.example.aiagenttestapp.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import java.io.File
import kotlin.math.sqrt

/**
 * Turns speech into a voiceprint, and matches voiceprints against enrolled people.
 *
 * Two sherpa objects that are useless apart: the *extractor* is the model that produces a vector from
 * audio, and the *manager* is an in-memory index of enrolled vectors that can be searched. The manager
 * has no persistence of its own -- it is rebuilt from Room on demand, which is why `SpeakerRepository`
 * owns one of these rather than the other way round.
 */
class SpeakerEmbedder {

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var manager: SpeakerEmbeddingManager? = null

    val isLoaded: Boolean get() = extractor != null

    /** Vector length the loaded model produces. Read from the model, never hardcoded. */
    val dim: Int get() = extractor?.dim() ?: 0

    /** Loads the embedding model. Tens of megabytes; never call from the main thread. */
    fun load(model: File, threadCount: Int = recommendedThreadCount()) {
        release()

        val created = SpeakerEmbeddingExtractor(
            assetManager = null,
            config = SpeakerEmbeddingExtractorConfig(
                model = model.absolutePath,
                numThreads = threadCount,
                debug = false,
                provider = "cpu",
            ),
        )

        extractor = created
        manager = SpeakerEmbeddingManager(created.dim())
        Log.i(TAG, "speaker embedder loaded, dim=${created.dim()}")
    }

    /**
     * Computes a voiceprint for [samples], or null if the model could not produce one.
     *
     * A null means the audio was too short or too quiet for the model to commit -- which is a real
     * answer, not an error, and callers treat it as "this take is unusable" rather than a failure.
     */
    fun embed(samples: FloatArray): FloatArray? {
        val active = extractor ?: return null
        if (samples.isEmpty()) return null

        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, AudioRecorder.SAMPLE_RATE)
            // Without this the extractor keeps waiting for more audio and never becomes ready.
            stream.inputFinished()
            if (active.isReady(stream)) active.compute(stream) else null
        } catch (e: Exception) {
            Log.w(TAG, "embedding failed", e)
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    /** Replaces the search index with exactly these people. */
    fun setEnrolled(enrolled: Map<String, List<FloatArray>>) {
        val active = manager ?: return

        active.allSpeakerNames().forEach { runCatching { active.remove(it) } }

        enrolled.forEach { (name, embeddings) ->
            val usable = embeddings.filter { it.size == active.dim }
            if (usable.isEmpty()) return@forEach
            runCatching { active.add(name, usable.toTypedArray()) }
                .onFailure { Log.w(TAG, "could not enrol $name", it) }
        }
    }

    /**
     * The enrolled name whose voiceprint [embedding] matches above [threshold], or null.
     *
     * Null is a perfectly good outcome and the caller shows "Speaker 2" for it. The threshold is biased
     * high on purpose: putting the wrong person's name against something they did not say is worse, in a
     * record someone may rely on, than declining to name them at all.
     */
    fun search(embedding: FloatArray, threshold: Float): String? {
        val active = manager ?: return null
        if (embedding.size != active.dim) return null

        return runCatching { active.search(embedding, threshold) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun release() {
        runCatching { manager?.release() }
        runCatching { extractor?.release() }
            .onFailure { Log.w(TAG, "releasing the speaker embedder failed", it) }
        manager = null
        extractor = null
    }

    /** Same big.LITTLE reasoning as the recogniser: saturating every core helps nothing. */
    private fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)

    private companion object {
        const val TAG = "SpeakerEmbedder"
    }
}

/**
 * Cosine similarity between two voiceprints, in -1..1.
 *
 * Used for the checks the sherpa manager cannot do: whether a person's own enrolment takes agree with
 * each other, and whether a new enrolment collides with somebody already on file. The manager only
 * answers "does this match a *stored* speaker", which is the wrong question during enrolment, when
 * nothing has been stored yet.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || a.size != b.size) return 0f

    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += (a[i] * b[i]).toDouble()
        normA += (a[i] * a[i]).toDouble()
        normB += (b[i] * b[i]).toDouble()
    }

    if (normA == 0.0 || normB == 0.0) return 0f
    return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
}

/** Element-wise mean of several voiceprints, for representing a cluster or a person by one vector. */
fun averageEmbedding(embeddings: List<FloatArray>): FloatArray? {
    val usable = embeddings.filter { it.isNotEmpty() }
    if (usable.isEmpty()) return null

    val dim = usable.first().size
    if (usable.any { it.size != dim }) return null

    val sum = FloatArray(dim)
    usable.forEach { embedding ->
        for (i in 0 until dim) sum[i] += embedding[i]
    }
    for (i in 0 until dim) sum[i] /= usable.size
    return sum
}
