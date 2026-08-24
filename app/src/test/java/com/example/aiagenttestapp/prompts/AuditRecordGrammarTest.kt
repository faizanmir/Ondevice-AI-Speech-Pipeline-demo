package com.example.aiagenttestapp.prompts

import com.example.aiagenttestapp.data.audit.AuditProtocolVocabulary
import com.example.aiagenttestapp.data.audit.AuditResultType
import com.example.aiagenttestapp.prompts.audit.AuditRecordGrammar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What can be checked about a GBNF grammar without llama.cpp to parse it.
 *
 * Only the engine can say whether the grammar compiles, and it says so at run time: a rejected
 * grammar logs and the turn decodes unconstrained. That is a safe failure but a silent one, so the
 * mistakes that are checkable here -- an undefined rule, a vocabulary the parser will not read back,
 * a trigger that fires on the wrong text -- are checked here.
 */
class AuditRecordGrammarTest {

    private val grammar = AuditRecordGrammar.GRAMMAR

    @Test
    fun `every rule the grammar references is defined`() {
        // A typo'd rule name is the one error that costs nothing to make and everything to find:
        // llama.cpp rejects the whole grammar, the drain worker logs one line, and every section
        // from then on decodes exactly as it did before -- so the constraint looks live and is not.
        val defined = DEFINITION.findAll(grammar).map { it.groupValues[1] }.toSet()
        val referenced = grammar.lines()
            .map { it.substringAfter("::=", missingDelimiterValue = "") }
            .flatMap { body -> IDENTIFIER.findAll(stripLiterals(body)).map { it.value } }
            .toSet()

        assertTrue("grammar defines no root", "root" in defined)
        (referenced - defined).let {
            assertTrue("grammar references undefined rule(s): $it", it.isEmpty())
        }
    }

    @Test
    fun `the closed vocabularies are the ones the parser reads back`() {
        // The grammar is what a model is allowed to emit and AuditProtocolVocabulary is what the
        // parser recognises. A word in one and not the other is a field that arrives blank in the
        // finished report, with nothing anywhere saying why.
        (
            AuditResultType.entries.map { it.wireName } +
                AuditProtocolVocabulary.ELEMENT_TYPES +
                AuditProtocolVocabulary.ACTION_PRIORITIES +
                AuditProtocolVocabulary.ACTION_STATUSES
            ).forEach { word ->
            assertTrue("grammar cannot emit: $word", grammar.contains("\"$word\""))
        }
    }

    @Test
    fun `the grammar allows both modes' notes block`() {
        // Quick mode answers with POINTS where detailed answers with FACTS, and the parser lands
        // both in the same place. One grammar has to cover both or quick decodes into a wall.
        assertTrue(grammar.contains("\"FACTS\""))
        assertTrue(grammar.contains("\"POINTS\""))
    }

    @Test
    fun `the trigger fires on a reply that thought and drafted first`() {
        // The point of a lazy grammar: everything before RECORDS is free text, because that is
        // where the reasoning block and the findings draft live and both exist to protect recall.
        val reply = """
            <think>The certificate was never signed off.</think>
            FINDINGS
            - calibration certificate not signed off
            ACTIONS
            - quality manager to sign it off
            RECORDS
            FACTS
            - Torque wrench calibrated 12 May.
        """.trimIndent()

        val match = Regex(AuditRecordGrammar.TRIGGER).find(reply)

        assertTrue("trigger never fired", match != null)
        // The grammar is fed from the first capture group, so root must start where this does.
        assertTrue(match!!.groupValues[1].startsWith("RECORDS"))
    }

    @Test
    fun `the trigger does not fire on the instruction being echoed back`() {
        // A model that restates the prompt ("2. RECORDS -- then write that list out again") must not
        // switch the grammar on mid-sentence, where the next legal token is a block header and the
        // sentence it was writing becomes unreachable. The newline in the trigger is what prevents
        // it, which is worth a test because it looks like an incidental detail.
        val echo = "I will answer in two steps. 2. RECORDS -- then write that list out again:"

        assertFalse(Regex(AuditRecordGrammar.TRIGGER).containsMatchIn(echo))
    }

    @Test
    fun `the grammar starts where the trigger hands over`() {
        // Root has to match the capture group's own text, or the grammar rejects the very first
        // thing it is fed and the turn dies at the RECORDS line.
        assertTrue(grammar.lineSequence().first().startsWith("""root ::= "RECORDS""""))
    }

    private fun stripLiterals(line: String) = line.replace(LITERAL, " ")

    private companion object {
        val DEFINITION = Regex("""^([a-z][a-zA-Z0-9-]*)\s*::=""", RegexOption.MULTILINE)
        val LITERAL = Regex(""""(?:[^"\\]|\\.)*"|\[[^]]*]""")
        val IDENTIFIER = Regex("""[a-z][a-zA-Z0-9-]*""")
    }
}
