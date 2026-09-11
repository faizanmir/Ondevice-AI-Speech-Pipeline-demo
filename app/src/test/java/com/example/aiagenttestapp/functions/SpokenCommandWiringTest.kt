package com.example.aiagenttestapp.functions

import com.example.aiagenttestapp.stt.KeywordAction
import com.example.aiagenttestapp.stt.SpokenKeywords
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That every spoken command the speech library can report is one this app can actually run.
 *
 * This lived in `SpokenKeywordsTest` while both halves were in the same module. They are not any
 * more: `SpokenKeywords` is `:stt`'s -- it knows what phrases the spotter listens for -- while
 * `VoiceCommands` is the app's dispatch table. Neither side can assert this alone, which makes it
 * exactly the kind of invariant that belongs to the host doing the joining.
 *
 * A library that declared a command id nothing here handled would fail quietly: the user would say
 * the phrase, the spotter would hear it, and nothing would happen.
 */
class SpokenCommandWiringTest {

    @Test
    fun `command keywords reuse real VoiceCommands ids`() {
        val known = VoiceCommands.specs.map { it.id }.toSet()

        val dangling = SpokenKeywords.entries
            .mapNotNull { SpokenKeywords.actionFor(it.id) as? KeywordAction.Command }
            .map { it.id }
            .distinct()
            .filterNot { it in known }

        // A spoken command must land in the same dispatch table as a typed one, or "open settings"
        // would mean two different things depending on which detector heard it.
        assertEquals(emptyList<String>(), dangling)
    }
}
