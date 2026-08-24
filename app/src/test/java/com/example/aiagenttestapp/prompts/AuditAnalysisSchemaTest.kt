package com.example.aiagenttestapp.prompts

import com.example.aiagenttestapp.data.audit.AuditAnalysisParser
import com.example.aiagenttestapp.data.audit.AuditProtocolVocabulary
import com.example.aiagenttestapp.data.audit.AuditResultType
import com.example.aiagenttestapp.prompts.audit.AuditAnalysisSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema handed to LLGuidance, and the parser that has to read what it produces.
 *
 * The engine is the only thing that can say whether LLGuidance accepts the schema, and it says so at
 * run time -- a rejected one leaves the turn unconstrained, which the drain worker logs and then
 * carries on from. So the failures that are checkable without a device are checked here: malformed
 * JSON, a vocabulary the parser will not read back, and a schema-shaped reply the parser drops.
 */
class AuditAnalysisSchemaTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tool = json.parseToJsonElement(AuditAnalysisSchema.TOOL_JSON).jsonObject
    private val parameters = tool["parameters"]!!.jsonObject
    private val properties = parameters["properties"]!!.jsonObject

    @Test
    fun `the tool description is well-formed JSON with a schema in it`() {
        // Built by string concatenation, so a missing comma is a real possibility and would be
        // rejected by the runtime rather than by anything on this side.
        assertEquals(AuditAnalysisSchema.TOOL_NAME, tool["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("object", parameters["type"]?.jsonPrimitive?.contentOrNull)
        assertTrue(properties.keys.containsAll(setOf("facts", "protocolElement", "nonConformities", "actions")))
    }

    @Test
    fun `only facts are required`() {
        // Absence is a real answer everywhere in this pipeline -- no findings, no verdict, no
        // conclusion -- and a schema that demanded them would make a model manufacture one. Facts
        // are the exception: a section that yielded not one fact was not read.
        val required = parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("facts"), required)
    }

    @Test
    fun `every closed vocabulary in the schema is one the parser reads back`() {
        // The schema is what a model is allowed to emit; AuditProtocolVocabulary and AuditResultType
        // are what the parser recognises. A value in one and not the other arrives as a blank field
        // in the finished report, with nothing anywhere saying why.
        assertEquals(
            AuditResultType.entries.map { it.wireName },
            enumAt(properties["protocolElement"]!!.jsonObject, "result"),
        )
        assertEquals(
            AuditProtocolVocabulary.ELEMENT_TYPES,
            enumAt(properties["protocolElement"]!!.jsonObject, "type"),
        )
        val action = properties["actions"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(AuditProtocolVocabulary.ACTION_PRIORITIES, enumAt(action, "priority"))
        assertEquals(AuditProtocolVocabulary.ACTION_STATUSES, enumAt(action, "status"))
        assertEquals(listOf("yes", "no"), enumAt(action, "accepted"))
    }

    @Test
    fun `a reply shaped by the schema parses into a complete analysis`() {
        // The end-to-end check that matters: what a constrained model can emit is exactly what
        // AuditAnalysisParser reads. A schema whose key names drifted from the parser's would pass
        // every other test here and lose the whole read.
        val reply = """
            {"facts":["Torque wrench calibrated 12 May."],
             "verdict":"OK for documentation",
             "alsoStated":[{"speaker":"Customer","text":"We calibrate annually"}],
             "unresolvedItems":["The certificate is still unsigned"],
             "protocolElement":{"statement":"Calibration was carried out",
               "type":"Result","speaker":"Auditor","result":"resultOkForDocumentation",
               "reason":"Only the approval record is missing",
               "evidence":"The calibration date","standards":["ISO 9001:2015 clause 7.1.5"]},
             "nonConformities":[{"title":"Certificate not signed off",
               "evidence":"the certificate was never signed off","result":"minorNonconformity",
               "standards":[]}],
             "actions":[{"title":"Sign it off","priority":"Medium","status":"Agreed",
               "accepted":"yes","standards":[]}]}
        """.trimIndent()

        val analysis = AuditAnalysisParser.parse(reply)

        assertNotNull(analysis)
        assertEquals(listOf("Torque wrench calibrated 12 May."), analysis!!.facts)
        assertEquals("OK for documentation", analysis.verdict)
        assertEquals("Customer", analysis.alsoStated.single().speaker)
        assertEquals("The certificate is still unsigned", analysis.unresolvedItems.single())
        // The singular key the schema uses, read into the list the report renders.
        val element = analysis.protocolElements.single()
        assertEquals(AuditResultType.OK_FOR_DOCUMENTATION, element.result)
        assertEquals("Auditor", element.speaker)
        assertEquals(listOf("ISO 9001:2015 clause 7.1.5"), element.standards)
        assertEquals(
            AuditResultType.MINOR_NONCONFORMITY,
            analysis.nonConformities.single().resultType,
        )
        val action = analysis.actions.single()
        assertEquals("Medium", action.priority)
        assertEquals("Agreed", action.status)
        assertEquals(true, action.accepted)
    }

    @Test
    fun `a section that found nothing still parses as a read section`() {
        // What the schema permits at its most minimal. This must not come back null, or the drain
        // worker records a clean section as unreadable and the report says a section was never
        // analysed -- the worst outcome this pipeline has.
        val analysis = AuditAnalysisParser.parse("""{"facts":["The line ran at 22 units per hour."]}""")

        assertNotNull(analysis)
        assertTrue(analysis!!.nonConformities.isEmpty())
        assertTrue(analysis.protocolElements.isEmpty())
        assertTrue("a section with facts is not an empty analysis", !analysis.isEmpty)
    }

    @Test
    fun `an unstated conclusion stays unstated`() {
        // The schema makes `result` optional rather than defaulting it, and the parser reads an
        // absent key as no conclusion. A schema that required it would have every element arrive
        // carrying a verdict the document never reached.
        val analysis = AuditAnalysisParser.parse(
            """{"facts":["x"],"protocolElement":{"statement":"Records were reviewed"}}""",
        )

        assertNull(analysis?.protocolElements?.single()?.result)
    }

    @Test
    fun `the schema costs what the reply cap is told it costs`() {
        // The drain worker subtracts an estimate of this text from every turn's output allowance,
        // because chunks were cut at enqueue and cannot be re-cut once an engine is resolved. If the
        // schema grew far past that, turns would overflow the window rather than be capped -- and
        // that failure is silent. A ceiling makes growth a decision.
        val tokens = com.example.aiagent.engine.core.ContextWindow
            .estimateTokens(AuditAnalysisSchema.TOOL_JSON)

        assertTrue("schema is $tokens tok", tokens < 900)
    }

    private fun enumAt(schema: JsonObject, property: String): List<String> =
        (schema["properties"]!!.jsonObject[property]!!.jsonObject["enum"] as JsonArray)
            .map { (it as JsonPrimitive).content }
}
