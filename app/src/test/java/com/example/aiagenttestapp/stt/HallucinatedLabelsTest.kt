package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinatedLabelsTest {

    private fun w(text: String, at: Float) = TimedWord(text, at, at + 0.3f)

    @Test
    fun `the two observed labels are dropped from the words, nothing else is`() {
        val words = listOf(w("Verfahren.", 1f), w("Vors.", 1.4f), w("Ich", 1.8f), w("frage,", 2.1f), w("Angekl.", 5f), w("Etwa", 5.3f))
        val out = HallucinatedLabels.stripWords(words)
        assertEquals(listOf("Verfahren.", "Ich", "frage,", "Etwa"), out.map { it.text })
    }

    @Test
    fun `real words that start the same way are untouched`() {
        val words = listOf(w("vorschlagen", 0f), w("Vorsitzende", 1f), w("angeklagt", 2f), w("Vors", 3f))
        assertSame(words, HallucinatedLabels.stripWords(words))
    }

    @Test
    fun `text loses the label and its spacing is repaired`() {
        assertEquals(
            "so lautet das Verfahren. Ich frage, weil ich",
            HallucinatedLabels.stripText("so lautet das Verfahren. Vors. Ich frage, weil ich"),
        )
        assertEquals(
            "gewartet? Es gab einen Fall",
            HallucinatedLabels.stripText("gewartet? Angekl. Es gab einen Fall"),
        )
    }

    @Test
    fun `text without a label comes back as the same object`() {
        val text = "Wir führen alles im Dokumentenmanagementsystem."
        assertSame(text, HallucinatedLabels.stripText(text))
    }

    @Test
    fun `a label glued to trailing punctuation still goes`() {
        assertEquals(listOf("Ja."), HallucinatedLabels.stripWords(listOf(w("Angekl.,", 0f), w("Ja.", 1f))).map { it.text })
    }

    @Test
    fun `only the FastConformer model is filtered`() {
        assertTrue(HallucinatedLabels.applies("fastconformer-en-de-es-fr"))
        assertFalse(HallucinatedLabels.applies("parakeet-tdt-0.6b-v3"))
        assertFalse(HallucinatedLabels.applies(null))
    }
}
