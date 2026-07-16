package com.example.aiagent.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandMatcherTest {

    private val commands = listOf(
        VoiceCommandSpec("open_settings", listOf("open settings", "open the settings")),
        VoiceCommandSpec("open_models", listOf("open models", "open model catalog")),
        VoiceCommandSpec("stop_recording", listOf("stop recording", "stop the recording")),
    )

    private fun matcher(cooldown: Long = 4_000L) = VoiceCommandMatcher(commands, cooldown)

    @Test
    fun `matches a command embedded in speech`() {
        // The whole point: the command is buried in a sentence, not spoken in isolation.
        val match = matcher().match("okay so please open settings now", 0)
        assertEquals("open_settings", match?.id)
    }

    @Test
    fun `ignores punctuation and capitalisation`() {
        // SenseVoice emits capitalised, punctuated text; the matcher must see through it.
        val match = matcher().match("Remember to email Sam. Open settings.", 0)
        assertEquals("open_settings", match?.id)
    }

    @Test
    fun `plain dictation is not a command`() {
        assertNull(matcher().match("I reopened the door and went to bed", 0))
        assertNull(matcher().match("the settings on my camera were wrong", 0))
        assertNull(matcher().match("", 0))
    }

    @Test
    fun `the same command does not re-fire within the cooldown`() {
        val m = matcher(cooldown = 4_000L)
        // The recogniser sees the phrase in three overlapping windows within two seconds.
        assertEquals("open_settings", m.match("open settings", 0)?.id)
        assertNull(m.match("open settings", 1_000))
        assertNull(m.match("open settings", 2_000))
    }

    @Test
    fun `the command fires again after the cooldown`() {
        val m = matcher(cooldown = 4_000L)
        assertEquals("open_settings", m.match("open settings", 0)?.id)
        assertEquals("open_settings", m.match("open settings", 5_000)?.id)
    }

    @Test
    fun `a different command is not blocked by another's cooldown`() {
        val m = matcher(cooldown = 4_000L)
        assertEquals("open_settings", m.match("open settings", 0)?.id)
        // stop_recording has its own cooldown, so it fires immediately.
        assertEquals("stop_recording", m.match("stop recording", 500)?.id)
    }

    @Test
    fun `the longest matching phrase wins`() {
        // "open model catalog" contains "open models"? No -- but this guards the tie-break rule.
        val ambiguous = listOf(
            VoiceCommandSpec("short", listOf("open")),
            VoiceCommandSpec("long", listOf("open settings")),
        )
        val match = VoiceCommandMatcher(ambiguous).match("please open settings", 0)
        assertEquals("long", match?.id)
    }

    @Test
    fun `reset clears cooldowns`() {
        val m = matcher()
        assertEquals("open_settings", m.match("open settings", 0)?.id)
        assertNull(m.match("open settings", 100))
        m.reset()
        assertEquals("open_settings", m.match("open settings", 200)?.id)
    }

    // --- stripCommandPhrases ---------------------------------------------------------------------

    @Test
    fun `strips a trailing command and its punctuation`() {
        val result = stripCommandPhrases(
            "Remember to email Sam. Open settings.",
            listOf("open settings"),
        )
        assertEquals("Remember to email Sam.", result)
    }

    @Test
    fun `strips a command from the middle`() {
        val result = stripCommandPhrases(
            "First stop recording then we continue",
            listOf("stop recording"),
        )
        assertEquals("First then we continue", result)
    }

    @Test
    fun `leaves an ordinary transcript untouched`() {
        val text = "Buy milk and call the dentist tomorrow."
        assertEquals(text, stripCommandPhrases(text, emptyList()))
    }
}
