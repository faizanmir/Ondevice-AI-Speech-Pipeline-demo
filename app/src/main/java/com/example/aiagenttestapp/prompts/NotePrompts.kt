package com.example.aiagenttestapp.prompts

import com.example.aiagenttestapp.data.notes.TaggedItem
import com.example.aiagenttestapp.functions.MarkerKind
import java.util.Locale

/**
 * What a voice note's summarisation asks the model for.
 *
 * Read back by [com.example.aiagenttestapp.data.notes.NoteAnalysisParser], which stays next to the
 * types it produces.
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
        partNumber: Int = 1,
        totalParts: Int = 1,
    ): String = buildString {
        if (totalParts > 1) {
            // The model is told it is reading a part, and told not to reach for the ending, because
            // both failures were cheap to prevent and expensive to detect afterwards: asked to
            // "summarise this transcript", a model handed section 2 of 5 writes a closing summary of
            // the whole note from a fifth of it, and the merge then has five confident endings to
            // reconcile. Naming the part turns that into a partial answer, which is what the merge
            // is built to combine.
            appendLine("This is part $partNumber of $totalParts of one voice note's transcript.")
            appendLine(
                "Report only what this part contains. Do not write a conclusion for the whole " +
                    "note, and do not refer to parts you have not been shown.",
            )
            appendLine()
        }

        appendLine("Summarise the following transcript of a voice note, then report its findings.")
        appendLine()

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
            "Write \"none\" under a heading that has no items. Keep the three headings in " +
                "English exactly as written above, and write everything else in the " +
                "transcript's language.",
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
