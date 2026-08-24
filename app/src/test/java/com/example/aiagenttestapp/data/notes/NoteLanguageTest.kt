package com.example.aiagenttestapp.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the language vote, using the shape that actually broke on device.
 *
 * The bug it replaces was not subtle once seen: three runs of the same English recording were stored
 * as `en`, `id` and `ms`, because whichever language the first slice named became the note's, and the
 * first slice is lead-in silence or half a word. The tests below are written from that recording's
 * shape -- one bad slice at the front, dozens of good ones behind it.
 */
class NoteLanguageTest {

    private fun slice(code: String?, seconds: Int = 20) =
        LanguageVote(code, seconds * 16_000)

    /** The exact failure: a wrong first slice against a recording that is plainly English. */
    @Test
    fun `a wrong first slice does not decide the note`() {
        val votes = listOf(slice("ms")) + List(20) { slice("en") }
        assertEquals("en", dominantLanguage(votes))
    }

    /** And the same when the noise is scattered rather than at the front. */
    @Test
    fun `scattered disagreement loses to the bulk of the recording`() {
        val votes = listOf(
            slice("en"), slice("id"), slice("en"), slice("ms"),
            slice("en"), slice("en"), slice("id"), slice("en"),
        )
        assertEquals("en", dominantLanguage(votes))
    }

    /**
     * Duration is the weight, not slice count. Four fragments naming one language must not outvote
     * the twenty minutes of speech that named another.
     */
    @Test
    fun `long slices outweigh many short ones`() {
        val votes = listOf(
            slice("de", seconds = 25),
            slice("de", seconds = 25),
            slice("en", seconds = 5),
            slice("en", seconds = 5),
            slice("en", seconds = 5),
            slice("en", seconds = 5),
        )
        assertEquals("de", dominantLanguage(votes))
    }

    /** Below the floor a guess is a coin toss, so it does not get to vote at all. */
    @Test
    fun `slices too short to identify a language are ignored`() {
        val votes = listOf(
            slice("ms", seconds = 1),
            slice("id", seconds = 2),
            slice("en", seconds = 20),
        )
        assertEquals("en", dominantLanguage(votes))
    }

    /** Slices that reported nothing are not evidence for anything. */
    @Test
    fun `null votes are skipped rather than counted`() {
        val votes = listOf(slice(null), slice(null), slice("en"), slice(null))
        assertEquals("en", dominantLanguage(votes))
    }

    /**
     * A genuinely short note still deserves its answer. Its only slice is under the floor, but it is
     * also the entire recording -- discarding it would report "no language" for a note that clearly
     * had one.
     */
    @Test
    fun `a short note falls back to its only opinion`() {
        assertEquals("en", dominantLanguage(listOf(slice("en", seconds = 2))))
    }

    @Test
    fun `no opinions at all stays null`() {
        assertNull(dominantLanguage(emptyList()))
        assertNull(dominantLanguage(listOf(slice(null), slice(null))))
    }

    /**
     * A real bilingual recording is not a bug, and this deliberately does not try to be clever about
     * it: the note carries one language, so the one with more speech behind it wins.
     */
    @Test
    fun `a genuinely mixed recording picks the language with more speech`() {
        val votes = listOf(
            slice("de", seconds = 20), slice("de", seconds = 20), slice("de", seconds = 20),
            slice("en", seconds = 20), slice("en", seconds = 20),
        )
        assertEquals("de", dominantLanguage(votes))
    }
}
