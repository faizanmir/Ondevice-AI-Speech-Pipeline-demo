package com.example.aiagenttestapp.data.speakers

import com.example.aiagenttestapp.data.benchmark.Wer

/**
 * How much of a transcript landed on the right speaker, against a reference that says who spoke.
 *
 * The other half of scoring a diarisation run. [Wer] answers whether the words were heard
 * correctly and is blind to attribution -- it strips `[S1]`/`[S2]` from the reference before
 * counting anything, deliberately, because scoring those tags as the words "s1"/"s2" charged a
 * deletion each and cost about 2.5 points on a 600-word dialogue. That leaves the question this
 * screen actually exists to answer unmeasured: the words can be perfect while every one of them is
 * filed under the wrong person.
 *
 * Two things here are not obvious and are the whole difficulty of the measure:
 *
 *  - **Speaker labels are arbitrary, so the mapping has to be solved, not assumed.** A reference
 *    calls someone `S1`; the run calls them "Alice" or "Unknown Speaker 2", and which cluster got
 *    which number is an accident of where the audio started. Comparing labels directly would score
 *    a perfect run at 0% as often as at 100%. The score is therefore taken under the one-to-one
 *    mapping that makes the run look best -- the same convention diarisation error rate uses, for
 *    the same reason.
 *  - **Only words both sides agree were said are compared.** A word the recogniser never produced
 *    has no speaker to be wrong about, and counting it here would fold transcription errors into
 *    the attribution number -- the two would move together and neither could be diagnosed. This
 *    means a badly truncated transcript can still score high, which is exactly why [comparedWords]
 *    is carried out with the percentage and why the row shows coverage first.
 */
object SpeakerAccuracy {

    /**
     * @param matchedWords Words whose speaker matched under [mapping].
     * @param comparedWords Words both transcripts agree on, and so the denominator.
     * @param mapping Reference speaker tag to the run's speaker name, as it was solved.
     */
    data class Result(
        val matchedWords: Int,
        val comparedWords: Int,
        val mapping: Map<String, String>,
    ) {
        val percent: Double get() = if (comparedWords == 0) 0.0 else 100.0 * matchedWords / comparedWords
    }

    /** One word of a transcript and who it is attributed to. */
    private data class TaggedWord(val word: String, val speaker: String)

    /**
     * `[S1]`, `[Alice]`, `[SPEAKER 2]`: a bracketed label starts that speaker's turn.
     *
     * Deliberately the same character class as [Wer.TAG], case-insensitively -- `Wer` lowercases
     * before it strips. The two must agree or one score reads different text from the other: a
     * looser rule here made `[Dr. Smith]` a speaker to this scorer while `Wer` left "dr smith" in
     * the reference as words, charging a deletion per turn for text nobody said. That is the exact
     * 2.5-point failure `Wer.TAG` exists to prevent, arriving through the other door.
     */
    private val SPEAKER_TAG = Regex("""\[/?([A-Za-z0-9\- ]+)\]""")

    /**
     * Scores [blocks] against a speaker-tagged [reference].
     *
     * Null rather than zero when the question cannot be asked: a reference with no speaker tags is
     * a plain transcript and says nothing about who spoke, and two transcripts with no word in
     * common give the percentage no denominator. Both are "not measured", and reporting either as
     * 0% would read as a run that got everything wrong.
     */
    fun score(reference: String, blocks: List<DiarizedBlock>, lang: String): Result? {
        // Empty means the reference carried no speaker tags at all: parseReference drops everything
        // before the first one, so an untagged transcript yields nothing rather than one anonymous
        // speaker owning the lot.
        val refWords = parseReference(reference, lang)
        if (refWords.isEmpty()) return null

        val hypWords = blocks
            .sortedBy { it.startSample }
            .flatMap { block ->
                Wer.normalise(block.text, expandNumbers = true, lang = lang)
                    .map { TaggedWord(it, block.speakerName) }
            }
        if (hypWords.isEmpty()) return null

        // The same alignment the word error rate is built on, so the two numbers describe the same
        // pairing of the two transcripts rather than two different guesses at how they line up.
        //
        // Counted straight into the table rather than through a list of one pair per word: the list
        // grew to a Pair per compared word -- hundreds of objects on an ordinary transcript -- and
        // every use of it was a sum this table already holds in at most 64 entries.
        val counts = mutableMapOf<Pair<String, String>, Int>()
        var compared = 0
        for (op in Wer.opcodes(refWords.map { it.word }, hypWords.map { it.word })) {
            if (op.tag != "equal") continue
            for (offset in 0 until (op.i2 - op.i1)) {
                val pair = refWords[op.i1 + offset].speaker to hypWords[op.j1 + offset].speaker
                counts[pair] = (counts[pair] ?: 0) + 1
                compared++
            }
        }
        if (compared == 0) return null

        val mapping = bestMapping(counts)
        val matched = mapping.entries.sumOf { (ref, hyp) -> counts[ref to hyp] ?: 0 }

        return Result(matchedWords = matched, comparedWords = compared, mapping = mapping)
    }

    /**
     * Splits a tagged reference into words, each carrying the speaker whose turn it falls in.
     *
     * Normalised a turn at a time rather than all at once, so a speaker change cannot be read
     * across. `Wer.normalise` replaces tags with a space before expanding numbers, which would let
     * "...twenty [S2] twenty six..." accumulate into one numeral spanning two people. Per turn it
     * cannot, which is both more correct here and the reason this word list is not required to
     * match the one `Wer` counts against -- the two measures are separate passes.
     */
    private fun parseReference(reference: String, lang: String): List<TaggedWord> {
        val text = Wer.DIRECTIVE.replace(reference, " ")
        val out = mutableListOf<TaggedWord>()

        var speaker: String? = null
        var cursor = 0
        for (match in SPEAKER_TAG.findAll(text)) {
            // Words before the first tag belong to nobody nameable and are dropped rather than
            // guessed at: a preamble line above the dialogue is the usual reason for them.
            speaker?.let { current ->
                Wer.normalise(text.substring(cursor, match.range.first), expandNumbers = true, lang = lang)
                    .forEach { out += TaggedWord(it, current) }
            }
            speaker = match.groupValues[1].trim().lowercase()
            cursor = match.range.last + 1
        }
        speaker?.let { current ->
            Wer.normalise(text.substring(cursor), expandNumbers = true, lang = lang)
                .forEach { out += TaggedWord(it, current) }
        }
        return out
    }

    /**
     * The one-to-one assignment of reference speakers to run speakers that matches the most words.
     *
     * Every weight is a word count and therefore never negative, so extending an assignment can
     * only help: the best answer always saturates the smaller side. That is why this enumerates the
     * **smaller** list into the larger and assigns every one of its members, rather than also
     * branching on leaving a speaker unmapped. The skip branch it replaces turned the search from
     * permutations into partial injections -- 1,441,729 nodes at eight speakers against the 40,320
     * this comment used to claim, each one allocating a fresh set and map -- on a path a text-field
     * press can reach. The answer is identical; only the work is not.
     *
     * Exhaustive while the smaller side is small, which it is: the screen caps the expected count
     * at 8. Above that it falls back to taking the biggest agreement first, which is not guaranteed
     * optimal -- but a recording with nine or more distinct voices has problems this number will
     * not be the first to show.
     */
    private fun bestMapping(counts: Map<Pair<String, String>, Int>): Map<String, String> {
        val refSpeakers = counts.keys.map { it.first }.distinct()
        val hypSpeakers = counts.keys.map { it.second }.distinct()
        if (minOf(refSpeakers.size, hypSpeakers.size) > EXHAUSTIVE_LIMIT) {
            return greedyMapping(counts)
        }

        // Enumerate the smaller side; flip the result back at the end if that was the run's.
        val flip = hypSpeakers.size < refSpeakers.size
        val from = if (flip) hypSpeakers else refSpeakers
        val to = if (flip) refSpeakers else hypSpeakers
        fun weight(f: String, t: String) = counts[if (flip) t to f else f to t] ?: 0

        var best = emptyMap<String, String>()
        var bestScore = -1
        // One mutable pair, undone on the way out, rather than a fresh copy per node.
        val chosen = mutableMapOf<String, String>()
        val taken = mutableSetOf<String>()

        fun search(index: Int, score: Int) {
            if (index == from.size) {
                if (score > bestScore) {
                    bestScore = score
                    best = chosen.toMap()
                }
                return
            }
            for (candidate in to) {
                if (candidate in taken) continue
                chosen[from[index]] = candidate
                taken += candidate
                search(index + 1, score + weight(from[index], candidate))
                chosen.remove(from[index])
                taken -= candidate
            }
        }
        search(0, 0)

        return if (flip) best.entries.associate { (hyp, ref) -> ref to hyp } else best
    }

    /**
     * Biggest agreement first, for the case the exhaustive search is too wide for.
     *
     * The keys of [counts] are the only speakers there are -- it is built from the aligned words --
     * so no membership check is needed beyond "neither side already taken".
     */
    private fun greedyMapping(counts: Map<Pair<String, String>, Int>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        val takenHyp = mutableSetOf<String>()
        counts.entries
            .sortedByDescending { it.value }
            .forEach { (pair, _) ->
                val (ref, hyp) = pair
                if (ref !in mapping && hyp !in takenHyp) {
                    mapping[ref] = hyp
                    takenHyp += hyp
                }
            }
        return mapping
    }

    /** Above this the assignment search is factorial in the smaller side; see [bestMapping]. */
    private const val EXHAUSTIVE_LIMIT = 8
}
