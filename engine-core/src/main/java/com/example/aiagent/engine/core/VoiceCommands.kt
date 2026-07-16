package com.example.aiagent.engine.core

/** One voice command and the phrases that trigger it. */
data class VoiceCommandSpec(
    val id: String,
    /** Spoken forms that fire it. First one is the canonical label. */
    val phrases: List<String>,
)

/** A command the user spoke, and the exact phrase that matched. */
data class VoiceCommandMatch(
    val id: String,
    /** The normalised phrase that fired, so the caller can strip it from the note. */
    val matchedPhrase: String,
)

/**
 * Recognises spoken commands inside a rolling speech-to-text transcript.
 *
 * The hard part of "act on a command while recording a note" is not hearing the words -- the ASR
 * does that -- it is deciding that a run of words was meant as a command rather than as note
 * content, and doing so *without* firing five times as the same phrase slides through successive
 * transcription windows. That second problem is what this class exists for.
 *
 * Two deliberate choices:
 *
 *  - **Phrase matching, not an LLM.** Detection runs every couple of seconds for the whole length
 *    of a recording, so it has to be instant and free. A fixed phrase table is both; a language
 *    model would be neither, and would drain the battery for a job a substring match does perfectly.
 *  - **Per-command cooldown.** The recogniser sees overlapping windows, so a phrase the user said
 *    once appears in several consecutive transcriptions. Without the cooldown, "open settings"
 *    would fire on every window it lingers in. [cooldownMs] collapses that into a single event.
 *
 * Pure and clock-injected ([match] takes `nowMs`) so the firing logic can be tested without a
 * device or a real timer.
 */
class VoiceCommandMatcher(
    private val commands: List<VoiceCommandSpec>,
    private val cooldownMs: Long = 4_000L,
) {

    private val lastFiredMs = mutableMapOf<String, Long>()

    /**
     * Returns the command in [rawText], or null if there is none or the same command fired within
     * the cooldown. When several match, the longest phrase wins -- "open model catalog" should beat
     * a bare "open" that some other command might use.
     */
    fun match(rawText: String, nowMs: Long): VoiceCommandMatch? {
        val text = normalize(rawText)
        if (text.isBlank()) return null

        var best: VoiceCommandMatch? = null
        for (command in commands) {
            for (phrase in command.phrases) {
                val normalisedPhrase = normalize(phrase)
                if (normalisedPhrase.isBlank()) continue
                if (!containsPhrase(text, normalisedPhrase)) continue

                if (best == null || normalisedPhrase.length > best.matchedPhrase.length) {
                    best = VoiceCommandMatch(command.id, normalisedPhrase)
                }
            }
        }

        val match = best ?: return null

        val previous = lastFiredMs[match.id]
        if (previous != null && nowMs - previous < cooldownMs) return null

        lastFiredMs[match.id] = nowMs
        return match
    }

    /** Forget every cooldown. Call when a new recording starts. */
    fun reset() = lastFiredMs.clear()

    private companion object {
        /** Lowercase, drop everything but letters/digits/spaces, collapse runs of space.
         *  Stripping punctuation is what lets "Open settings." match the phrase "open settings",
         *  and dropping non-ASCII means a window the recogniser filled with CJK noise simply
         *  fails to match rather than firing something at random. */
        fun normalize(text: String): String = text
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Whole-phrase containment: the space-padding stops "open" matching inside "reopened". */
        fun containsPhrase(text: String, phrase: String): Boolean =
            " $text ".contains(" $phrase ")
    }
}

/**
 * Removes spoken command phrases from a transcript before it is saved.
 *
 * The user said "...email Sam. Open settings." to fire a command; the note should read "...email
 * Sam." The command already did its job -- leaving its trigger words in the saved text is just
 * noise. The trailing punctuation is taken with the phrase so a stray full stop is not left behind.
 */
fun stripCommandPhrases(transcript: String, phrases: Collection<String>): String {
    if (phrases.isEmpty()) return transcript

    var result = transcript
    for (phrase in phrases) {
        if (phrase.isBlank()) continue
        // Optional leading space and trailing punctuation are consumed with the phrase, so removing
        // it does not leave "Sam.  ." behind.
        val pattern = Regex("(?i)\\s*\\b" + Regex.escape(phrase) + "\\b[.,!?;:]*")
        result = result.replace(pattern, "")
    }

    return result
        .replace(Regex("\\s+([.,!?;:])"), "$1") // no space before punctuation we left behind
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}
