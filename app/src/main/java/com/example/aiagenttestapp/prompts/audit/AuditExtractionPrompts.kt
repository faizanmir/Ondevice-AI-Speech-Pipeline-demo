package com.example.aiagenttestapp.prompts.audit

import com.example.aiagenttestapp.data.audit.AuditOutputFormat
import com.example.aiagenttestapp.data.audit.AuditPromptProfile
import com.example.aiagenttestapp.data.audit.AuditProtocolVocabulary
import com.example.aiagenttestapp.data.audit.AuditResultType

/**
 * The prompts that pull findings out of one chunk of a document.
 *
 * The bulk of the audit's prompt text, and the part that is tuned: the preamble carries the worked
 * examples, and its shape is what [AuditOutputFormat] selects between.
 */
object AuditExtractionPrompts {

    /**
     * The format extraction asks for. Records by default -- see [AuditOutputFormat] -- with JSON
     * retained so a regression can be measured against the format it replaced rather than argued
     * about.
     */
    val OUTPUT_FORMAT = AuditOutputFormat.RECORDS


    /**
     * Thinking is deliberately NOT suppressed for an audit run, and this note exists because it was
     * once tried.
     *
     * The reasoning looked redundant on paper -- the two-step answer already drafts every finding in
     * plain text before the JSON, which is the same shape of work a `<think>` block does -- so a
     * `/no_think` directive was pinned here to halve the output budget. On a Qwen3-1.7B audit of a
     * real transcript it cost findings outright: the run that had been returning graded
     * non-conformities returned none. Extraction is the one stage where the model has to *decide*
     * what counts, and taking its reasoning away is not a formatting change.
     *
     * Note this is independent of the chat "Let models think" setting, and always was. A user who
     * turns thinking off to make chat quicker is not asking for a weaker audit.
     */
    /**
     * A `totalParts` that means "more than one section", for measuring a prompt rather than building
     * one. The value is never shown to a model: the only thing `totalParts` decides in [extraction]
     * and [quickExtraction] is whether the header carries a "section N of M" suffix, and the
     * fixed-cost measurements below want the longer of the two so a single-section document ends up
     * with more headroom than reserved rather than less.
     *
     * It cannot be the real count: this measurement sizes the chunks (via
     * [AuditChunker.chunkCharBudget]), and the count of chunks is what that sizing produces. The
     * digits are immaterial anyway -- the widest real header, "section 80 of 80", is two characters
     * longer than this one, well under a token.
     */
    internal const val MULTI_PART = 2


    /**
     * Temperature the audit run pins regardless of the user's chat setting. Extraction is a faithful
     * read, not a creative task: a low temperature stops a small model paraphrasing findings away or
     * merging two into one, and keeps runs consistent. (Inert when "Reproducible output" is on --
     * that forces argmax decoding, which ignores temperature entirely.)
     *
     * Raised from 0.2 after watching a section die in a repetition loop: 9,347 characters of bullet
     * points in which the closing item repeated the opening one word for word, so the reply never
     * reached the JSON step and the section was lost. Degenerate repetition is a *low*-temperature
     * failure, and 0.2 sits deep in the range that invites it. 0.4 is still far below the 0.8 a chat
     * uses -- faithful enough for a read, with enough spread to break a loop before it starts.
     */
    const val EXTRACTION_TEMPERATURE = 0.4f


    /**
     * The seed an audit run pins, so the same document read twice reads the same way.
     *
     * A chat draws a fresh seed per load on purpose -- two chats given the same prompt should not
     * replay one token stream. An audit is the opposite: it is a compliance artefact, and a run that
     * grades a missing signature minor on Monday and OK-for-documentation on Tuesday is not two
     * readings, it is one reading and a coin toss. Whoever has to defend the report cannot say which
     * one they got.
     *
     * A fixed seed at [EXTRACTION_TEMPERATURE], NOT greedy decoding. Greedy would be more
     * reproducible still and is exactly the mode that cost a section to a repetition loop -- the
     * reason the temperature was raised from 0.2 in the first place. Fixing the seed removes the
     * variance between runs without removing the spread within one, which is what breaks a loop.
     *
     * Does not override "Reproducible output": that setting already pins a seed and forces argmax,
     * and a user who asked for it is asking for something stricter than this, not different.
     */
    const val EXTRACTION_SEED = 20_260_804


    /**
     * The fixed instructions, byte-identical for a given profile, so nothing that varies per chunk
     * (the part number, the text itself) appears above this line. On llama.cpp that stability is
     * load-bearing: nativeIngestPrompt reuses the shared prefix, so the preamble is decoded once per
     * document instead of once per chunk. Elsewhere it is drift-proofing.
     *
     * Built once per profile and cached, so it cannot drift by accident.
     */
    fun preamble(
        profile: AuditPromptProfile,
        format: AuditOutputFormat = OUTPUT_FORMAT,
        draft: Boolean = true,
        /**
         * Whether to ask for the per-section facts.
         *
         * They feed one thing and one thing only -- the overall summary -- and are rendered nowhere
         * in the report, so a run that will not write a summary should not pay to extract them. That
         * cost is not the summary's single turn: it is several lines of output on EVERY section, and
         * on a long document it is the larger of the two by far.
         */
        facts: Boolean = true,
    ): String = CACHE.getOrPut(Key(profile, format, draft, facts)) {
        buildPreamble(profile, format, draft, facts)
    }

    private data class Key(
        val profile: AuditPromptProfile,
        val format: AuditOutputFormat,
        val draft: Boolean,
        val facts: Boolean,
    )

    // Built once per (profile, format, draft) and reused. The stability matters on llama.cpp, where
    // a byte-identical prefix is what lets the preamble be decoded once per document rather than
    // once per section -- which is also why `draft` is decided once for a whole document rather than
    // per section from measured speed: changing the preamble mid-document would break that reuse.

    private val CACHE = mutableMapOf<Key, String>()


    // The vocabularies, spelled by the code that reads them back rather than typed out again here.
    // A prompt that offers a word the parser does not know produces a value the report cannot show,
    // and that failure is invisible -- the field simply arrives blank. Deriving both from one list
    // makes it impossible: correcting AuditProtocolVocabulary against the iOS spec corrects the
    // prompt in the same edit.
    /**
     * The result vocabulary as the prompts spell it, from the enum itself so a name cannot be
     * taught here and read back differently by [AuditResultType.fromWire].
     *
     * Internal rather than private because [AuditQuickPrompts] teaches the same four names: both
     * modes now state a conclusion in this vocabulary, and two copies of the list is exactly how one
     * of them ends up a version behind.
     */
    internal val RESULT_TYPES_LINE = AuditResultType.entries.joinToString(", ") { it.wireName }
    private const val MAX_ALSO_STATED = AuditProtocolVocabulary.MAX_ALSO_STATED

    // Example C's protocol half in the JSON shape, shared by both profiles: the two differ only in
    // whether a finding carries "detail", and duplicating the element and the actions to express
    // that would be two more places for the same example to drift.
    private const val ELEMENT_JSON =
        """"alsoStated":[{"speaker":"Customer","text":"We calibrate every torque wrench """ +
            """annually"}],""" +
            """"unresolvedItems":["The calibration certificate is still unsigned"],""" +
            """"protocolElements":[{"statement":"Calibration was carried out, but the """ +
            """certificate was not signed off","type":"Result","speaker":"Auditor",""" +
            """"result":"resultOkForDocumentation","reason":"The work was done on 12 May; only """ +
            """the approval record is missing","evidence":"The calibration date, the """ +
            """procedure's sign-off requirement, and the blank line"}],"""

    private const val ACTIONS_JSON =
        """"actions":[{"title":"Quality manager to sign off the certificate",""" +
            """"status":"Agreed","accepted":"yes","standards":[]},""" +
            """{"title":"Check other calibration certificates for the same gap",""" +
            """"status":"Agreed","accepted":"yes","standards":[]}]}"""
    /**
     * The `"facts":[...]` key for a JSON example, or nothing when facts were not asked for.
     *
     * A key shown in an example is a key a model will fill in, so an example that kept it while the
     * instructions dropped it would reinstate the cost the toggle exists to remove -- quietly, and
     * only in the format nobody reads the logs for.
     */
    private fun factsKey(facts: Boolean, values: String): String =
        if (facts) """"facts":$values,""" else ""

    private val ELEMENT_TYPES_LINE = AuditProtocolVocabulary.ELEMENT_TYPES.joinToString(", ")
    private val PRIORITIES_LINE = AuditProtocolVocabulary.ACTION_PRIORITIES.joinToString(", ")
    private val STATUSES_LINE = AuditProtocolVocabulary.ACTION_STATUSES.joinToString(", ")


    private fun buildPreamble(
        profile: AuditPromptProfile,
        format: AuditOutputFormat,
        draft: Boolean,
        facts: Boolean,
    ): String = buildString {
        val rich = profile == AuditPromptProfile.RICH
        appendLine("You are auditing one section of a transcript.")
        appendLine("Report only what appears in the section you are given. Other sections are read separately.")
        appendLine()

        // IDENTICAL IN BOTH PROFILES, deliberately. Concrete cue words are the main driver of recall
        // on a small model, so trimming them to save tokens would let the same transcript produce
        // different findings on two builds. For a compliance artefact that is a defensibility
        // problem, not a performance trade: the profiles may only differ in text that demonstrates
        // *format*, never in text that decides *what counts as a finding*.
        appendLine("Your main job: find EVERY non-conformity in the text. A non-conformity is any point")
        appendLine("where a requirement, standard, procedure, or commitment was not met -- for example")
        appendLine("something missing, not done, expired, overdue, skipped, not recorded, not signed,")
        appendLine("not calibrated, not trained, out of date, out of specification, not followed, or")
        appendLine("unauthorised. Do not leave any out -- small and minor issues count too.")
        // "Use an empty list" is a JSON-ism, and RECORDS -- the default format -- has no lists to
        // empty: absence there is an omitted block. Phrased for both, so neither format is told to
        // write a shape the other cannot hold.
        appendLine("If the text contains none, report none. Never invent one to fill a gap.")
        appendLine()

        // The v2 wording here was "list each one as its own item, do not merge several into one" --
        // written to protect recall, with nothing distinguishing several distinct issues from one
        // issue mentioned several times. On a dialogue transcript, which restates every finding in
        // the discussion, the evidence list and the closing summary, that reliably tripled the
        // count. Identity is anchored on the object and requirement, NOT on sharing a fix: one
        // remedial act ("retrain all staff") can resolve two genuinely distinct findings.
        appendLine("Count issues, not mentions. A transcript restates the same issue as it is")
        appendLine("noticed, discussed and summarised; those are ONE non-conformity, not several.")
        appendLine("Two mentions are the same issue when the same record, item, person, or event")
        appendLine("fails the same requirement; keep one item with the clearest wording. Genuinely")
        appendLine("different problems always stay separate items, even if they look alike or would")
        appendLine("be fixed together.")
        appendLine()

        // Actions used to be an eight-word afterthought against a fourteen-cue definition of
        // non-conformities, and the model allocated attention accordingly: it wrote the actions
        // into the prose and left the actions array empty. Equal billing, own cue words, and the
        // one structural hint that matters -- they live in the closing exchange.
        appendLine("Also find EVERY action. An action is a step the text itself says will be taken,")
        appendLine("should be taken, or is recommended -- something to be completed, corrected,")
        appendLine("signed, recorded, reviewed, verified, checked, or followed up elsewhere. They")
        appendLine("usually sit in the closing exchange, where a recommendation and the reply")
        appendLine("accepting it are ONE action, not two. Do not invent an action the text does")
        appendLine("not state; if it states none, report none.")
        appendLine()

        // The protocol element: what the document was audited AGAINST, and what was concluded about
        // it. One per document, tied to the standard it cites -- so one per section here, and the
        // sections are collapsed into that one afterwards (AuditChunker.collapseElements).
        //
        // Asked for even where the section is clean. The conclusion is the part that says the audit
        // happened at all, and reading only for non-conformities keeps the failures while throwing
        // away everything that makes them defensible.
        appendLine("Also recognise the PROTOCOL ELEMENT this section audits: the requirement or")
        appendLine("standard it is being judged against, and the ONE conclusion reached about it --")
        appendLine("whether or not that conclusion is a failure. Give the statement it reached,")
        appendLine("whose it is, its type, the conclusion, why, and what the conclusion rests on.")
        appendLine("One element per section, the one the cited standard belongs to.")
        appendLine()

        // Not a transcript of who said what -- that is what facts and the summary are for. This is
        // the short list of things a reader would otherwise not learn: kept few and kept brief,
        // because a section that reproduces the dialogue here buries the two points that mattered.
        appendLine("Also list, as STATED, at most $MAX_ALSO_STATED short points the summary would")
        appendLine("otherwise miss -- what a party says they hold, maintain or have done. One line")
        appendLine("each, and none that merely repeats a fact or a finding above.")
        appendLine()

        // Distinct from both a non-conformity (a requirement judged unmet) and an action (a step
        // someone committed to): an unresolved item is a gap the document left open, and it is the
        // part of a report somebody has to chase.
        appendLine("Also list anything left UNRESOLVED: a record still missing, an approval never")
        appendLine("produced, a question the document raised and did not answer.")
        appendLine()

        appendLine("Also give:")
        // Omitted entirely when no summary will be written. Not trimmed to a shorter instruction --
        // absent, because an instruction the report has no use for still costs output on every
        // section, which is the whole reason the toggle exists.
        if (facts) {
            appendLine("- facts: the concrete factual content of this section -- who, what, equipment,")
            appendLine("  dates, numbers, locations, decisions. Short lines, specific not general. These")
            appendLine("  are the raw material for a later overall summary, so do not generalise them away.")
        }
        // This line has been written both ways and the literal wins. Saying "use \"\"" gets a model
        // to copy that token an extra time -- "verdict":"","", -- a stray empty string where a key
        // belongs. Saying "leave it empty" instead gets it taken literally -- "verdict":, -- a key
        // with no value at all, and that failed more than twice as often: five sections in eight
        // against two. Both are malformed, but only one of them is a shape the model can be shown.
        // The literal stays, and repairCommonMalformations covers what it costs.
        appendLine("- verdict: if the text states an overall result or classification in its own")
        appendLine("  words (for example \"OK for documentation\"), copy that wording exactly. Use \"\"")
        appendLine("  if none is stated. Never grade the findings yourself, and never translate the")
        appendLine("  stated wording onto another scale.")
        appendLine()

        // The classification the finding itself carries, as opposed to the document's own stated
        // verdict above. Asked for here rather than graded in a later turn on purpose: a second
        // pass re-reads a finding with no memory of what was concluded, and can only ever soften
        // it. Severity that moves one way has to be decided once, where the evidence is.
        appendLine("- result: one of $RESULT_TYPES_LINE. There is no name for a plain pass --")
        appendLine("  an element that simply conformed carries no result line at all.")
        appendLine("  Give it on every element and on every non-conformity. Omit it if the text")
        appendLine("  supports no clear conclusion -- that is an answer, not a gap to fill. Use")
        appendLine("  resultOkForDocumentation when the work was sound but its records, approval")
        appendLine("  or traceability were weak. If the auditor stated a result, it stands.")
        appendLine("  An ELEMENT and a NONCONFORMITY under it are graded separately: the element")
        appendLine("  judges the requirement as a whole and may conclude")
        appendLine("  resultOkForDocumentation or resultPotentialImprovement, while a")
        appendLine("  non-conformity names a gap and so is ONLY ever minorNonconformity or")
        // Named one by one. The rule read "never grade a non-conformity as OK", and a model
        // obligingly graded them resultPotentialImprovement instead -- which is not OK, and is
        // equally not a non-conformity. A prohibition has to name what it prohibits.
        appendLine("  majorNonconformity. Never resultOkForDocumentation or")
        appendLine("  resultPotentialImprovement on a non-conformity.")
        appendLine("- verdict is the document's OWN words, never one of the names above. If the text")
        appendLine("  states no result in its own wording, leave it out.")
        appendLine()

        appendLine("Rules:")
        appendLine("- Use only what the text says. Never add, infer, or generalise beyond it.")
        appendLine("- Every non-conformity must include \"evidence\": a word-for-word quote of at most")
        appendLine("  15 words, copied exactly from the text. If you cannot quote it, it is not a")
        appendLine("  finding -- leave it out.")
        appendLine("- Name a standard only if a standard or clause is written in the text (e.g.")
        appendLine("  \"ISO 9001:2015 §7.2\"). It is often named once, far from the issue it refers")
        appendLine("  to -- look there too, and attach it to the finding it belongs to. Never")
        appendLine("  invent one; if the text names none, leave it out.")
        appendLine()

        // Draft first, JSON last. Once "nonConformities":[ is emitted the model cannot go
        // back and add the one it notices later, so the thinking must finish before the
        // JSON starts. Findings are kept terse to limit the token cost of this stage.
        // The draft is scratch space: somewhere to list everything and check for duplicates before
        // committing to a shape. It costs roughly double the output tokens, because every finding is
        // written twice, and it is written FIRST -- so a reply cut short spends its whole budget on
        // text the app discards and the section is lost. Two sections truncated at the same instant
        // showed it exactly: one cut mid-records kept 8,564 bytes of findings, one cut still in its
        // draft kept 64. It stays where decode is fast enough that the cut never comes, and goes
        // where it is not.
        if (draft) {
            appendLine("Answer in two steps, in this order:")
            appendLine("1. FINDINGS -- plain text: one short line per non-conformity, then a line reading")
            appendLine("   ACTIONS and one short line per action. Terse fragments, not sentences. This is")
            appendLine("   where you check nothing was missed and no issue is listed twice.")
        }
        if (format == AuditOutputFormat.RECORDS) {
            appendLine(
                if (draft) "2. RECORDS -- then write that list out again in this exact form:"
                else "Answer with records only, in this exact form:",
            )
            // The constraints first, then the shape itself with nothing between the blocks. The
            // blank lines this used to carry were decoration: AuditRecordParser closes a block on
            // the next header, so it reads either layout, and the worked example below still shows
            // the spaced-out version a model will actually copy.
            appendLine("One field per line. No brackets, no braces, no quotation marks around")
            appendLine("values, no commas between fields.")
            appendLine()
            appendLine("RECORDS")
            if (facts) {
                appendLine("FACTS")
                appendLine("- a short fact")
                // Two, not one: a single bullet sets a prior of a single fact, the same way one
                // worked example sets a prior of one finding.
                appendLine("- a short fact")
            }
            appendLine("VERDICT")
            appendLine("the stated result, word for word -- omit this block if none is stated")
            appendLine("STATED")
            appendLine("- who said it: what they said")
            appendLine("UNRESOLVED")
            appendLine("- what is still missing or open")
            appendLine("ELEMENT")
            appendLine("statement: what this element of the protocol concluded or asserted")
            appendLine("type: $ELEMENT_TYPES_LINE")
            appendLine("speaker: whose element it is")
            appendLine("result: one of the names above -- omit this line if none is clear")
            appendLine("reason: why it concluded that")
            appendLine("evidence: what the conclusion rests on")
            appendLine("NONCONFORMITY")
            appendLine("title: a short title")
            if (rich) appendLine("detail: one short sentence")
            appendLine("quote: the word-for-word quote")
            // The records format carried no result line until the protocol arrived, so every report
            // produced in this format graded nothing at all -- the shared vocabulary existed only on
            // the JSON path nobody runs. One line closes that.
            appendLine("result: one of the names above -- omit this line if the text supports none")
            appendLine("standard: the clause named in the text -- omit this line if none is")
            appendLine("ACTION")
            appendLine("title: a short title")
            appendLine("priority: $PRIORITIES_LINE -- omit if the text does not say")
            appendLine("status: $STATUSES_LINE -- omit if the text does not say")
            appendLine("accepted: yes or no -- omit if the text does not say")
            appendLine()
            appendLine("Repeat ELEMENT, NONCONFORMITY and ACTION for each one you found.")
        } else {
            appendLine(
                if (draft) "2. JSON -- then convert that list, unchanged, into one JSON object of this shape:"
                else "Answer with one JSON object of this shape, and nothing else:",
            )
            appendLine(
                if (rich) {
                    "{" + factsKey(facts, """["..."]""") + """"verdict":"...",""" +
                        """"alsoStated":[{"speaker":"...","text":"..."}],"unresolvedItems":["..."],""" +
                        """"protocolElements":[{"statement":"...","type":"Result","speaker":"...",""" +
                        """"result":"resultOkForDocumentation","reason":"...","evidence":"..."}],""" +
                        """"nonConformities":[{"title":"...","detail":"...","evidence":"...",""" +
                        """"result":"minorNonconformity","standards":["ISO 9001:2015 §7.2"]}],""" +
                        """"actions":[{"title":"...","detail":"...","status":"Proposed",""" +
                        """"accepted":"yes","standards":[]}]}"""
                } else {
                    "{" + factsKey(facts, """["..."]""") + """"verdict":"...",""" +
                        """"alsoStated":[{"speaker":"...","text":"..."}],"unresolvedItems":["..."],""" +
                        """"protocolElements":[{"statement":"...","type":"Result","speaker":"...",""" +
                        """"result":"resultOkForDocumentation","reason":"...","evidence":"..."}],""" +
                        """"nonConformities":[{"title":"...","evidence":"...",""" +
                        """"result":"minorNonconformity","standards":["ISO 9001:2015 §7.2"]}],""" +
                        """"actions":[{"title":"...","status":"Proposed","standards":[]}]}"""
                },
            )
        }
        appendLine(
            if (format == AuditOutputFormat.RECORDS) {
                "Put the records last, and write nothing after them. No code fences."
            } else {
                "Put the JSON last, and write nothing after it. No code fences."
            },
        )
        appendLine()

        // Worked examples are the largest block in this preamble -- they were ~45% of it -- and the
        // preamble is charged against the context window of EVERY chunk, even on an engine that
        // re-decodes none of it (AuditQueue reserves it per chunk, and that reserve is what shrinks
        // the text each chunk can carry). So this is where the profiles now differ: RICH shows all
        // three examples, LEAN shows only the dialogue and states in prose what the other two
        // demonstrated. The instructions above are still byte-identical in both -- the profiles may
        // differ in text that demonstrates *format*, never in text that decides what counts.
        if (rich) {
            exampleA(format, draft, facts)
            exampleB(format, draft, facts)
        } else {
            // What examples A and B taught, asserted instead of shown. The known risk of a single
            // worked example is the prior it sets -- one example holding one finding invites one
            // finding per section, exactly as A's two invited two -- so these two sentences carry
            // the "many or none" range that A and B carried between them.
            appendLine("A section may hold many issues or none. List every one you find; when there")
            appendLine("are none the lists are empty, and that is a correct answer, not a failure to")
            appendLine("look. Two distinct problems are always two items, however alike they read.")
            appendLine()
        }
        exampleC(rich, format, draft, facts)

        appendLine("Now analyse the text below the same way -- find every non-conformity and every")
        appendLine("action in it.")
    }


    /**
     * Recall (two issues yield two items) and both an empty and a populated standards array. Its
     * actions array is empty ON PURPOSE: the text states no action, and the inferred "carry out the
     * inspection" an older draft carried taught exactly the invention the grounding rule forbids.
     *
     * RICH only, so it carries the "detail" fields unconditionally -- LEAN no longer shows it at all.
     */
    private fun StringBuilder.exampleA(format: AuditOutputFormat, draft: Boolean, facts: Boolean) {
        appendLine("Worked example A -- text that contains issues:")
        appendLine(
            "  \"The extinguisher was last inspected 14 months ago; the annual check was missed. " +
                "Two new staff had not completed the induction training required by ISO 45001 " +
                "clause 7.2.\"",
        )
        appendLine("a correct reply is:")
        if (draft) {
            appendLine("FINDINGS")
            appendLine("- extinguisher inspection overdue, 14 months")
            appendLine("- induction training incomplete, 2 staff, ISO 45001 7.2")
            appendLine("ACTIONS")
            appendLine("- none stated")
        }
        if (format == AuditOutputFormat.RECORDS) {
            appendLine("RECORDS")
            if (facts) {
                appendLine("FACTS")
                appendLine("- Extinguisher last inspected 14 months ago.")
                appendLine("- Two new staff had not completed induction training.")
                appendLine()
            }
            appendLine("NONCONFORMITY")
            appendLine("title: Fire extinguisher inspection overdue")
            appendLine("detail: Last inspected 14 months ago; the annual check was missed.")
            appendLine("quote: last inspected 14 months ago; the annual check was missed")
            appendLine()
            appendLine("NONCONFORMITY")
            appendLine("title: Induction training not completed")
            appendLine("detail: Two new staff had not done the required induction.")
            appendLine("quote: Two new staff had not completed the induction training")
            appendLine("standard: ISO 45001 clause 7.2")
        } else {
            appendLine("JSON")
            appendLine(
                "{" + factsKey(
                    facts,
                    """["Extinguisher last inspected 14 months ago.",""" +
                        """"Two new staff had not completed induction training."]""",
                ) + """"verdict":"",""" +
                    """"nonConformities":[{"title":"Fire extinguisher inspection overdue",""" +
                    """"detail":"Last inspected 14 months ago; the annual check was missed.",""" +
                    """"evidence":"last inspected 14 months ago; the annual check was missed",""" +
                    """"standards":[]},""" +
                    """{"title":"Induction training not completed",""" +
                    """"detail":"Two new staff had not done the required induction.",""" +
                    """"evidence":"Two new staff had not completed the induction training",""" +
                    """"standards":["ISO 45001 clause 7.2"]}],""" +
                    """"actions":[]}""",
            )
        }
        appendLine()
    }


    /**
     * The counterweight to A. A single example containing two findings sets a prior that roughly two
     * findings are expected, which manufactures false positives on clean text. This shows that an
     * empty list is a correct answer.
     */
    private fun StringBuilder.exampleB(format: AuditOutputFormat, draft: Boolean, facts: Boolean) {
        appendLine("Worked example B -- text that contains no issues:")
        appendLine(
            "  \"The calibration log was signed on 3 March and all four operators held current " +
                "certificates. The line ran at 22 units per hour.\"",
        )
        appendLine("a correct reply is:")
        if (draft) {
            appendLine("FINDINGS")
            appendLine("- none")
            appendLine("ACTIONS")
            appendLine("- none")
        }
        if (format == AuditOutputFormat.RECORDS) {
            appendLine("RECORDS")
            if (facts) {
                appendLine("FACTS")
                appendLine("- Calibration log signed 3 March.")
                appendLine("- Four operators held current certificates.")
                appendLine("- Line ran at 22 units per hour.")
                appendLine()
            }
            // The element that PASSED, which is the whole reason this example now carries one: a
            // section with no findings still has a protocol, and a report that showed nothing for it
            // would say the section was never read. No speaker line -- the text names none, and
            // omitting it here is the demonstration.
            appendLine("ELEMENT")
            appendLine("statement: Calibration records and operator certification were in order")
            appendLine("type: Result")
            // No result line, and that is the demonstration. The vocabulary records what an audit
            // had to SAY about an element; an element that simply conformed gives it nothing to
            // say, so the grade is omitted rather than forced onto the mildest of the four.
            appendLine("reason: The log was signed and all four operators held current certificates")
            // No NONCONFORMITY or ACTION block at all -- which is what "none" looks like in this
            // format, and is the whole point of this example.
        } else {
            appendLine("JSON")
            appendLine(
                "{" + factsKey(
                    facts,
                    """["Calibration log signed 3 March.",""" +
                        """"Four operators held current certificates.",""" +
                        """"Line ran at 22 units per hour."]""",
                ) + """"verdict":"",""" +
                    """"protocolElements":[{"statement":"Calibration records and operator """ +
                    """certification were in order","type":"Result",""" +
                    """"reason":"The log was signed and all four operators held current """ +
                    """certificates"}],""" +
                    """"nonConformities":[],"actions":[]}""",
            )
        }
        appendLine()
    }


    /**
     * The three dialogue behaviours A and B never showed: one issue mentioned twice collapsing to
     * one item, actions living in the closing recommend-and-commit exchange, and a clause plus a
     * stated verdict named away from the finding they belong to. The only example LEAN carries,
     * because it is the only one that demonstrates all three.
     *
     * It is DELIBERATELY not the ISO 27001 transcript that exposed those failures: a near-verbatim
     * copy would leak that document's answer into the preamble -- invalidating it as a regression
     * test and inviting its clause to be echoed into documents that never cite it.
     *
     * The dialogue is written as tightly as those behaviours allow. Every clause left in is load
     * bearing -- the customer's remark and the auditor's confirmation are the two mentions that
     * must collapse, the verdict and the clause have to sit away from the finding, and the closing
     * pair has to recommend and accept -- so this is the floor, not a first draft.
     */
    private fun StringBuilder.exampleC(
        rich: Boolean,
        format: AuditOutputFormat,
        draft: Boolean,
        facts: Boolean,
    ) {
        appendLine("Worked example C -- a dialogue that mentions one issue twice:")
        appendLine(
            "  \"Customer: We calibrate every torque wrench annually. The sign-off line on this " +
                "one's calibration certificate is " +
                "blank. Auditor: The calibration was done on 12 May, but the certificate was never " +
                "signed off by the quality manager, as your procedure requires. I am recording the " +
                "result as OK for documentation. The requirement is ISO 9001:2015 clause 7.1.5. " +
                "The quality manager should sign it off, and you should check the other " +
                "calibration certificates for the same gap. Customer: It will be signed this week, " +
                "and we will review the rest of the file.\"",
        )
        appendLine("a correct reply is:")
        if (draft) {
            appendLine("FINDINGS")
            appendLine("- calibration certificate not signed off (mentioned twice, one issue)")
            appendLine("ACTIONS")
            appendLine("- quality manager to sign off the certificate")
            appendLine("- check other calibration certificates for the same gap")
        }
        if (format == AuditOutputFormat.RECORDS) {
            appendLine("RECORDS")
            if (facts) {
                appendLine("FACTS")
                appendLine(
                    "- Torque wrench calibrated 12 May; certificate not signed off by the quality manager.",
                )
                appendLine()
            }
            appendLine("STATED")
            appendLine("- Customer: We calibrate every torque wrench annually")
            appendLine()
            appendLine("UNRESOLVED")
            appendLine("- The calibration certificate is still unsigned")
            appendLine()
            appendLine("VERDICT")
            appendLine("OK for documentation")
            appendLine()
            // The element the dialogue is actually about, and the one thing no other example shows:
            // a conclusion with its reason and its grounds, sitting above the non-conformity that
            // qualified it rather than being that non-conformity restated.
            appendLine("ELEMENT")
            appendLine("statement: Calibration was carried out, but the certificate was not signed off")
            appendLine("type: Result")
            appendLine("speaker: Auditor")
            appendLine("result: resultOkForDocumentation")
            appendLine("reason: The work was done on 12 May; only the approval record is missing")
            appendLine("evidence: The calibration date, the procedure's sign-off requirement, and the blank line")
            appendLine()
            appendLine("NONCONFORMITY")
            appendLine("title: Calibration certificate not signed off")
            if (rich) {
                appendLine(
                    "detail: Calibration done 12 May, but the certificate was never signed off as " +
                        "the procedure requires.",
                )
            }
            appendLine("quote: the certificate was never signed off by the quality manager")
            // The finding is graded as a finding, and the ELEMENT above it is graded as an element.
            // They differ on purpose and this example is where that is taught: the requirement was
            // met in substance -- the calibration happened -- so the element concludes OK for
            // documentation, while the specific gap it names is still a non-conformity.
            //
            // This line read resultOkForDocumentation until a run graded the same missing signature
            // both ways on two passes of one file. It was the example doing it: the cue list calls
            // "not signed" a non-conformity, the result rule calls weak approval OK for
            // documentation, and a finding labelled with the element's conclusion let a model take
            // either. AuditResultType.isNonconformity is false for resultOkForDocumentation, so the
            // old line also asked for a NONCONFORMITY block whose grade said it was not one.
            appendLine("result: minorNonconformity")
            appendLine("standard: ISO 9001:2015 clause 7.1.5")
            appendLine()
            // No priority line on either action: the dialogue states none, and a field invented to
            // fill the shape is the failure every other rule here exists to prevent.
            appendLine("ACTION")
            appendLine("title: Quality manager to sign off the certificate")
            appendLine("status: Agreed")
            appendLine("accepted: yes")
            appendLine()
            appendLine("ACTION")
            appendLine("title: Check other calibration certificates for the same gap")
            appendLine("status: Agreed")
            appendLine("accepted: yes")
        } else {
        appendLine("JSON")
            appendLine(
                if (rich) {
                "{" + factsKey(
                    facts,
                    """["Torque wrench calibrated 12 May; certificate not signed off by """ +
                        """the quality manager."]""",
                ) + """"verdict":"OK for documentation",""" +
                    ELEMENT_JSON +
                    """"nonConformities":[{"title":"Calibration certificate not signed off",""" +
                    """"detail":"Calibration done 12 May, but the certificate was never signed """ +
                    """off as the procedure requires.",""" +
                    """"evidence":"the certificate was never signed off by the quality manager",""" +
                    """"result":"minorNonconformity",""" +
                    """"standards":["ISO 9001:2015 clause 7.1.5"]}],""" +
                    // No empty "detail" fields here: a field shown blank teaches only that it may
                    // be blank, which the schema line above already says.
                    ACTIONS_JSON
            } else {
                "{" + factsKey(
                    facts,
                    """["Torque wrench calibrated 12 May; certificate not signed off by """ +
                        """the quality manager."]""",
                ) + """"verdict":"OK for documentation",""" +
                    ELEMENT_JSON +
                    """"nonConformities":[{"title":"Calibration certificate not signed off",""" +
                    """"evidence":"the certificate was never signed off by the quality manager",""" +
                    """"result":"minorNonconformity",""" +
                    """"standards":["ISO 9001:2015 clause 7.1.5"]}],""" +
                    ACTIONS_JSON
            },
        )
        }
        appendLine()
    }


    /**
     * MAP stage, run once per chunk. Everything variable lives below the preamble, which is what
     * lets a prefix-reusing engine skip re-decoding it.
     *
     * Emits facts + verdict + non-conformities + actions, each finding carrying its own
     * `resultType`. Stated here rather than graded afterwards: a later pass re-reads a finding
     * with no memory of what was concluded, so it can only soften one, and severity that moves in
     * a single direction has to be decided where the evidence is. Parsed leniently by
     * [AuditAnalysisParser]; quotes are checked by [AuditEvidence].
     */
    fun extraction(
        part: String,
        partNumber: Int,
        totalParts: Int,
        profile: AuditPromptProfile = AuditPromptProfile.RICH,
        format: AuditOutputFormat = OUTPUT_FORMAT,
        draft: Boolean = true,
        facts: Boolean = true,
    ): String = buildString {
        append(preamble(profile, format, draft, facts))
        appendLine()
        if (totalParts > 1) {
            appendLine("----- BEGIN TEXT (section $partNumber of $totalParts) -----")
        } else {
            appendLine("----- BEGIN TEXT -----")
        }
        appendLine(part)
        appendLine("----- END TEXT -----")
    }
}
