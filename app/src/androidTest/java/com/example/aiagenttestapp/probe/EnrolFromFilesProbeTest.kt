package com.example.aiagenttestapp.probe

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiagenttestapp.data.notes.WavFile
import com.example.aiagenttestapp.stt.SpeakerEmbedder
import com.example.aiagenttestapp.stt.cosineSimilarity
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Enrols voices from WAV files instead of through the microphone, for experiments only.
 *
 * The enrolment screen records from the mic, which is right for a person sitting in front of the
 * phone and wrong for a measurement: playing a synthesised voice into the mic adds room, speaker and
 * capture colouring, and an earlier run showed that is not free -- the same audio scored 73.6%
 * imported and 69.0% over the air. When the question is whether the *models* can tell two voices
 * apart, that colouring is noise on the answer.
 *
 * So this reads takes from `files/enroll/`, embeds them with the shipped model, and writes the
 * voiceprints to `files/enrolled-new.tsv` for the host to fold into `speakers.db`. It deliberately
 * writes a file rather than logging: a 512-float vector does not survive logcat's line limit, and a
 * silently truncated voiceprint would match nobody while looking fine.
 *
 * Takes are named `<person>-takeN.wav`; everything before the first dash is the person.
 *
 * It also prints the same-speaker and cross-speaker similarities, because that pair of numbers is
 * the whole prerequisite for the experiment. If cross-speaker similarity is not clearly below
 * same-speaker, the voices are not separable by this model and nothing downstream can be read as
 * evidence about clustering, naming or attribution.
 */
class EnrolFromFilesProbeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    @Test
    fun embedEnrolmentTakes() {
        val model = File(context.filesDir, "audio-models/speaker/embedding.onnx")
        val dir = File(context.filesDir, "enroll")
        assumeTrue("embedding model missing", model.exists())
        assumeTrue("no files/enroll directory", dir.isDirectory)

        val takes = dir.listFiles()?.filter { it.extension.equals("wav", ignoreCase = true) }?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("no takes in files/enroll", takes.isNotEmpty())

        val embedder = SpeakerEmbedder()
        embedder.load(model, threadCount = THREADS)

        val rows = mutableListOf<Triple<String, String, FloatArray>>()
        takes.forEach { take ->
            val person = take.nameWithoutExtension.substringBefore('-')
                .replaceFirstChar { it.uppercase() }
            val samples = WavFile.read(take)
            val embedding = embedder.embed(samples)
            if (embedding == null) {
                log("${take.name}: NO VOICEPRINT -- too short, too quiet, or unusable")
                return@forEach
            }
            rows += Triple(person, take.name, embedding)
            log("${take.name} -> $person, %.1fs, dim=%d".format(samples.size / 16000f, embedding.size))
        }
        embedder.release()
        assumeTrue("nothing embedded", rows.isNotEmpty())

        log("---- separability of these takes ----")
        var minSame = Float.MAX_VALUE
        var maxCross = -Float.MAX_VALUE
        for (i in rows.indices) {
            for (j in i + 1 until rows.size) {
                val score = cosineSimilarity(rows[i].third, rows[j].third)
                val same = rows[i].first == rows[j].first
                if (same) minSame = minOf(minSame, score) else maxCross = maxOf(maxCross, score)
                log("  %-5s %-16s vs %-16s %.3f".format(if (same) "SAME" else "CROSS", rows[i].second, rows[j].second, score))
            }
        }
        log("  worst same-speaker %.3f, best cross-speaker %.3f, separation gap %+.3f".format(
            minSame, maxCross, minSame - maxCross,
        ))

        val out = File(context.filesDir, "enrolled-new.tsv")
        out.writeText(rows.joinToString("\n") { (person, _, vector) ->
            person + "\t" + vector.joinToString(" ") { "%.6f".format(it) }
        } + "\n")
        log("wrote ${out.absolutePath}, ${rows.size} voiceprints")
    }

    private companion object {
        const val TAG = "EnrolFromFiles"
        const val THREADS = 4
    }
}
