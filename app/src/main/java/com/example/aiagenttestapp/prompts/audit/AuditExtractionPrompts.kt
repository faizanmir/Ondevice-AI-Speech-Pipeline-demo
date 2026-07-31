package com.example.aiagenttestapp.prompts.audit

import com.example.aiagenttestapp.data.audit.AuditOutputFormat
import com.example.aiagenttestapp.data.audit.AuditPromptProfile

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
    ): String = CACHE.getOrPut(Triple(profile, format, draft)) { buildPreamble(profile, format, draft) }

    // Built once per (profile, format, draft) and reused. The stability matters on llama.cpp, where
    // a byte-identical prefix is what lets the preamble be decoded once per document rather than
    // once per section -- which is also why `draft` is decided once for a whole document rather than
    // per section from measured speed: changing the preamble mid-document would break that reuse.

    private val CACHE =
        mutableMapOf<Triple<AuditPromptProfile, AuditOutputFormat, Boolean>, String>()


    private fun buildPreamble(
        profile: AuditPromptProfile,
        format: AuditOutputFormat,
        draft: Boolean,
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
        appendLine("If the text contains none, use an empty list. Do not invent one to fill the list.")
        appendLine()

        // The v2 wording here was "list each one as its own item, do not merge several into one" --
        // written to protect recall, with nothing distinguishing several distinct issues from one
        // issue mentioned several times. On a dialogue transcript, which restates every finding in
        // the discussion, the evidence list and the closing summary, that reliably tripled the
        // count. Identity is anchored on the object and requirement, NOT on sharing a fix: one
        // remedial act ("retrain all staff") can resolve two genuinely distinct findings.
        appendLine("Count issues, not mentions. A transcript often raises the same issue several")
        appendLine("times -- when it is first noticed, when it is discussed, in an evidence list, and")
        appendLine("again in the closing summary. Those are ONE non-conformity, not several. Two")
        appendLine("mentions are the same issue when the same record, item, person, or event fails")
        appendLine("the same requirement; keep one item with the clearest wording. Genuinely")
        appendLine("different problems always stay separate items, even if they look alike or would")
        appendLine("be fixed together.")
        appendLine()

        // Actions used to be an eight-word afterthought against a fourteen-cue definition of
        // non-conformities, and the model allocated attention accordingly: it wrote the actions
        // into the prose and left the actions array empty. Equal billing, own cue words, and the
        // one structural hint that matters -- they live in the closing exchange.
        appendLine("Also find EVERY action. An action is a step the text itself says will be taken,")
        appendLine("should be taken, or is recommended -- something to be completed, corrected,")
        appendLine("signed, recorded, reviewed, verified, checked, or followed up elsewhere.")
        appendLine("Actions usually sit near the end: in what the auditor recommends and in what the")
        appendLine("audited party commits to in reply. Both count, but a recommendation and the")
        appendLine("reply accepting it are ONE action, not two. Do not invent an action the text")
        appendLine("does not state; if it states none, use an empty list.")
        appendLine()

        appendLine("Also give:")
        appendLine("- facts: the concrete factual content of this section -- who, what, equipment,")
        appendLine("  dates, numbers, locations, decisions. Short lines, specific not general. These")
        appendLine("  are the raw material for a later overall summary, so do not generalise them away.")
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

        appendLine("Rules:")
        appendLine("- Use only what the text says. Never add, infer, or generalise beyond it.")
        appendLine("- Every non-conformity must include \"evidence\": a word-for-word quote of at most")
        appendLine("  15 words, copied exactly from the text. If you cannot quote it, it is not a")
        appendLine("  finding -- leave it out.")
        appendLine("- Put a \"standards\" value only if a standard or clause is written in the text")
        appendLine("  (e.g. \"ISO 9001:2015 §7.2\"). It is often named just once, in a closing")
        appendLine("  statement far from where the issue is discussed -- check there too, and attach")
        appendLine("  it to the finding it refers to. Never invent one; use [] if none is named.")
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
            appendLine()
            appendLine("RECORDS")
            appendLine("FACTS")
            appendLine("- a short fact")
            appendLine("- a short fact")
            appendLine()
            appendLine("VERDICT")
            appendLine("the stated result, word for word -- leave this block out if none is stated")
            appendLine()
            appendLine("NONCONFORMITY")
            appendLine("title: a short title")
            if (rich) appendLine("detail: one short sentence")
            appendLine("quote: the word-for-word quote")
            appendLine("standard: the clause named in the text -- leave this line out if none is")
            appendLine()
            appendLine("ACTION")
            appendLine("title: a short title")
            appendLine()
            appendLine("Repeat the NONCONFORMITY block for each one, and the ACTION block for each")
            appendLine("action. One field per line. No brackets, no braces, no quotation marks around")
            appendLine("values, no commas between fields.")
        } else {
            appendLine(
                if (draft) "2. JSON -- then convert that list, unchanged, into one JSON object of this shape:"
                else "Answer with one JSON object of this shape, and nothing else:",
            )
            appendLine(
                if (rich) {
                    """{"facts":["..."],"verdict":"...",""" +
                        """"nonConformities":[{"title":"...","detail":"...","evidence":"...",""" +
                        """"standards":["ISO 9001:2015 §7.2"]}],""" +
                        """"actions":[{"title":"...","detail":"...","standards":[]}]}"""
                } else {
                    """{"facts":["..."],"verdict":"...",""" +
                        """"nonConformities":[{"title":"...","evidence":"...",""" +
                        """"standards":["ISO 9001:2015 §7.2"]}],""" +
                        """"actions":[{"title":"...","standards":[]}]}"""
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
            exampleA(format, draft)
            exampleB(format, draft)
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
        exampleC(rich, format, draft)

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
    private fun StringBuilder.exampleA(format: AuditOutputFormat, draft: Boolean) {
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
            appendLine("FACTS")
            appendLine("- Extinguisher last inspected 14 months ago.")
            appendLine("- Two new staff had not completed induction training.")
            appendLine()
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
                """{"facts":["Extinguisher last inspected 14 months ago.",""" +
                    """"Two new staff had not completed induction training."],"verdict":"",""" +
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
    private fun StringBuilder.exampleB(format: AuditOutputFormat, draft: Boolean) {
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
            appendLine("FACTS")
            appendLine("- Calibration log signed 3 March.")
            appendLine("- Four operators held current certificates.")
            appendLine("- Line ran at 22 units per hour.")
            // No NONCONFORMITY or ACTION block at all -- which is what "none" looks like in this
            // format, and is the whole point of this example.
        } else {
            appendLine("JSON")
            appendLine(
                """{"facts":["Calibration log signed 3 March.",""" +
                    """"Four operators held current certificates.",""" +
                    """"Line ran at 22 units per hour."],"verdict":"",""" +
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
    private fun StringBuilder.exampleC(rich: Boolean, format: AuditOutputFormat, draft: Boolean) {
        appendLine("Worked example C -- a dialogue that mentions one issue twice:")
        appendLine(
            "  \"Customer: The sign-off line on the torque wrench's calibration certificate is " +
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
            appendLine("FACTS")
            appendLine(
                "- Torque wrench calibrated 12 May; certificate not signed off by the quality manager.",
            )
            appendLine()
            appendLine("VERDICT")
            appendLine("OK for documentation")
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
            appendLine("standard: ISO 9001:2015 clause 7.1.5")
            appendLine()
            appendLine("ACTION")
            appendLine("title: Quality manager to sign off the certificate")
            appendLine()
            appendLine("ACTION")
            appendLine("title: Check other calibration certificates for the same gap")
        } else {
        appendLine("JSON")
            appendLine(
                if (rich) {
                """{"facts":["Torque wrench calibrated 12 May; certificate not signed off by the """ +
                    """quality manager."],"verdict":"OK for documentation",""" +
                    """"nonConformities":[{"title":"Calibration certificate not signed off",""" +
                    """"detail":"Calibration done 12 May, but the certificate was never signed """ +
                    """off as the procedure requires.",""" +
                    """"evidence":"the certificate was never signed off by the quality manager",""" +
                    """"standards":["ISO 9001:2015 clause 7.1.5"]}],""" +
                    // No empty "detail" fields here: a field shown blank teaches only that it may
                    // be blank, which the schema line above already says.
                    """"actions":[{"title":"Quality manager to sign off the certificate",""" +
                    """"standards":[]},""" +
                    """{"title":"Check other calibration certificates for the same gap",""" +
                    """"standards":[]}]}"""
            } else {
                """{"facts":["Torque wrench calibrated 12 May; certificate not signed off by the """ +
                    """quality manager."],"verdict":"OK for documentation",""" +
                    """"nonConformities":[{"title":"Calibration certificate not signed off",""" +
                    """"evidence":"the certificate was never signed off by the quality manager",""" +
                    """"standards":["ISO 9001:2015 clause 7.1.5"]}],""" +
                    """"actions":[{"title":"Quality manager to sign off the certificate",""" +
                    """"standards":[]},""" +
                    """{"title":"Check other calibration certificates for the same gap",""" +
                    """"standards":[]}]}"""
            },
        )
        }
        appendLine()
    }


    /**
     * MAP stage, run once per chunk. Everything variable lives below the preamble, which is what
     * lets a prefix-reusing engine skip re-decoding it.
     *
     * Emits facts + non-conformities + actions. Severity is NOT asked for here -- it is graded in a
     * separate pass ([gradeSeverity]) so this step never splits its attention away from finding
     * everything. Parsed leniently by [AuditAnalysisParser]; quotes are checked by [AuditEvidence].
     */
    fun extraction(
        part: String,
        partNumber: Int,
        totalParts: Int,
        profile: AuditPromptProfile = AuditPromptProfile.RICH,
        format: AuditOutputFormat = OUTPUT_FORMAT,
        draft: Boolean = true,
    ): String = buildString {
        append(preamble(profile, format, draft))
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
