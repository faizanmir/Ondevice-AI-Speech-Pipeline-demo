package com.example.aiagenttestapp.data.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogTurnsTest {

    private var nextId = 0L

    private fun block(
        speaker: String,
        cluster: Int,
        start: Int,
        end: Int,
        text: String,
    ) = DiarizedBlock(
        id = ++nextId,
        recordingId = 1,
        startSample = start,
        endSample = end,
        cluster = cluster,
        speakerName = speaker,
        text = text,
    )

    @Test
    fun `a conversation keeps one turn per speaker change`() {
        val turns = DialogTurns.from(
            listOf(
                block("Anita", 0, 0, 100, "morning"),
                block("Ben", 1, 100, 200, "morning"),
                block("Anita", 0, 200, 300, "shall we start"),
            ),
        )

        assertEquals(listOf("Anita", "Ben", "Anita"), turns.map { it.speakerName })
        assertEquals(listOf("morning", "morning", "shall we start"), turns.map { it.text })
    }

    /**
     * The case this grouping exists for: the diariser split one person into two clusters and
     * enrolment matched both halves to the same voice, so the blocks say "Anita, then Anita".
     */
    @Test
    fun `two clusters resolved to one name read as one turn`() {
        val turns = DialogTurns.from(
            listOf(
                block("Anita", 0, 0, 100, "so the plan is"),
                block("Anita", 3, 100, 260, "we ship on friday"),
                block("Ben", 1, 260, 300, "agreed"),
            ),
        )

        assertEquals(2, turns.size)
        assertEquals("so the plan is we ship on friday", turns[0].text)
        // The turn spans both blocks, and keeps the first one's start -- which is where the
        // timestamp on screen points and where the list's key comes from.
        assertEquals(0, turns[0].startSample)
        assertEquals(260, turns[0].endSample)
        assertEquals(1L, turns[0].id)
        assertEquals(0, turns[0].cluster)
    }

    @Test
    fun `distinct unknown speakers are not merged`() {
        val turns = DialogTurns.from(
            listOf(
                block("Unknown Speaker 1", 0, 0, 100, "hello"),
                block("Unknown Speaker 2", 1, 100, 200, "hello back"),
            ),
        )

        assertEquals(2, turns.size)
    }

    @Test
    fun `an empty transcript has no turns`() {
        assertEquals(emptyList<DialogTurn>(), DialogTurns.from(emptyList()))
    }

    @Test
    fun `a blank block does not leave a stray space in the turn`() {
        val turns = DialogTurns.from(
            listOf(
                block("Anita", 0, 0, 100, "one"),
                block("Anita", 2, 100, 200, "   "),
                block("Anita", 3, 200, 300, "two"),
            ),
        )

        assertEquals(1, turns.size)
        assertEquals("one two", turns[0].text)
    }
}
