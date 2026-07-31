@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.aiagenttestapp.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aiagenttestapp.data.audit.AuditAnalysis
import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditResultType
import com.example.aiagenttestapp.data.audit.AuditSeverity
import com.example.aiagenttestapp.ui.components.formatDuration

/**
 * The per-document report body: how it was produced, then summary, non-conformities, and actions.
 * Shared across screens.
 */
@Composable
fun AuditReportContent(
    analysis: AuditAnalysis,
    modelName: String? = null,
    analysisMillis: Long = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Above everything: a report covering only part of the document must say so before it shows
        // findings, or a clean-looking result reads as a clean document.
        if (analysis.isIncomplete) {
            IncompleteBanner(analysis)
        }
        AuditProvenanceRow(
            modelName = modelName,
            analysisMillis = analysisMillis,
            engineName = analysis.engineName,
            promptProfile = analysis.promptProfile,
        )
        // The summary is always shown, even for a clean document with no findings -- for those it is
        // the whole result, so it must never be the section that silently disappears.
        AuditSectionCard(title = "Summary") {
            // The source's own overall classification, copied verbatim by the pipeline -- never a
            // grade the app assigned. Shown first: it is the authoritative reading of the findings
            // below, and the severity badges are only the app's triage of them.
            if (analysis.verdict.isNotBlank()) {
                Text(
                    "Stated result: “${analysis.verdict}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
            }
            // A quick read's summary IS the point list, so it renders as one. Keyed off the mode
            // rather than off which field happens to be non-empty, so a quick report that produced
            // no points still says so as a quick report rather than falling back to prose wording.
            when {
                analysis.auditMode == AuditMode.QUICK && analysis.keyPoints.isNotEmpty() ->
                    BulletList(analysis.keyPoints)

                analysis.summary.isNotBlank() ->
                    Text(analysis.summary, style = MaterialTheme.typography.bodyMedium)

                else -> Text(
                    "No summary was produced for this document.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Only a detailed read produces non-conformities. Showing an empty "No non-conformities
        // found" card on a quick report would be a claim it never made -- quick mode does not look
        // for them, and "none found" reads as "none there".
        if (analysis.auditMode == AuditMode.DETAILED) {
            AuditFindingsCard(
                title = "Non-conformities",
                findings = analysis.nonConformities,
                emptyText = "No non-conformities found.",
            )
        }
        AuditFindingsCard(
            title = "Actions needed",
            findings = analysis.actions,
            emptyText = "No actions identified.",
        )
    }
}

/** The quick summary's points, numbered so they can be referred to. */
@Composable
private fun BulletList(points: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        points.forEachIndexed { index, point ->
            Row {
                Text(
                    "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(24.dp),
                )
                Text(point, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Who wrote this report and what it cost: the model pinned to the document and the wall-clock time
 * it spent. Deliberately a quiet strip above the findings rather than a card -- it is provenance for
 * the report, not another section of it. Renders nothing when neither fact is known.
 */
@Composable
private fun AuditProvenanceRow(
    modelName: String?,
    analysisMillis: Long,
    engineName: String = "",
    promptProfile: String = "",
) {
    val duration = formatDuration(analysisMillis)
    if (modelName.isNullOrBlank() && duration.isEmpty() && engineName.isBlank()) return

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!modelName.isNullOrBlank()) {
            ProvenanceChip(Icons.Default.Memory, modelName)
        }
        // The engine and profile are part of the artefact's provenance: the same transcript can be
        // run through different engines, and a reader has to be able to see which one this was.
        if (engineName.isNotBlank()) {
            ProvenanceChip(
                Icons.Default.Bolt,
                if (promptProfile.isNotBlank()) "$engineName · $promptProfile" else engineName,
            )
        }
        if (duration.isNotEmpty()) {
            ProvenanceChip(Icons.Default.Schedule, "Generated in $duration")
        }
    }
}

/**
 * A report that covers only part of its document has to say so before anything else -- and say WHY,
 * per section, when the pipeline recorded it. The reasons come from code diagnosing the reply's
 * shape, so they are statements of fact ("the model's JSON was cut off"), not model output. Old
 * reports saved before reasons existed simply show the headline alone.
 */
@Composable
private fun IncompleteBanner(analysis: AuditAnalysis) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                // A document can be incomplete without any section failing -- a tail that was
                // never chunked, or notes the summary could not hold -- so the headline is chosen
                // by what actually happened rather than assuming a failed section caused it.
                Text(
                    when {
                        analysis.unanalysedSections == 1 ->
                            "1 section of this document could not be analysed. These findings are incomplete."

                        analysis.unanalysedSections > 1 ->
                            "${analysis.unanalysedSections} sections of this document could not be " +
                                "analysed. These findings are incomplete."

                        analysis.truncatedChars > 0 ->
                            "This report does not cover the whole document."

                        else -> "This report covers the whole document, with one caveat."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                analysis.unanalysedReasons.forEach { reason ->
                    Spacer(Modifier.height(2.dp))
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProvenanceChip(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AuditSectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun AuditFindingsCard(title: String, findings: List<AuditFinding>, emptyText: String) {
    // Worst grade first, but nothing is hidden: a report of only minors or observations still lists
    // every one. sortedByDescending is stable, so items of equal grade keep transcript order.
    val ordered = findings.sortedByDescending { AuditSeverity.rank(it.severity) }
    AuditSectionCard(title = "$title (${findings.size})") {
        val breakdown = severityBreakdown(findings)
        if (breakdown.isNotEmpty()) {
            Text(
                breakdown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (ordered.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AuditSectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ordered.forEachIndexed { index, finding ->
                Row {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(24.dp),
                    )
                    Column {
                        // The shared vocabulary wins when the model gave one; the old three-way
                        // grade is the fallback while both are in play.
                        val resultType = finding.resultType
                        if (resultType != null) {
                            ResultBadge(resultType)
                            Spacer(Modifier.height(4.dp))
                        } else if (finding.severity.isNotBlank()) {
                            SeverityBadge(finding.severity)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(finding.title, style = MaterialTheme.typography.bodyMedium)
                        if (finding.detail.isNotBlank()) {
                            Text(
                                finding.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Only ever a quote checked against the source text -- AuditEvidence clears
                        // one it could not find -- so this can be shown as the actual wording.
                        if (finding.evidence.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "“${finding.evidence}”",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (finding.standards.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                finding.standards.forEach { StandardChip(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "2 major · 1 minor · 3 observations", or "" when nothing here is graded (e.g. actions).
 * Internal so the PDF export renders the identical tally -- two implementations of this line would
 * eventually disagree on some report.
 */
internal fun severityBreakdown(findings: List<AuditFinding>): String {
    val graded = listOf(
        AuditSeverity.MAJOR to "major",
        AuditSeverity.MINOR to "minor",
        AuditSeverity.OBSERVATION to "observation",
    ).mapNotNull { (grade, label) ->
        val n = findings.count { it.severity == grade }
        when {
            n == 0 -> null
            grade == AuditSeverity.OBSERVATION && n != 1 -> "$n observations"
            else -> "$n $label"
        }
    }.toMutableList()
    // Ungraded findings are counted only alongside graded ones, so the tally always adds up to the
    // list. A wholly ungraded list (actions, or a model that graded nothing) still shows no line.
    val ungraded = findings.count { it.severity.isBlank() }
    if (ungraded > 0 && graded.isNotEmpty()) graded += "$ungraded ungraded"
    return graded.joinToString(" · ")
}

/** A coloured grade tag on a non-conformity, so a minor deviation is visibly a minor -- not a major. */
@Composable
private fun SeverityBadge(severity: String) {
    val (container, content, label) = when (severity) {
        AuditSeverity.MAJOR ->
            Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Major")
        AuditSeverity.MINOR ->
            Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, "Minor")
        else ->
            Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "Observation")
    }
    Surface(shape = RoundedCornerShape(6.dp), color = container, contentColor = content) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** A standard/clause quoted from the transcript, shown as a read-only pill next to a finding. */
@Composable
private fun StandardChip(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Rule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The badge for a conclusion in the shared vocabulary.
 *
 * Deliberately not merged with [SeverityBadge]: the two are different scales with different
 * consequences, and while both are in play a reader needs to see which one a finding was judged on.
 * The colours match where the meanings do -- a major is a major on either -- so the report does not
 * appear to change its mind mid-list.
 *
 * There is no badge for a *null* result. It would land on every action too, since actions are
 * ungraded by design and blank on both scales, and labelling a commitment "unclassified" is worse
 * than labelling it nothing. Distinguishing them needs findings and actions to be separate kinds,
 * which is what the protocol-element split brings.
 */
@Composable
private fun ResultBadge(resultType: AuditResultType) {
    val (container, content, label) = when (resultType) {
        AuditResultType.MAJOR_NONCONFORMITY -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Major",
        )

        AuditResultType.MINOR_NONCONFORMITY -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Minor",
        )

        // Reads as a qualified pass rather than a failure, because that is what it is: the activity
        // was sound and only its paperwork was not.
        AuditResultType.OK_FOR_DOCUMENTATION -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "OK · documentation",
        )

        AuditResultType.POTENTIAL_IMPROVEMENT -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Improvement",
        )

        AuditResultType.OK -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "OK",
        )
    }
    Surface(shape = RoundedCornerShape(6.dp), color = container, contentColor = content) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
