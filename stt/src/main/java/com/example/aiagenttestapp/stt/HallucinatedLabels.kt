package com.example.aiagenttestapp.stt

/**
 * Removes the turn labels a recogniser learned from its training transcripts and emits as words.
 *
 * **What was seen.** On a German question-and-answer recording the FastConformer en/de/es/fr model
 * produced `Vors.` 74 times and `Angekl.` 46 times -- every single one a standalone token immediately
 * after a sentence end, at a change of speaker. They are not mishearings: *Vors.* is *Vorsitzender*
 * and *Angekl.* is *Angeklagter*, the turn labels of a German court protocol. The model has seen
 * enough of those to have learned that a dialogue comes with labels, and at each hand-over it writes
 * the one it expects -- the presiding judge when the questioner resumes, the defendant when the
 * answer comes. Those 120 tokens were 30% of the file's word error rate (10.3% against ~7.2%
 * without them), sitting inside every German figure on the scoreboard, and no percentage could
 * have shown them; the itemised comparison did in one glance.
 *
 * **What this does.** Drops a token that is exactly one of [LABELS] -- the abbreviation with its
 * full stop, allowing trailing punctuation -- from both the text and the timed words, so the
 * diarised transcript (built from the words) and the plain transcript (built from the text) agree.
 * Nothing else is touched: `vorschlagen`, `Vorsitzende`, `angeklagt` and every real word pass
 * through, because a full stop glued onto a bare abbreviation is not something a person says.
 *
 * **What it deliberately does not do.** It is applied only to the model that was observed doing
 * this, and the list grows only from observation: a plausible sibling such as *Zeuge* (witness) is
 * also an ordinary word, and stripping words on suspicion would trade a visible artefact for a silent
 * loss. If another label turns up, add it here with the recording it turned up in.
 */
object HallucinatedLabels {

    /** Observed 2026-08-31 on `long_de` / `german_mixed_long` (Piper voices Kerstin and Thorsten). */
    private val LABELS = setOf("Vors.", "Angekl.")

    private val TOKEN = Regex("""^(Vors|Angekl)\.[,;:!?]*$""")
    private val IN_TEXT = Regex("""(?<!\S)(Vors|Angekl)\.(?=[,;:!?]*(\s|$))""")

    /** Only the model that was caught doing it. */
    fun applies(modelId: String?): Boolean = modelId?.startsWith("fastconformer") == true

    fun stripWords(words: List<TimedWord>): List<TimedWord> =
        if (words.none { TOKEN.matches(it.text.trim()) }) words else words.filterNot { TOKEN.matches(it.text.trim()) }

    fun stripText(text: String): String {
        if (LABELS.none { it in text }) return text
        return IN_TEXT.replace(text, "").replace(Regex(" {2,}"), " ").trim()
    }
}
