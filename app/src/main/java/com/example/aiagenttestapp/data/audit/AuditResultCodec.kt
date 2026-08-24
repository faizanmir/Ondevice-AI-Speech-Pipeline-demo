package com.example.aiagenttestapp.data.audit

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Serialises an [AuditAnalysis] to and from the JSON the worker writes to disk for the ViewModel to
 * read back. Encodes into exactly the shape [AuditAnalysisParser] already reads, so decoding just
 * reuses that parser -- no second schema, and no need for the serialization compiler plugin (the
 * app module only pulls in kotlinx-serialization's runtime JSON, not the codegen).
 */
object AuditResultCodec {

    fun encode(analysis: AuditAnalysis): String = buildJsonObject {
        put("summary", analysis.summary)
        if (analysis.verdict.isNotBlank()) put("verdict", analysis.verdict)
        // facts and evidence must be here, not just readable by the parser: this encoding IS the
        // per-chunk checkpoint, so a field missing from it is lost between the map and reduce stages.
        putJsonArray("facts") { analysis.facts.forEach { add(it) } }
        // Quick mode's whole deliverable, so it must be encoded, not merely readable -- the same
        // rule as facts above.
        if (analysis.keyPoints.isNotEmpty()) {
            putJsonArray("keyPoints") { analysis.keyPoints.forEach { add(it) } }
        }
        if (analysis.mode.isNotBlank()) put("mode", analysis.mode)
        putJsonArray("nonConformities") { analysis.nonConformities.forEach { add(it.toJson()) } }
        putJsonArray("actions") { analysis.actions.forEach { add(it.toJson()) } }
        // The protocol half of the read. Written unconditionally alongside the findings for the same
        // reason facts are: this encoding is the per-chunk checkpoint, and a field missing from it is
        // lost between the map and reduce stages.
        putJsonArray("protocolElements") { analysis.protocolElements.forEach { add(it.toJson()) } }
        putJsonArray("alsoStated") { analysis.alsoStated.forEach { add(it.toJson()) } }
        putJsonArray("unresolvedItems") { analysis.unresolvedItems.forEach { add(it) } }
        // Written as strings so the lenient parser reads them back with the same helpers as the rest.
        if (analysis.parseFailed) put("parseFailed", "true")
        if (analysis.parseError.isNotBlank()) put("parseError", analysis.parseError)
        if (analysis.unanalysedSections > 0) {
            put("unanalysedSections", analysis.unanalysedSections.toString())
        }
        if (analysis.unanalysedReasons.isNotEmpty()) {
            putJsonArray("unanalysedReasons") { analysis.unanalysedReasons.forEach { add(it) } }
        }
        // Written as strings for the same reason as parseFailed: the lenient parser reads them back
        // with the helpers it already has, and no field needs a second decoding path.
        if (analysis.truncatedChars > 0) put("truncatedChars", analysis.truncatedChars.toString())
        if (analysis.notesTrimmed) put("notesTrimmed", "true")
        if (analysis.engineName.isNotBlank()) put("engineName", analysis.engineName)
        if (analysis.promptProfile.isNotBlank()) put("promptProfile", analysis.promptProfile)
        // Written only when false, so a decoder that never sees the key reads the default -- true --
        // which is what every report predating the choice actually was.
        if (!analysis.includeSummary) put("includeSummary", "false")
        // Written per chunk, not just on the final result: this encoding IS the checkpoint, so a
        // document that is interrupted and resumed can still add up what the whole of it cost.
        // Numbers as strings, like every other scalar here, so the lenient parser reads them back
        // with the helpers it already has.
        if (!analysis.runStats.isEmpty) {
            put(
                "runStats",
                buildJsonObject {
                    put("turns", analysis.runStats.turns.toString())
                    put("promptTokens", analysis.runStats.promptTokens.toString())
                    put("generatedTokens", analysis.runStats.generatedTokens.toString())
                    put("prefillMillis", analysis.runStats.prefillMillis.toString())
                    put("decodeMillis", analysis.runStats.decodeMillis.toString())
                },
            )
        }
    }.toString()

    fun decode(json: String): AuditAnalysis? = AuditAnalysisParser.parse(json)

    private fun AuditFinding.toJson() = buildJsonObject {
        put("title", title)
        put("detail", detail)
        put("evidence", evidence)
        put("severity", severity)
        // Omitted rather than written null when absent: the spec's `nil` is an absent value, and
        // both apps have to agree on what "no conclusion" looks like on the wire.
        resultType?.let { put("resultType", it.wireName) }
        putJsonArray("standards") { standards.forEach { add(it) } }
        // Actions only, and omitted when unset for the same reason as resultType: an absent priority
        // and a priority of "Low" are different claims. `accepted` is written as a word so the
        // lenient parser reads it back through the same helper that wrote it.
        if (priority.isNotBlank()) put("priority", priority)
        if (status.isNotBlank()) put("status", status)
        accepted?.let { put("accepted", if (it) "yes" else "no") }
    }

    private fun AuditProtocolElement.toJson() = buildJsonObject {
        put("statement", statement)
        if (type.isNotBlank()) put("type", type)
        if (speaker.isNotBlank()) put("speaker", speaker)
        result?.let { put("result", it.wireName) }
        if (reason.isNotBlank()) put("reason", reason)
        if (evidence.isNotBlank()) put("evidence", evidence)
        putJsonArray("standards") { standards.forEach { add(it) } }
    }

    private fun AuditStatement.toJson() = buildJsonObject {
        if (speaker.isNotBlank()) put("speaker", speaker)
        put("text", text)
    }
}
