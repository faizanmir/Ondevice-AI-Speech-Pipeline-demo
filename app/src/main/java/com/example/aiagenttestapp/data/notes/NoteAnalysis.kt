package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerKind
import java.util.Locale

/** One finding as the model reported it, before it is tied to a note. */
data class ParsedFinding(
    val kind: MarkerKind,
    val text: String,
    val owner: String? = null,
    val source: FindingSource = FindingSource.Inferred,
)

/** What the model made of a transcript: prose for the user, plus structured findings. */
data class NoteAnalysis(
    val summary: String,
    val findings: List<ParsedFinding>,
)


/**
 * Reads the model's sectioned answer.
 *
 * Deliberately forgiving. Small models decorate headings, renumber bullets, drop sections and
 * occasionally answer in prose -- and the user is waiting on a summary, not on well-formed Markdown.
 * Anything unrecognisable falls back to being treated as the summary, which is the reading that loses
 * the least.
 */
object NoteAnalysisParser {

    private val NONE_WORDS = setOf(
        "none", "none.", "n/a", "nothing", "no items", "keine", "keine.",
    )

    private enum class Section { Summary, NonConformities, Actions }

    fun parse(raw: String): NoteAnalysis {
        val text = raw.trim()
        if (text.isEmpty()) return NoteAnalysis("", emptyList())

        val summary = StringBuilder()
        val findings = mutableListOf<ParsedFinding>()

        // Everything before the first recognised heading is summary: a model that skipped the heading
        // and went straight into bullets has still written a summary.
        var section = Section.Summary
        var order = 0
        var sawHeading = false

        for (line in text.lines()) {
            val heading = headingOf(line)
            if (heading != null) {
                section = heading
                sawHeading = true
                continue
            }

            when (section) {
                Section.Summary -> summary.appendLine(line)

                Section.NonConformities, Section.Actions -> {
                    val item = bulletText(line) ?: continue
                    if (item.lowercase() in NONE_WORDS) continue

                    val (body, owner) = splitOwner(item)
                    if (body.isBlank()) continue

                    findings += ParsedFinding(
                        kind = if (section == Section.NonConformities) {
                            MarkerKind.NonConformity
                        } else {
                            MarkerKind.Action
                        },
                        text = body,
                        owner = owner,
                        source = FindingSource.Inferred,
                    )
                    order++
                }
            }
        }

        // No headings at all: the model answered in prose. Keep the whole thing as the summary rather
        // than reporting nothing, and let the tagged-item floor supply the findings.
        val summaryText = if (sawHeading) summary.toString().trim() else text

        return NoteAnalysis(summary = summaryText, findings = findings)
    }

    /** Recognises a section heading however the model chose to decorate it. */
    private fun headingOf(line: String): Section? {
        val bare = line.trim()
            .trim('#', '*', '_', ' ', ':', '-')
            .lowercase()

        return when {
            bare.isEmpty() -> null
            bare.startsWith("summary") || bare.startsWith("zusammenfassung") -> Section.Summary
            bare.startsWith("non-conformit") || bare.startsWith("nonconformit") ||
                bare.startsWith("non conformit") || bare.startsWith("abweichung") -> {
                Section.NonConformities
            }
            bare.startsWith("action") || bare.startsWith("maßnahme") ||
                bare.startsWith("massnahme") -> Section.Actions
            else -> null
        }
    }

    /** Strips whatever bullet marker the model used, or returns null for a non-bullet line. */
    private fun bulletText(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // "- x", "* x", "• x", "1. x", "1) x"
        val stripped = trimmed
            .replace(Regex("^[-*•]\\s+"), "")
            .replace(Regex("^\\d+[.)]\\s+"), "")

        // A line with no marker at all is still taken as an item: models drop the bullet often enough
        // that discarding those lines would lose real findings.
        return stripped.trim().trim('*', '_').trim().ifBlank { null }
    }

    /** Splits a trailing "— owner: Bob" off an item. */
    private fun splitOwner(item: String): Pair<String, String?> {
        val match = Regex("(?i)[\\s—–-]*\\(?owner\\s*:\\s*([^)\\n]+)\\)?\\s*$").find(item)
            ?: return item.trim() to null

        val owner = match.groupValues[1].trim().trim('.', ',')
        val body = item.removeRange(match.range).trim().trimEnd('—', '–', '-', ' ')

        val unassigned = owner.lowercase() in setOf("unassigned", "nobody", "none", "n/a", "-")
        return body to owner.takeIf { it.isNotBlank() && !unassigned }
    }
}

/**
 * Puts back any tagged item the model failed to report.
 *
 * The deterministic floor under everything the model does. It matches loosely -- a model that rephrased
 * "the extinguisher expired" as "Extinguisher in bay 3 has expired" has reported it, and re-adding the
 * original would duplicate the finding -- so a tagged item counts as covered when a reported finding of
 * the same kind shares enough distinctive words with it.
 *
 * Anything genuinely missing is appended with [FindingSource.Tagged], and every tagged item that *was*
 * matched has its reported version promoted to Tagged too, so the UI can show which findings the user
 * put there themselves.
 */
fun NoteAnalysis.withTaggedFloor(tagged: List<TaggedItem>): NoteAnalysis {
    if (tagged.isEmpty()) return this

    val findings = this.findings.toMutableList()

    for (item in tagged) {
        val matchIndex = findings.indexOfFirst { finding ->
            finding.kind == item.kind && overlaps(finding.text, item.text)
        }

        if (matchIndex >= 0) {
            findings[matchIndex] = findings[matchIndex].copy(source = FindingSource.Tagged)
        } else {
            findings += ParsedFinding(
                kind = item.kind,
                text = item.text,
                source = FindingSource.Tagged,
            )
        }
    }

    return copy(findings = findings)
}

/**
 * Renders the analysis back into the report the user reads and edits.
 *
 * Re-rendering rather than keeping the model's raw text is what stops the summary and the findings
 * table from disagreeing. The deterministic floor can add a finding the model never wrote down, and a
 * note showing "3 non-conformities" as a badge while its text lists two would be worse than either
 * number alone. The model's *prose* is preserved exactly; only the finding lists are regenerated.
 */
fun NoteAnalysis.renderReport(): String = buildString {
    if (summary.isNotBlank()) {
        appendLine("## Summary")
        appendLine(summary.trim())
    }

    val byKind = findings.groupBy { it.kind }

    listOf(
        MarkerKind.NonConformity to "## Non-conformities",
        MarkerKind.Action to "## Actions",
    ).forEach { (kind, heading) ->
        val items = byKind[kind].orEmpty()
        if (items.isEmpty()) return@forEach

        appendLine()
        appendLine(heading)
        items.forEach { finding ->
            append("- ").append(finding.text.trim())
            finding.owner?.let { append(" — owner: ").append(it) }
            appendLine()
        }
    }
}.trim()

/**
 * True when two findings are plausibly the same thing.
 *
 * Content words only, and short ones dropped: "the", "a", "is" are shared by every pair of sentences in
 * a language and would make everything look like a match. Half the shorter item's distinctive words in
 * common is a deliberately loose bar -- a false match loses a duplicate, while a false miss puts a
 * near-duplicate finding in front of the user, and of those two the duplicate is the worse outcome in a
 * report someone has to act on.
 */
private fun overlaps(a: String, b: String): Boolean {
    fun words(text: String) = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 3 }
        .toSet()

    val wordsA = words(a)
    val wordsB = words(b)
    if (wordsA.isEmpty() || wordsB.isEmpty()) return false

    val shared = wordsA.intersect(wordsB).size
    return shared * 2 >= minOf(wordsA.size, wordsB.size)
}
