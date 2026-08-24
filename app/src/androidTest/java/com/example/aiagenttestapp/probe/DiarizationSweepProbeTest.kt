package com.example.aiagenttestapp.probe

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiagenttestapp.data.notes.WavFile
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
 * Measures what sherpa's clustering knobs actually do to *this* app's models on *this* app's audio.
 *
 * Written because a two-speaker file came back as eleven clusters, and the fix was about to be a
 * guessed number. [com.example.aiagenttestapp.stt.SpeakerDiarizer] passes sherpa's stock
 * `threshold = 0.5`, but a threshold is only meaningful against the score distribution of the
 * embedding model it is cutting -- and sherpa's default was set against 3D-Speaker ERes2Net while
 * this app ships WeSpeaker CAM++. Nothing published covers that pairing, so the number has to be
 * measured here or not known at all.
 *
 * A probe rather than a unit test, for the same reason [PlatformSpeechProbeTest] is: the answer lives
 * entirely inside two native models, so no JVM test can produce it. It asserts almost nothing. The
 * output is the table in logcat.
 *
 * ## Reading the output
 *
 * There is no reference diarisation for this recording, so the probe reports proxies rather than DER:
 *
 *  - `clusters` -- how many speakers it decided there were. Ground truth for the sweep file is 2.
 *  - `top2` -- the share of attributed speech held by the two largest clusters. A correct run puts
 *    this at ~1.0; over-segmentation drags it down as real speech leaks into fragment clusters.
 *  - `tiny` -- clusters holding under a second. These are the ones that poison naming downstream:
 *    they are too short to embed reliably, so they miss the match threshold and surface as a fresh
 *    "Unknown Speaker" in the middle of somebody's sentence.
 *
 * The `numClusters = 2` row is the control and the most important line in the table. It is not a
 * candidate setting -- sherpa cuts the dendrogram at exactly k, which is why forcing a count has
 * collapsed whole conversations onto one speaker before. It is here to establish the ceiling: if the
 * embeddings genuinely separate these two voices, that row shows a balanced two-way split, and
 * tuning the threshold is worth doing. If that row is also lopsided, the embedding model cannot tell
 * these voices apart and no threshold will save it.
 *
 * ## Running it
 *
 * **Do not run this with `./gradlew :app:connectedDebugAndroidTest`.** That task uninstalls both APKs
 * when it finishes, and on this app the package's internal storage is where every downloaded model
 * and every Room database lives -- so the teardown costs a multi-gigabyte re-download and wipes
 * `speakers.db`, taking every enrolled voice with it. That is not hypothetical; it is how this note
 * came to be written. Install and instrument by hand instead, which never uninstalls:
 *
 * ```
 * ./gradlew :app:installDebug :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *
 * # The audio has to be readable by the app's own uid. Streaming it through run-as is the reliable
 * # way: a plain `adb push` into the app's external dir lands owned by shell with 0660, which the
 * # app cannot open -- File.exists() returns true and the read still fails with EACCES.
 * adb shell "cat /sdcard/Download/eleven_two_voice.wav \
 *     | run-as com.example.aiagenttestapp sh -c 'cat > files/sweep-audio.wav'"
 *
 * adb shell am instrument -w \
 *     -e class com.example.aiagenttestapp.probe.DiarizationSweepProbeTest \
 *     com.example.aiagenttestapp.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class DiarizationSweepProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    @Test
    fun sweepClusteringKnobs() {
        val segmentation = File(context.filesDir, "audio-models/speaker/segmentation.onnx")
        val embedding = File(context.filesDir, "audio-models/speaker/embedding.onnx")
        val wav = File(context.filesDir, SWEEP_FILE)

        // Assumptions rather than assertions: a device without the models downloaded, or without the
        // sweep file pushed, has nothing to say about clustering and should not report a failure.
        assumeTrue("segmentation model missing: $segmentation", segmentation.exists())
        assumeTrue("embedding model missing: $embedding", embedding.exists())
        assumeTrue("sweep audio missing: $wav -- see the class KDoc for how to stage it", wav.exists())

        val samples = WavFile.read(wav)
        log("audio: ${wav.name}, ${samples.size} samples, %.1fs".format(samples.size / 16000f))
        log("cols: numClusters threshold minDurationOn -> clusters turns top2 tiny seconds elapsed")

        // Threshold first at sherpa's stock minDurationOn, so the two knobs are never moved at once
        // and a change in the table can only be attributed to one of them.
        for (threshold in THRESHOLDS) {
            run(segmentation, embedding, samples, numClusters = -1, threshold = threshold, minOn = 0.3f)
        }
        // Then the fragment gate, at both ends of the threshold range: a 0.3 s turn cannot produce a
        // usable voiceprint, so raising this may matter independently of where the dendrogram is cut.
        for (minOn in MIN_DURATIONS_ON) {
            run(segmentation, embedding, samples, numClusters = -1, threshold = 0.5f, minOn = minOn)
            run(segmentation, embedding, samples, numClusters = -1, threshold = 0.8f, minOn = minOn)
        }
        // The ceiling. See the class KDoc -- this is a diagnostic, not a proposed setting.
        run(segmentation, embedding, samples, numClusters = 2, threshold = 0f, minOn = 0.3f)
    }

    private fun run(
        segmentation: File,
        embedding: File,
        samples: FloatArray,
        numClusters: Int,
        threshold: Float,
        minOn: Float,
    ) {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation.absolutePath),
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embedding.absolutePath,
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold),
            minDurationOn = minOn,
            minDurationOff = 0.5f,
        )

        val diarization = OfflineSpeakerDiarization(assetManager = null, config = config)
        val startedAt = System.currentTimeMillis()
        val segments = try {
            diarization.process(samples).toList()
        } catch (e: Exception) {
            log("k=%d t=%.2f on=%.2f -> FAILED: %s".format(numClusters, threshold, minOn, e.message))
            return
        } finally {
            // Released inside the loop on purpose. Two of these resident at once is two copies of the
            // embedding model, and the sweep runs a dozen of them back to back.
            runCatching { diarization.release() }
        }
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000f

        val perCluster = segments.groupBy { it.speaker }
            .mapValues { (_, its) -> its.sumOf { (it.end - it.start).toDouble() } }
        val totalSpeech = perCluster.values.sum()
        val top2 = perCluster.values.sortedDescending().take(2).sum()
        val tiny = perCluster.values.count { it < 1.0 }

        log(
            "k=%2d t=%.2f on=%.2f -> clusters=%2d turns=%3d top2=%.2f tiny=%d speech=%.1fs in %.1fs".format(
                numClusters,
                threshold,
                minOn,
                perCluster.size,
                segments.size,
                if (totalSpeech > 0) top2 / totalSpeech else 0.0,
                tiny,
                totalSpeech,
                elapsed,
            ),
        )
        log("     sizes: " + perCluster.toSortedMap().entries.joinToString(" ") { "c%d=%.1fs".format(it.key, it.value) })
    }

    private companion object {
        const val TAG = "DiarizationSweep"
        const val SWEEP_FILE = "sweep-audio.wav"
        const val THREADS = 4
        val THRESHOLDS = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f)
        val MIN_DURATIONS_ON = listOf(0.5f, 1.0f)
    }
}
