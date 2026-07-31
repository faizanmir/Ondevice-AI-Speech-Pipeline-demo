package com.example.aiagenttestapp.data.audit

/**
 * Reads the line-oriented record format an extraction turn can be asked to produce instead of JSON.
 *
 * The format exists because JSON has no error locality. Every section lost in this pipeline was lost
 * to two characters -- a stray `""` where a key belonged, a colon with no value after it -- in
 * replies where every finding had been extracted correctly, every quote was intact and the object
 * was properly closed. One bad character invalidates the whole document, so the model doing 99% of
 * the work well counted for nothing.
 *
 * Records have no paired delimiters, no punctuation between fields, and nothing to escape. A garbled
 * line loses that line; a garbled block loses that block; **a reply cut off half way keeps every
 * block before the cut**. That last property is the one that matters most here -- truncation stops
 * being fatal and becomes mere degradation.
 *
 * Tolerant in the same ways [AuditAnalysisParser] is: markdown decoration, case, trailing colons on
 * headers, and alternative field names all pass. It is deliberately *not* tolerant of a record with
 * no title, which is dropped exactly as the JSON parser drops one.
 */
object AuditRecordParser {

    /**
     * Reads whatever [response] contains. Never fails: an unreadable reply yields an empty analysis,
     * and the caller decides what that means -- the same judgement it already makes about a reply
     * that parsed to nothing (see the drain worker's `unusable`).
     */
    fun parse(response: String): AuditAnalysis {
        val lines = response.lineSequence()
            .map { it.trim().trim('*', '#', '`').trim() }
            .toList()

        // Everything above RECORDS is the plain-text draft, which restates every finding and would
        // otherwise be read as content. When the model omits the header, fall back to scanning the
        // whole reply rather than returning nothing.
        val start = lines.indexOfFirst { headerOf(it) == Block.RECORDS }.let { if (it >= 0) it + 1 else 0 }

        val facts = mutableListOf<String>()
        val nonConformities = mutableListOf<AuditFinding>()
        val actions = mutableListOf<AuditFinding>()
        var verdict = ""

        var block: Block? = null
        var fields = mutableMapOf<Field, String>()
        var lastField: Field? = null

        fun flush() {
            val finding = fields.toFinding()
            when {
                finding == null -> Unit
                block == Block.NONCONFORMITY -> nonConformities += finding
                block == Block.ACTION -> actions += finding
                else -> Unit
            }
            fields = mutableMapOf()
            lastField = null
        }

        for (index in start until lines.size) {
            val line = lines[index]
            val header = headerOf(line)

            when {
                header != null -> {
                    flush()
                    block = header
                }

                // A blank line ends a block. Two in a row are harmless: flushing nothing is nothing.
                line.isEmpty() -> flush()

                block == Block.FACTS -> line.removePrefix("-").trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { facts += it }

                block == Block.VERDICT -> verdict = "$verdict $line".trim()

                block == Block.NONCONFORMITY || block == Block.ACTION -> {
                    val field = fieldOf(line)
                    if (field != null) {
                        fields[field.first] = field.second
                        lastField = field.first
                    } else {
                        // Not a recognised field, so it is the continuation of the last one -- a
                        // quote the model wrapped across lines, or a clause on its own line. Joining
                        // it beats discarding it, and beats mistaking it for a field: see fieldOf.
                        lastField?.let { fields[it] = "${fields[it].orEmpty()} $line".trim() }
                    }
                }

                else -> Unit // preamble, or content before any header
            }
        }
        // The reply may simply have stopped. Whatever the last block got to is still worth keeping.
        flush()

        return AuditAnalysis(
            verdict = verdict,
            facts = facts,
            nonConformities = nonConformities,
            actions = actions,
        )
    }

    private enum class Block { RECORDS, FACTS, VERDICT, NONCONFORMITY, ACTION }

    private enum class Field { TITLE, DETAIL, QUOTE, STANDARD }

    /**
     * The block a line opens, or null if it opens none.
     *
     * Matched on the whole line rather than a prefix, so the draft's "ACTIONS" heading cannot be
     * mistaken for an ACTION record -- and a trailing colon is allowed because models add one.
     */
    private fun headerOf(line: String): Block? {
        val word = line.trimEnd(':').trim().uppercase()
        return when (word) {
            "RECORDS" -> Block.RECORDS
            // POINTS is what quick mode asks for, and it lands in the same place: both modes are
            // gathering the raw per-section material a later stage condenses. One parser reads both,
            // so there is no second implementation to drift.
            "FACTS", "POINTS" -> Block.FACTS
            "VERDICT" -> Block.VERDICT
            "NONCONFORMITY", "NON-CONFORMITY", "NONCONFORMANCE" -> Block.NONCONFORMITY
            "ACTION" -> Block.ACTION
            else -> null
        }
    }

    private val FIELDS = mapOf(
        "title" to Field.TITLE,
        "name" to Field.TITLE,
        "finding" to Field.TITLE,
        "issue" to Field.TITLE,
        "detail" to Field.DETAIL,
        "description" to Field.DETAIL,
        "quote" to Field.QUOTE,
        "evidence" to Field.QUOTE,
        "standard" to Field.STANDARD,
        "standards" to Field.STANDARD,
        "clause" to Field.STANDARD,
    )

    /**
     * Splits a line into a field and its value, or null when it is not a field line at all.
     *
     * The subtlety this exists for: values contain colons. "ISO 9001:2015 clause 7.1.5" on its own
     * line would, under a naive split, become a field called "ISO 9001". So a line only counts as a
     * field when the text before the first colon is a *recognised* name -- everything else is prose,
     * and the caller joins it to the value it follows.
     */
    private fun fieldOf(line: String): Pair<Field, String>? {
        val at = line.indexOf(':').takeIf { it > 0 } ?: return null
        val field = FIELDS[line.take(at).trim().lowercase()] ?: return null
        return field to line.substring(at + 1).trim()
    }

    /** A record with no title is dropped, exactly as the JSON parser drops one. */
    private fun Map<Field, String>.toFinding(): AuditFinding? {
        val title = get(Field.TITLE)?.trim().orEmpty().ifBlank { return null }
        return AuditFinding(
            title = title,
            detail = get(Field.DETAIL)?.trim().orEmpty(),
            // One line may name more than one clause; splitting on the separators a model actually
            // uses costs nothing and the list is deduplicated downstream anyway.
            standards = get(Field.STANDARD)
                ?.split(';', ',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            evidence = get(Field.QUOTE)?.trim().orEmpty(),
        )
    }
}
