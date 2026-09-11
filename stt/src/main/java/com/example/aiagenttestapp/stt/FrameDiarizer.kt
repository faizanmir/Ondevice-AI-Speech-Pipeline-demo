package com.example.aiagenttestapp.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Diarisation built on the segmentation model's frame posteriors rather than on sherpa's segments.
 *
 * Same two models as [SpeakerDiarizer]'s sherpa path and the same answer shape -- stretches of audio
 * with a cluster number on each -- but the three stages between them are this app's rather than one
 * sealed native call, which is what makes the extras possible. See
 * [com.example.aiagenttestapp.data.DiarizationEngine] for why that is worth a second ONNX Runtime.
 *
 * ## The run
 *
 * 1. **Window.** The recording is walked in [WINDOW_SEC] windows, which is what pyannote-3.0 was
 *    trained on. Each is decoded to a [PyannoteSegmenter.FrameTrack] -- kept, not reduced -- and
 *    contributes its overlap regions.
 * 2. **Embed.** One voiceprint per segment. Segments long enough to hide a missed hand-over also get
 *    sliding sub-window voiceprints, taken *here* rather than later, so resegmentation at the end is
 *    a millisecond Viterbi over vectors that already exist instead of a second pass over the audio.
 * 3. **Cluster and repair.** [SpeakerClustering], in the order that file documents.
 *
 * ## Why the extras are computed even though nothing is forced to use them
 *
 * [overlapRegions] and [wordLabel] are populated by every run and read by callers that want them.
 * They cost nothing extra: the overlap regions fall out of posteriors already in memory, and
 * [wordLabel] is a lookup over the frame tracks the run kept anyway. A caller on the sherpa engine
 * gets an empty region list and an unattributed answer, which is the same shape rather than an
 * error, so nothing downstream needs to know which engine ran.
 *
 * Not thread-safe, and one instance holds one recording's state: diarisation lanes each build their
 * own, exactly as they do for the sherpa path.
 */
class FrameDiarizer(
    segmentationModel: File,
    embeddingModel: File,
    threadCount: Int,
    /**
     * Cosine **distance** at which agglomerative merging stops -- 1 - similarity, so a larger number
     * merges more freely. Not a similarity floor, and the two are easy to confuse.
     *
     * Calibrated per embedding model, because they do not share a scale: see [thresholdFor].
     */
    private val clusterThreshold: Float,
    /** A positive count is honoured as a hard k. Zero or less means work it out. */
    private val expectedSpeakers: Int,
) {

    private val segmenter = PyannoteSegmenter(segmentationModel, threadCount)

    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = null,
        config = SpeakerEmbeddingExtractorConfig(
            model = embeddingModel.absolutePath,
            numThreads = threadCount,
            debug = false,
            // Deliberately not taking the Settings provider. This extractor is fed by an ORT session
            // this app owns, and the point of the provider setting is to compare sherpa's own
            // sessions; running half of one diarisation on a different provider would make its
            // timing describe two configurations. The segmenter's threads are the knob here.
            provider = "cpu",
        ),
    )

    /** One decoded window, kept so [wordLabel] can read frame mass back out after the run. */
    private class Window(
        val startSec: Float,
        val track: PyannoteSegmenter.FrameTrack,
        /** window-local speaker -> indices into [segments] */
        val byLocal: HashMap<Int, ArrayList<Int>> = HashMap(),
    )

    private class Segment(
        val startSec: Float,
        val endSec: Float,
        val embedding: FloatArray,
        val windowIndex: Int,
        val local: Int,
        val subStartsSec: FloatArray,
        val subEmbeddings: List<FloatArray>,
    )

    private val windows = ArrayList<Window>()
    private val segments = ArrayList<Segment>()
    private var labels = IntArray(0)

    /**
     * Segments resegmentation split, by segment index. A segment is here only when the voice inside
     * it changed -- the uncommon case.
     */
    private val splits = HashMap<Int, List<SpeakerClustering.Piece>>()

    /** Stretches where the model heard two people at once. Empty until a run finishes. */
    var overlapRegions: List<OverlapDetector.Region> = emptyList()
        private set

    /**
     * Per-frame confidence for the audio of the last run, in that audio's own coordinates.
     *
     * Flattened from the decoded windows into one array so the run's state can be released and
     * reused -- a lane's diarizer goes back into the warm pool as soon as its chunk is done, long
     * before the words it has to be weighed against exist. Empty until a run finishes.
     *
     * The caller offsets this into recording time and merges the chunks; see [FrameConfidence].
     */
    var frameWeights: FloatArray = FloatArray(0)
        private set

    /** Audio samples one frame of [frameWeights] covers. Zero until a run finishes. */
    var samplesPerFrame: Int = 0
        private set

    /** Unit-length centroid per final cluster, reusable for naming without embedding anything again. */
    var centroids: Map<Int, FloatArray> = emptyMap()
        private set

    /**
     * Diarises a whole recording.
     *
     * Returns an empty list when there was nothing to attribute, which callers treat as "carry on
     * without speaker labels" rather than as an error -- the same contract the sherpa path has.
     */
    fun diarize(samples: FloatArray): List<DiarizedSegment> {
        reset()
        if (samples.isEmpty()) return emptyList()

        val startedAt = System.currentTimeMillis()
        val totalSec = samples.size / AudioRecorder.SAMPLE_RATE.toFloat()
        val overlaps = ArrayList<OverlapDetector.Region>()

        var processedSec = 0
        while (totalSec - processedSec > MIN_WINDOW_SEC) {
            val endSec = minOf(processedSec + WINDOW_SEC, ceil(totalSec).toInt())
            step(samples, processedSec, endSec, overlaps)
            processedSec = endSec
        }
        overlapRegions = overlaps
        flattenFrameWeights(samples.size)

        if (segments.isEmpty()) {
            Log.i(TAG, "no speech segments found; carrying on without speaker labels")
            return emptyList()
        }

        val embeddings = segments.map { it.embedding }
        val durations = FloatArray(segments.size) { segments[it].endSec - segments[it].startSec }

        labels = if (expectedSpeakers > 0) {
            SpeakerClustering.kMeans(embeddings, expectedSpeakers)
        } else {
            SpeakerClustering.agglomerate(embeddings, clusterThreshold)
        }

        // The repair passes only run when the count was ours to decide. A caller that stated the
        // count has asserted how many people are in the room, and absorbing or folding a cluster
        // would quietly overrule that -- the sherpa path honours a stated count the same way.
        var absorbed = 0
        var folded = 0
        var merged = 0
        if (expectedSpeakers <= 0) {
            absorbed = SpeakerClustering.absorbCrumbs(embeddings, labels, durations)
            folded = SpeakerClustering.foldPhantoms(embeddings, labels, durations)
            merged = SpeakerClustering.mergeLookalikes(embeddings, labels) { from, into, sim ->
                Log.i(TAG, "same voice: cluster %d folded into %d at cosine %.3f".format(from, into, sim))
            }
        }

        val order = segments.indices.sortedBy { segments[it].startSec }
        val startsSec = FloatArray(segments.size) { segments[it].startSec }
        val endsSec = FloatArray(segments.size) { segments[it].endSec }
        val flips = SpeakerClustering.smoothTemporally(embeddings, labels, order, startsSec, endsSec)

        val majors = labels.toSortedSet().toList()
        centroids = SpeakerClustering.centroids(embeddings, labels, majors)
        val splitCount = resegmentAll(majors)

        val turns = buildTurns(order, samples.size)

        Log.i(
            TAG,
            ("%.1fs of audio: %d segments -> %d turns, %d clusters " +
                "(absorbed %d, folded %d, same-voice merges %d, smoothed %d, %d splits), " +
                "%d overlap regions, %d ms").format(
                totalSec, segments.size, turns.size, labels.toSet().size,
                absorbed, folded, merged, flips, splitCount,
                overlapRegions.size, System.currentTimeMillis() - startedAt,
            ),
        )
        return turns
    }

    /**
     * Which cluster holds the most frame mass across `[startMs, endMs)`, and how clearly.
     *
     * This is the attribution [com.example.aiagenttestapp.data.speakers.SpeakerAlignment] cannot do
     * from segments: rather than asking which turn a word's start timestamp lands in -- one instant,
     * one answer, no way to tell a confident placement from a coin flip -- it sums every frame the
     * word spans, weighting each by the model's own confidence at that frame. A word straddling a
     * hand-over goes to whoever actually holds more of it.
     *
     * @return cluster and a confidence in 0..1 (the normalised top-versus-second mass margin), or
     *   `-1 to 0f` when the span holds no attributable speech.
     */
    fun wordLabel(startMs: Long, endMs: Long): Pair<Int, Float> {
        if (windows.isEmpty()) return NOT_ATTRIBUTED

        val fromSec = startMs / 1000f
        val toSec = maxOf(endMs / 1000f, fromSec + MIN_SPAN_SEC)
        val mass = HashMap<Int, Float>()

        for (window in windows) {
            val windowEnd = window.startSec + window.track.cls.size * window.track.hopSec
            if (toSec <= window.startSec || fromSec >= windowEnd) continue

            val first = maxOf(0, ((fromSec - window.startSec) / window.track.hopSec).toInt())
            val last = minOf(
                window.track.cls.size - 1,
                ((toSec - window.startSec) / window.track.hopSec).toInt(),
            )
            for (frame in first..last) {
                val at = window.startSec + frame * window.track.hopSec
                for (local in PyannoteSegmenter.POWERSET[window.track.cls[frame]]) {
                    val cluster = clusterAt(window, local, at) ?: continue
                    // Floored rather than raw: a frame the model was unsure about still says
                    // somebody was talking, and dropping it to zero would let a run of hesitant
                    // frames count for nothing at all.
                    mass[cluster] = (mass[cluster] ?: 0f) +
                        window.track.margin[frame].coerceAtLeast(MIN_FRAME_WEIGHT)
                }
            }
        }
        if (mass.isEmpty()) return NOT_ATTRIBUTED

        var top = -1
        var best = -1f
        var second = 0f
        for ((cluster, m) in mass) {
            if (m > best) {
                second = best
                best = m
                top = cluster
            } else if (m > second) {
                second = m
            }
        }
        if (second < 0f) second = 0f
        return top to ((best - second) / (best + second + 1e-6f)).coerceIn(0f, 1f)
    }

    fun release() {
        segmenter.release()
        runCatching { extractor.release() }
            .onFailure { Log.w(TAG, "releasing the embedding extractor failed", it) }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Lays the decoded windows' confidence margins end to end over the whole clip.
     *
     * The windows do not overlap and are decoded in order, so this is a copy rather than a merge --
     * but it is done by *time* rather than by appending, because a window that produced no frames
     * (too short to decode) must leave a hole where it was instead of pulling everything after it
     * earlier.
     */
    private fun flattenFrameWeights(sampleCount: Int) {
        val first = windows.firstOrNull()
        if (first == null || first.track.hopSec <= 0f) {
            frameWeights = FloatArray(0)
            samplesPerFrame = 0
            return
        }
        val perFrame = (first.track.hopSec * AudioRecorder.SAMPLE_RATE).toInt().coerceAtLeast(1)
        val out = FloatArray(sampleCount / perFrame + 1)
        for (window in windows) {
            val offset = (window.startSec * AudioRecorder.SAMPLE_RATE).toInt() / perFrame
            for (frame in window.track.margin.indices) {
                val at = offset + frame
                if (at in out.indices) {
                    // Floored the same way word attribution floors it: a frame the model was unsure
                    // about still says somebody was talking, and zero would let a run of hesitant
                    // frames count for nothing at all.
                    out[at] = window.track.margin[frame].coerceAtLeast(MIN_FRAME_WEIGHT)
                }
            }
        }
        frameWeights = out
        samplesPerFrame = perFrame
    }

    private fun reset() {
        windows.clear()
        segments.clear()
        splits.clear()
        labels = IntArray(0)
        overlapRegions = emptyList()
        centroids = emptyMap()
        frameWeights = FloatArray(0)
        samplesPerFrame = 0
    }

    private fun step(
        samples: FloatArray,
        fromSec: Int,
        toSec: Int,
        overlaps: ArrayList<OverlapDetector.Region>,
    ) {
        val rate = AudioRecorder.SAMPLE_RATE
        val from = fromSec * rate
        val to = minOf(toSec * rate, samples.size)
        if (to - from < (rate * MIN_WINDOW_SEC).toInt()) return

        val track = segmenter.frames(samples.copyOfRange(from, to))
        OverlapDetector.accumulate(overlaps, OverlapDetector.detect(track, fromSec.toFloat()))

        val window = Window(fromSec.toFloat(), track)
        windows += window
        val windowIndex = windows.size - 1

        for (local in segmenter.segments(track)) {
            val startSec = fromSec + local.startSec
            val endSec = fromSec + local.endSec
            val embedding = embed(samples, startSec, endSec) ?: continue

            var subStarts = FloatArray(0)
            var subEmbeddings: List<FloatArray> = emptyList()
            if (endSec - startSec >= RESEG_MIN_SEGMENT_SEC) {
                val starts = ArrayList<Float>()
                var at = startSec
                while (at + RESEG_SUBWINDOW_SEC <= endSec + 0.01f) {
                    starts += at
                    at += RESEG_HOP_SEC
                }
                if (starts.size >= 2) {
                    val vectors = starts.mapNotNull { embed(samples, it, it + RESEG_SUBWINDOW_SEC) }
                    if (vectors.size == starts.size) {
                        subStarts = starts.toFloatArray()
                        subEmbeddings = vectors
                    }
                }
            }

            segments += Segment(
                startSec = startSec,
                endSec = endSec,
                embedding = embedding,
                windowIndex = windowIndex,
                local = local.local,
                subStartsSec = subStarts,
                subEmbeddings = subEmbeddings,
            )
            window.byLocal.getOrPut(local.local) { ArrayList() } += segments.size - 1
        }
    }

    /** Unit-length voiceprint for a stretch, or null when the model would not commit to one. */
    private fun embed(samples: FloatArray, startSec: Float, endSec: Float): FloatArray? {
        val rate = AudioRecorder.SAMPLE_RATE
        val from = (startSec * rate).toInt().coerceIn(0, samples.size)
        val to = (endSec * rate).toInt().coerceIn(from, samples.size)
        if (to - from < (rate * MIN_EMBED_SEC).toInt()) return null

        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples.copyOfRange(from, to), rate)
            stream.inputFinished()
            val vector = extractor.compute(stream)
            if (vector.isEmpty()) return null

            var norm = 0f
            for (v in vector) norm += v * v
            norm = sqrt(norm)
            if (norm <= 0f) return null
            for (i in vector.indices) vector[i] /= norm
            vector
        } catch (e: Exception) {
            Log.w(TAG, "embedding a segment failed; it will not be clustered", e)
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun resegmentAll(majors: List<Int>): Int {
        splits.clear()
        if (majors.size < 2) return 0
        var count = 0
        for (i in segments.indices) {
            val segment = segments[i]
            if (segment.subEmbeddings.size < 2) continue
            val pieces = SpeakerClustering.resegment(
                subEmbeddings = segment.subEmbeddings,
                subStartsSec = segment.subStartsSec,
                segmentStartSec = segment.startSec,
                segmentEndSec = segment.endSec,
                centres = centroids,
                majors = majors,
                subWindowSec = RESEG_SUBWINDOW_SEC,
                hopSec = RESEG_HOP_SEC,
            )
            if (pieces.isEmpty()) continue
            splits[i] = pieces
            count += pieces.size - 1
        }
        return count
    }

    /** Time-ordered turns, joining consecutive stretches of one speaker across a short gap. */
    private fun buildTurns(order: List<Int>, sampleCount: Int): List<DiarizedSegment> {
        val rate = AudioRecorder.SAMPLE_RATE
        val turns = ArrayList<DiarizedSegment>()

        fun push(startSec: Float, endSec: Float, cluster: Int) {
            val startSample = (startSec * rate).toInt().coerceIn(0, sampleCount)
            val endSample = (endSec * rate).toInt().coerceIn(0, sampleCount)
            if (endSample <= startSample) return

            val last = turns.lastOrNull()
            if (last != null &&
                last.cluster == cluster &&
                startSample - last.endSample <= (JOIN_GAP_SEC * rate).toInt()
            ) {
                turns[turns.size - 1] = last.copy(endSample = maxOf(last.endSample, endSample))
            } else {
                turns += DiarizedSegment(startSample, endSample, cluster)
            }
        }

        for (i in order) {
            val pieces = splits[i]
            if (pieces != null) {
                for (piece in pieces) push(piece.startSec, piece.endSec, piece.label)
            } else {
                push(segments[i].startSec, segments[i].endSec, labels[i])
            }
        }
        return turns
    }

    /** The cluster a window-local speaker belongs to at [at], following any resegmentation split. */
    private fun clusterAt(window: Window, local: Int, at: Float): Int? {
        val candidates = window.byLocal[local] ?: return null
        var nearest: Int? = null
        var nearestDistance = NEAR_SEGMENT_SEC
        for (i in candidates) {
            val segment = segments[i]
            if (at >= segment.startSec && at <= segment.endSec) return labelAt(i, at)
            val distance = if (at < segment.startSec) segment.startSec - at else at - segment.endSec
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = labelAt(i, at)
            }
        }
        return nearest
    }

    private fun labelAt(index: Int, at: Float): Int {
        val pieces = splits[index] ?: return labels[index]
        for (piece in pieces) if (at >= piece.startSec && at <= piece.endSec) return piece.label
        return if (at < pieces.first().startSec) pieces.first().label else pieces.last().label
    }

    companion object {

        private const val TAG = "FrameDiarizer"

        /** What pyannote-3.0 was trained on. Windows do not overlap: the clustering that follows is
         *  what ties them together, so paying to embed the same speech twice buys nothing here. */
        const val WINDOW_SEC = 10

        /** Below this there is not enough audio in the window to decode. */
        const val MIN_WINDOW_SEC = 0.35f

        /** Below this the embedder produces noise rather than a voiceprint. */
        const val MIN_EMBED_SEC = 0.25f

        /** Long enough to hide a hand-over the segmentation model did not hear. */
        const val RESEG_MIN_SEGMENT_SEC = 3.0f
        const val RESEG_SUBWINDOW_SEC = 1.6f
        const val RESEG_HOP_SEC = 0.8f

        /** Consecutive stretches of one speaker closer than this are one turn. */
        const val JOIN_GAP_SEC = 0.3f

        /** How far outside a segment a frame may sit and still be attributed to it. */
        private const val NEAR_SEGMENT_SEC = 0.15f

        /** Floor on a frame's weight, so an uncertain frame still counts as speech. */
        private const val MIN_FRAME_WEIGHT = 0.05f

        /** Shortest span [wordLabel] will consider, so a zero-length word still reads a frame. */
        private const val MIN_SPAN_SEC = 0.02f

        private val NOT_ATTRIBUTED = -1 to 0f

        /**
         * Agglomerative cut distance for an embedding model.
         *
         * The two embedders do not share a similarity scale, and running one against a threshold
         * tuned for the other is a known way to collapse a two-speaker recording -- the catalogue
         * note on [com.example.aiagenttestapp.data.audiomodels.AudioModels] records it happening.
         * Unknown ids get the ERes2Net value, which is the default bundle.
         */
        fun thresholdFor(embeddingModelId: String?): Float =
            if (embeddingModelId?.contains("campplus", ignoreCase = true) == true) 0.48f else 0.60f
    }
}
