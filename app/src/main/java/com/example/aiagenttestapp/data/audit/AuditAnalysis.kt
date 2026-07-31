package com.example.aiagenttestapp.data.audit

import com.example.aiagent.engine.core.ContextWindow
import com.example.aiagent.engine.core.EngineId
import com.example.aiagenttestapp.util.Reasoning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One audit finding: a short [title] plus optional [detail] -- evidence, a clause, an owner, etc. */
data class AuditFinding(
    val title: String,
    val detail: String = "",
    /**
     * Standards or clauses the transcript explicitly cites for this finding (e.g.
     * "ISO 9001:2015 §7.2"). Only what the transcript actually names -- the analysis is instructed
     * never to invent one -- so an empty list means the transcript named no standard here.
     */
    val standards: List<String> = emptyList(),
    /**
     * How serious this non-conformity is: [AuditSeverity.MAJOR], [MINOR][AuditSeverity.MINOR] or
     * [OBSERVATION][AuditSeverity.OBSERVATION] -- or blank when the finding is an action (which is
     * not graded) or the model gave no grade. Kept so a minor deviation is flagged and shown as
     * such, never silently folded in with the majors or dropped for not being one.
     */
    val severity: String = "",
    /**
     * A word-for-word quote from the source text supporting this finding. Blank means either none
     * was offered or the one offered could not be found in the text -- [AuditEvidence] clears a quote
     * it cannot verify, so a non-blank value here has been checked against the source, never merely
     * claimed. Declared last so existing positional constructor calls keep working.
     */
    val evidence: String = "",
    /**
     * The conclusion reached about this element, in the vocabulary both apps share.
     *
     * Null means no *clear* conclusion, which is a verdict in itself and not a gap to be filled --
     * see [AuditResultType]. Runs alongside [severity] while the pipeline moves onto it; [severity]
     * is the older three-way grade and will go once every producer sets this.
     */
    val resultType: AuditResultType? = null,
)

/**
 * The three grades an audit finding can carry, most serious first. A "minor" or "observation" is
 * still a non-conformity to be reported -- the grade only ranks it, it never gates whether it shows.
 */
object AuditSeverity {
    const val MAJOR = "major"
    const val MINOR = "minor"
    const val OBSERVATION = "observation"

    /**
     * Folds whatever a model wrote -- "Major", "minor non-conformity", "obs", "critical" -- onto one
     * of the three canonical grades, or "" if it named none or something unrecognised. Substring
     * matching, because small models rarely emit the bare word on its own.
     *
     * The *last* grade word mentioned wins, not the highest-ranked one. That matters as soon as the
     * model is allowed to reason before answering: "this is not major, an isolated lapse -- minor"
     * ends on the real verdict, where a priority-ordered scan would read the word it just rejected.
     */
    fun normalise(raw: String): String {
        val text = raw.lowercase()
        if (text.isBlank()) return ""

        var grade = ""
        var at = -1
        for ((candidate, words) in MARKERS) {
            for (word in words) {
                val index = text.lastIndexOf(word)
                if (index > at) {
                    at = index
                    grade = candidate
                }
            }
        }
        return grade
    }

    /**
     * Like [normalise], but the *first* grade word wins.
     *
     * The two rules exist because the two prompts are shaped differently, and each rule is wrong for
     * the other prompt. Where the model reasons before answering, the last word is the conclusion and
     * an earlier one may be a possibility it rejected. Where the model is asked for a bare word, there
     * is no reasoning to scan past -- and the realistic failure is the opposite one: a model that
     * echoes the options back ("major, minor, or observation") would be read as "observation" by a
     * last-wins scan, silently downgrading a finding.
     */
    fun normaliseFirst(raw: String): String {
        val text = raw.lowercase()
        if (text.isBlank()) return ""

        var grade = ""
        var at = Int.MAX_VALUE
        for ((candidate, words) in MARKERS) {
            for (word in words) {
                val index = text.indexOf(word)
                if (index in 0 until at) {
                    at = index
                    grade = candidate
                }
            }
        }
        return grade
    }

    private val MARKERS = listOf(
        MAJOR to listOf("major", "critical", "serious"),
        MINOR to listOf("minor"),
        OBSERVATION to listOf("observ", "opportunity", "ofi"),
    )

    /** Sort/merge rank: higher is more serious; an ungraded finding sorts last. */
    fun rank(severity: String): Int = when (severity) {
        MAJOR -> 3
        MINOR -> 2
        OBSERVATION -> 1
        else -> 0
    }

    /** The more serious of two grades -- so a finding graded minor in one chunk and major in another surfaces as major. */
    fun moreSevere(a: String, b: String): String = if (rank(a) >= rank(b)) a else b

    /**
     * Reads a batched grading reply ("1: minor", "2 - major", "3. observation") into index -> grade,
     * 0-based. Only lines that both start with a number and name a grade are taken, so preamble or
     * trailing chatter is ignored rather than misread; anything absent from the result is re-graded
     * on its own by the caller.
     */
    fun parseBatch(reply: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (line in reply.lineSequence()) {
            val match = BATCH_LINE.find(line.trim()) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (number < 1) continue
            val grade = normalise(match.groupValues[2])
            if (grade.isNotEmpty()) result[number - 1] = grade
        }
        return result
    }

    private val BATCH_LINE = Regex("""^(\d{1,3})\s*[.:)\-]\s*(.+)$""")
}

/**
 * The structured read of an audit transcript the model is asked to produce: the non-conformities it
 * found, the actions needed to resolve them, a summary of the conversation, and a few follow-up
 * questions (FAQs) the user can tap to ask live.
 *
 * Every field defaults to empty so a partial or garbled model response still yields a usable object
 * rather than throwing -- an on-device model will sometimes drop a section or malform one array.
 */
data class AuditAnalysis(
    val summary: String = "",
    /**
     * The overall result in the source's own words ("OK for documentation"), copied by the
     * extraction stage and never re-scaled: an auditor's stated classification and the app's own
     * major/minor triage are different judgements with different consequences in audit practice, so
     * the authoritative one is kept verbatim. Blank when the document states none.
     */
    val verdict: String = "",
    val nonConformities: List<AuditFinding> = emptyList(),
    val actions: List<AuditFinding> = emptyList(),
    val faqs: List<String> = emptyList(),
    /**
     * The concrete factual content of one chunk -- who, what, dates, numbers, equipment. The raw
     * material the final summary is written from, so a detail survives being compressed once rather
     * than twice (a per-chunk summary that is then summarised again loses the specifics).
     */
    val facts: List<String> = emptyList(),
    /**
     * Quick mode, final result only: the whole-document summary as at most
     * [QuickAudit.MAX_POINTS] points.
     *
     * Its own field rather than newlines packed into [summary], because it is a list and the report
     * and the PDF both render it as one. A detailed report leaves this empty and a quick one leaves
     * [summary] empty, so [mode] -- not the emptiness of a field -- is what decides the layout.
     */
    val keyPoints: List<String> = emptyList(),
    /**
     * Final result only: which kind of read produced this report, as an [AuditMode] name. Blank on
     * reports saved before quick mode existed, which [AuditMode.from] reads as DETAILED -- correct,
     * since detailed was the only read that existed then.
     */
    val mode: String = "",
    /**
     * Per-chunk only: the model's reply for this section could not be parsed at all. Recorded rather
     * than discarded because the alternative -- an empty analysis silently marked done -- presents a
     * document as fully audited when a section of it was never read. For a compliance artefact that
     * is the worst available failure, so it is counted into [unanalysedSections] and shown.
     */
    val parseFailed: Boolean = false,
    /**
     * Per-chunk only: why this section's reply could not be parsed, in words fit for the report
     * ("the model's JSON was cut off before it closed"). Diagnosed in code from the reply's shape
     * by [AuditAnalysisParser.diagnose]; blank when [parseFailed] is false.
     */
    val parseError: String = "",
    /** Final result only: how many sections could not be analysed. 0 means the document is complete. */
    val unanalysedSections: Int = 0,
    /**
     * Final result only: one line per unanalysed section, naming the section and why it failed --
     * the reasoning behind [unanalysedSections], which alone only says how many. Also carries the
     * document-level gaps below, so everything a reader must know before trusting this report is in
     * one list.
     */
    val unanalysedReasons: List<String> = emptyList(),
    /**
     * Final result only: characters of the document that were never chunked, because it needed more
     * sections than the queue's cap allows. 0 for a document that fits, which is nearly all of them.
     */
    val truncatedChars: Int = 0,
    /**
     * Final result only: the per-section notes did not all fit the summary prompt, so the overall
     * summary was written from a subset of them. The findings are unaffected -- they are merged in
     * code, never through the summary -- but the prose is thinner than the document supports.
     */
    val notesTrimmed: Boolean = false,
    /**
     * Final result only: which engine and prompt profile produced this report. Recorded so the
     * artefact can explain itself later -- two builds can run the same transcript through different
     * engines, and an auditor has to be able to see which one this was.
     */
    val engineName: String = "",
    val promptProfile: String = "",
) {
    // facts and verdict count towards emptiness: a clean section legitimately returns facts (or a
    // stated verdict) with no findings, and the parser uses isEmpty to pick the real object out of
    // a reply that also contains a draft.
    val isEmpty: Boolean
        get() = summary.isBlank() && verdict.isBlank() && nonConformities.isEmpty() &&
            actions.isEmpty() && faqs.isEmpty() && facts.isEmpty() && keyPoints.isEmpty()

    /** Which read produced this. Old reports carry no mode and are detailed by construction. */
    val auditMode: AuditMode get() = AuditMode.from(mode.ifBlank { null })

    /**
     * Whether this report covers less than the whole document, for any reason. What the reader has
     * to be told before the findings, so a partial read is never presented in the same voice as a
     * complete one -- whether the gap is a section that failed, a tail that was never chunked, or
     * notes the summary could not hold.
     */
    val isIncomplete: Boolean
        get() = unanalysedSections > 0 || truncatedChars > 0 || notesTrimmed
}

/**
 * Prompt builders for the chunked transcript audit pipeline.
 *
 * Two stages:
 *   MAP    - [extraction] runs once per chunk: facts + verdict + non-conformities + actions,
 *            each finding stating its own result type
 *   REDUCE - [finalSummary] runs once, emits the overall prose summary only
 *
 * There was a third, between them, that graded each merged finding. It went with the shared result
 * vocabulary: a grading pass is a second opinion by construction, and a second opinion can only
 * ever soften a finding.
 *
 * Non-conformities and actions are merged in code between the stages, NOT by the model. Handing a
 * small model 40 findings and asking it to consolidate is where recall silently dies. The split of
 * duplicate-handling mirrors that: the model collapses restatements *within* its own chunk (it can
 * see they are the same issue), and [AuditChunker.mergeFindings] does the cross-chunk merge, where
 * no single model call ever sees both mentions.
 */
/**
 * How much prompt an engine can afford, which is decided by whether it reuses a shared prefix.
 *
 * [RICH] is for engines that diff an incoming prompt against what they last decoded (llama.cpp does
 * this in nativeIngestPrompt): the preamble is decoded once and reused across chunks, so paying for
 * worked examples costs little *time* and buys recall.
 *
 * [LEAN] is for engines with no such reuse (LiteRT-LM 0.14 has no session fork, and rebuilds the
 * conversation on reset). There the preamble is re-prefilled on every chunk, so it is the most
 * expensive text in the pipeline and carries one worked example instead of three.
 *
 * What prefix reuse does NOT buy either profile is *space*: [AuditQueue] reserves the preamble out
 * of the context window for every chunk whether or not it will be re-decoded, so every token here
 * is a token of transcript that chunk cannot carry -- which is why even RICH is kept as short as
 * its examples allow.
 */
enum class AuditPromptProfile {
    RICH,
    LEAN,
    ;

    /** Stored on the finished report, so the artefact records how it was produced. */
    val label: String get() = name.lowercase()

    companion object {
        fun forEngine(engineId: EngineId): AuditPromptProfile =
            if (engineId == EngineId.LLAMA_CPP) RICH else LEAN
    }
}

/**
 * The shape an extraction turn is asked to answer in.
 *
 * [JSON] is what this pipeline started with and what most models are drilled on. Its problem is not
 * that models get it wrong often, it is that it has no error locality: one stray character anywhere
 * invalidates the whole document, so a reply with every finding extracted, every quote intact and
 * the object properly closed can still be worth nothing. Every section lost here was lost that way.
 *
 * [RECORDS] is a line-oriented alternative with no paired delimiters, no punctuation between fields
 * and nothing to escape. A garbled line costs a line, a garbled block costs a block, and a reply cut
 * off half way keeps everything before the cut -- so truncation degrades a section instead of losing
 * it. It also costs fewer tokens, which is where nearly all the time goes.
 *
 * Both are kept so the two can be run over the same document and compared on what actually matters:
 * sections parsed, and findings per section.
 */
enum class AuditOutputFormat { JSON, RECORDS }


/**
 * Pulls an [AuditAnalysis] out of a model response.
 *
 * Tolerant on purpose, exactly like the tool-call parser: models wrap JSON in ```json fences, open
 * with "Sure!", precede it with a `<think>` block, and vary their key names. So rather than demand
 * clean output we scan for the first balanced JSON object and read whatever fields we recognise,
 * accepting either `["a","b"]` or `[{"title":"a"}]` for the finding arrays.
 */
object AuditAnalysisParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A response can hold more than one `{...}`: the model drafts its findings in plain text before
     * the JSON, and a draft may itself contain braces. So rather than trusting the first balanced
     * pair, take the first one that both parses and carries a field we recognise. A parsed-but-empty
     * object is kept only as a fallback, so `{}` still yields an empty analysis rather than null.
     */
    fun parse(response: String): AuditAnalysis? {
        var fallback: AuditAnalysis? = null
        for (candidate in balancedJsonObjects(response)) {
            // Parse as-is, and only if that fails try the repaired form. Repairing blindly would
            // rewrite replies that were never broken.
            val obj = parseObject(candidate)
                ?: parseObject(repairCommonMalformations(candidate))
                ?: continue

            val analysis = obj.toAnalysis()
            if (!analysis.isEmpty) return analysis
            if (fallback == null) fallback = analysis
        }
        return fallback
    }

    /**
     * Why [parse] returned null for [response], in words a report reader can act on. Diagnosed
     * from the reply's shape in plain code -- the ways a small model's reply becomes unreadable
     * are few and visible: it said nothing, it wrote prose without any JSON, or it opened a JSON
     * object that never closed, which is almost always the output budget cutting generation off
     * mid-answer. Only called after parsing has failed, so the fallthrough is genuinely malformed
     * JSON rather than "we did not look".
     */
    fun diagnose(response: String): String {
        val text = response.trim()
        return when {
            text.isEmpty() -> "the model returned an empty reply"
            !text.contains('{') -> "the model's reply contained no JSON to read"
            balancedJsonObjects(text).isEmpty() ->
                "the model's JSON was cut off before it closed"
            else -> "the model's JSON could not be read"
        }
    }

    private fun parseObject(candidate: String): JsonObject? = try {
        json.parseToJsonElement(candidate) as? JsonObject
    } catch (e: Exception) {
        null
    }

    /**
     * Repairs the two malformations this pipeline has actually seen around an empty field, both of
     * them unambiguous, both observed in real replies:
     *
     *  - `"verdict":"","","nonConformities":[...` -- the value, then a *second* empty string where
     *    the next key belongs. A key must be followed by a colon, so an empty string followed by a
     *    comma is malformed however it got there.
     *  - `"verdict":,"nonConformities":[...` -- a key and colon with no value at all. A colon must
     *    be followed by a value, and the only value this can have meant is an empty one.
     *
     * Each cost an entire section that had otherwise been extracted perfectly well: every finding
     * present, every quote intact, the object closed properly, and the whole thing thrown away over
     * two characters.
     *
     * The distinction has to be structural, not textual. `""` inside an array is ordinary data
     * (`"facts":["", ...]`) and a blind replacement would corrupt replies that parse. Hence the
     * container stack.
     *
     * In the same spirit as the rest of this parser, which already tolerates code fences, `<think>`
     * blocks, "Sure!" preambles and renamed keys rather than demanding clean output from a model
     * that will not reliably give it.
     */
    private fun repairCommonMalformations(text: String): String {
        val out = StringBuilder(text.length)
        val containers = ArrayDeque<Char>()
        var expectKey = false
        var index = 0

        while (index < text.length) {
            when (val c = text[index]) {
                '{' -> { containers.addLast('{'); expectKey = true; out.append(c); index++ }
                '[' -> { containers.addLast('['); expectKey = false; out.append(c); index++ }
                '}', ']' -> { containers.removeLastOrNull(); expectKey = false; out.append(c); index++ }
                ',' -> { expectKey = containers.lastOrNull() == '{'; out.append(c); index++ }
                ':' -> {
                    expectKey = false
                    out.append(c)
                    // A colon with nothing after it before the next comma or brace: supply the empty
                    // value the model omitted, rather than lose the section over it.
                    var after = index + 1
                    while (after < text.length && text[after].isWhitespace()) after++
                    if (after < text.length && (text[after] == ',' || text[after] == '}')) {
                        out.append("\"\"")
                    }
                    index++
                }
                '"' -> {
                    val close = stringEnd(text, index)
                    if (close == null) {
                        // Unterminated: nothing further can be judged, so pass the rest through.
                        out.append(text, index, text.length)
                        return out.toString()
                    }
                    val token = text.substring(index, close + 1)
                    var after = close + 1
                    while (after < text.length && text[after].isWhitespace()) after++
                    val strayKey = expectKey && containers.lastOrNull() == '{' && token == "\"\"" &&
                        after < text.length && text[after] == ','
                    if (strayKey) {
                        // Drop the empty key and the comma that follows it; a key is still expected.
                        index = after + 1
                    } else {
                        out.append(token)
                        index = close + 1
                    }
                }

                else -> { out.append(c); index++ }
            }
        }
        return out.toString()
    }

    /** Index of the quote closing the string that opens at [start], or null if it never closes. */
    private fun stringEnd(text: String, start: Int): Int? {
        var i = start + 1
        while (i < text.length) {
            when {
                text[i] == '\\' -> i++
                text[i] == '"' -> return i
            }
            i++
        }
        return null
    }

    /**
     * The parser's own account of why a reply would not parse, for the log.
     *
     * [parse] catches the serialization exception and returns null, which is right for the pipeline
     * -- a section is lost either way -- but it throws away the only precise statement anyone will
     * get about what was wrong. kotlinx names the offset and what it expected there, so this
     * recovers the message and quotes the JSON either side of the offset. That is the difference
     * between "the model's JSON could not be read" and seeing the stray quotation mark.
     *
     * Re-parses rather than plumbing the message out of [parse], because this only ever runs after a
     * failure. Returns null when every candidate parses -- the reply was rejected for some other
     * reason, and there is no parser error to report.
     *
     * Log only. The report says something a reader of an audit can use; this says something the
     * person fixing the prompt can use.
     */
    fun parseFailureDetail(response: String): String? {
        for (candidate in balancedJsonObjects(response.trim())) {
            val message = runCatching { json.parseToJsonElement(candidate) }
                .exceptionOrNull()?.message ?: continue
            val firstLine = message.lineSequence().firstOrNull().orEmpty().take(MESSAGE_CHARS)
            val offset = OFFSET.find(message)?.groupValues?.get(1)?.toIntOrNull()
                ?: return "parser: $firstLine"
            val from = (offset - AROUND_CHARS).coerceIn(0, candidate.length)
            val to = (offset + AROUND_CHARS).coerceIn(from, candidate.length)
            val around = candidate.substring(from, to).replace("\n", "\\n")
            return "parser: $firstLine | around offset $offset: $around"
        }
        return null
    }

    private val OFFSET = Regex("""offset (\d+)""")
    private const val MESSAGE_CHARS = 160
    private const val AROUND_CHARS = 70

    private fun JsonObject.toAnalysis() = AuditAnalysis(
        summary = firstString("summary", "conversationSummary"),
        verdict = firstString("verdict", "statedResult", "auditResult"),
        nonConformities = findings("nonConformities", "non_conformities", "findings"),
        actions = findings("actions", "actionItems", "correctiveActions"),
        faqs = stringsUnder("faqs", "questions", "faq"),
        facts = stringsUnder("facts", "keyFacts", "notes"),
        keyPoints = stringsUnder("keyPoints", "points"),
        mode = firstString("mode"),
        parseFailed = (this["parseFailed"] as? JsonPrimitive)?.contentOrNull == "true",
        parseError = firstString("parseError"),
        unanalysedSections = (this["unanalysedSections"] as? JsonPrimitive)?.contentOrNull
            ?.toIntOrNull() ?: 0,
        unanalysedReasons = stringsUnder("unanalysedReasons"),
        truncatedChars = (this["truncatedChars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        notesTrimmed = (this["notesTrimmed"] as? JsonPrimitive)?.contentOrNull == "true",
        engineName = firstString("engineName"),
        promptProfile = firstString("promptProfile"),
    )

    /** First value under any of [keys] that is a non-blank string primitive, or "". */
    private fun JsonObject.firstString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
            .orEmpty()

    /** First array under any of [keys], mapping each element to an [AuditFinding]. */
    private fun JsonObject.findings(vararg keys: String): List<AuditFinding> =
        firstArray(*keys)?.mapNotNull { it.toFinding() } ?: emptyList()

    /**
     * First array under any of [keys], read as plain strings. Tolerant: a bare string is taken as-is;
     * an object yields its most descriptive field (question / standard / title / ...). Used for both
     * the FAQ list and a finding's standards, where a small model may emit either shape.
     */
    private fun JsonObject.stringsUnder(vararg keys: String): List<String> =
        firstArray(*keys)?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.trim()?.ifBlank { null }
                is JsonObject ->
                    el.firstString("standard", "clause", "reference", "question", "q", "title", "name")
                        .ifBlank { null }
                else -> null
            }
        } ?: emptyList()

    private fun JsonObject.firstArray(vararg keys: String): JsonArray? =
        keys.firstNotNullOfOrNull { this[it] as? JsonArray }

    private fun JsonElement.toFinding(): AuditFinding? = when (this) {
        is JsonPrimitive -> contentOrNull?.trim()?.ifBlank { null }?.let { AuditFinding(it) }
        is JsonObject -> {
            val title = firstString("title", "name", "nonConformity", "action", "finding")
            // "evidence" is deliberately NOT a detail alias any more: it is its own field now, and
            // leaving it here would mean the two silently fought over the same value.
            val detail = firstString("detail", "description", "owner")
            val evidence = firstString("evidence", "quote", "excerpt")
            val standards = stringsUnder("standards", "standard", "clauses", "references")
            val severity = AuditSeverity.normalise(
                firstString("severity", "classification", "level", "grade", "type", "category"),
            )
            // Read from its own key, and only that key. The severity aliases above are a net cast
            // wide on purpose, because a three-way grade can be spelled a dozen ways; a result type
            // is a closed vocabulary, so anything that is not one of its five names is no
            // conclusion rather than a near miss to be guessed at.
            val resultType = AuditResultType.fromWire(firstString("resultType"))
            when {
                title.isNotEmpty() ->
                    AuditFinding(title, detail, standards, severity, evidence, resultType)

                detail.isNotEmpty() -> AuditFinding(
                    title = detail,
                    standards = standards,
                    severity = severity,
                    evidence = evidence,
                    resultType = resultType,
                )

                else -> null
            }
        }
        else -> null
    }

    /**
     * Every top-level `{...}` that balances, in order. Brace-counting rather than regex because the
     * object nests ({"title":...} inside an array), and string-aware so a `}` inside a quoted value
     * does not end the object early. Mirrors the tool-call extractor.
     *
     * Nested objects are not returned separately -- scanning resumes after each match -- so these are
     * genuine candidates for "the reply's JSON", not fragments of one.
     */
    private fun balancedJsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var index = text.indexOf('{')
        while (index >= 0) {
            val close = balancedEnd(text, index)
            if (close == null) {
                index = text.indexOf('{', index + 1)
            } else {
                objects += text.substring(index, close + 1)
                index = text.indexOf('{', close + 1)
            }
        }
        return objects
    }

    /** Index of the `}` closing the object that opens at [start], or null if it never balances. */
    private fun balancedEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
