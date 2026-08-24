package com.example.aiagenttestapp.data.notes

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.AuditChunker

/**
 * How a voice-note transcript is split into context-sized sections for a *detailed* summary.
 *
 * This exists because the summariser used to be handed the whole transcript in one prompt, and that
 * is only safe while notes are short. A 22-minute inspection note transcribes to ~17,800 characters
 * -- about 5,100 tokens -- against the 4,096-token window every LiteRT-LM bundle gets
 * ([com.example.aiagenttestapp.data.ModelContextDefaults.DEFAULT_TOKENS], because the format carries
 * no declared length). The prompt did not fail; it overflowed, and the engine evicts from the *start*
 * of the prompt to make room. So the opening of the note -- the scope, the site, who was present --
 * was silently dropped, and the model summarised a transcript whose beginning it had never seen,
 * without anything anywhere reporting a problem.
 *
 * The fix is the one the audit pipeline already worked out: read the transcript section by section
 * (the "map"), then combine the per-section answers in code (the "reduce"). The mechanics of
 * splitting are genuinely the same problem, so they are *reused* from [AuditChunker] rather than
 * reimplemented -- same natural-boundary preference, same overlap so a finding straddling a boundary
 * lands whole in at least one section, same [AuditChunker.ChunkPlan] carrying what a cap left behind.
 * Only the budget differs, and it differs for a reason spelled out in [outputReserveTokens].
 */
object NoteChunking {

    /**
     * Tokens held back for one section's reply.
     *
     * This is where a note differs from an audit, and it differs in the direction that helps. Audit
     * extraction *expands* its input -- every finding is written three times over, as a draft line,
     * then as a title, a detail and an evidence quote in JSON -- so it reserves two thirds of the free
     * window and still gets cut off. A note summary *reduces*: a section of transcript comes back as a
     * few bullets. The reply is a fraction of the section rather than a multiple of it.
     *
     * So this takes half, rather than the audit's two thirds, which would halve the section size for
     * a reply that never needs it — and section count is what this pipeline pays for in minutes.
     *
     * Only the detailed path comes through here. Quick sizes through
     * [QuickRead.plan][com.example.aiagenttestapp.data.audit.QuickRead.plan], which uses the audit
     * queue's own budget for quick, so the two features cut a transcript into the same sections.
     *
     * Erring towards the reply is still the right direction when the estimate is wrong, for the reason
     * the audit chunker records: a section that runs out of room mid-answer is worth nothing at all,
     * while a section smaller than it needed to be is worth exactly what it found.
     *
     * Floored at [MIN_OUTPUT_RESERVE_TOKENS] so a small window still leaves room for a whole reply.
     */
    fun outputReserveTokens(contextTokens: Int, promptTokens: Int): Int {
        val free = (contextTokens - promptTokens).coerceAtLeast(0)
        return (free / 2).coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS)
    }

    /**
     * The smallest reply worth reserving for: three headings and a handful of bullets under each.
     *
     * Lower than the audit's 1,024 because a note reply carries no `<think>`-sized JSON object and no
     * plain-text draft -- it is bullets and nothing else.
     */
    const val MIN_OUTPUT_RESERVE_TOKENS = 512

    /**
     * The character budget for one section, once the prompt scaffolding and room for that section's
     * reply are set aside. So a section fills as much of the *actual* window as is safe: a 128K model
     * reads most notes in one pass, a 4K model reads a long one in several.
     *
     * Floored at [AuditChunker.MIN_CHUNK_TOKENS] for the same load-bearing reason it is there:
     * nothing refuses a small window up front, so a window that cannot hold the preamble plus a reply
     * arrives here and leaves with floor-sized sections that will overflow and be evicted. That is a
     * worse summary, not a broken one -- and it is still strictly better than the single unchunked
     * prompt this replaces, which overflowed on every note long enough to matter.
     */
    fun chunkCharBudget(
        contextTokens: Int,
        promptTokens: Int,
        charsPerToken: Double = ContextWindow.LATIN_CHARS_PER_TOKEN,
    ): Int {
        val reserve = outputReserveTokens(contextTokens, promptTokens)
        val chunkTokens = (contextTokens - promptTokens - reserve)
            .coerceAtLeast(AuditChunker.MIN_CHUNK_TOKENS)
        return ContextWindow.estimateChars(chunkTokens, charsPerToken)
    }

    /**
     * The most sections one note may be read in.
     *
     * Generous rather than tight: at a 4K window each section is roughly 8,000 characters, so this
     * covers a transcript of ~100,000 characters -- on the order of ten hours of speech, far past
     * what the recorder or the device will produce in one sitting. It exists as a backstop against a
     * pathological transcript, not as a routine limit, and whatever it cuts is reported rather than
     * silently dropped ([AuditChunker.ChunkPlan.droppedChars]).
     */
    const val MAX_CHUNKS = 12

    /**
     * Splits [transcript] for a model with [contextTokens] of context.
     *
     * [promptTokens] is what the fixed parts of the prompt cost, measured from the real prompt builder
     * by [com.example.aiagenttestapp.prompts.NotePromptBudget] so the two cannot drift apart.
     */
    fun plan(
        transcript: String,
        contextTokens: Int,
        promptTokens: Int,
        maxChunks: Int = MAX_CHUNKS,
    ): AuditChunker.ChunkPlan {
        // Measured from the transcript itself rather than assumed Latin: a German or Hindi note packs
        // far fewer characters into a token, and sizing it as Latin would build sections that overflow
        // the window they were sized for -- the exact failure this whole file exists to remove.
        val charsPerToken = ContextWindow.charsPerToken(transcript)
        val budget = chunkCharBudget(contextTokens, promptTokens, charsPerToken)
        return AuditChunker.plan(transcript, maxChars = budget, maxChunks = maxChunks)
    }
}
