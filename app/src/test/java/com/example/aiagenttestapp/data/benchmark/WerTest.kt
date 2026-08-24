package com.example.aiagenttestapp.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Kotlin port against `docs/wer.py` -- literally. Every expectation in this file was
 * produced by running the Python script on the same input (2026-08-18), not by reasoning about
 * what the answer ought to be. That is the whole point of the port: an on-device WER is only
 * comparable to the published benchmark table if it comes out of identical arithmetic, quirks
 * included. If a test here disagrees with the code, the code is wrong, even where the Python
 * behaviour looks like a bug -- see `the hundreds rule swallows a trailing noun`, which is one.
 */
class WerTest {

    // ---- The flagship case: numbers, tags, silence directives ----------------------------------

    private val englishRef =
        "The temperature was nineteen point four degrees on the sixteenth of August twenty " +
            "twenty six. [[slnc 500]] [NON-CONFORMITY] Batch T four two dash seven one six " +
            "failed at minus twenty one. [/NON-CONFORMITY] One hundred and eighty units were " +
            "held, twenty two thousand remain."
    private val englishHyp =
        "the temperature was 19.4 degrees on the 16th of august 2026 batch T42-716 failed " +
            "at -21 180 units were held 22000 remain"

    @Test
    fun `english normalisation rewrites numbers, tags and codes as the script does`() {
        val report = Wer.report(englishRef, englishHyp, lang = "en")

        // Raw: 39 reference words against 22, so 24 errors -- 7 of them substitutions and the
        // 17 surplus reference words deletions. The old longest-match diff called all 24
        // substitutions, which was never true of a hypothesis this much shorter.
        assertEquals(39, report.raw.referenceWords)
        assertEquals(22, report.raw.hypothesisWords)
        assertEquals(7, report.raw.substitutions)
        assertEquals(17, report.raw.deletions)
        assertEquals(0, report.raw.insertions)
        assertEquals(24, report.raw.errors)

        // Normalised: both sides 22 words, 4 errors -- the residue the number grammar cannot merge
        // ("t 42-716" vs "t42-716", and the hundreds quirk below).
        assertEquals(22, report.normalised.referenceWords)
        assertEquals(2, report.normalised.substitutions)
        assertEquals(1, report.normalised.deletions)
        assertEquals(1, report.normalised.insertions)
        assertEquals(4, report.normalised.errors)
    }

    /**
     * `\b(\w+) hundred(?: and (\w+(?: \w+)?))?\b` greedily takes a two-word tail, so
     * "one hundred and eighty units" captures tail "eighty units", which values to nothing,
     * `or 0`s to zero, and the whole phrase -- units included -- becomes "100". A Python
     * behaviour, faithfully kept: fixing it would silently change every published number.
     */
    @Test
    fun `the hundreds rule swallows a trailing noun exactly as the script does`() {
        val report = Wer.report(englishRef, englishHyp, lang = "en")
        assertTrue(
            "expected the 100 vs '180 units' pair, got ${report.topPairs}",
            report.topPairs.any { it.first == "100" && it.second == "180 units" },
        )
    }

    /** "zero" is falsy in Python's or-chain, so "zero thousand" never rewrites -- and "0" stays. */
    @Test
    fun `zero thousand is left alone, matching Python truthiness`() {
        val report = Wer.report(
            "zero thousand items and twenty twenty",
            "0 thousand items and 2020",
            lang = "en",
        )
        assertEquals(5, report.normalised.referenceWords)
        assertEquals(0, report.normalised.errors)
        // Raw: "zero"->"0" and "twenty twenty"->"2020", the latter two reference words against
        // one hypothesis word -- one substitution and one deletion, three errors in total.
        assertEquals(2, report.raw.substitutions)
        assertEquals(1, report.raw.deletions)
        assertEquals(3, report.raw.errors)
    }

    // ---- Plain errors, both passes identical ---------------------------------------------------

    @Test
    fun `an errorful transcript scores 2 substitutions 2 deletions 2 insertions`() {
        val report = Wer.report(
            "the operator recorded the reading and signed the log before the morning shift ended",
            "the operator recorded a reading signed the log after morning shift ended and left",
            lang = "en",
        )

        for (pass in listOf(report.raw, report.normalised)) {
            assertEquals(14, pass.referenceWords)
            assertEquals(2, pass.substitutions)
            assertEquals(2, pass.deletions)
            assertEquals(2, pass.insertions)
            assertEquals(100.0 * 6 / 14, pass.werPercent, 1e-9)
        }
        assertEquals(
            listOf(
                Triple("the", "a", 1),
                Triple("and", "<dropped>", 1),
                Triple("before the", "after", 1),
                Triple("<inserted>", "and left", 1),
            ),
            report.topPairs,
        )
    }

    // ---- German --------------------------------------------------------------------------------

    @Test
    fun `german numerals normalise to a perfect score`() {
        val report = Wer.report(
            "Die Temperatur betrug neunzehn Komma vier Grad am siebenundzwanzigsten März, " +
                "Charge zweihundertsiebzehn Strich vier.",
            "die temperatur betrug 19,4 grad am 27. märz charge 217-4",
            lang = "de",
        )

        assertEquals(10, report.normalised.referenceWords)
        assertEquals(0, report.normalised.errors)
        // Raw: the three unexpanded number phrases, 14 reference words against 10 -- three
        // substitutions and the four surplus words deleted, seven errors in total.
        assertEquals(14, report.raw.referenceWords)
        assertEquals(3, report.raw.substitutions)
        assertEquals(4, report.raw.deletions)
        assertEquals(7, report.raw.errors)
    }

    /**
     * Speaker tags are markup, not speech.
     *
     * The corpus in `docs/data` carries none, so [WerGoldenTest] cannot see this rule -- proved by
     * reverting it and watching every golden still pass. It needs pinning here because the mistake
     * is silent and expensive: a reference imported with `[S1]`/`[S2]` scored those as the words
     * "s1"/"s2" and charged a deletion for each, about 2.5 points on a 600-word dialogue, and the
     * inflated number looks like a worse recogniser rather than a dirty reference.
     */
    @Test
    fun `speaker tags are stripped like any other markup`() {
        assertEquals(
            listOf("thanks", "for", "coming", "to", "the", "audit"),
            Wer.normalise("[S1] Thanks for coming to the audit.", expandNumbers = true, lang = "en"),
        )
        assertEquals(
            listOf("batch", "9", "failed"),
            Wer.normalise(
                "[NON-CONFORMITY] Batch nine failed. [/NON-CONFORMITY]",
                expandNumbers = true,
                lang = "en",
            ),
        )
        // A tagged reference against a clean transcript must score zero, not two deletions.
        assertEquals(
            0,
            Wer.report("[S1] the seal was intact", "the seal was intact", "en").normalised.errors,
        )
    }

    // ---- Word boundaries are Unicode, and portably so -------------------------------------------

    /**
     * "achtägige" must survive whole: Python's `\b` is Unicode, so "acht" inside it is not a word.
     * A bare Java `\b` is ASCII and would see a boundary between "t" and "ä", rewriting a real
     * German word to "8ägige" — the reason [Wer] spells its boundaries out as lookarounds.
     *
     * That spelling also has to be portable: the first device run died in [Wer]'s static
     * initialiser because `(?U)` is a JVM-only inline flag and Android's regex engine is ICU.
     * This test pins the meaning; the lookarounds are what make it hold on both engines.
     */
    @Test
    fun `a number word inside a longer german word is left alone`() {
        assertEquals(
            listOf("der", "achtägige", "zeitraum", "und", "8", "grad"),
            Wer.normalise("der achtägige Zeitraum und acht Grad", expandNumbers = true, lang = "de"),
        )
    }

    // ---- The diff is a minimum edit distance ---------------------------------------------------

    /**
     * Reordered phrases: difflib keeps the longest block and reports the moved phrase as a
     * deletion plus an insertion (6 errors), where a mover-aware or different alignment could
     * score less. Pinned because this is exactly where a Levenshtein port would quietly diverge
     * from every historical measurement.
     */
    @Test
    fun `a moved phrase costs three substitutions, not a delete plus an insert`() {
        val report = Wer.report(
            "check the seal check the valve check the pump then stop",
            "check the valve check the pump check the seal then stop",
            lang = "en",
        )

        // The three nouns rotate. An optimal script substitutes each in place -- three errors --
        // where the longest-match diff moved a whole "check the seal" block and charged six
        // (three deletions plus three insertions), doubling the rate for one transposition.
        assertEquals(3, report.normalised.substitutions)
        assertEquals(0, report.normalised.deletions)
        assertEquals(0, report.normalised.insertions)
        assertEquals(
            listOf(
                Triple("seal", "valve", 1),
                Triple("valve", "pump", 1),
                Triple("pump", "seal", 1),
            ),
            report.topPairs,
        )
    }

    /** A replace block is charged max(refLen, hypLen), difflib-style -- not sub+ins/del. */
    /**
     * The failure that replaced the diff, in miniature.
     *
     * A 29-minute recording played a dialogue twice; the reference held it twice. The longest-match
     * diff anchored the reference's *first* copy against the hypothesis's *second* pass, charged
     * the leftovers as 1,899 deletions plus 1,858 insertions, and reported **103.9%** for a
     * transcript that was 8.7% wrong. Repetition is exactly where longest-match alignment has no
     * reason to prefer the right copy, and real recognitions of the same audio always differ
     * slightly -- which is enough to tip it onto the wrong one.
     */
    @Test
    fun `a repeated reference aligns pass for pass`() {
        val once = "the operator checked the seal and signed the log"
        val report = Wer.report(
            "$once $once",
            // Two recognitions of the same words, each wrong in its own place -- as two passes
            // over repeated audio always are.
            "the operator checked the seat and signed the log " +
                "the operator checked the seal and signed the dog",
            lang = "en",
        )

        assertEquals(18, report.normalised.referenceWords)
        assertEquals(2, report.normalised.substitutions)
        assertEquals(0, report.normalised.deletions)
        assertEquals(0, report.normalised.insertions)
        assertEquals(100.0 * 2 / 18, report.normalised.werPercent, 1e-9)
    }

    @Test
    fun `a lopsided replace splits into a substitution and deletions`() {
        val score = Wer.score(
            listOf("a", "b", "c", "d", "e"),
            listOf("a", "x", "e"),
        )
        // "b c d" vs "x": one word substituted, two dropped. Still three errors, as when the whole
        // block was charged max(3, 1) as substitutions -- but the breakdown now says what happened.
        assertEquals(1, score.substitutions)
        assertEquals(2, score.deletions)
        assertEquals(0, score.insertions)
        assertEquals(3, score.errors)
    }

    // ---- Tokeniser edges -----------------------------------------------------------------------

    @Test
    fun `edge punctuation is stripped but interior punctuation is kept`() {
        assertEquals(
            listOf("versand", "19,4", "t42-716"),
            Wer.normalise("Versand. 19,4 T42-716,", expandNumbers = false, lang = "en"),
        )
    }

    @Test
    fun `disallowed characters split tokens rather than vanishing`() {
        // The char filter replaces with a space, so "it's" becomes two tokens -- as in Python.
        assertEquals(
            listOf("it", "s", "8", "5"),
            Wer.normalise("it's 8/5", expandNumbers = false, lang = "en"),
        )
    }

    @Test
    fun `an empty reference does not divide by zero`() {
        val report = Wer.report("", "whatever was said", lang = "en")
        assertEquals(0, report.normalised.referenceWords)
        assertEquals(0.0, report.normalised.werPercent, 0.0)
    }
}
