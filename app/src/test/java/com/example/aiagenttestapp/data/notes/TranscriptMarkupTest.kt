package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markup is handed to the user in an editable text field, so the parser's real job is surviving
 * whatever comes back out of it. These lean hard on the malformed cases for that reason.
 */
class TranscriptMarkupTest {

    private val known = setOf("Alice", "Bob")

    // -------- render / parse round trip --------

    @Test
    fun `round trips speakers and tags`() {
        val blocks = listOf(
            TranscriptBlock("Alice", "Checked bay 3 this morning."),
            TranscriptBlock("Alice", "the extinguisher expired last month", setOf(MarkerKind.NonConformity)),
            TranscriptBlock("Bob", "I'll order a replacement today."),
            TranscriptBlock("Bob", "Bob orders a replacement before Friday", setOf(MarkerKind.Action)),
        )

        val rendered = TranscriptMarkup.render(blocks)
        val parsed = TranscriptMarkup.parse(rendered, known)

        assertEquals(blocks.map { it.text }, parsed.map { it.text })
        assertEquals(blocks.map { it.tags }, parsed.map { it.tags })
        // Re-rendering what was parsed must be stable, or every save would churn the text.
        assertEquals(rendered, TranscriptMarkup.render(parsed))
    }

    @Test
    fun `consecutive blocks from one speaker merge into a paragraph`() {
        val rendered = TranscriptMarkup.render(
            listOf(
                TranscriptBlock("Alice", "First slice."),
                TranscriptBlock("Alice", "Second slice."),
                TranscriptBlock("Bob", "A reply."),
            ),
        )

        assertEquals("Alice: First slice. Second slice.\n\nBob: A reply.", rendered)
    }

    @Test
    fun `renders without speakers when diarisation did not run`() {
        val rendered = TranscriptMarkup.render(
            listOf(
                TranscriptBlock(null, "Checked bay 3."),
                TranscriptBlock(null, "the extinguisher expired", setOf(MarkerKind.NonConformity)),
            ),
        )

        assertEquals(
            "Checked bay 3.\n\n[NON-CONFORMITY] the extinguisher expired [/NON-CONFORMITY]",
            rendered,
        )
        assertEquals(listOf(null, null), TranscriptMarkup.parse(rendered).map { it.speaker })
    }

    // -------- hostile input --------

    @Test
    fun `a sentence containing a colon is not mistaken for a speaker`() {
        val parsed = TranscriptMarkup.parse("So the issue is: the valve leaks.", known)

        assertEquals(1, parsed.size)
        assertEquals(null, parsed[0].speaker)
        // The whole sentence survives, colon included -- no invented speaker, no truncation.
        assertEquals("So the issue is: the valve leaks.", parsed[0].text)
    }

    @Test
    fun `an unknown name is not treated as a speaker`() {
        val parsed = TranscriptMarkup.parse("Charlie: hello there", known)

        assertEquals(null, parsed[0].speaker)
        assertEquals("Charlie: hello there", parsed[0].text)
    }

    @Test
    fun `numbered unknown speakers are recognised without being enrolled`() {
        val parsed = TranscriptMarkup.parse("Speaker 2: over here", emptySet())

        assertEquals("Speaker 2", parsed[0].speaker)
        assertEquals("over here", parsed[0].text)
    }

    @Test
    fun `a tag the user failed to close still yields the tagged text`() {
        val parsed = TranscriptMarkup.parse(
            "Alice: [NON-CONFORMITY] the extinguisher expired last month",
            known,
        )

        assertEquals(1, parsed.size)
        assertEquals(setOf(MarkerKind.NonConformity), parsed[0].tags)
        assertEquals("the extinguisher expired last month", parsed[0].text)
    }

    @Test
    fun `text before and after a tag is kept as its own blocks`() {
        val parsed = TranscriptMarkup.parse(
            "Alice: before [ACTION] do the thing [/ACTION] after",
            known,
        )

        assertEquals(listOf("before", "do the thing", "after"), parsed.map { it.text })
        assertEquals(
            listOf(emptySet(), setOf(MarkerKind.Action), emptySet<MarkerKind>()),
            parsed.map { it.tags },
        )
        // The speaker carries across the tag rather than being lost at it.
        assertTrue(parsed.all { it.speaker == "Alice" })
    }

    @Test
    fun `parsing junk never throws and never loses the text`() {
        val junk = "[/ACTION] stray close [NON-CONFORMITY][ACTION] nested-ish [/NON-CONFORMITY]"

        val parsed = TranscriptMarkup.parse(junk, known)

        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.any { it.text.contains("stray close") })
    }

    @Test
    fun `blank and whitespace-only input parse to nothing`() {
        assertEquals(emptyList<TranscriptBlock>(), TranscriptMarkup.parse("", known))
        assertEquals(emptyList<TranscriptBlock>(), TranscriptMarkup.parse("   \n\n  \n ", known))
    }

    // -------- tagged item extraction --------

    @Test
    fun `tagged items are lifted out in order with their kind`() {
        val text = """
            Alice: Checked bay 3.

            [NON-CONFORMITY] the extinguisher expired [/NON-CONFORMITY]

            Bob: Noted.

            [ACTION] order a replacement [/ACTION]
        """.trimIndent()

        val items = TranscriptMarkup.taggedItems(text, known)

        assertEquals(
            listOf(
                TaggedItem(MarkerKind.NonConformity, "the extinguisher expired"),
                TaggedItem(MarkerKind.Action, "order a replacement"),
            ),
            items,
        )
    }

    @Test
    fun `speakers in a transcript are listed once each in order`() {
        val text = "Alice: one\n\nBob: two\n\nAlice: three"

        assertEquals(listOf("Alice", "Bob"), TranscriptMarkup.speakersIn(text, known))
    }

    // -------- the fallback path: markers found in already-transcribed text --------

    @Test
    fun `wraps a spoken marker pair found in plain text`() {
        val wrapped = TranscriptMarkup.wrapSpokenMarkers(
            "Checked bay 3. Start non conformity, the extinguisher expired. " +
                "End non conformity. Moving on.",
        )

        assertTrue(wrapped.contains("[NON-CONFORMITY]"))
        assertTrue(wrapped.contains("[/NON-CONFORMITY]"))
        // The trigger phrases themselves are gone from the text.
        assertFalse(wrapped.lowercase().contains("start non conformity"))
        assertFalse(wrapped.lowercase().contains("end non conformity"))

        val items = TranscriptMarkup.taggedItems(wrapped)
        assertEquals(1, items.size)
        assertEquals(MarkerKind.NonConformity, items[0].kind)
        assertTrue(items[0].text.contains("extinguisher expired"))
    }

    @Test
    fun `hyphenated and run-together spellings are both found`() {
        listOf(
            "Start non-conformity, the valve leaks. End non-conformity.",
            "Start nonconformity the valve leaks end nonconformity",
        ).forEach { raw ->
            val items = TranscriptMarkup.taggedItems(TranscriptMarkup.wrapSpokenMarkers(raw))
            assertEquals("failed for: $raw", 1, items.size)
            assertTrue("failed for: $raw", items[0].text.contains("valve leaks"))
        }
    }

    @Test
    fun `the longer action item phrase wins over the shorter one`() {
        val wrapped = TranscriptMarkup.wrapSpokenMarkers(
            "Start action item, order a new extinguisher. End action item.",
        )

        val items = TranscriptMarkup.taggedItems(wrapped)
        assertEquals(1, items.size)
        // If "start action" had matched first, "item" would be stranded at the head of the text.
        assertEquals("order a new extinguisher.", items[0].text)
    }

    @Test
    fun `ordinary speech containing the word action is not turned into a marker`() {
        listOf(
            "The pump was in action when we arrived.",
            "We need to take action on this soon.",
            "That is actionable, but not urgent.",
        ).forEach { raw ->
            val wrapped = TranscriptMarkup.wrapSpokenMarkers(raw)
            assertEquals("false positive for: $raw", raw, wrapped)
            assertEquals("false positive for: $raw", emptyList<TaggedItem>(), TranscriptMarkup.taggedItems(wrapped))
        }
    }

    @Test
    fun `an unclosed spoken marker runs to the end of the text`() {
        val wrapped = TranscriptMarkup.wrapSpokenMarkers(
            "Start non conformity, the extinguisher expired and nobody replaced it.",
        )

        val items = TranscriptMarkup.taggedItems(wrapped)
        assertEquals(1, items.size)
        assertTrue(items[0].text.contains("nobody replaced it"))
    }

    @Test
    fun `a German spoken marker is found`() {
        val wrapped = TranscriptMarkup.wrapSpokenMarkers(
            "Abweichung beginnen, der Feuerlöscher ist abgelaufen. Abweichung beenden.",
        )

        val items = TranscriptMarkup.taggedItems(wrapped)
        assertEquals(1, items.size)
        assertEquals(MarkerKind.NonConformity, items[0].kind)
        assertTrue(items[0].text.contains("Feuerlöscher"))
    }

    @Test
    fun `text with no markers is returned unchanged`() {
        val plain = "Just an ordinary note about the loading dock."
        assertEquals(plain, TranscriptMarkup.wrapSpokenMarkers(plain))
    }
}
