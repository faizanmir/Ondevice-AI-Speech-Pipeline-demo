package com.example.aiagenttestapp.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the on-device scorer to what `docs/wer.py` produces, on the real corpus.
 *
 * This is the one property [Wer] exists for. Every WER this project has published came from the
 * Python script; the Kotlin is a port so the same numbers can be produced on the device, and a port
 * that has silently drifted is worse than no port at all -- it produces numbers that look
 * comparable and are not. The unit tests elsewhere pin the *rules*; this pins the *answers*, on the
 * actual reference transcripts, in both languages, from 2.6% to 57.7%.
 *
 * The expectations below are `wer.py`'s output, not the Kotlin's. Regenerate them with:
 *
 * ```sh
 * python3 docs/wer.py docs/data/<reference> docs/data/<transcript> --lang=<en|de>
 * ```
 *
 * and change them only when the Python changes -- the direction of the dependency is the point. If
 * this test fails, the two scorers disagree and neither number can be trusted until they do not.
 *
 * A drift caught here would have been caught nowhere else: the alignment swap on 2026-08-19 changed
 * both implementations at once, and a re-score of this corpus is what showed nine of ten published
 * numbers were unaffected.
 */
class WerGoldenTest {

    private data class Counts(
        val refWords: Int,
        val sub: Int,
        val del: Int,
        val ins: Int,
        val wer: Double,
    )

    private data class Golden(
        val reference: String,
        val transcript: String,
        val lang: String,
        val raw: Counts,
        val normalised: Counts,
    )

    private val goldens = listOf(
        // The best run on the audit script, and the worst -- the two ends of the published table.
        Golden(
            "audit_script_en2.txt", "transcript_en2_whisper_sd8sg4.txt", "en",
            raw = Counts(refWords = 3090, sub = 259, del = 277, ins = 3, wer = 17.4),
            normalised = Counts(refWords = 2908, sub = 123, del = 98, ins = 6, wer = 7.8),
        ),
        Golden(
            "audit_script_en2.txt", "transcript_en2_platform_unpaced_sd8sg4.txt", "en",
            raw = Counts(refWords = 3090, sub = 315, del = 1563, ins = 16, wer = 61.3),
            normalised = Counts(refWords = 2908, sub = 266, del = 1391, ins = 20, wer = 57.7),
        ),
        // A truncated reference, which is its own thing to get wrong.
        Golden(
            "audit_script_en2_first10min.txt", "transcript_en2_platform_10min_sd8sg4.txt", "en",
            raw = Counts(refWords = 1597, sub = 124, del = 312, ins = 0, wer = 27.3),
            normalised = Counts(refWords = 1485, sub = 59, del = 204, ins = 4, wer = 18.0),
        ),
        // Conversational content, both backends, where raw and normalised nearly agree.
        Golden(
            "dialog_major_non_conformity.txt", "transcript_dialog_major_whisper_sd8sg4.txt", "en",
            raw = Counts(refWords = 868, sub = 15, del = 5, ins = 4, wer = 2.8),
            normalised = Counts(refWords = 868, sub = 14, del = 5, ins = 4, wer = 2.6),
        ),
        Golden(
            "dialog_major_non_conformity.txt", "transcript_dialog_major_platform_sd8sg4.txt", "en",
            raw = Counts(refWords = 868, sub = 72, del = 52, ins = 7, wer = 15.1),
            normalised = Counts(refWords = 868, sub = 71, del = 52, ins = 7, wer = 15.0),
        ),
        // German: a different numeral grammar, and the ß and compound-word rules with it.
        Golden(
            "audit_de.txt", "transcript_de.txt", "de",
            raw = Counts(refWords = 1996, sub = 260, del = 88, ins = 35, wer = 19.2),
            normalised = Counts(refWords = 1971, sub = 191, del = 69, ins = 40, wer = 15.2),
        ),
    )

    /**
     * `docs/data`, found by walking up from wherever the test was started.
     *
     * Gradle runs unit tests with the module directory as the working directory, but that is a
     * convention rather than a promise, and an IDE may not honour it. Walking up is what keeps this
     * from being a test that passes or fails on how it was launched.
     */
    private val corpus: File by lazy {
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "docs/data") }
            .firstOrNull { it.isDirectory }
            ?: error("docs/data not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the kotlin scorer reproduces wer_py on the reference corpus`() {
        val failures = mutableListOf<String>()

        for (golden in goldens) {
            val reference = File(corpus, golden.reference)
            val transcript = File(corpus, golden.transcript)
            assertTrue("missing ${reference.name}", reference.isFile)
            assertTrue("missing ${transcript.name}", transcript.isFile)

            val report = Wer.report(reference.readText(), transcript.readText(), golden.lang)

            for ((label, expected, actual) in listOf(
                Triple("raw", golden.raw, report.raw),
                Triple("normalised", golden.normalised, report.normalised),
            )) {
                val got = Counts(
                    refWords = actual.referenceWords,
                    sub = actual.substitutions,
                    del = actual.deletions,
                    ins = actual.insertions,
                    // Rounded, not truncated: `wer.py` prints with %.1f, and truncating turned
                    // an exact agreement on 61.2857% into 61.2 against its 61.3.
                    wer = kotlin.math.round(actual.werPercent * 10) / 10.0,
                )
                if (got != expected) {
                    failures += "${golden.transcript} [$label]\n      wer.py: $expected\n      kotlin: $got"
                }
            }
        }

        assertTrue(
            "the two scorers disagree on ${failures.size} of ${goldens.size * 2} passes:\n  " +
                failures.joinToString("\n  "),
            failures.isEmpty(),
        )
    }

    /**
     * Coverage and CER, the two numbers the shared protocol reads either side of the WER.
     *
     * CER is checked against `jiwer.cer` over *our* tokens -- the same character-level Levenshtein
     * their kit computes -- so the cross-check means the same thing on both sides even though the
     * word-level normalisers differ.
     */
    @Test
    fun `coverage and CER match the shared protocol's definitions`() {
        val whisper = Wer.report(
            File(corpus, "audit_script_en2.txt").readText(),
            File(corpus, "transcript_en2_whisper_sd8sg4.txt").readText(),
            "en",
        )
        // 2816 hypothesis words against 2908 reference words.
        assertEquals(96.8, whisper.coverage, 0.1)
        assertEquals(2.44, whisper.cerPercent, 0.05)     // jiwer.cer on the same tokens

        val platform = Wer.report(
            File(corpus, "dialog_major_non_conformity.txt").readText(),
            File(corpus, "transcript_dialog_major_platform_sd8sg4.txt").readText(),
            "en",
        )
        assertEquals(8.09, platform.cerPercent, 0.05)

        // The signature the protocol warns about: a truncated run reads as a plausible WER.
        val truncated = Wer.report(
            File(corpus, "audit_script_en2.txt").readText(),
            File(corpus, "transcript_en2_platform_unpaced_sd8sg4.txt").readText(),
            "en",
        )
        assertTrue("coverage was ${truncated.coverage}", truncated.coverage < 90.0)
    }

    /**
     * The corpus is part of the test. A reference that quietly gained a speaker tag or a header
     * would move every number computed from it, and the failure would look like a scorer bug.
     */
    @Test
    fun `the reference corpus contains no unspoken markup`() {
        val offenders = corpus.listFiles().orEmpty()
            .filter { it.isFile && (it.name.startsWith("dialog_") || it.name.startsWith("audit_script")) }
            .mapNotNull { file ->
                val tags = Regex("""\[[^\]]{1,30}]""").findAll(file.readText())
                    .map { it.value }
                    .filterNot { it.startsWith("[[") }      // say() directives, stripped by design
                    .toList()
                if (tags.isEmpty()) null else "${file.name}: ${tags.distinct().take(4)}"
            }

        assertEquals("references must hold only spoken words", emptyList<String>(), offenders)
    }
}
