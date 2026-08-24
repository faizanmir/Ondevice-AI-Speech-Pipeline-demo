package com.example.aiagenttestapp.probe

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.SpeakerEmbedder
import com.example.aiagenttestapp.stt.averageEmbedding
import com.example.aiagenttestapp.stt.cosineSimilarity
import com.example.aiagenttestapp.stt.matchSpeaker
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Asks whether a different embedding model would attribute short turns better than the one we ship.
 *
 * [SpeakerAttributionProbeTest] established that attribution fails on turn-length audio: WeSpeaker
 * CAM++ committed to only 42 of 109 turns, and the 67 it declined averaged 0.348 against the enrolled
 * voices -- inside the range measured *between different people*, so those vectors carry no usable
 * identity rather than the wrong one. That is a property of the model on two seconds of speech, and
 * the obvious question is whether a stronger one has the same limit. Nothing published answers it:
 * speaker-verification leaderboards report EER on full utterances, which is the case that already
 * works here.
 *
 * ## Why it does not simply re-enrol
 *
 * A voiceprint only means anything to the model that produced it, and this app stores voiceprints
 * rather than the enrolment audio -- so Bob and Tim cannot be re-enrolled under a candidate model.
 * Instead the turns CAM++ *was* confident about become the labelled set. Those labels are anchored to
 * recordings the user made deliberately and scored 0.70-0.84, well clear of the 0.46 ceiling measured
 * between different speakers, so they are trustworthy even though they are not hand-annotated.
 *
 * Every model is then scored on the same turns by leave-one-out: build each person's centroid from
 * the other labelled turns and see whether this turn lands nearest its own. That is scale-free, which
 * matters because a fixed 0.6 threshold means different things to different models and comparing raw
 * acceptance counts across them would be meaningless.
 *
 * **Bucketed by turn length, because that is the whole question.** A model that is perfect above four
 * seconds and a coin flip below one is exactly the model we already have.
 *
 * ## Running it
 *
 * Read [DiarizationSweepProbeTest]'s warning about `connectedDebugAndroidTest` first.
 *
 * ```
 * for m in <candidate>.onnx; do
 *   adb shell "cat $m | run-as com.example.aiagenttestapp sh -c 'cat > files/probe-models/'$(basename $m)"
 * done
 * adb shell am instrument -w \
 *     -e class com.example.aiagenttestapp.probe.EmbeddingModelProbeTest \
 *     com.example.aiagenttestapp.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class EmbeddingModelProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    /** A turn, its audio, and what the incumbent model believed about it. */
    private class Turn(val seconds: Double, val clip: FloatArray, var label: String? = null)

    @Test
    fun compareEmbeddingModelsOnShortTurns() {
        val segmentation = File(context.filesDir, "audio-models/speaker/segmentation.onnx")
        val incumbent = File(context.filesDir, "audio-models/speaker/embedding.onnx")
        val wav = File(context.filesDir, "sweep-audio.wav")
        val enrolledFile = File(context.filesDir, "enrolled.tsv")

        assumeTrue("segmentation model missing", segmentation.exists())
        assumeTrue("incumbent embedding model missing", incumbent.exists())
        assumeTrue("sweep audio missing", wav.exists())
        assumeTrue("enrolled.tsv missing", enrolledFile.exists())

        val enrolled = enrolledFile.readLines().filter { it.isNotBlank() }.map { line ->
            val (name, vector) = line.split('\t', limit = 2)
            name to vector.trim().split(' ').map { it.toFloat() }.toFloatArray()
        }.groupBy({ it.first }, { it.second })

        val samples = WavFile.read(wav)

        // Segmentation is shared: the turns are the same whichever model embeds them, so diarising
        // once keeps the comparison to the one variable that is being tested.
        val diarization = OfflineSpeakerDiarization(
            assetManager = null,
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation.absolutePath),
                    numThreads = THREADS, debug = false, provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = incumbent.absolutePath, numThreads = THREADS, debug = false, provider = "cpu",
                ),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            ),
        )
        val turns = try {
            diarization.process(samples).toList().mapNotNull { turn ->
                val from = (turn.start * RATE).toInt().coerceIn(0, samples.size)
                val until = (turn.end * RATE).toInt().coerceIn(from, samples.size)
                if (until <= from) null else Turn((until - from) / RATE.toDouble(), samples.copyOfRange(from, until))
            }
        } finally {
            runCatching { diarization.release() }
        }
        log("turns: ${turns.size}, total %.1fs".format(turns.sumOf { it.seconds }))

        // Seed labels from the incumbent against the real enrolments.
        SpeakerEmbedder().let { embedder ->
            embedder.load(incumbent, threadCount = THREADS)
            turns.forEach { turn ->
                turn.label = embedder.embed(turn.clip)?.let {
                    matchSpeaker(it, enrolled, MATCH_THRESHOLD, MATCH_MARGIN).acceptedName
                }
            }
            embedder.release()
        }
        val labelled = turns.filter { it.label != null }
        log("seed labels from the shipped model: ${labelled.size} of ${turns.size} turns, " +
            labelled.groupingBy { it.label!! }.eachCount())
        assumeTrue("too few seed labels to compare models", labelled.size >= 10)

        val candidates = buildList {
            add(incumbent)
            File(context.filesDir, "probe-models").listFiles()
                ?.filter { it.extension == "onnx" }?.sortedBy { it.name }?.let { addAll(it) }
        }
        log("comparing ${candidates.size} models over ${labelled.size} labelled turns")
        log("cols: model dim | leave-one-out accuracy overall and by turn length | mean margin")

        candidates.forEach { model -> score(model, turns, labelled) }
    }

    private fun score(model: File, turns: List<Turn>, labelled: List<Turn>) {
        val embedder = SpeakerEmbedder()
        val loaded = runCatching { embedder.load(model, threadCount = THREADS) }
            .onFailure { log("${model.name}: FAILED to load -- ${it.message}") }.isSuccess
        if (!loaded) return

        val vectors: Map<Turn, FloatArray> = try {
            turns.mapNotNull { turn -> embedder.embed(turn.clip)?.let { turn to it } }.toMap()
        } finally {
            embedder.release()
        }

        val usable = labelled.filter { vectors.containsKey(it) }
        if (usable.size < 4) {
            log("${model.name}: only ${usable.size} labelled turns produced a vector -- skipped")
            return
        }

        // Leave-one-out: the turn under test never contributes to the centroid it is measured against,
        // so a model cannot score well simply by memorising the set.
        data class Outcome(val correct: Boolean, val margin: Float, val seconds: Double)
        val outcomes = usable.mapNotNull { held ->
            val centroids = usable.filter { it !== held }
                .groupBy { it.label!! }
                .mapNotNull { (name, its) -> averageEmbedding(its.map { vectors.getValue(it) })?.let { name to it } }
                .toMap()
            if (centroids.size < 2) return@mapNotNull null

            val scored = centroids.mapValues { (_, centroid) -> cosineSimilarity(vectors.getValue(held), centroid) }
            val ranked = scored.entries.sortedByDescending { it.value }
            Outcome(
                correct = ranked.first().key == held.label,
                margin = ranked.first().value - ranked[1].value,
                seconds = held.seconds,
            )
        }
        if (outcomes.isEmpty()) {
            log("${model.name}: no usable outcomes")
            return
        }

        fun bucket(from: Double, until: Double): String {
            val inBucket = outcomes.filter { it.seconds >= from && it.seconds < until }
            if (inBucket.isEmpty()) return "  n/a"
            return "%3.0f%%(%d)".format(inBucket.count { it.correct } * 100.0 / inBucket.size, inBucket.size)
        }

        log(
            "%-46s dim=%-4d overall=%3.0f%%(%d)  <1s=%s 1-2s=%s 2-4s=%s 4s+=%s  margin=%.3f".format(
                model.name,
                vectors.values.firstOrNull()?.size ?: 0,
                outcomes.count { it.correct } * 100.0 / outcomes.size,
                outcomes.size,
                bucket(0.0, 1.0),
                bucket(1.0, 2.0),
                bucket(2.0, 4.0),
                bucket(4.0, Double.MAX_VALUE),
                outcomes.map { it.margin }.average(),
            ),
        )
    }

    private companion object {
        const val TAG = "EmbeddingModelProbe"
        const val THREADS = 4
        const val RATE = 16000
        const val MATCH_THRESHOLD = 0.6f
        const val MATCH_MARGIN = 0.05f
    }
}
