package com.example.aiagenttestapp.prompts.audit

import com.example.aiagent.engine.core.ContextWindow

/**
 * The prompt that turns per-chunk findings into the final report, and the arithmetic that decides
 * how many of those findings fit in it.
 *
 * The budget lives with the prompt it measures rather than with the other token maths: what fits is
 * a property of this prompt's fixed text, so the two have to change together or the summary starts
 * overflowing the context.
 */
object AuditSummaryPrompts {

    /**
     * REDUCE stage. Takes only the facts gathered per chunk, in document order, plus the stated
     * [verdict] if any chunk captured one.
     *
     * Deliberately does NOT receive the non-conformities or actions: those are unioned and
     * deduplicated in code ([AuditChunker.mergeFindings]). The model's only job here is prose, which
     * keeps the decode budget small and removes any opportunity to drop a finding.
     *
     * Plain prose out, no JSON wrapper: there is no structure to enforce in a paragraph, and forcing
     * long text through a JSON string only invites the escaping mistakes (raw newlines, bare quotes)
     * that a small model makes. The caller takes the reply as-is.
     *
     * The one prompt in the pipeline whose payload is not bounded by chunking: a document may hold
     * up to [AuditQueue.MAX_CHUNKS] sections, each contributing facts, so [maxNoteChars] caps what
     * is carried. Past the cap the notes are trimmed to an even share per section rather than
     * truncated at the tail -- an over-long prompt loses its *start* to eviction, and dropping the
     * tail instead would lose the closing section, which is where an audit states its result. Both
     * failures are silent in the finished summary, which is why the cap is applied here rather than
     * left to the engine.
     *
     * @param factsByPart facts lists in document order, one entry per chunk
     * @param verdict the document's stated overall result, verbatim, or "" if it states none
     * @param maxNoteChars characters of notes this prompt may carry; see [summaryNoteBudget]
     */
    fun finalSummary(
        factsByPart: List<List<String>>,
        verdict: String = "",
        maxNoteChars: Int = Int.MAX_VALUE,
    ): String = buildString {
        appendLine("You are writing the overall summary of one document.")
        appendLine("Below are factual notes taken from each section of it, in order.")
        appendLine()
        appendLine("Write a detailed summary of the document as a whole: what it covers, what was")
        appendLine("checked, what happened, and what state things were in.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only the notes below. Every statement must trace back to a note.")
        appendLine("- Do not add context, do not speculate, do not fill gaps. If the notes do not")
        appendLine("  say something, leave it out.")
        appendLine("- Keep the specifics: dates, numbers, names and equipment stay in.")
        if (verdict.isNotBlank()) {
            // The stated result must survive in its own words. Summarised through a small model it
            // otherwise drifts onto whatever scale the model prefers -- the exact failure the
            // verdict field exists to prevent.
            appendLine("- The document states its overall result as \"$verdict\". End the summary by")
            appendLine("  reporting that result in exactly those words -- do not reword it.")
        }
        // Unconditional, where the no-judgement rule used to ride along with a stated verdict. A
        // document that reached no conclusion is the case where a model is *most* inclined to
        // supply one, and prose is where an invented verdict is hardest to see: it arrives as a
        // sentence rather than a field, so nothing downstream can tell it was never in the notes.
        appendLine("- Do not judge, grade or conclude anything yourself. Report what the notes say")
        appendLine("  and what result the document stated, if it stated one. If it did not, say so")
        appendLine("  plainly and leave it there.")
        appendLine("- Be as long as the material supports and no longer. Do not pad to feel complete.")
        appendLine()
        appendLine("Reply with the summary as plain text only. No headings, no JSON, no code fences.")
        appendLine()
        appendLine("----- BEGIN NOTES -----")
        trimNotes(factsByPart, maxNoteChars).forEachIndexed { index, facts ->
            append(sectionHeader(index))
            facts.forEach { appendLine("- $it") }
        }
        appendLine("----- END NOTES -----")
    }


    /** What [finalSummary] will spend on [factsByPart], headers and bullet markers included. */
    fun noteChars(factsByPart: List<List<String>>): Int =
        factsByPart.foldIndexed(0) { index, total, facts ->
            total + sectionHeader(index).length + facts.sumOf { it.length + LINE_OVERHEAD }
        }


    /**
     * Characters of notes [finalSummary] may carry on a model with [contextTokens] of context, once
     * its own scaffolding, the loaded system prompt and room for the prose reply are set aside.
     *
     * Floored at [MIN_SUMMARY_NOTE_TOKENS] so a small model still gets *some* notes -- a summary
     * written from a handful of facts is thin, but one written from none is a blank report.
     */
    fun summaryNoteBudget(contextTokens: Int, verdict: String = ""): Int {
        val free = contextTokens -
            ContextWindow.estimateTokens(AuditSystemPrompts.SYSTEM_PROMPT) -
            ContextWindow.estimateTokens(finalSummary(emptyList(), verdict)) -
            SUMMARY_OUTPUT_RESERVE_TOKENS
        return ContextWindow.estimateChars(free.coerceAtLeast(MIN_SUMMARY_NOTE_TOKENS))
    }


    /**
     * Tokens held back for the summary itself, and the cap the summary turn is actually stopped at.
     *
     * Reservation and limit are deliberately the same number: a stage allowed to generate more than
     * was reserved for it overflows the window, which is the failure this pipeline keeps finding new
     * ways to hit. Larger than a chunk's reserve because this stage generates long prose by design,
     * and larger again than it once was because a reasoning model spends part of the allowance on a
     * think block before the summary starts.
     */
    const val SUMMARY_OUTPUT_RESERVE_TOKENS = 2048


    /** Notes floor: below this a reduce prompt has too little to summarise to be worth running. */
    internal const val MIN_SUMMARY_NOTE_TOKENS = 256


    /**
     * Trims [factsByPart] to fit [maxChars], so the summary still spans the document end to end
     * rather than stopping wherever the budget ran out.
     *
     * The share is worked out in two passes, because an equal split alone is badly wasteful at
     * length: on an 80-section document a ~9,600-char budget is 120 characters a section, so every
     * dense section is cut to a single fact while the light ones hand back room nobody uses. Instead
     * sections that fit within the equal share keep everything, and what they do not spend is
     * redistributed among the sections that overflowed. On a real document -- a few dense sections
     * among many light ones -- that is the difference between one fact each and most sections
     * surviving whole.
     *
     * A section keeps at least its first fact, truncated if need be: a section silently contributing
     * nothing to the summary is the failure this exists to prevent.
     */
    internal fun trimNotes(factsByPart: List<List<String>>, maxChars: Int): List<List<String>> {
        if (maxChars == Int.MAX_VALUE) return factsByPart
        val sections = factsByPart.count { it.isNotEmpty() }
        if (sections == 0 || noteChars(factsByPart) <= maxChars) return factsByPart

        // Pass one: what each section would cost, measured against an equal share.
        val equalShare = (maxChars / sections).coerceAtLeast(1)
        val costs = factsByPart.mapIndexed { index, facts ->
            if (facts.isEmpty()) 0
            else sectionHeader(index).length + facts.sumOf { it.length + LINE_OVERHEAD }
        }
        // Pass two: the room the sections that fit did not use, split among those that did not.
        val unspent = costs.filter { it in 1..equalShare }.sumOf { equalShare - it }
        val overflowing = costs.count { it > equalShare }
        val share = if (overflowing > 0) equalShare + unspent / overflowing else equalShare
        return factsByPart.mapIndexed { index, facts ->
            if (facts.isEmpty()) return@mapIndexed facts
            // The header is charged first, so a section's share covers what it will actually render.
            val header = sectionHeader(index).length
            var used = header
            val kept = facts.takeWhile {
                used += it.length + LINE_OVERHEAD
                used <= share
            }
            // A first fact longer than its section's entire share is truncated, not kept whole and
            // not dropped. Dropping loses the section from the summary; keeping it whole puts the
            // prompt back over the window, where the eviction it causes is silent and costs the
            // *start* of the document. Only reachable when one fact outruns the whole share, which
            // the "short lines" instruction makes rare.
            kept.ifEmpty {
                listOf(facts.first().take((share - header - LINE_OVERHEAD).coerceAtLeast(1)))
            }
        }
    }


    internal fun sectionHeader(index: Int) = "Section ${index + 1}:\n"


    /** The "- " and newline each note line costs on top of the fact itself. */
    internal const val LINE_OVERHEAD = 3
}
