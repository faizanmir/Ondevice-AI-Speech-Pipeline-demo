package com.example.aiagenttestapp.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guard sits between a language model and a user's only copy of what was said, so the tests that
 * matter most are the ones proving it does *not* fire. Every "keeps" case below is a sentence a
 * person could plausibly say into a site recording that an over-eager rule would have deleted.
 */
class GemmaTranscriptGuardTest {

    // -------- stripping what the model added --------

    @Test
    fun `keeps a plain transcript untouched`() {
        val text = "Checked bay three this morning. The extinguisher tag is out of date."
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }

    @Test
    fun `strips a bare label`() {
        assertEquals(
            "The gate hinge is loose.",
            GemmaTranscriptGuard.clean("Transcript: The gate hinge is loose."),
        )
    }

    @Test
    fun `strips a conversational opener`() {
        assertEquals(
            "The gate hinge is loose.",
            GemmaTranscriptGuard.clean("Sure, here is the transcription: The gate hinge is loose."),
        )
    }

    @Test
    fun `strips a label on its own line`() {
        assertEquals(
            "The gate hinge is loose.\nIt needs a new pin.",
            GemmaTranscriptGuard.clean("Transcript:\nThe gate hinge is loose.\nIt needs a new pin."),
        )
    }

    @Test
    fun `unwraps a code fence`() {
        assertEquals(
            "The gate hinge is loose.",
            GemmaTranscriptGuard.clean("```\nThe gate hinge is loose.\n```"),
        )
    }

    @Test
    fun `unwraps a fully quoted reply`() {
        assertEquals(
            "The gate hinge is loose.",
            GemmaTranscriptGuard.clean("\"The gate hinge is loose.\""),
        )
    }

    @Test
    fun `leaves quotes that are part of the speech`() {
        // Only the ends match, but there is a quote inside -- these two are quoting something within
        // the transcript rather than wrapping it, and stripping them would unbalance the text.
        val text = "\"Stop,\" he said, and then he said \"go\""
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }

    // -------- dropping what is not a transcript at all --------

    @Test
    fun `drops a refusal`() {
        assertEquals("", GemmaTranscriptGuard.clean("I'm sorry, but I cannot transcribe this audio."))
    }

    @Test
    fun `drops a report of silence`() {
        assertEquals("", GemmaTranscriptGuard.clean("The audio contains no speech."))
    }

    @Test
    fun `drops a lone annotation`() {
        assertEquals("", GemmaTranscriptGuard.clean("[inaudible]"))
    }

    @Test
    fun `drops a label whose content was a refusal`() {
        assertEquals(
            "",
            GemmaTranscriptGuard.clean("Transcript: The audio is silent."),
        )
    }

    @Test
    fun `drops nothing for empty input`() {
        assertEquals("", GemmaTranscriptGuard.clean("   "))
    }

    // -------- the false positives that matter --------

    @Test
    fun `keeps short speech that mentions silence`() {
        val text = "The alarm is silent, so the panel must be dead."
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }

    @Test
    fun `keeps a short apology`() {
        val text = "I'm sorry about that, I dropped the clipboard."
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }

    @Test
    fun `keeps someone saying a bit was inaudible`() {
        val text = "That last bit was inaudible over the compressor."
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }

    @Test
    fun `keeps a long transcript that happens to contain a marker phrase`() {
        // Over the length bound, so the markers are never consulted. This is the whole reason the
        // bound exists: people talk about recordings on recordings.
        val text = "Right, so I'm walking into bay three now and the first thing to say is that " +
            "the audio contains a lot of background noise from the compressor, which is running " +
            "again despite being flagged last week. Make a note of that for the report please."
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }

    @Test
    fun `keeps a sentence beginning with a word from the label list`() {
        val text = "Transcription of the meeting is due on Friday."
        assertEquals(text, GemmaTranscriptGuard.clean(text))
    }
}
