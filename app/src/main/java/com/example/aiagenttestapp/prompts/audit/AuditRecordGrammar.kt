package com.example.aiagenttestapp.prompts.audit

import com.example.aiagenttestapp.data.audit.AuditProtocolVocabulary
import com.example.aiagenttestapp.data.audit.AuditResultType

/**
 * A GBNF grammar for the RECORDS format, so an extraction turn cannot answer in any other shape.
 *
 * This is the same format [AuditExtractionPrompts] asks for in words. Asking is not enforcing: a
 * small model drops a field name, wraps a value in quotes, or invents a sixth result type, and every
 * one of those costs a field or the whole section. A grammar makes them unreachable -- the tokens
 * that would continue a malformed line are not in the allowed set to begin with.
 *
 * What it does NOT do is bound length. `block+` and a free-text line are both unbounded, so a model
 * can still repeat itself until a cap fires; the turn's token and time limits remain the only guard
 * against a runaway. The difference is what a runaway leaves behind: records that parse up to the
 * cut, rather than prose the parser reads as nothing.
 *
 * It is deliberately additive. Nothing is removed from the preamble on its account: the prompt still
 * teaches the format, and this build is measuring what the constraint alone changes before anything
 * is deleted on the strength of it. A grammar also constrains *shape* and never *judgement* -- it
 * cannot make a model find a non-conformity -- so the instructions that decide what counts stay
 * exactly where they are regardless.
 *
 * ## Written to match the parser, not the skeleton
 *
 * [AuditRecordParser] closes a block on the next header and accepts them in any order, so the
 * grammar does too. Pinning the skeleton's order would forbid a reply the parser reads perfectly
 * well, and a grammar that rejects a valid answer is worse than no grammar at all.
 *
 * The closed vocabularies -- result names, element types, priorities, statuses -- are spelled from
 * the same constants the parser reads back, so a value that reaches the report cannot be one this
 * build does not recognise.
 */
object AuditRecordGrammar {

    /**
     * The regex that switches the grammar on, matched against the generated text.
     *
     * Lazy on purpose: everything before RECORDS is free text, which is what lets a reasoning model
     * think and lets the draft pass list its findings in prose first. Both exist to protect recall
     * and constraining from the first token would forbid them.
     *
     * The trailing newline is part of the trigger so an echo of the instruction ("2. RECORDS -- then
     * write...") does not fire it. A trigger that never fires costs nothing -- the turn simply
     * decodes unconstrained, exactly as it did before this existed -- so the strict direction is the
     * safe one.
     */
    const val TRIGGER = """^[\s\S]*?(RECORDS:?[ \t]*\n)"""

    val GRAMMAR: String by lazy { build() }

    private fun build(): String = buildString {
        // Root mirrors the trigger's capture group: the grammar is fed text starting at "RECORDS".
        appendLine("""root ::= "RECORDS" ":"? sp nl block+""")
        appendLine("block ::= (facts | verdict | stated | unresolved | element | nonconformity | action) blank")
        appendLine()
        // POINTS as well as FACTS, matching the parser, which lands both in the same place. Neither
        // prompt asks for POINTS now that quick mode reports an element instead of key points, so
        // this is tolerance rather than a mode's requirement -- and it stays because the grammar has
        // to accept everything the parser reads, or a reply that would have parsed becomes
        // unsamplable.
        appendLine("""facts ::= ("FACTS" | "POINTS") sp nl bullet+""")
        appendLine("""stated ::= "STATED" sp nl bullet+""")
        appendLine("""unresolved ::= "UNRESOLVED" sp nl bullet+""")
        // A verdict is the document's own wording, so it is a line of free text -- never a value out
        // of a vocabulary. Copying it verbatim is the whole point of the field.
        appendLine("""verdict ::= "VERDICT" sp nl text nl""")
        appendLine()
        appendLine("""element ::= "ELEMENT" sp nl efield+""")
        appendLine("efield ::= statement | etype | speaker | result | reason | evidence | standard")
        appendLine("""statement ::= "statement:" sp text nl""")
        appendLine("""speaker ::= "speaker:" sp text nl""")
        appendLine("""reason ::= "reason:" sp text nl""")
        appendLine("""evidence ::= "evidence:" sp text nl""")
        appendLine()
        appendLine("""nonconformity ::= "NONCONFORMITY" sp nl nfield+""")
        appendLine("nfield ::= title | detail | quote | result | standard")
        appendLine("""title ::= "title:" sp text nl""")
        appendLine("""detail ::= "detail:" sp text nl""")
        appendLine("""quote ::= "quote:" sp text nl""")
        appendLine("""standard ::= "standard:" sp text nl""")
        appendLine()
        appendLine("""action ::= "ACTION" sp nl afield+""")
        appendLine("afield ::= title | detail | priority | status | accepted | standard")
        appendLine("""accepted ::= "accepted:" sp ("yes" | "no") nl""")
        appendLine()
        // The closed vocabularies, spelled by the code that reads them back. A model cannot invent a
        // sixth result type here, which is the one class of value the report renders as a blank
        // rather than as something visibly wrong.
        appendLine("""result ::= "result:" sp (${alternatives(AuditResultType.entries.map { it.wireName })}) nl""")
        appendLine("""etype ::= "type:" sp (${alternatives(AuditProtocolVocabulary.ELEMENT_TYPES)}) nl""")
        appendLine("""priority ::= "priority:" sp (${alternatives(AuditProtocolVocabulary.ACTION_PRIORITIES)}) nl""")
        appendLine("""status ::= "status:" sp (${alternatives(AuditProtocolVocabulary.ACTION_STATUSES)}) nl""")
        appendLine()
        appendLine("""bullet ::= "-" sp text nl""")
        // Any run of characters that is not a line break. Values contain colons, digits and quotes
        // from the source text, and none of that is the grammar's business -- only the line is.
        appendLine("""text ::= [^\n]+""")
        appendLine("""sp ::= [ \t]*""")
        appendLine("""nl ::= "\n"""")
        // Blank lines between blocks are optional, because the worked examples show them and the
        // skeleton does not. The parser is indifferent; the grammar has to accept both.
        appendLine("""blank ::= "\n"*""")
    }

    private fun alternatives(values: List<String>): String =
        values.joinToString(" | ") { "\"$it\"" }
}
