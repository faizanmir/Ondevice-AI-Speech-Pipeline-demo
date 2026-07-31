package com.example.aiagenttestapp.prompts

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditOutputFormat
import com.example.aiagenttestapp.data.audit.AuditPromptProfile
import com.example.aiagenttestapp.data.audit.QuickAudit

object AuditPrompts {

    /**
     * The format extraction asks for. Records by default -- see [AuditOutputFormat] -- with JSON
     * retained so a regression can be measured against the format it replaced rather than argued
     * about.
     */
    val OUTPUT_FORMAT = AuditOutputFormat.RECORDS

    /** The system prompt the audit session loads the model with: tools off, transcript-grounded. */
    const val SYSTEM_PROMPT =
        "You are an audit assistant. You read audit transcripts and report strictly from their " +
            "contents -- identifying non-conformities and the corrective actions required. Be " +
            "precise and concise, and never invent details that are not in the transcript."

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
    private const val MULTI_PART = 2

    /**
     * Tokens one extraction turn spends before a single character of transcript: the system prompt
     * plus the preamble and section markers. Measured from the prompts themselves so it cannot
     * drift when they are edited.
     *
     * Does NOT include the `<think>` block a reasoning model may emit -- that is output, and is
     * covered by [AuditChunker.outputReserveTokens], which is sized for it.
     *
     * Defaults to [AuditPromptProfile.RICH] -- the larger of the two -- because chunk sizes are
     * pinned at enqueue while the engine, and so the profile, is resolved later. Reserving for the
     * largest preamble is the only sizing that stays safe if that resolution changes.
     */
    fun fixedPromptTokens(profile: AuditPromptProfile = AuditPromptProfile.RICH): Int =
        ContextWindow.estimateTokens(SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(
                // Measured with the draft, the larger of the two, so a run that drops it has more
                // room than reserved rather than less.
                extraction(
                    "",
                    partNumber = 1,
                    totalParts = MULTI_PART,
                    profile = profile,
                    draft = true,
                ),
            )

    /**
     * The same measurement for whichever mode is running. Quick's preamble is a fraction of
     * detailed's, and that difference is most of why quick needs fewer sections to cover the same
     * document: the preamble is charged against every section's window, so a shorter one leaves more
     * of each window for text.
     */
    fun fixedPromptTokens(mode: AuditMode, profile: AuditPromptProfile): Int = when (mode) {
        AuditMode.DETAILED -> fixedPromptTokens(profile)
        AuditMode.QUICK -> quickFixedPromptTokens()
    }

    fun quickFixedPromptTokens(): Int =
        ContextWindow.estimateTokens(QUICK_SYSTEM_PROMPT) +
            ContextWindow.estimateTokens(
                quickExtraction("", partNumber = 1, totalParts = MULTI_PART),
            )

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

    /**
     * REDUCE stage. Takes only the facts gathered per chunk, in document order, plus the stated
     * [verdict] if any chunk captured one.
     *
     * Deliberately does NOT receive the non-conformities or actions: those are unioned and
     * deduplicated in code ([AuditChunker.mergeFindings]). The model's only job here is prose, which
     * keeps the decode budget small and removes any opportunity to drop a finding.
     *
     * Plain prose out, no JSON wrapper: there is no structure to enforce in a paragraph, and forcing
     * long text through a JSON string only invites the escaping mistakes (raw newlines, bare quotes)
     * that a small model makes. The caller takes the reply as-is.
     *
     * The one prompt in the pipeline whose payload is not bounded by chunking: a document may hold
     * up to [AuditQueue.MAX_CHUNKS] sections, each contributing facts, so [maxNoteChars] caps what
     * is carried. Past the cap the notes are trimmed to an even share per section rather than
     * truncated at the tail -- an over-long prompt loses its *start* to eviction, and dropping the
     * tail instead would lose the closing section, which is where an audit states its result. Both
     * failures are silent in the finished summary, which is why the cap is applied here rather than
     * left to the engine.
     *
     * @param factsByPart facts lists in document order, one entry per chunk
     * @param verdict the document's stated overall result, verbatim, or "" if it states none
     * @param maxNoteChars characters of notes this prompt may carry; see [summaryNoteBudget]
     */
    fun finalSummary(
        factsByPart: List<List<String>>,
        verdict: String = "",
        maxNoteChars: Int = Int.MAX_VALUE,
    ): String = buildString {
        appendLine("You are writing the overall summary of one document.")
        appendLine("Below are factual notes taken from each section of it, in order.")
        appendLine()
        appendLine("Write a detailed summary of the document as a whole: what it covers, what was")
        appendLine("checked, what happened, and what state things were in.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only the notes below. Every statement must trace back to a note.")
        appendLine("- Do not add context, do not speculate, do not fill gaps. If the notes do not")
        appendLine("  say something, leave it out.")
        appendLine("- Keep the specifics: dates, numbers, names and equipment stay in.")
        if (verdict.isNotBlank()) {
            // The stated result must survive in its own words. Summarised through a small model it
            // otherwise drifts onto whatever scale the model prefers -- the exact failure the
            // verdict field exists to prevent.
            appendLine("- The document states its overall result as \"$verdict\". End the summary by")
            appendLine("  reporting that result in exactly those words -- do not reword it, and do")
            appendLine("  not add any judgement of your own.")
        }
        appendLine("- Be as long as the material supports and no longer. Do not pad to feel complete.")
        appendLine()
        appendLine("Reply with the summary as plain text only. No headings, no JSON, no code fences.")
        appendLine()
        appendLine("----- BEGIN NOTES -----")
        trimNotes(factsByPart, maxNoteChars).forEachIndexed { index, facts ->
            append(sectionHeader(index))
            facts.forEach { appendLine("- $it") }
        }
        appendLine("----- END NOTES -----")
    }

    /** What [finalSummary] will spend on [factsByPart], headers and bullet markers included. */
    fun noteChars(factsByPart: List<List<String>>): Int =
        factsByPart.foldIndexed(0) { index, total, facts ->
            total + sectionHeader(index).length + facts.sumOf { it.length + LINE_OVERHEAD }
        }

    /**
     * Characters of notes [finalSummary] may carry on a model with [contextTokens] of context, once
     * its own scaffolding, the loaded system prompt and room for the prose reply are set aside.
     *
     * Floored at [MIN_SUMMARY_NOTE_TOKENS] so a small model still gets *some* notes -- a summary
     * written from a handful of facts is thin, but one written from none is a blank report.
     */
    fun summaryNoteBudget(contextTokens: Int, verdict: String = ""): Int {
        val free = contextTokens -
            ContextWindow.estimateTokens(SYSTEM_PROMPT) -
            ContextWindow.estimateTokens(finalSummary(emptyList(), verdict)) -
            SUMMARY_OUTPUT_RESERVE_TOKENS
        return ContextWindow.estimateChars(free.coerceAtLeast(MIN_SUMMARY_NOTE_TOKENS))
    }

    /**
     * Tokens held back for the summary itself, and the cap the summary turn is actually stopped at.
     *
     * Reservation and limit are deliberately the same number: a stage allowed to generate more than
     * was reserved for it overflows the window, which is the failure this pipeline keeps finding new
     * ways to hit. Larger than a chunk's reserve because this stage generates long prose by design,
     * and larger again than it once was because a reasoning model spends part of the allowance on a
     * think block before the summary starts.
     */
    const val SUMMARY_OUTPUT_RESERVE_TOKENS = 2048

    /** Notes floor: below this a reduce prompt has too little to summarise to be worth running. */
    private const val MIN_SUMMARY_NOTE_TOKENS = 256

    /**
     * Trims [factsByPart] to fit [maxChars], so the summary still spans the document end to end
     * rather than stopping wherever the budget ran out.
     *
     * The share is worked out in two passes, because an equal split alone is badly wasteful at
     * length: on an 80-section document a ~9,600-char budget is 120 characters a section, so every
     * dense section is cut to a single fact while the light ones hand back room nobody uses. Instead
     * sections that fit within the equal share keep everything, and what they do not spend is
     * redistributed among the sections that overflowed. On a real document -- a few dense sections
     * among many light ones -- that is the difference between one fact each and most sections
     * surviving whole.
     *
     * A section keeps at least its first fact, truncated if need be: a section silently contributing
     * nothing to the summary is the failure this exists to prevent.
     */
    private fun trimNotes(factsByPart: List<List<String>>, maxChars: Int): List<List<String>> {
        if (maxChars == Int.MAX_VALUE) return factsByPart
        val sections = factsByPart.count { it.isNotEmpty() }
        if (sections == 0 || noteChars(factsByPart) <= maxChars) return factsByPart

        // Pass one: what each section would cost, measured against an equal share.
        val equalShare = (maxChars / sections).coerceAtLeast(1)
        val costs = factsByPart.mapIndexed { index, facts ->
            if (facts.isEmpty()) 0
            else sectionHeader(index).length + facts.sumOf { it.length + LINE_OVERHEAD }
        }
        // Pass two: the room the sections that fit did not use, split among those that did not.
        val unspent = costs.filter { it in 1..equalShare }.sumOf { equalShare - it }
        val overflowing = costs.count { it > equalShare }
        val share = if (overflowing > 0) equalShare + unspent / overflowing else equalShare
        return factsByPart.mapIndexed { index, facts ->
            if (facts.isEmpty()) return@mapIndexed facts
            // The header is charged first, so a section's share covers what it will actually render.
            val header = sectionHeader(index).length
            var used = header
            val kept = facts.takeWhile {
                used += it.length + LINE_OVERHEAD
                used <= share
            }
            // A first fact longer than its section's entire share is truncated, not kept whole and
            // not dropped. Dropping loses the section from the summary; keeping it whole puts the
            // prompt back over the window, where the eviction it causes is silent and costs the
            // *start* of the document. Only reachable when one fact outruns the whole share, which
            // the "short lines" instruction makes rare.
            kept.ifEmpty {
                listOf(facts.first().take((share - header - LINE_OVERHEAD).coerceAtLeast(1)))
            }
        }
    }

    private fun sectionHeader(index: Int) = "Section ${index + 1}:\n"

    /** The "- " and newline each note line costs on top of the fact itself. */
    private const val LINE_OVERHEAD = 3

    // ---- Quick mode ------------------------------------------------------------------------------
    //
    // A different job, not a shorter version of the same one. Detailed extraction has to DECIDE what
    // counts as a non-conformity and defend it with a verified quote and a grade; quick only has to
    // report what the section says and what it says will be done. Everything that made detailed
    // expensive is therefore absent here rather than merely trimmed:
    //
    //   - no plain-text draft, which doubles the output tokens of every section
    //   - no evidence quotes, which are the largest field in a detailed finding
    //   - no severity pass at all, which on a long document is a whole extra sweep of the findings
    //   - no worked examples of what a non-conformity is, which is most of the detailed preamble
    //
    // The chunking is unchanged. Quick reads the whole document section by section exactly as
    // detailed does; only what it asks of each section, and what it does afterwards, is different.

    /** The system prompt a quick run loads: reporting, not judging. */
    const val QUICK_SYSTEM_PROMPT =
        "You summarise documents and transcripts. You report strictly from their contents, keeping " +
            "the specifics, and you never invent detail that is not there."

    /**
     * Quick MAP stage, run once per chunk: the section's key points and any actions it states.
     *
     * Same RECORDS shape detailed uses -- POINTS is read as facts, ACTION as an action -- so the one
     * existing [AuditRecordParser] reads both modes and there is no second parser to keep in step.
     */
    fun quickExtraction(part: String, partNumber: Int, totalParts: Int): String = buildString {
        append(quickPreamble())
        appendLine()
        if (totalParts > 1) {
            appendLine("----- BEGIN TEXT (section $partNumber of $totalParts) -----")
        } else {
            appendLine("----- BEGIN TEXT -----")
        }
        appendLine(part)
        appendLine("----- END TEXT -----")
    }

    /** Built once and reused, for the same prefix-reuse reason as the detailed preamble. */
    private val QUICK_PREAMBLE by lazy { buildQuickPreamble() }

    fun quickPreamble(): String = QUICK_PREAMBLE

    private fun buildQuickPreamble(): String = buildString {
        appendLine("You are reading one section of a document.")
        appendLine("Report only what appears in the section you are given. Other sections are read separately.")
        appendLine()
        appendLine("Give two things:")
        appendLine("- POINTS: the key content of this section -- what it covers, what was checked or")
        appendLine("  discussed, what happened, and what state things were in. Keep the specifics:")
        appendLine("  dates, numbers, names, equipment, decisions and any problems noted. Short lines,")
        appendLine("  specific not general. These are the raw material for a later overall summary,")
        appendLine("  so do not generalise them away.")
        appendLine("- ACTIONS: every step the text itself says will be taken, should be taken, or is")
        appendLine("  recommended -- something to be completed, corrected, signed, recorded, reviewed,")
        appendLine("  verified, checked, or followed up. Actions usually sit near the end, in what is")
        appendLine("  recommended and in what the other party commits to in reply. Both count, but a")
        appendLine("  recommendation and the reply accepting it are ONE action, not two.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only what the text says. Never add, infer, or generalise beyond it.")
        appendLine("- Do not invent an action the text does not state. If it states none, write no")
        appendLine("  ACTION blocks at all -- that is a correct answer, not a failure to look.")
        appendLine("- Do not grade or judge anything. Report what is there.")
        appendLine()
        appendLine("Answer in this exact form, and nothing else:")
        appendLine()
        appendLine("RECORDS")
        appendLine("POINTS")
        appendLine("- a short point")
        appendLine("- a short point")
        appendLine()
        appendLine("ACTION")
        appendLine("title: a short title")
        appendLine()
        appendLine("Repeat the ACTION block for each action. One field per line. No brackets, no")
        appendLine("braces, no quotation marks around values, no commas between fields.")
        appendLine("Write nothing after the records. No code fences.")
        appendLine()

        // One worked example, carrying the two behaviours that actually go wrong here: an action
        // that lives in a closing recommend-and-accept exchange counting once rather than twice, and
        // points that keep their numbers instead of being smoothed into prose. Deliberately not a
        // second "clean text" example -- quick mode has no empty-list failure mode to counterweight,
        // since every section has points.
        appendLine("Worked example:")
        appendLine(
            "  \"Auditor: The torque wrench was calibrated on 12 May, but the certificate was never " +
                "signed off by the quality manager. The quality manager should sign it off, and you " +
                "should check the other certificates for the same gap. Customer: It will be signed " +
                "this week, and we will review the rest of the file.\"",
        )
        appendLine("a correct reply is:")
        appendLine("RECORDS")
        appendLine("POINTS")
        appendLine("- Torque wrench calibrated 12 May.")
        appendLine("- Calibration certificate not signed off by the quality manager.")
        appendLine()
        appendLine("ACTION")
        appendLine("title: Quality manager to sign off the certificate")
        appendLine()
        appendLine("ACTION")
        appendLine("title: Check the other calibration certificates for the same gap")
        appendLine()
        appendLine("Now read the text below the same way.")
    }

    /**
     * Quick REDUCE stage: condense every section's points into at most [QuickAudit.MAX_POINTS].
     *
     * Points only. The actions are unioned and deduplicated in code
     * ([AuditChunker.mergeFindings]), exactly as detailed does with its findings -- handing a small
     * model the whole action list and asking it to consolidate is where recall silently dies, and
     * there is no reason to take that risk for a list the code can merge exactly.
     *
     * [maxNoteChars] bounds the notes the same way [finalSummary] does and for the same reason: a
     * document may reach here with up to [AuditQueue.MAX_CHUNKS] sections of points, and an
     * over-long prompt loses its *start* to eviction rather than its tail.
     */
    fun quickSummary(
        pointsByPart: List<List<String>>,
        maxPoints: Int = QuickAudit.MAX_POINTS,
        maxNoteChars: Int = Int.MAX_VALUE,
    ): String = buildString {
        appendLine("Below are notes taken from each section of one document, in order.")
        appendLine()
        appendLine("Write the overall summary of the document as at most $maxPoints bullet points.")
        appendLine()
        appendLine("Rules:")
        appendLine("- At most $maxPoints points. Fewer is fine if the document does not support more.")
        appendLine("- Cover the document as a whole, start to end -- not just its opening sections.")
        appendLine("- Merge notes that repeat the same thing into one point.")
        appendLine("- Keep the specifics: dates, numbers, names and equipment stay in.")
        appendLine("- Use only the notes below. Every point must trace back to a note. Do not add")
        appendLine("  context, do not speculate, do not fill gaps.")
        appendLine("- One line per point, each starting with \"- \". No headings, no numbering, no")
        appendLine("  JSON, no code fences, and no text before or after the points.")
        appendLine()
        appendLine("----- BEGIN NOTES -----")
        trimNotes(pointsByPart, maxNoteChars).forEachIndexed { index, points ->
            append(sectionHeader(index))
            points.forEach { appendLine("- $it") }
        }
        appendLine("----- END NOTES -----")
    }

    /**
     * Characters of notes [quickSummary] may carry, mirroring [summaryNoteBudget]. Its own function
     * because the two prompts are different sizes and sharing one number would silently over- or
     * under-feed whichever mode did not own it.
     */
    fun quickSummaryNoteBudget(contextTokens: Int): Int {
        val free = contextTokens -
            ContextWindow.estimateTokens(QUICK_SYSTEM_PROMPT) -
            ContextWindow.estimateTokens(quickSummary(emptyList())) -
            QUICK_SUMMARY_OUTPUT_RESERVE_TOKENS
        return ContextWindow.estimateChars(free.coerceAtLeast(MIN_SUMMARY_NOTE_TOKENS))
    }

    /**
     * Tokens held back for the quick summary, and the cap its turn is stopped at.
     *
     * Smaller than [SUMMARY_OUTPUT_RESERVE_TOKENS] because the output is bounded by design -- ten
     * bullet points, not open-ended prose -- but not tiny, since a reasoning model spends part of
     * the allowance thinking before the first bullet appears.
     */
    const val QUICK_SUMMARY_OUTPUT_RESERVE_TOKENS = 1024

    /**
     * Reads a quick summary reply into at most [maxPoints] points.
     *
     * The cap is enforced here rather than trusted to the prompt: asked for "at most 10" a small
     * model will hand back fourteen, and a limit that lives only in a prompt is a request. Tolerant
     * of the decorations models add -- bullets, numbering, markdown emphasis -- and it drops any
     * preamble line that is not a point, so "Here are the key points:" never becomes point one.
     */
    fun parseQuickPoints(reply: String, maxPoints: Int = QuickAudit.MAX_POINTS): List<String> {
        val points = mutableListOf<String>()
        for (raw in reply.lineSequence()) {
            // Emphasis is unwrapped BEFORE the bullet is matched, and only in matched pairs. An
            // asterisk is both a bullet marker and an emphasis marker, so a blanket strip of '*'
            // deletes the very marker the match below looks for -- which silently dropped every
            // point a model wrote as "* like this".
            val line = unwrapEmphasis(raw)
            if (line.isEmpty()) continue
            // A point is a line the model marked as one: a bullet, or a number followed by a
            // separator. Anything else is preamble ("Here are the key points:") or trailing chatter,
            // and taking it would put the model's throat-clearing in the report.
            val body = BULLET.find(line)?.groupValues?.get(1) ?: continue
            // Again on the content, for a point whose text is itself emphasised.
            val point = unwrapEmphasis(body)
            if (point.isEmpty()) continue
            points += point
            if (points.size == maxPoints) break
        }
        return points
    }

    /**
     * Removes matched markdown wrappers from [text], longest marker first so `***x***` does not
     * leave a stray asterisk. Only pairs: a leading marker with no partner is left alone, because
     * that is what a bullet looks like.
     */
    private fun unwrapEmphasis(text: String): String {
        var value = text.trim()
        for (marker in EMPHASIS_MARKERS) {
            while (
                value.length > marker.length * 2 &&
                value.startsWith(marker) &&
                value.endsWith(marker)
            ) {
                value = value.substring(marker.length, value.length - marker.length).trim()
            }
        }
        return value
    }

    private val EMPHASIS_MARKERS = listOf("***", "___", "**", "__", "*", "_", "`")

    /** "- point", "* point", "1. point", "2) point" -- the markers a model actually emits. */
    private val BULLET = Regex("""^(?:[-–—•*+]|\d{1,2}\s*[.):])\s+(.+)$""")

    /**
     * The second pass: grade one already-found non-conformity as major / minor / observation. Split
     * out of [extraction] on purpose -- grading one finding in isolation is a tiny, unambiguous ask a
     * small model answers well, where folding it into the find-everything step measurably hurt recall.
     * The reply is one word, read back through [AuditSeverity.normalise].
     */
    /**
     * Grades a whole batch of non-conformities in one call, which is what makes the severity pass
     * affordable: one call costs a single conversation rebuild and one system-prompt prefill, where
     * grading one at a time paid both per finding for a one-word answer.
     *
     * The reply is read back by index, and anything the model skips or garbles is re-graded
     * individually with [gradeSeverity] -- so batching buys the speed without betting correctness on
     * a small model keeping a numbered list aligned.
     */
    fun gradeSeverityBatch(findings: List<AuditFinding>): String = buildString {
        appendLine("Classify how serious each audit non-conformity below is. For each one choose:")
        appendLine("- major: a requirement is not met in a way that breaks or defeats the process")
        appendLine("- minor: an isolated lapse or partial gap that does not break the whole process")
        appendLine("- observation: a concern or improvement opportunity, not yet an actual breach")
        appendLine()
        appendLine("Reply with one line per item, numbered exactly as below, in the form:")
        appendLine("1: minor")
        appendLine("2: major")
        appendLine("Give every number a grade. One word per line, no other text.")
        appendLine()
        findings.forEachIndexed { index, finding ->
            append("${index + 1}. ")
            appendLine(finding.title)
            if (finding.evidence.isNotBlank()) appendLine("   quote: ${finding.evidence}")
        }
        appendLine()
        append(NO_THINKING)
    }

    /**
     * One finding, one word, no reasoning. The per-finding companion to [gradeSeverityBatch], used
     * for whatever the batch skipped or garbled.
     *
     * [gradeSeverity] asks the same question and lets the model reason first; this does not, which is
     * the whole point -- reasoning is what made grading cost hundreds of decode tokens to produce one
     * word out of three. That one is kept as the last resort for findings neither fast pass settles.
     */
    fun gradeSeverityFast(finding: AuditFinding): String = buildString {
        appendLine("Classify how serious this audit non-conformity is. Choose exactly one:")
        appendLine("- major: a requirement is not met in a way that breaks or defeats the process")
        appendLine("- minor: an isolated lapse or partial gap that does not break the whole process")
        appendLine("- observation: a concern or improvement opportunity, not yet an actual breach")
        appendLine()
        append("Non-conformity: ")
        appendLine(finding.title)
        if (finding.evidence.isNotBlank()) appendLine("Quote: ${finding.evidence}")
        appendLine()
        appendLine("Reply with exactly one word: major, minor, or observation. No other text.")
        appendLine()
        append(NO_THINKING)
    }

    /**
     * Thinking off, for the grading prompts only.
     *
     * It rides on the prompt rather than the system prompt because that is the only place it can be
     * stage-specific: one system prompt is loaded for the whole run, and extraction needs its
     * reasoning -- taking that away cost findings outright when it was tried. Grading is a different
     * task, picking one of three words about a finding that has already been found, and a `<think>`
     * block there is pure cost. It also has to go: a reasoning model writes its thinking *before* the
     * answer, so an eight-token cap with thinking left on would truncate the thinking and never reach
     * the word.
     */
    private val NO_THINKING = ReasoningPrompts.NO_THINK_DIRECTIVE

    fun gradeSeverity(finding: AuditFinding): String = buildString {
        appendLine("Classify how serious this audit non-conformity is. Choose exactly one:")
        appendLine("- major: a requirement is not met in a way that breaks or defeats the process")
        appendLine("- minor: an isolated lapse or partial gap that does not break the whole process")
        appendLine("- observation: a concern or improvement opportunity, not yet an actual breach")
        appendLine()
        append("Non-conformity: ")
        appendLine(finding.title)
        if (finding.detail.isNotBlank()) {
            append("Details: ")
            appendLine(finding.detail)
        }
        appendLine()
        // Reasoning before the verdict, not after: a grade emitted as the very first token has to be
        // decided with no working room. One sentence is enough, and the trailing word is what counts
        // -- AuditSeverity.normalise reads the last grade mentioned, so the conclusion wins.
        appendLine("Give one short sentence of reasoning, then end your reply with the grade alone on")
        appendLine("the final line: exactly one of major, minor, or observation.")
    }
}
