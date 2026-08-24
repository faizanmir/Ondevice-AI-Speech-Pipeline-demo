package com.example.aiagenttestapp.probe

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.SpeakerEmbedder
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
 * Asks whether clustering is worth doing at all on a recording whose speakers are already enrolled.
 *
 * [DiarizationSweepProbeTest] measures how many clusters come out. It cannot say whether they are
 * *right*, because there is no reference diarisation for the sweep file. This probe supplies one:
 * every enrolled voice is a labelled example, so each diarisation turn can be embedded and matched
 * against Bob and Tim directly. That gives a per-turn opinion which owes nothing to clustering, and
 * two things become measurable that were not before.
 *
 *  - **Cluster purity.** For each cluster, how the turns inside it divide between the enrolled
 *    people. A cluster holding 90 s of Bob and 40 s of Tim is not an over-segmentation problem and no
 *    threshold will fix it; it is the clustering putting two people in one bucket.
 *  - **Whether the clustering step earns its place.** The app's pipeline clusters first and names the
 *    clusters afterwards, so one impure cluster mislabels every word inside it and one person split
 *    across four clusters is four independent chances to fail the match. Matching per turn cannot
 *    make either mistake. If the per-turn column is the cleaner of the two, the clustering step is
 *    costing accuracy rather than adding it -- for enrolled speakers, which is the case this app
 *    cares most about.
 *
 * The enrolled voiceprints are read from a pushed TSV rather than from Room, so this runs against a
 * fixed reference that cannot drift as voices are re-enrolled, and works on a device whose database
 * has been cleared. One line per take: `name<TAB>v0 v1 ... v511`.
 *
 * ## Running it
 *
 * Read [DiarizationSweepProbeTest]'s note on `connectedDebugAndroidTest` first -- it uninstalls the
 * app, and this probe needs the same models and staged audio that note describes.
 *
 * ```
 * adb shell "cat enrolled.tsv | run-as com.example.aiagenttestapp sh -c 'cat > files/enrolled.tsv'"
 * adb shell am instrument -w \
 *     -e class com.example.aiagenttestapp.probe.SpeakerAttributionProbeTest \
 *     -e threshold 0.5 -e minOn 0.3 \
 *     com.example.aiagenttestapp.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class SpeakerAttributionProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    @Test
    fun compareClusteringAgainstDirectMatching() {
        val segmentation = File(context.filesDir, "audio-models/speaker/segmentation.onnx")
        val embeddingModel = File(context.filesDir, "audio-models/speaker/embedding.onnx")
        val wav = File(context.filesDir, "sweep-audio.wav")
        val enrolledFile = File(context.filesDir, "enrolled.tsv")

        assumeTrue("segmentation model missing", segmentation.exists())
        assumeTrue("embedding model missing", embeddingModel.exists())
        assumeTrue("sweep audio missing", wav.exists())
        assumeTrue("enrolled.tsv missing -- see the class KDoc", enrolledFile.exists())

        val threshold = args.getString("threshold")?.toFloatOrNull() ?: 0.5f
        val minOn = args.getString("minOn")?.toFloatOrNull() ?: 0.3f
        val numClusters = args.getString("numClusters")?.toIntOrNull() ?: -1

        val enrolled: Map<String, List<FloatArray>> = enrolledFile.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val (name, vector) = line.split('\t', limit = 2)
                name to vector.trim().split(' ').map { it.toFloat() }.toFloatArray()
            }
            .groupBy({ it.first }, { it.second })
        log("enrolled: " + enrolled.entries.joinToString { "${it.key}x${it.value.size}" })

        val samples = WavFile.read(wav)
        log("config: k=$numClusters threshold=$threshold minOn=$minOn over %.1fs".format(samples.size / 16000f))

        val diarization = OfflineSpeakerDiarization(
            assetManager = null,
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation.absolutePath),
                    numThreads = THREADS,
                    debug = false,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = embeddingModel.absolutePath,
                    numThreads = THREADS,
                    debug = false,
                    provider = "cpu",
                ),
                clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold),
                minDurationOn = minOn,
                minDurationOff = 0.5f,
            ),
        )
        val turns = try {
            diarization.process(samples).toList()
        } finally {
            runCatching { diarization.release() }
        }
        log("turns: ${turns.size}")

        // A second, independent copy of the embedding model. sherpa's diarizer keeps its own inside
        // the native object and exposes no way to borrow it, so per-turn matching has to load one.
        val embedder = SpeakerEmbedder()
        embedder.load(embeddingModel, threadCount = THREADS)

        data class Judged(val cluster: Int, val seconds: Double, val verdict: String, val best: String, val score: Float)

        val judged = turns.mapNotNull { turn ->
            val from = (turn.start * RATE).toInt().coerceIn(0, samples.size)
            val until = (turn.end * RATE).toInt().coerceIn(from, samples.size)
            if (until <= from) return@mapNotNull null
            val clip = samples.copyOfRange(from, until)
            val embedding = embedder.embed(clip) ?: return@mapNotNull Judged(
                turn.speaker, (until - from) / RATE.toDouble(), "no-embedding", "none", 0f,
            )
            val decision = matchSpeaker(embedding, enrolled, MATCH_THRESHOLD, MATCH_MARGIN)
            Judged(
                cluster = turn.speaker,
                seconds = (until - from) / RATE.toDouble(),
                verdict = decision.acceptedName ?: "unmatched",
                best = decision.bestName ?: "none",
                score = decision.bestScore,
            )
        }
        embedder.release()

        // How the per-turn opinion divides overall. "unmatched" is the honest outcome for a turn too
        // short or too mixed to commit to, and its share is the real cost of matching per turn.
        log("---- per-turn direct matching (no clustering involved) ----")
        judged.groupBy { it.verdict }
            .toList()
            .sortedByDescending { (_, its) -> its.sumOf { it.seconds } }
            .forEach { (verdict, its) ->
                log("  %-12s %3d turns %6.1fs  mean best score %.3f".format(
                    verdict, its.size, its.sumOf { it.seconds }, its.map { it.score }.average(),
                ))
            }

        // The purity table. Each cluster against the per-turn opinion: if clustering agreed with the
        // enrolled voices, every row here names one person and nothing else.
        log("---- cluster purity against enrolled voices ----")
        judged.groupBy { it.cluster }.toSortedMap().forEach { (cluster, its) ->
            val byName = its.groupBy { it.verdict }
                .mapValues { (_, v) -> v.sumOf { it.seconds } }
                .toList().sortedByDescending { it.second }
            val named = byName.filter { it.first != "unmatched" && it.first != "no-embedding" }
            val total = named.sumOf { it.second }
            val purity = if (total > 0) named.first().second / total else 0.0
            log("  c%-2d %6.1fs purity=%.2f  %s".format(
                cluster,
                its.sumOf { it.seconds },
                purity,
                byName.joinToString(" ") { "%s=%.1fs".format(it.first, it.second) },
            ))
        }

        // The bottom line: how much speech clustering would hand to the wrong person. Every cluster
        // takes the name of whichever enrolled voice holds most of it -- which is what labelClusters
        // effectively does -- so anything inside it belonging to somebody else is mislabelled.
        val mislabelled = judged.groupBy { it.cluster }.entries.sumOf { (_, its) ->
            val named = its.filter { it.verdict != "unmatched" && it.verdict != "no-embedding" }
            val winner = named.groupBy { it.verdict }.mapValues { (_, v) -> v.sumOf { it.seconds } }
                .maxByOrNull { it.value }?.key
            named.filter { it.verdict != winner }.sumOf { it.seconds }
        }
        val namedSeconds = judged.filter { it.verdict != "unmatched" && it.verdict != "no-embedding" }
            .sumOf { it.seconds }
        log("---- summary ----")
        log("  identifiable speech      : %.1fs".format(namedSeconds))
        log("  mislabelled by clustering: %.1fs (%.1f%% of identifiable)".format(
            mislabelled, if (namedSeconds > 0) mislabelled / namedSeconds * 100 else 0.0,
        ))
    }

    private companion object {
        const val TAG = "SpeakerAttribution"
        const val THREADS = 4
        const val RATE = 16000
        const val MATCH_THRESHOLD = 0.6f
        const val MATCH_MARGIN = 0.05f
    }
}
