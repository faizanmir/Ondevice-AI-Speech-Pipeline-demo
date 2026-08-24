package com.example.aiagenttestapp.data.notes

import com.example.aiagent.engine.core.normalizeSpokenText
import com.example.aiagenttestapp.functions.MarkerEdge
import com.example.aiagenttestapp.functions.MarkerKind
import com.example.aiagenttestapp.functions.SpokenKeywords

/** One possibly tagged piece of a transcript. */
data class TranscriptBlock(
    val text: String,
    val tags: Set<MarkerKind> = emptySet(),
)

/** A tagged item lifted out of a transcript, ready to hand to the summariser as ground truth. */
data class TaggedItem(
    val kind: MarkerKind,
    val text: String,
)

/**
 * The on-disk and on-screen form of a transcript that carries tags.
 *
 * A strict, re-parsable plain-text format rather than a structured column, for one reason: the review
 * screen hands the user an editable text field, and they *must* be able to fix what the recogniser got
 * wrong. Any representation the user cannot edit in place would mean either giving that up or building
 * a parallel structured editor. Text they can correct, and this parser reads their corrections back.
 *
 * ```
 * Checked bay 3 this morning.
 *
 * [NON-CONFORMITY] the extinguisher expired last month [/NON-CONFORMITY]
 *
 * I'll order a replacement today.
 *
 * [ACTION] order a replacement extinguisher before Friday [/ACTION]
 * ```
 *
 * Parsing never throws. Everything it cannot make sense of degrades to plain text, because the input
 * is a text box a human has been typing in and losing their note to a format error would be
 * indefensible. That also covers notes written before speaker identification was removed: a leftover
 * `Alice: ` prefix is no longer structural, so it simply stays in the text it was already part of.
 */
object TranscriptMarkup {

    private const val NC_OPEN = "[NON-CONFORMITY]"
    private const val NC_CLOSE = "[/NON-CONFORMITY]"
    private const val ACTION_OPEN = "[ACTION]"
    private const val ACTION_CLOSE = "[/ACTION]"

    private fun openTag(kind: MarkerKind) =
        if (kind == MarkerKind.NonConformity) NC_OPEN else ACTION_OPEN

    private fun closeTag(kind: MarkerKind) =
        if (kind == MarkerKind.NonConformity) NC_CLOSE else ACTION_CLOSE

    /**
     * Renders blocks to the text the user will see and edit.
     *
     * Consecutive blocks with the same tags are merged, so speech that ran across three recogniser
     * slices reads as one paragraph instead of three stuttered ones.
     */
    fun render(blocks: List<TranscriptBlock>): String {
        val merged = mergeAdjacent(blocks)

        return merged.joinToString("\n\n") { block ->
            block.tags.fold(block.text.trim()) { text, kind ->
                "${openTag(kind)} $text ${closeTag(kind)}"
            }
        }.trim()
    }

    /** Reads a transcript back, including one a user has edited by hand. */
    fun parse(text: String): List<TranscriptBlock> {
        val blocks = mutableListOf<TranscriptBlock>()

        for (paragraph in text.split(Regex("\n\\s*\n"))) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue

            blocks += readTags(trimmed)
        }

        return blocks.filter { it.text.isNotBlank() }
    }

    /**
     * Splits a paragraph into tagged and untagged blocks.
     *
     * Handles a user who deleted a closing bracket: an unclosed tag simply runs to the end of the
     * paragraph rather than swallowing the rest of the note or dropping the text.
     */
    private fun readTags(paragraph: String): List<TranscriptBlock> {
        val blocks = mutableListOf<TranscriptBlock>()
        var rest = paragraph

        while (rest.isNotBlank()) {
            val next = MarkerKind.entries
                .mapNotNull { kind ->
                    rest.indexOf(openTag(kind)).takeIf { it >= 0 }?.let { kind to it }
                }
                .minByOrNull { it.second }

            if (next == null) {
                blocks += TranscriptBlock(rest.trim())
                break
            }

            val (kind, at) = next
            val before = rest.take(at).trim()
            if (before.isNotEmpty()) blocks += TranscriptBlock(before)

            val afterOpen = rest.substring(at + openTag(kind).length)
            val closeAt = afterOpen.indexOf(closeTag(kind))

            if (closeAt < 0) {
                // No closing bracket: take the remainder as the tagged text and stop.
                blocks += TranscriptBlock(afterOpen.trim(), setOf(kind))
                break
            }

            blocks += TranscriptBlock(afterOpen.take(closeAt).trim(), setOf(kind))
            rest = afterOpen.substring(closeAt + closeTag(kind).length)
        }

        return blocks.filter { it.text.isNotBlank() }
    }

    /**
     * The tagged items in a transcript, in order.
     *
     * This is what makes the explicit tags a guarantee rather than a hope: they are lifted out here, in
     * Kotlin, and handed to the summariser as ground truth to reproduce. A 1-3 B model asked to both
     * extract and classify will drop some of them, and a marker the user deliberately spoke is a
     * contract, not a suggestion.
     */
    fun taggedItems(text: String): List<TaggedItem> =
        parse(text)
            .flatMap { block -> block.tags.map { kind -> TaggedItem(kind, block.text) } }
            .filter { it.text.isNotBlank() }

    /**
     * Wraps spoken markers found in already-transcribed text.
     *
     * The fallback path for languages the keyword spotter has no model for. Unlike the spotter, which
     * cuts the audio so trigger words are never transcribed, here the words are already in the text and
     * have to be located and removed.
     *
     * Exact phrase matching only, on [normalizeSpokenText]-ed text, with the same pairing rules as
     * [SpokenMarkers.pair]. Deliberately no fuzzy matching: without acoustic evidence, edit distance on
     * recogniser output invents markers nobody spoke, and a phantom non-conformity in an inspection
     * report is worse than a missed one.
     */
    fun wrapSpokenMarkers(text: String): String {
        if (text.isBlank()) return text

        val found = findMarkerPhrases(text)
        if (found.isEmpty()) return text

        val builder = StringBuilder()
        var cursor = 0
        val open = mutableMapOf<MarkerKind, Boolean>()

        for (hit in found) {
            builder.append(text, cursor, hit.start)

            when (hit.edge) {
                MarkerEdge.Start -> {
                    // A repeated open closes the previous one, matching SpokenMarkers.pair.
                    if (open[hit.kind] == true) builder.append(' ').append(closeTag(hit.kind))
                    builder.append(' ').append(openTag(hit.kind)).append(' ')
                    open[hit.kind] = true
                }

                MarkerEdge.End -> {
                    if (open[hit.kind] == true) {
                        builder.append(' ').append(closeTag(hit.kind)).append(' ')
                        open[hit.kind] = false
                    }
                    // An end with nothing open is dropped along with its phrase.
                }
            }
            cursor = hit.endExclusive
        }

        builder.append(text, cursor, text.length)
        // Anything still open runs to the end, as in the audio path.
        open.filterValues { it }.keys.forEach { builder.append(' ').append(closeTag(it)) }

        return builder.toString()
            .replace(Regex("\\s+([.,!?;:])"), "$1")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    private data class PhraseHit(
        val kind: MarkerKind,
        val edge: MarkerEdge,
        val start: Int,
        val endExclusive: Int,
    )

    /**
     * Locates marker phrases in raw text, tolerating the punctuation and casing a recogniser adds.
     *
     * Works by scanning word boundaries in the raw string and normalising each candidate window, so the
     * offsets returned point back into the *original* text -- normalising the whole string first would
     * shift every offset and make the result unusable for editing.
     */
    private fun findMarkerPhrases(text: String): List<PhraseHit> {
        val hits = mutableListOf<PhraseHit>()

        // Longest phrase first so "start action item" beats "start action"; within a length, order is
        // irrelevant because a position is consumed once.
        val candidates = SpokenKeywords.spokenPhrases
            .flatMap { (key, phrases) -> phrases.map { Triple(key.first, key.second, it) } }
            .sortedByDescending { it.third.length }

        val wordStarts = Regex("\\b").findAll(text).map { it.range.first }.distinct().toList()

        var consumedUpTo = 0
        for (start in wordStarts) {
            if (start < consumedUpTo) continue

            val hit = candidates.firstNotNullOfOrNull { (kind, edge, phrase) ->
                matchAt(text, start, phrase)?.let { end -> PhraseHit(kind, edge, start, end) }
            } ?: continue

            hits += hit
            consumedUpTo = hit.endExclusive
        }

        return hits
    }

    /**
     * If [phrase] occurs at [start] in [text], returns the exclusive end offset in the original text.
     *
     * Compares word by word so the raw text may carry punctuation and any casing between words -- the
     * phrase is normalised, the text is not.
     */
    private fun matchAt(text: String, start: Int, phrase: String): Int? {
        val words = phrase.split(' ')
        var cursor = start

        for ((index, word) in words.withIndex()) {
            // Skip separators between words, but never at the very first word -- that would let a
            // phrase match starting from arbitrary punctuation.
            if (index > 0) {
                while (cursor < text.length && !text[cursor].isLetterOrDigit()) cursor++
            }
            val end = cursor + word.length
            if (end > text.length) return null
            if (normalizeSpokenText(text.substring(cursor, end)) != word) return null
            // The candidate must end on a word boundary, or "action" would match inside "actionable".
            if (end < text.length && text[end].isLetterOrDigit()) return null
            cursor = end
        }

        // Take trailing punctuation with the phrase so removing it does not leave a stray full stop.
        while (cursor < text.length && text[cursor] in ".,!?;:") cursor++
        return cursor
    }

    /** Collapses runs of same-tag blocks so a paragraph reads as one. */
    private fun mergeAdjacent(blocks: List<TranscriptBlock>): List<TranscriptBlock> {
        val merged = mutableListOf<TranscriptBlock>()

        for (block in blocks) {
            if (block.text.isBlank()) continue
            val last = merged.lastOrNull()

            if (last != null && last.tags == block.tags) {
                merged[merged.lastIndex] = last.copy(
                    text = "${last.text.trim()} ${block.text.trim()}".trim(),
                )
            } else {
                merged += block
            }
        }

        return merged
    }
}
