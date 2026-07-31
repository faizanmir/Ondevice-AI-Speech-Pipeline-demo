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
 * Builds the summarisation prompt and reads the model's answer back.
 *
 * The design point worth knowing: the tagged items are extracted in Kotlin *before* the model sees
 * anything, handed to it as ground truth, and then re-inserted afterwards if it dropped them. A 1-3 B
 * model asked to summarise, reproduce a list, and classify new items in one pass will drop things from
 * the middle of that list. A marker the user deliberately spoke into a recording is a contract, so the
 * model is never its only custodian -- it is asked to *phrase* the tagged findings, not to remember
 * which ones exist.
 */
object NotePrompts {

    const val SYSTEM_PROMPT: String =
        "You summarise voice notes from site inspections. You are accurate and concise, you never " +
            "invent detail, and you only ever report what the transcript actually says. You always " +
            "write in the language the transcript is in."

    private const val SUMMARY_HEADER = "## Summary"
    private const val NC_HEADER = "## Non-conformities"
    private const val ACTIONS_HEADER = "## Actions"

    /**
     * The analysis prompt.
     *
     * Tagged items appear twice on purpose -- inline in the transcript, and again as an explicit
     * numbered list. The inline copy gives the model the surrounding conversation, which is what lets
     * it turn "it expired last month" into "the bay 3 extinguisher expired last month". The list is
     * what makes coverage checkable. The redundancy costs a few dozen tokens and removes the failure
     * mode where a tagged finding is silently lost.
     */
    fun analysisPrompt(
        transcript: String,
        tagged: List<TaggedItem>,
        language: String?,
        speakers: List<String>,
    ): String = buildString {
        appendLine("Summarise the following transcript of a voice note, then report its findings.")
        appendLine()

        if (speakers.isNotEmpty()) {
            appendLine(
                "The transcript is labelled with who was speaking: ${speakers.joinToString(", ")}. " +
                    "Use their names when an action belongs to someone.",
            )
            appendLine()
        }

        appendLine("Reply in exactly these three sections, with these headings:")
        appendLine()
        appendLine(SUMMARY_HEADER)
        appendLine("- short bullet points covering the key points and decisions")
        appendLine()
        appendLine(NC_HEADER)
        appendLine("- one bullet per problem, defect or rule violation")
        appendLine()
        appendLine(ACTIONS_HEADER)
        appendLine("- one bullet per thing somebody needs to do, as \"what to do — owner: who\"")
        appendLine()
        appendLine(
            "Write \"none\" under a heading that has no items. Keep the three headings in English " +
                "exactly as written above, and write everything else in the transcript's language.",
        )
        appendLine()

        val taggedNc = tagged.filter { it.kind == MarkerKind.NonConformity }
        val taggedActions = tagged.filter { it.kind == MarkerKind.Action }

        if (taggedNc.isNotEmpty() || taggedActions.isNotEmpty()) {
            appendLine(
                "The speaker marked some of these out loud while recording. They are shown in the " +
                    "transcript inside [NON-CONFORMITY] and [ACTION] tags. Every one of them must " +
                    "appear in your answer -- tidy up the wording, but do not leave any out and do " +
                    "not merge them together.",
            )
            appendLine()
            if (taggedNc.isNotEmpty()) {
                appendLine("Marked non-conformities:")
                taggedNc.forEachIndexed { i, item -> appendLine("${i + 1}. ${item.text}") }
                appendLine()
            }
            if (taggedActions.isNotEmpty()) {
                appendLine("Marked actions:")
                taggedActions.forEachIndexed { i, item -> appendLine("${i + 1}. ${item.text}") }
                appendLine()
            }
            appendLine(
                "Then add any further non-conformities or actions the transcript describes but the " +
                    "speaker did not mark.",
            )
        } else {
            appendLine(
                "The speaker did not mark anything explicitly, so read the transcript and identify " +
                    "any non-conformities and actions it describes.",
            )
        }

        appendLine()
        appendLine("Use only what is in the transcript. If something is unclear, leave it out.")
        appendLine(languageDirective(language))
        appendLine()
        appendLine("Transcript:")
        appendLine(transcript)
    }

    /**
     * The instruction template stays English -- small models follow English instructions most
     * reliably -- but the target language is named outright, which the same models obey far more
     * consistently than "the same language as the input". The relative phrasing is kept only as
     * the fallback for when the recogniser reported no language.
     */
    fun languageDirective(language: String?): String {
        val name = language
            ?.let { Locale(it).getDisplayLanguage(Locale.ENGLISH) }
            // An unknown code comes back unchanged; a directive naming "de" would only confuse.
            ?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }

        return if (name != null) {
            "Write your answer in $name, the language of the transcript."
        } else {
            "Write your answer in the same language as the transcript."
        }
    }
}

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
