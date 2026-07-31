package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.normalizeSpokenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the hardcoded BPE token sequences in [SpokenKeywords].
 *
 * The tokens were generated offline from the model's `bpe.model`, and a keyword built from a token the
 * model does not know does not error -- it simply never matches. That makes the failure mode of a
 * model bump "spoken markers quietly stopped working", which nobody notices until an inspection has
 * already been recorded without them. Checking every token against the shipped vocabulary turns that
 * into a red build instead.
 *
 * `kws-gigaspeech-tokens.txt` in test resources is the `tokens.txt` from
 * sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile. It must be replaced whenever the model
 * in `AudioModelCatalog.KEYWORDS` changes.
 */
class SpokenKeywordsTest {

    private val vocabulary: Set<String> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("kws-gigaspeech-tokens.txt")
        assertNotNull("kws-gigaspeech-tokens.txt is missing from test resources", stream)
        stream!!.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                // Each line is "<token> <id>", and a token may itself be a space (" 3"), so split
                // from the right and keep everything before the id.
                line.takeIf { it.isNotBlank() }?.substringBeforeLast(' ')
            }.toSet()
        }
    }

    @Test
    fun `every keyword token exists in the model vocabulary`() {
        val unknown = SpokenKeywords.entries.flatMap { entry ->
            entry.tokens.split(" ")
                .filter { it.isNotBlank() }
                .filterNot { it in vocabulary }
                .map { "${entry.phrase}: $it" }
        }

        assertEquals(
            "these tokens are not in the keyword model's vocabulary, so they can never match",
            emptyList<String>(),
            unknown,
        )
    }

    @Test
    fun `every keyword id maps to an action`() {
        val orphans = SpokenKeywords.entries
            .map { it.id }
            .distinct()
            .filter { SpokenKeywords.actionFor(it) == null }

        assertEquals("keyword ids the spotter can return but nothing acts on", emptyList<String>(), orphans)
    }

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

    @Test
    fun `keywords spec renders one line per entry with its id`() {
        val lines = SpokenKeywords.keywordsSpec().lines()

        assertEquals(SpokenKeywords.entries.size, lines.size)
        SpokenKeywords.entries.forEachIndexed { index, entry ->
            val line = lines[index]
            assertTrue("$line should start with its tokens", line.startsWith(entry.tokens))
            assertTrue("$line should carry its id", line.endsWith("@${entry.id}"))
            if (entry.threshold != null) {
                assertTrue("$line should carry its threshold", line.contains("#${entry.threshold}"))
            }
        }
    }

    @Test
    fun `all four marker edges have spoken phrases`() {
        for (kind in MarkerKind.entries) {
            for (edge in MarkerEdge.entries) {
                val phrases = SpokenKeywords.spokenPhrases[kind to edge]
                assertTrue("$kind/$edge has no spoken phrases", !phrases.isNullOrEmpty())
            }
        }
    }

    @Test
    fun `spoken phrases are stored normalised and longest first`() {
        for ((key, phrases) in SpokenKeywords.spokenPhrases) {
            phrases.forEach { phrase ->
                assertEquals("$key phrase should already be normalised", normalizeSpokenText(phrase), phrase)
            }
            // Longest-first is load-bearing: "start action item" must be tried before "start action",
            // or the shorter phrase matches and leaves "item" stranded in the tagged text.
            assertEquals(
                "$key phrases should be sorted longest first",
                phrases.sortedByDescending { it.length },
                phrases,
            )
        }
    }

    @Test
    fun `hyphenation and spacing of non-conformity fold together`() {
        val starts = SpokenKeywords.spokenPhrases.getValue(
            MarkerKind.NonConformity to MarkerEdge.Start,
        )

        // The three ways a recogniser might write it all normalise onto a registered phrase.
        listOf(
            "Start non-conformity",
            "start   non conformity",
            "START NON CONFORMITY.",
        ).forEach { raw ->
            assertTrue("$raw should match a registered phrase", normalizeSpokenText(raw) in starts)
        }
    }
}
