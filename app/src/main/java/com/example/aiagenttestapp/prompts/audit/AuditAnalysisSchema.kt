package com.example.aiagenttestapp.prompts.audit

import com.example.aiagenttestapp.data.audit.AuditProtocolVocabulary
import com.example.aiagenttestapp.data.audit.AuditResultType

/**
 * A JSON Schema for one section's extraction, for engines that constrain output to a schema rather
 * than to a grammar.
 *
 * LiteRT-LM embeds LLGuidance, which takes a Lark grammar, a regex or a JSON Schema -- but 0.14's
 * Kotlin API exposes no way to hand it a grammar directly. The one public route is a tool
 * description, so the constraint has to arrive as a schema on a declared tool, and the model answers
 * by "calling" that tool with the analysis as its arguments.
 *
 * ## Why this is JSON when the pipeline's own format is RECORDS
 *
 * Not a reversal of that decision. RECORDS exists because JSON has no error locality: one stray
 * character loses a section that was otherwise extracted perfectly. A schema-constrained model
 * cannot emit that stray character -- the failure RECORDS was adopted to survive is the exact
 * failure a schema removes. What a schema does NOT remove is truncation: a JSON object cut off
 * mid-array is still worth nothing, where a record list cut off keeps every block before the cut.
 * That is the trade this path makes, and it is why both are kept and measured against each other
 * rather than one being declared the winner.
 *
 * The prompt for a schema-constrained run therefore asks for JSON -- [AuditOutputFormat.JSON], the
 * variant [AuditExtractionPrompts] has carried all along for exactly this comparison. Nothing in the
 * preamble is written for this; the existing branch is simply selected.
 *
 * ## The vocabularies are enums here
 *
 * Which is the strongest form this constraint takes: a model cannot invent a sixth result type or a
 * priority nobody defined, because those tokens are not sampleable. Spelled from the same constants
 * the parser reads back, so the schema and the parser cannot disagree about what a valid value is.
 */
object AuditAnalysisSchema {

    /** What the tool is called. Appears in the model's reply, so it reads as an instruction. */
    const val TOOL_NAME = "report_section"

    const val TOOL_DESCRIPTION =
        "Report everything found in this section of the document: its facts, the protocol element " +
            "it audits, what was stated, what is unresolved, every non-conformity and every action."

    /** The tool description LiteRT-LM declares, schema included. */
    val TOOL_JSON: String by lazy { toolJson() }

    /** The parameter schema on its own, for measuring what it costs and for tests. */
    val SCHEMA: String by lazy { schema() }

    private fun toolJson(): String =
        """{"name":"$TOOL_NAME","description":"$TOOL_DESCRIPTION","parameters":${schema()}}"""

    private fun schema(): String = buildString {
        append("""{"type":"object","properties":{""")
        append(""""facts":$STRINGS,""")
        append(
            """"verdict":{"type":"string","description":""" +
                """"The document's stated overall result, word for word. Empty if it states none."},""",
        )
        append(""""alsoStated":{"type":"array","items":$STATEMENT},""")
        append(""""unresolvedItems":$STRINGS,""")
        // One element, not an array: the report shows the single requirement the document was
        // audited against, and a schema that invited a list would invite the model to fill it.
        append(""""protocolElement":$ELEMENT,""")
        append(""""nonConformities":{"type":"array","items":$FINDING},""")
        append(""""actions":{"type":"array","items":$ACTION}},""")
        // Everything else may be absent, and absence is a real answer everywhere in this pipeline --
        // no findings, no verdict, no conclusion. Only facts are required, because a section that
        // yielded not one fact was not read.
        append("""$REQUIRED,"additionalProperties":false}""")
    }

    private const val STRINGS = """{"type":"array","items":{"type":"string"}}"""

    private const val REQUIRED = """"required":["facts"]"""

    private val STATEMENT =
        """{"type":"object","properties":{"speaker":{"type":"string"},""" +
            """"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""

    private val ELEMENT: String by lazy {
        """{"type":"object","properties":{""" +
            """"statement":{"type":"string"},""" +
            """"type":${enumOf(AuditProtocolVocabulary.ELEMENT_TYPES)},""" +
            """"speaker":{"type":"string"},""" +
            """"result":${enumOf(AuditResultType.entries.map { it.wireName })},""" +
            """"reason":{"type":"string"},""" +
            """"evidence":{"type":"string"},""" +
            """"standards":$STRINGS},""" +
            """"required":["statement"],"additionalProperties":false}"""
    }

    private val FINDING: String by lazy {
        """{"type":"object","properties":{""" +
            """"title":{"type":"string"},""" +
            """"detail":{"type":"string"},""" +
            // Named "evidence" because that is what the parser reads and what AuditEvidence checks
            // against the source text. A schema cannot make a quote real -- only verbatim.
            """"evidence":{"type":"string","description":"A word-for-word quote from the text."},""" +
            """"result":${enumOf(AuditResultType.entries.map { it.wireName })},""" +
            """"standards":$STRINGS},""" +
            """"required":["title"],"additionalProperties":false}"""
    }

    private val ACTION: String by lazy {
        """{"type":"object","properties":{""" +
            """"title":{"type":"string"},""" +
            """"detail":{"type":"string"},""" +
            """"priority":${enumOf(AuditProtocolVocabulary.ACTION_PRIORITIES)},""" +
            """"status":${enumOf(AuditProtocolVocabulary.ACTION_STATUSES)},""" +
            """"accepted":${enumOf(listOf("yes", "no"))},""" +
            """"standards":$STRINGS},""" +
            """"required":["title"],"additionalProperties":false}"""
    }

    private fun enumOf(values: List<String>): String =
        """{"type":"string","enum":[${values.joinToString(",") { "\"$it\"" }}]}"""
}
