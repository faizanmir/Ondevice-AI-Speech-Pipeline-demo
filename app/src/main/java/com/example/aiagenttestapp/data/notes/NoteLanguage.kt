package com.example.aiagenttestapp.data.notes

/**
 * One slice's opinion about what language it heard, and how much audio that opinion is based on.
 *
 * [samples] is the weight rather than a detail: a twenty-second slice and a one-second slice are not
 * equally good evidence, and a recogniser asked to name a language from a stray syllable will still
 * name one.
 */
data class LanguageVote(val code: String?, val samples: Int)

/**
 * Decides a note's language from its slices' guesses.
 *
 * Replaces first-non-null-wins, which was not a tie-break rule but an accident: the whole note
 * inherited whichever language the *first* slice happened to name, and the first slice is the worst
 * possible witness -- it is the one most likely to be lead-in silence, a half-caught word, or the
 * speaker clearing their throat.
 *
 * It was invisible on the ONNX backends, where every slice of an English recording says `en` and so
 * the first slice is right by luck. Android's platform recogniser detects per utterance and disagrees
 * with itself constantly: three runs of the *same English audio* were stored as `en`, then `id`
 * (Indonesian), then `ms` (Malay). That is not cosmetic -- `NotePrompts.languageDirective` turns the
 * stored value into "Write your answer in Malay", so a wrong first slice mistranslates the summary of
 * a recording whose every other slice was correctly identified.
 *
 * Plurality by duration, with two deliberate choices:
 *
 *  - **Short slices do not vote.** Below [MIN_VOTING_SAMPLES] there is not enough audio to identify a
 *    language, and letting those in is how a single syllable outvoted twenty minutes of speech.
 *  - **No confidence threshold.** A plurality across dozens of slices is the best evidence available,
 *    and the alternative is null, which throws away a usable answer to avoid an unlikely one. Null is
 *    reserved for "nothing voted", which is honest and which the directive already handles.
 */
fun dominantLanguage(votes: List<LanguageVote>): String? {
    val counted = votes
        .filter { it.code != null && it.samples >= MIN_VOTING_SAMPLES }
        .groupBy { it.code!! }
        .mapValues { (_, group) -> group.sumOf { it.samples.toLong() } }

    // Every slice was too short to trust. Fall back to the unweighted opinion rather than to nothing:
    // a two-second note is still a note, and its only slice is all the evidence that exists.
    if (counted.isEmpty()) {
        return votes.firstOrNull { it.code != null }?.code
    }

    return counted.maxByOrNull { it.value }?.key
}

/** Four seconds. Shorter than this is a fragment, and a fragment's language guess is a coin toss. */
internal const val MIN_VOTING_SAMPLES = 4 * 16_000
