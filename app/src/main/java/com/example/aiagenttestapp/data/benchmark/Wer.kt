package com.example.aiagenttestapp.data.benchmark

/**
 * Word error rate for a transcript against the script that was read aloud.
 *
 * A line-for-line port of `docs/wer.py`, and the porting rule is fidelity over taste: every WER
 * figure this app has ever published was produced by that script, and an on-device number is only
 * comparable to the table in `docs/README.md` if it comes out of the *same* arithmetic. Three
 * things in particular are deliberate copies of Python behaviour rather than the textbook choice:
 *
 *  - **The diff is a minimum edit distance.** Both sides used to port Python's `difflib`
 *    (Ratcliff-Obershelp: longest *contiguous* matching blocks), which is not a minimal edit
 *    script. It agreed with an optimal alignment on ordinary transcripts and failed spectacularly
 *    on one input: a reference containing repeated content. Scoring audio that plays a dialogue
 *    twice against a reference holding it twice, it anchored the reference's first copy against the
 *    hypothesis's *second* pass and reported **103.9%** where the answer was 8.7%. Both scorers
 *    were changed together on 2026-08-19; numbers published before that date came from the old
 *    alignment and are a few tenths different.
 *  - **"zero" is not a number.** `wer.py`'s `_en_value` chains lookups with `or`, and 0 is falsy
 *    in Python, so "zero" resolves to nothing and "zero thousand" is never rewritten. Fixing
 *    that here would un-fix comparability.
 *  - **Rule order is load-bearing.** Years before the general English rules (a left-to-right
 *    accumulator reads "twenty twenty six" as 46), tag-stripping before number expansion, ß→ss
 *    after the German table.
 *
 * Two numbers are reported on purpose, as in the script: the raw figure counts "nineteen point
 * four" vs "19.4" as errors, overstating the damage; the normalised figure rewrites number
 * phrases on both sides identically, leaving only genuine mistakes.
 */
object Wer {

    /** One scoring pass. [werPercent] uses the reference word count as the denominator. */
    data class PassScore(
        val referenceWords: Int,
        val hypothesisWords: Int,
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        /** (reference run, hypothesis run) per non-equal opcode; placeholders for one-sided ones. */
        val pairs: List<Pair<String, String>>,
    ) {
        val errors: Int get() = substitutions + deletions + insertions
        val werPercent: Double
            get() = if (referenceWords == 0) 0.0 else 100.0 * errors / referenceWords
    }

    data class Report(
        val raw: PassScore,
        val normalised: PassScore,
        /**
         * Hypothesis words over reference words, as a percentage.
         *
         * Read this *before* the WER, per the shared protocol: under ~90% the transcript is
         * truncated -- the engine starved or the session died -- and the number that follows is a
         * measure of how much is missing, not of how well it heard. A 30-60% "WER" with low
         * coverage is the classic signature, and it has already been mistaken here for an accuracy
         * result. Over 110% means a repetition loop or the wrong file pair.
         */
        val coverage: Double,
        /**
         * Character error rate on the normalised text.
         *
         * The cross-check for when WER looks wrong: high WER with low CER means tokenisation --
         * numbers, codes, compound words -- rather than misrecognition. Mandatory alongside WER for
         * German, where compounds make the word-level rate read harsher than the transcript is.
         */
        val cerPercent: Double,
        /** The normalised pass's most frequent error pairs with their counts, capped at 25. */
        val topPairs: List<Triple<String, String, Int>>,
    )

    /**
     * Scores [hypothesis] against [reference]. [lang] is "en" or "de" and picks the numeral
     * grammar, exactly as `--lang=` does; anything unrecognised behaves as German, the script's
     * default.
     */
    fun report(reference: String, hypothesis: String, lang: String): Report {
        // say() silence directives ([[slnc 500]]) are stripped from the reference only, so the
        // Archive say-scripts work directly as references -- same as wer.py's main().
        val ref = reference.replace(Regex("""\[\[[^\]]*\]\]"""), "")

        val raw = score(normalise(ref, expandNumbers = false, lang = lang),
            normalise(hypothesis, expandNumbers = false, lang = lang))
        val norm = score(normalise(ref, expandNumbers = true, lang = lang),
            normalise(hypothesis, expandNumbers = true, lang = lang))

        // Counter.most_common: count descending, insertion order on ties. groupingBy preserves
        // encounter order and sortedByDescending is stable, so the tie-break matches.
        val topPairs = norm.pairs
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(25)
            .map { Triple(it.key.first, it.key.second, it.value) }

        val refWords = normalise(ref, expandNumbers = true, lang = lang)
        val hypWords = normalise(hypothesis, expandNumbers = true, lang = lang)

        return Report(
            raw = raw,
            normalised = norm,
            coverage = if (refWords.isEmpty()) 0.0 else 100.0 * hypWords.size / refWords.size,
            cerPercent = characterErrorRate(refWords, hypWords),
            topPairs = topPairs,
        )
    }

    /**
     * Error rate over characters rather than words, on the normalised text.
     *
     * The same alignment, one level down: words are joined with single spaces and compared
     * character by character, which is what `jiwer.cer` does and therefore what the shared protocol
     * expects. Its value is as a disagreement detector -- a high WER beside a low CER means the
     * words were nearly right and the tokenisation was not, which on number- and code-heavy content
     * is most of the difference between two scorers.
     */
    internal fun characterErrorRate(ref: List<String>, hyp: List<String>): Double {
        val r = ref.joinToString(" ").map { it.toString() }
        val h = hyp.joinToString(" ").map { it.toString() }
        if (r.isEmpty()) return 0.0
        return 100.0 * score(r, h).errors / r.size
    }

    // ---- Tokenisation ---------------------------------------------------------------------------

    /**
     * `[NON-CONFORMITY]`, `[/ACTION]`, and speaker tags like `[S1]`.
     *
     * Digits are in the class deliberately. Without them a reference carrying `[S1]`/`[S2]` scored
     * those as the words "s1"/"s2" and charged a deletion for each -- about 2.5 points on a
     * 600-word dialogue, for text nobody ever said.
     */
    private val TAG = Regex("""\[/?[a-z0-9\- ]+\]""")
    private val NON_ALPHANUM = Regex("""[^a-zäöü0-9,.\- ]""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * The tokenizer, applied identically to reference and hypothesis. Order is the script's:
     * lowercase, strip marker tags, expand numbers, ß→ss, filter to the allowed characters
     * (everything else becomes a *space*, splitting tokens rather than deleting characters),
     * collapse whitespace, split, strip `.,-` from token edges, drop empties.
     */
    internal fun normalise(text: String, expandNumbers: Boolean, lang: String): List<String> {
        var t = text.lowercase()
        t = TAG.replace(t, " ")
        if (expandNumbers) {
            t = if (lang == "en") expandEnglish(t) else expandGerman(t)
        }
        t = t.replace("ß", "ss")
        t = NON_ALPHANUM.replace(t, " ")
        t = WHITESPACE.replace(t, " ")

        return t.split(" ")
            .map { it.trim { c -> c == '.' || c == ',' || c == '-' } }
            .filter { it.isNotEmpty() }
    }

    // ---- Word boundaries ------------------------------------------------------------------------

    /**
     * `\w` as Python means it, written out.
     *
     * Python 3 defines `\w` (and therefore `\b`) over Unicode for str patterns, so "zwölf" is one
     * word to `wer.py`. Java's default `\w` is ASCII, which puts a boundary between "f" and "ö" --
     * so a bare `\b` here would score German differently from the script this is a port of.
     */
    private const val WORD_CHAR = """[\p{L}\p{N}_]"""

    /**
     * `\b`, spelled out as lookarounds, and **not** written as `(?U)\b`.
     *
     * This cost a device run. `(?U)` is a Java extension: it compiles happily on the JVM, every
     * unit test here passed with it, and the first real transcription on device died in this
     * object's static initialiser --
     * `PatternSyntaxException: Syntax error in regexp pattern near index 3`. Android's regex is
     * ICU, which has no inline `U` flag, so the whole file was one unreachable class. Lookarounds
     * over an explicit character class mean the same thing on both engines and cannot regress
     * into a platform difference no JVM test can see.
     */
    private const val WB_BEFORE = """(?<!$WORD_CHAR)"""
    private const val WB_AFTER = """(?!$WORD_CHAR)"""

    /** `\bword\b`, portably. */
    private fun bounded(pattern: String) = Regex("$WB_BEFORE$pattern$WB_AFTER")

    // ---- German numerals ------------------------------------------------------------------------

    /**
     * Spoken German numerals -> digit forms, longest first so "zweitausendsechsundzwanzig" is
     * consumed before "zwei". A flat list of the phrases the scripts happen to use, copied
     * verbatim from wer.py (its duplicate "zweiundzwanzig" entry included -- harmless, and a
     * diff against the source should show nothing).
     */
    private val GERMAN_NUMBERS = listOf(
        "zweitausendsechsundzwanzig" to "2026",
        "zweiundzwanzigtausend" to "22000",
        "zweitausendzweihundertvier" to "2204",
        "zweihundertsiebzehn" to "217",
        "zweihundertneunundzwanzig" to "229",
        "vierhundertsechs" to "406",
        "vierhundertzwölf" to "412",
        "zweihundertvier" to "204",
        "dreihunderteinunddreißig" to "331",
        "neunundneunzig" to "99",
        "sechsundsiebzig" to "76",
        "siebenundzwanzigsten" to "27.",
        "einundzwanzigsten" to "21.",
        "fünfunddreißig" to "35",
        "vierundsiebzig" to "74",
        "zweiundsiebzig" to "72",
        "vierundfünfzig" to "54",
        "fünfundfünfzig" to "55",
        "achtundachtzig" to "88",
        "siebenundzwanzig" to "27",
        "vierundzwanzig" to "24",
        "vierunddreißig" to "34",
        "einunddreißig" to "31",
        "neunzehnten" to "19.",
        "vierzehnten" to "14.",
        "achtzehnten" to "18.",
        "zweiundzwanzig" to "22",
        "fünfundvierzig" to "45",
        "zweiundzwanzig" to "22",
        "dreiundzwanzig" to "23",
        "neunundfünfzig" to "59",
        "fünfzehn" to "15",
        "vierzehn" to "14",
        "neunzehn" to "19",
        "achtzehn" to "18",
        "siebzehn" to "17",
        "sechzehn" to "16",
        "dreizehn" to "13",
        "zwanzig" to "20",
        "dreißig" to "30",
        "vierzig" to "40",
        "fünfzig" to "50",
        "sechzig" to "60",
        "siebzig" to "70",
        "achtzig" to "80",
        "neunzig" to "90",
        "zwölf" to "12",
        "dritten" to "3.",
        "neunten" to "9.",
        "elf" to "11",
        "zehn" to "10",
        "null" to "0",
        "eins" to "1",
        "zwei" to "2",
        "drei" to "3",
        "vier" to "4",
        "fünf" to "5",
        "sechs" to "6",
        "sieben" to "7",
        "acht" to "8",
        "neun" to "9",
    ).map { (word, digit) -> bounded(word) to digit }

    private val GERMAN_KOMMA = Regex("""(\d)\s+komma\s+(\d)""")
    private val GERMAN_STRICH = bounded("strich")
    private val GERMAN_HYPHEN = Regex("""(\d)\s+-\s+(\d)""")

    private fun expandGerman(text: String): String {
        var t = text
        for ((pattern, digit) in GERMAN_NUMBERS) t = pattern.replace(t, Regex.escapeReplacement(digit))
        t = GERMAN_KOMMA.replace(t, "$1,$2")
        t = GERMAN_STRICH.replace(t, "-")
        t = GERMAN_HYPHEN.replace(t, "$1-$2")
        return t
    }

    // ---- English numerals -----------------------------------------------------------------------

    // Insertion order is preserved (Python dicts do; LinkedHashMap does) because the rewrite
    // loops iterate these in order.
    private val ENGLISH_UNITS = linkedMapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val ENGLISH_TENS = linkedMapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val ENGLISH_ORDINALS = linkedMapOf(
        "first" to "1st", "second" to "2nd", "third" to "3rd", "fourth" to "4th", "fifth" to "5th",
        "sixth" to "6th", "seventh" to "7th", "eighth" to "8th", "ninth" to "9th", "tenth" to "10th",
        "eleventh" to "11th", "twelfth" to "12th", "thirteenth" to "13th", "fourteenth" to "14th",
        "fifteenth" to "15th", "sixteenth" to "16th", "seventeenth" to "17th",
        "eighteenth" to "18th", "nineteenth" to "19th", "twentieth" to "20th", "thirtieth" to "30th",
    )

    private val YEARS =
        bounded("""twenty twenty(?: (one|two|three|four|five|six|seven|eight|nine))?""")
    private val THOUSANDS = bounded("""($WORD_CHAR+(?: $WORD_CHAR+)?) thousand""")
    private val HUNDREDS =
        bounded("""($WORD_CHAR+) hundred(?: and ($WORD_CHAR+(?: $WORD_CHAR+)?))?""")
    private val POINT = Regex("""(\d)\s+point\s+(\d)""")
    private val DASH = bounded("dash")
    // Boundary on the leading edge only, as in wer.py's `\bminus\s+(\d)`.
    private val MINUS = Regex("""${WB_BEFORE}minus\s+(\d)""")
    private val DIGIT_HYPHEN = Regex("""(\d)\s*-\s*(\d)""")
    private val DIGIT_RUN = bounded("""\d(?:\s+\d)+""")

    private fun expandEnglish(text: String): String {
        var t = text

        // Years first: "twenty twenty six" composes to 46 under any left-to-right accumulator.
        t = YEARS.replace(t) { m ->
            val unit = m.groupValues[1]
            if (unit.isEmpty()) "2020" else "20${20 + ENGLISH_UNITS.getValue(unit)}"
        }

        // Compound ordinals ("twenty ninth") before the bare ones.
        for ((tens, tv) in ENGLISH_TENS) {
            for ((ordWord, ordDigit) in ENGLISH_ORDINALS) {
                if (ordWord == "twentieth" || ordWord == "thirtieth") continue
                val unit = ordDigit.takeWhile { it.isDigit() }.toInt()
                if (unit > 9) continue
                t = t.replace(bounded("$tens $ordWord"), "${tv + unit}${ordDigit.takeLast(2)}")
            }
        }
        for ((word, digit) in ENGLISH_ORDINALS) {
            t = t.replace(bounded(word), digit)
        }

        // Scales. The truthiness copy: a phrase valuing 0 (or nothing) leaves the match alone.
        t = THOUSANDS.replace(t) { m ->
            val value = enValue(m.groupValues[1])
            if (value != null && value != 0) "${value * 1000}" else m.value
        }
        t = HUNDREDS.replace(t) { m -> enHundred(m) }

        // Bare compounds and singles, longest first so "forty seven" beats "forty".
        for ((tens, tv) in ENGLISH_TENS) {
            for ((unit, uv) in ENGLISH_UNITS) {
                if (uv in 1..9) t = t.replace(bounded("$tens $unit"), "${tv + uv}")
            }
        }
        for ((word, value) in ENGLISH_TENS + ENGLISH_UNITS) {
            t = t.replace(bounded(word), "$value")
        }

        // "seven point nine" -> 7.9, repeated so "8 point 5 point 1" folds all the way down.
        repeat(4) { t = POINT.replace(t, "$1.$2") }
        t = DASH.replace(t, "-")
        t = MINUS.replace(t, "-$1")
        // Digits only on both sides -- a blanket rule glued ordinary words onto negative numbers.
        t = DIGIT_HYPHEN.replace(t, "$1-$2")
        // Spoken batch codes ("T four two dash seven one six" -> "T42-716"): runs of single
        // digits close up; a lone digit is left alone.
        t = DIGIT_RUN.replace(t) { m -> m.value.replace(" ", "") }
        return t
    }

    /**
     * Numeric value of a short spoken-number phrase, or null. The `or`-chain copy: a unit lookup
     * that answers 0 falls through exactly as Python falsiness makes it, so "zero" is null.
     */
    private fun enValue(phrase: String): Int? {
        val parts = phrase.split(" ")
        if (parts.size == 1) {
            val w = parts[0]
            if (w.isNotEmpty() && w.all { it.isDigit() }) return w.toInt()
            return ENGLISH_UNITS[w]?.takeIf { it != 0 } ?: ENGLISH_TENS[w]
        }
        if (parts.size == 2 && parts[0] in ENGLISH_TENS && parts[1] in ENGLISH_UNITS) {
            val v = ENGLISH_UNITS.getValue(parts[1])
            if (v in 1..9) return ENGLISH_TENS.getValue(parts[0]) + v
        }
        return null
    }

    private fun enHundred(m: MatchResult): String {
        val head = enValue(m.groupValues[1]) ?: return m.value
        val tailPhrase = m.groupValues[2]
        val tail = if (tailPhrase.isNotEmpty()) enValue(tailPhrase) ?: 0 else 0
        return "${head * 100 + tail}"
    }

    // ---- Scoring: a SequenceMatcher port --------------------------------------------------------

    internal data class Opcode(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    internal fun score(ref: List<String>, hyp: List<String>): PassScore {
        var sub = 0
        var del = 0
        var ins = 0
        val pairs = mutableListOf<Pair<String, String>>()

        for (op in opcodes(ref, hyp)) {
            when (op.tag) {
                "replace" -> {
                    // An optimal script pairs off what it can and calls the remainder what it is,
                    // rather than charging a whole block as substitutions the way a longest-match
                    // diff had to. The total is unchanged; the breakdown is finally honest.
                    val refRun = op.i2 - op.i1
                    val hypRun = op.j2 - op.j1
                    sub += minOf(refRun, hypRun)
                    del += maxOf(0, refRun - hypRun)
                    ins += maxOf(0, hypRun - refRun)
                    pairs += ref.subList(op.i1, op.i2).joinToString(" ") to
                        hyp.subList(op.j1, op.j2).joinToString(" ")
                }
                "delete" -> {
                    del += op.i2 - op.i1
                    pairs += ref.subList(op.i1, op.i2).joinToString(" ") to "<dropped>"
                }
                "insert" -> {
                    ins += op.j2 - op.j1
                    pairs += "<inserted>" to hyp.subList(op.j1, op.j2).joinToString(" ")
                }
            }
        }

        return PassScore(
            referenceWords = ref.size,
            hypothesisWords = hyp.size,
            substitutions = sub,
            deletions = del,
            insertions = ins,
            pairs = pairs,
        )
    }

    /**
     * Errors charged to each reference word, summing to the same total [score] reports.
     *
     * The common frame two runs are compared in -- see [MatchedPairs]. Attribution by *reference*
     * position rather than by decoded slice is what lets two backends be compared at all: they cut
     * the audio differently, so their slices are not the same segments, while the reference is the
     * same text for both.
     *
     * An insertion has no reference word of its own, so it is charged to the word it was inserted
     * before (the last one, at the end of the transcript). That keeps the total honest and puts the
     * error in the segment a reader would point at.
     */
    internal fun errorProfile(ref: List<String>, hyp: List<String>): IntArray {
        val out = IntArray(ref.size)
        if (ref.isEmpty()) return out

        for (op in opcodes(ref, hyp)) {
            when (op.tag) {
                "delete" -> for (i in op.i1 until op.i2) out[i]++

                "insert" -> out[minOf(op.i1, out.size - 1)] += op.j2 - op.j1

                "replace" -> {
                    // One error per reference word covers the substitutions and any deletions;
                    // a longer hypothesis run adds its surplus as insertions.
                    for (i in op.i1 until op.i2) out[i]++
                    val surplus = (op.j2 - op.j1) - (op.i2 - op.i1)
                    if (surplus > 0) out[minOf(op.i2 - 1, out.size - 1)] += surplus
                }
            }
        }
        return out
    }

    /**
     * The minimum edit script between two word lists, in `difflib`'s opcode shape.
     *
     * Exact, computed in a band that widens until the answer proves itself. A full table is the
     * textbook form and is quadratic in memory -- a 3,800-word reference against a 3,600-word
     * transcript is 14 million cells, and references only get longer -- while the optimal path for
     * any transcript worth scoring hugs the diagonal. Correctness does not rest on that guess: see
     * the termination rule below.
     */
    internal fun opcodes(a: List<String>, b: List<String>): List<Opcode> {
        // Common prefix and suffix are matched by every optimal alignment, so they never need to
        // enter the DP. On a good transcript that is most of the input.
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var end = 0
        while (end < a.size - start && end < b.size - start &&
            a[a.size - 1 - end] == b[b.size - 1 - end]
        ) {
            end++
        }

        val coreA = a.subList(start, a.size - end)
        val coreB = b.subList(start, b.size - end)

        // Every step the optimal path takes away from the diagonal is one insertion or deletion, so
        // a path costing c can never stray further than c from it: once the banded cost comes back
        // at or under the band width, no wider band can find anything cheaper. The first rule here
        // was "stop when two successive widths agree", which is not sound -- two too-narrow bands
        // can agree on the same wrong answer, and in a fuzz run against brute force one pair in
        // 1500 did exactly that.
        var band = maxOf(INITIAL_BAND, kotlin.math.abs(coreA.size - coreB.size) + 1)
        var ops: IntArray
        while (true) {
            val (candidate, cost) = banded(coreA, coreB, band)
            ops = candidate
            if (cost <= band || band > coreA.size + coreB.size) break
            band *= 2
        }

        // OP_EQUAL is zero, so the stripped prefix and suffix need no filling in.
        val full = IntArray(start + ops.size + end)
        ops.copyInto(full, start)
        return group(full)
    }

    /**
     * One banded pass: the operation per aligned position, and the cost. The cost is the true edit
     * distance only when the optimal path fits inside the band, which [opcodes] is what checks.
     */
    private fun banded(a: List<String>, b: List<String>, band: Int): Pair<IntArray, Int> {
        val n = a.size
        val m = b.size
        if (n == 0) return IntArray(m) { OP_INSERT } to m

        val growHi = maxOf(0, m - n)
        val growLo = maxOf(0, n - m)
        fun lo(i: Int) = maxOf(0, i - growLo - band)
        fun hi(i: Int) = minOf(m, i + growHi + band)

        var prevLo = lo(0)
        var prevHi = hi(0)
        var prev = IntArray(prevHi - prevLo + 1) { prevLo + it }   // row 0: all insertions
        val marks = ArrayList<Pair<Int, ByteArray>>(n)

        for (i in 1..n) {
            val rowLo = lo(i)
            val rowHi = hi(i)
            val row = IntArray(rowHi - rowLo + 1)
            val mark = ByteArray(rowHi - rowLo + 1)

            for (j in rowLo..rowHi) {
                var best = UNREACHABLE
                var from = MARK_DELETE
                if (j - 1 >= rowLo) {
                    val v = row[j - 1 - rowLo] + 1
                    if (v < best) { best = v; from = MARK_INSERT }
                }
                if (j in prevLo..prevHi) {
                    val v = prev[j - prevLo] + 1
                    if (v < best) { best = v; from = MARK_DELETE }
                }
                if (j > 0 && j - 1 in prevLo..prevHi) {
                    val same = a[i - 1] == b[j - 1]
                    val v = prev[j - 1 - prevLo] + if (same) 0 else 1
                    if (v <= best) { best = v; from = if (same) MARK_EQUAL else MARK_REPLACE }
                }
                if (j == 0) {
                    // First column is i deletions, always. Once the band has moved off column 0 it
                    // is not derivable from the row above, and without this an alignment that opens
                    // with a deletion is lost.
                    best = i
                    from = MARK_DELETE
                }
                row[j - rowLo] = best
                mark[j - rowLo] = from
            }

            marks += rowLo to mark
            prev = row
            prevLo = rowLo
            prevHi = rowHi
        }

        val script = IntArray(n + m)
        var at = script.size
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i == 0) { script[--at] = OP_INSERT; j--; continue }
            val (rowLo, mark) = marks[i - 1]
            val step = if (j in rowLo..(rowLo + mark.size - 1)) mark[j - rowLo] else MARK_DELETE
            when (step) {
                MARK_INSERT -> { script[--at] = OP_INSERT; j-- }
                MARK_DELETE -> { script[--at] = OP_DELETE; i-- }
                MARK_EQUAL -> { script[--at] = OP_EQUAL; i--; j-- }
                else -> { script[--at] = OP_REPLACE; i--; j-- }
            }
        }
        return script.copyOfRange(at, script.size) to prev[m - prevLo]
    }

    /** Consecutive non-equal operations become one opcode, the way `difflib` reports them. */
    private fun group(script: IntArray): List<Opcode> {
        val out = mutableListOf<Opcode>()
        var i = 0
        var j = 0
        var refRun = 0
        var hypRun = 0

        fun flush() {
            if (refRun == 0 && hypRun == 0) return
            val tag = when {
                refRun > 0 && hypRun > 0 -> "replace"
                refRun > 0 -> "delete"
                else -> "insert"
            }
            out += Opcode(tag, i, i + refRun, j, j + hypRun)
            i += refRun
            j += hypRun
            refRun = 0
            hypRun = 0
        }

        for (op in script) {
            when (op) {
                OP_EQUAL -> {
                    flush()
                    val last = out.lastOrNull()
                    if (last != null && last.tag == "equal" && last.i2 == i) {
                        out[out.size - 1] = last.copy(i2 = last.i2 + 1, j2 = last.j2 + 1)
                    } else {
                        out += Opcode("equal", i, i + 1, j, j + 1)
                    }
                    i++
                    j++
                }
                OP_REPLACE -> { refRun++; hypRun++ }
                OP_DELETE -> refRun++
                else -> hypRun++
            }
        }
        flush()
        return out
    }

    /** Wide enough that an ordinary transcript never needs a second pass. */
    private const val INITIAL_BAND = 64

    /** Larger than any real cost, and small enough that adding one cannot overflow. */
    private const val UNREACHABLE = 1 shl 29

    private const val OP_EQUAL = 0
    private const val OP_REPLACE = 1
    private const val OP_DELETE = 2
    private const val OP_INSERT = 3

    private const val MARK_INSERT: Byte = 1
    private const val MARK_DELETE: Byte = 2
    private const val MARK_EQUAL: Byte = 3
    private const val MARK_REPLACE: Byte = 4
}
