package com.example.aiagenttestapp.data.audit

import com.example.aiagent.engine.core.ContextWindow

/**
 * Splits an over-long transcript into context-sized chunks for map-reduce analysis, and merges the
 * per-chunk findings back together.
 *
 * On-device context is a hard, RAM-bounded wall, so a transcript larger than the model's window is
 * read in pieces: each chunk is analysed on its own (the "map"), then the findings are combined (the
 * "reduce"). Chunks break on natural boundaries -- a blank line, else a line break -- rather than
 * mid-sentence, and overlap slightly so a finding straddling a boundary still lands whole in at
 * least one chunk. Purely mechanical and side-effect free, so it is unit-tested directly.
 */
object AuditChunker {

    /**
     * Greedily packs [text] into chunks of at most [maxChars], preferring to end a chunk on a
     * paragraph (then a line) break in the back half of the window, and repeating [overlapChars] of
     * trailing text at the start of the next chunk. Returns a single chunk when the text already
     * fits, and always makes forward progress (no infinite loop on pathological input).
     */
    /** Never chunk smaller than this, so a tiny or heavily-reserved context still makes progress. */
    const val MIN_CHUNK_TOKENS = 256

    /**
     * Tokens held back for the reply to one chunk: any `<think>` block, the plain-text findings
     * draft, and then the JSON.
     *
     * **Two thirds of what the prompt leaves free**, because this pipeline's reply is larger than the
     * text that produced it. A section is not summarised down: every finding is written three times
     * over -- once in the plain-text draft, then as a title, a detail and an evidence quote in the
     * JSON -- on top of any reasoning.
     *
     * The ratio has been measured twice, and the second measurement is why this is two thirds rather
     * than a half. Qwen3-1.7B answered a 1,194-token section in ~1,100 tokens (0.92). Gemma-4-E2B
     * needed ~2,270 for a 1,121-token section (2.0), and with a half split it was cut off mid-object
     * on section after section -- well-formed JSON, simply with nowhere left to put it.
     *
     * Erring towards the reply is deliberate. Sizing wrong in this direction costs sections that are
     * smaller than they could be; sizing wrong in the other costs sections entirely, and a truncated
     * section is worth nothing at all while a small finished one is worth what it found.
     *
     * A 1:4 split was tried first and was worse still: on a 4K model it left 512 tokens for a reply
     * that needed 1,483, and decode ran past the end of the window.
     *
     * Floored at [MIN_OUTPUT_RESERVE_TOKENS] so a small context still leaves room for a whole reply.
     * Deliberately unbounded above: on a large context the section grows too, and the ratio holds.
     */
    fun outputReserveTokens(contextTokens: Int, promptTokens: Int): Int {
        val free = (contextTokens - promptTokens).coerceAtLeast(0)
        return (free * 2 / 3).coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS)
    }

    /**
     * The reserve for [mode].
     *
     * Detailed writes every finding three times over -- draft, then title, detail and quote -- so it
     * needs two thirds of the free window for its reply. Quick writes each point once, as a short
     * line, and asks for no quotes and no draft: its reply is a fraction of the section rather than a
     * multiple of it, so a third is ample. That difference compounds -- a smaller reserve on top of a
     * shorter preamble means a bigger chunk, which means fewer sections, which is most of where the
     * time saving actually comes from.
     *
     * Still floored at [MIN_OUTPUT_RESERVE_TOKENS]: the failure mode this pipeline keeps rediscovering
     * is a reply with nowhere to go, and it costs a whole section every time.
     */
    fun outputReserveTokens(contextTokens: Int, promptTokens: Int, mode: AuditMode): Int {
        val free = (contextTokens - promptTokens).coerceAtLeast(0)
        val share = when (mode) {
            AuditMode.DETAILED -> free * 2 / 3
            AuditMode.QUICK -> free / 3
        }
        return share.coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS)
    }

    /**
     * The smallest reply worth reserving for: a `<think>` block plus a draft and JSON for a couple
     * of findings. Below this the model cannot finish an answer, and an unfinished answer is a lost
     * section, not a short one.
     */
    const val MIN_OUTPUT_RESERVE_TOKENS = 1024

    /**
     * The character budget for one chunk on a model with [contextTokens] of context, once
     * [promptTokens] (the system prompt and the extraction prompt's scaffolding, from
     * [AuditPrompts.fixedPromptTokens]) and room for that chunk's reply are set aside. So the chunk
     * fills as much of the *actual* window as is safe (a 128K model takes near-128K chunks, a 4K
     * model small ones), rather than a flat fraction that under-fills large contexts. Floored at
     * [MIN_CHUNK_TOKENS] so a tiny context never yields a zero-length budget -- though a context
     * that small should never reach here, as [minimumContextTokens] refuses it up front.
     */
    fun chunkCharBudget(
        contextTokens: Int,
        promptTokens: Int,
        charsPerToken: Double = ContextWindow.LATIN_CHARS_PER_TOKEN,
        mode: AuditMode = AuditMode.DETAILED,
    ): Int {
        val reserve = outputReserveTokens(contextTokens, promptTokens, mode)
        val chunkTokens = (contextTokens - promptTokens - reserve).coerceAtLeast(MIN_CHUNK_TOKENS)
        return ContextWindow.estimateChars(chunkTokens, charsPerToken)
    }

    /**
     * The context an audit run asks for, however much the model could offer.
     *
     * llama.cpp allocates the whole KV cache when the session opens, so the window is not a ceiling
     * you grow into -- it is memory spent up front. On a 1.9B model that is roughly 45 KB a token, so
     * a 22K window costs a gigabyte before a single token is read, and on a phone already holding the
     * weights that is the difference between running and swapping.
     *
     * This pipeline does not need a large window: it needs the audit prompt, one section, and room
     * for the reply. 8K buys ~3,200-token sections with ~3,200 tokens of reply, which is comfortably
     * more than the longest extraction observed, at a KV cost near 370 MB rather than 1 GB.
     *
     * Not larger, because the memory is real. Not smaller, because the preamble is charged against
     * every section's window: at 8K it is 23% of it, at 4K it was 45% -- which is what made a 4K
     * model split documents into twice as many sections as it needed to.
     */
    fun auditContextTokens(modelContextTokens: Int): Int =
        modelContextTokens.coerceAtMost(AUDIT_CONTEXT_TOKENS)

    const val AUDIT_CONTEXT_TOKENS = 8192

    /**
     * The smallest context a document can actually be audited in: the fixed prompt, the smallest
     * useful reply reserve, and one floor-sized chunk of transcript.
     *
     * Below this the arithmetic in [chunkCharBudget] stops being a budget and becomes a wish -- the
     * floor keeps handing out chunks the window cannot hold, and every turn overflows. Refusing the
     * model is the honest answer; see [AuditLoadPlanner.plan], which is where that refusal is made.
     */
    fun minimumContextTokens(promptTokens: Int): Int =
        promptTokens + MIN_OUTPUT_RESERVE_TOKENS + MIN_CHUNK_TOKENS

    /**
     * A document split into at most [maxChunks] pieces, and how much of it that left unread.
     *
     * [droppedChars] exists because the cap used to be applied with a bare `take(n)`: the tail simply
     * vanished and the report presented a partial read of a document as a complete one. A number that
     * travels with the chunks is the difference between a limit and a silent loss.
     */
    data class ChunkPlan(val chunks: List<String>, val droppedChars: Int) {
        val isTruncated: Boolean get() = droppedChars > 0
    }

    /**
     * [chunk], capped at [maxChunks], reporting whatever the cap left behind.
     *
     * The cap is a real limit -- a document needing more sections than this is hours of on-device
     * inference -- so it stays, but what it costs is now measurable by the caller and reportable to
     * whoever reads the finished audit.
     */
    fun plan(
        text: String,
        maxChars: Int,
        maxChunks: Int,
        overlapChars: Int = maxChars / 10,
    ): ChunkPlan {
        val trimmed = text.trim()
        val all = chunk(trimmed, maxChars, overlapChars)
        if (all.size <= maxChunks) return ChunkPlan(all, droppedChars = 0)

        val kept = all.take(maxChunks)
        // Chunks overlap, so coverage is where the last kept chunk *ends*, not the sum of their
        // lengths. Located by search rather than tracked through the loop: the chunk text is
        // verbatim, so its position is unambiguous, and this keeps chunk() itself unchanged.
        val lastKept = kept.last()
        val coveredTo = trimmed.indexOf(lastKept).takeIf { it >= 0 }?.plus(lastKept.length)
            ?: trimmed.length
        return ChunkPlan(kept, droppedChars = (trimmed.length - coveredTo).coerceAtLeast(0))
    }

    fun chunk(text: String, maxChars: Int, overlapChars: Int = maxChars / 10): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val limit = maxChars.coerceAtLeast(1)
        if (trimmed.length <= limit) return listOf(trimmed)

        val overlap = overlapChars.coerceIn(0, limit / 2)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < trimmed.length) {
            val hardEnd = (start + limit).coerceAtMost(trimmed.length)
            var end = hardEnd
            if (end < trimmed.length) {
                // Break on a natural boundary, but not so early the chunk becomes tiny. Preference
                // order matters for a transcript: a speaker turn is the real semantic unit, so
                // cutting there keeps one person's statement -- and the evidence quote inside it --
                // whole. Paragraph and line breaks are the fallbacks; a hard cut is the last resort
                // and is the case overlap exists to cover.
                //
                // Two floors, because the boundaries are not worth the same.
                //
                // A speaker turn keeps one person's statement -- and any quote inside it -- whole,
                // and a quote split across two chunks is verifiable in neither, so AuditEvidence
                // clears it and the finding loses its evidence. That is worth giving back up to half
                // the window for. Paragraph and line breaks buy far less and are almost always
                // available near the end of the window anyway, so they get a tight floor.
                //
                // A single floor at half the limit let a chunk end anywhere in a 2:1 range, which is
                // why chunk counts varied so widely; a single tight floor cuts long speaker turns in
                // half. Neither alone is right. What remains of the variance is the cost of
                // respecting speaker turns at all, and is preferred to unquotable evidence.
                val speakerFloor = start + limit / 2
                val breakFloor = start + limit * BREAK_FLOOR_NUMERATOR / BREAK_FLOOR_DENOMINATOR
                val speaker = lastSpeakerTurn(trimmed, speakerFloor, end)
                val para = trimmed.lastIndexOf("\n\n", end - 1)
                val line = trimmed.lastIndexOf('\n', end - 1)
                val boundary = when {
                    speaker >= speakerFloor -> speaker
                    para >= breakFloor -> para
                    line >= breakFloor -> line
                    else -> -1
                }
                if (boundary > start) end = boundary
            }
            trimmed.substring(start, end).trim().takeIf { it.isNotBlank() }?.let { chunks += it }
            if (end >= trimmed.length) break
            // Next chunk overlaps the tail of this one, but must advance strictly past `start`.
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return chunks
    }

    /** A chunk may give back at most a fifth of its window to land on a paragraph or line break. */
    private const val BREAK_FLOOR_NUMERATOR = 4
    private const val BREAK_FLOOR_DENOMINATOR = 5

    /**
     * Combines findings from every chunk into one list: each incoming finding is folded into the
     * first already-kept finding that describes the same issue, or appended. Order of first
     * appearance is preserved.
     *
     * "Same issue" is deliberately fuzzier than the exact-title match this replaces. Chunks overlap,
     * and a dialogue restates each finding in different words -- "missing formal residual risk
     * acceptance record" / "missing approval record for residual risk" -- so exact matching shipped
     * the same finding three times, and for a compliance artefact three-where-one is a wrong answer,
     * not a cosmetic one. See [sameIssue] for the rules and the tunable threshold.
     */
    fun mergeFindings(perChunk: List<List<AuditFinding>>): List<AuditFinding> {
        val merged = mutableListOf<AuditFinding>()
        // Title tokens for everything kept so far, computed once each. This comparison is O(n^2) by
        // nature -- every finding is checked against every kept one -- and an 80-section document
        // reaches a few hundred findings, so re-tokenising both sides inside the inner loop meant
        // tens of thousands of regex splits for information that never changes.
        val keptTokens = mutableListOf<Set<String>>()

        for (finding in perChunk.flatten()) {
            if (normalise(finding.title).isEmpty()) continue
            val tokens = informativeTokens(finding.title)
            val at = merged.indices.firstOrNull { sameIssue(merged[it], keptTokens[it], finding, tokens) }
            if (at == null) {
                merged += finding
                keptTokens += tokens
            } else {
                // combine() keeps the existing title, so its tokens stay valid.
                merged[at] = combine(merged[at], finding)
            }
        }
        return merged
    }

    private fun combine(existing: AuditFinding, incoming: AuditFinding) = existing.copy(
        detail = existing.detail.ifBlank { incoming.detail },
        // Same first-non-blank rule as detail. A quote verified in either chunk is a verified
        // quote, so the merged finding keeps whichever one survived.
        evidence = existing.evidence.ifBlank { incoming.evidence },
        standards = (existing.standards + incoming.standards).distinct(),
        // Keep the worse grade: the same lapse seen as minor in one chunk and major in another is
        // a major, and must never be de-escalated by the merge.
        severity = AuditSeverity.moreSevere(existing.severity, incoming.severity),
    )

    /**
     * Whether two findings describe the same underlying issue. In order:
     *  1. exact normalised titles -> same;
     *  2. differing numeric tokens ("extinguisher 12" vs "extinguisher 14") -> different, always --
     *     numbers are the strongest cheap signal of distinctness;
     *  3. one verified evidence quote containing the other -> same (they quote the same sentence);
     *  4. one title's informative tokens containing the other's, or their Jaccard overlap reaching
     *     [TITLE_SIMILARITY] -> same.
     *
     * The known residual risk is rule 4 merging titles that differ only in their subject noun
     * ("forklift daily check missed" / "crane daily check missed", Jaccard 0.6). That is the trade
     * for catching the observed triple-count (0.57), made here in code where it can be inspected,
     * unit-tested and tuned -- not inside a model where it fails silently.
     */
    private fun sameIssue(
        a: AuditFinding,
        ta: Set<String>,
        b: AuditFinding,
        tb: Set<String>,
    ): Boolean {
        if (normalise(a.title) == normalise(b.title)) return true
        if (ta.isEmpty() || tb.isEmpty()) return false

        val numbersA = ta.filterTo(mutableSetOf()) { it.any(Char::isDigit) }
        val numbersB = tb.filterTo(mutableSetOf()) { it.any(Char::isDigit) }
        if (numbersA.isNotEmpty() && numbersB.isNotEmpty() && numbersA != numbersB) return false

        val quoteA = normalise(a.evidence)
        val quoteB = normalise(b.evidence)
        val bothQuoted = quoteA.isNotEmpty() && quoteB.isNotEmpty()
        if (bothQuoted && (quoteA.contains(quoteB) || quoteB.contains(quoteA))) return true

        if (ta.containsAll(tb) || tb.containsAll(ta)) return true
        val overlap = ta.intersect(tb).size.toDouble() / ta.union(tb).size
        // Two findings that each carry a *verified* quote, from sentences that do not contain one
        // another, are evidence of two different sentences in the source. That is not proof they are
        // different issues -- a dialogue restates a finding in several places -- but it is enough to
        // demand a closer title match before collapsing them. Without this the fuzzy threshold is
        // applied to every pair on a long document, and the chance of silently deleting a distinct
        // finding grows with the number of findings.
        val threshold = if (bothQuoted) TITLE_SIMILARITY_QUOTED else TITLE_SIMILARITY
        return overlap >= threshold
    }

    /**
     * Jaccard overlap of informative title tokens at or above which two findings merge. The observed
     * duplicate pair ("missing formal residual risk acceptance record" / "missing approval record
     * for residual risk") scores 0.57; the closest constructed false-merge ("forklift/crane daily
     * check missed") scores 0.6 but is usually saved by rule 2's differing numbers or rule 3's
     * differing quotes. Raise this if genuinely distinct findings start collapsing.
     */
    private const val TITLE_SIMILARITY = 0.55

    /**
     * The bar two findings must clear when each quotes a different sentence. Set above the 0.6 of the
     * closest known false-merge pair ("forklift daily check missed" / "crane daily check missed"),
     * which differing quotes are exactly the signal for, and below a near-restatement of one title.
     */
    private const val TITLE_SIMILARITY_QUOTED = 0.75

    /** Words that carry no identity: grammar, plus anything under three letters without a digit. */
    private val STOPWORDS = setOf(
        "the", "and", "for", "not", "was", "were", "are", "has", "have", "had", "been",
        "with", "that", "this", "its", "their", "from", "into", "does", "did", "any",
    )

    private fun informativeTokens(title: String): Set<String> =
        normalise(title)
            .split(NON_ALPHANUMERIC)
            .filter { token -> token.length > 2 || token.any(Char::isDigit) }
            .filterNot { it in STOPWORDS }
            .toSet()

    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}.]+")

    private fun normalise(text: String): String =
        text.lowercase().trim().replace(Regex("\\s+"), " ")

    /**
     * Index of the last line start between [floor] and [end] that begins a speaker turn -- a short
     * name-like label followed by a colon ("Auditor:", "Site Manager:", "Speaker 2:"). Returns -1 if
     * there is none, which is the normal case for prose documents and simply falls through to the
     * paragraph and line preferences.
     */
    private fun lastSpeakerTurn(text: String, floor: Int, end: Int): Int {
        var candidate = -1
        var lineStart = text.lastIndexOf('\n', end - 1)
        while (lineStart >= floor) {
            val bodyStart = lineStart + 1
            if (SPEAKER_LABEL.containsMatchIn(lineAt(text, bodyStart, end))) {
                candidate = lineStart
                break
            }
            lineStart = text.lastIndexOf('\n', lineStart - 1)
        }
        return candidate
    }

    private fun lineAt(text: String, from: Int, limit: Int): String {
        val lineEnd = text.indexOf('\n', from).let { if (it < 0 || it > limit) limit else it }
        if (from >= lineEnd) return ""
        // A speaker label lives at the very start of the line, so a short window is enough to spot it.
        return text.substring(from, minOf(lineEnd, from + SPEAKER_SCAN_CHARS))
    }

    /** "Auditor:", "Site Manager:", "Speaker 2:" -- a short label at line start, then a colon. */
    private val SPEAKER_LABEL = Regex("""^[\p{Lu}][\p{L}\d .'’\-]{0,38}:\s""")

    private const val SPEAKER_SCAN_CHARS = 48
}
