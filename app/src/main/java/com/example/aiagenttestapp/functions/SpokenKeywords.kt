package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.normalizeSpokenText

/** A tag the user can open and close by voice while recording. */
enum class MarkerKind { NonConformity, Action }

/** Whether a spotted marker opens its tag or closes it. */
enum class MarkerEdge { Start, End }

/** What a spotted keyword does. */
sealed interface KeywordAction {

    /** Opens or closes a tagged span in the transcript. */
    data class Mark(val kind: MarkerKind, val edge: MarkerEdge) : KeywordAction

    /**
     * Reuses a [VoiceCommands] id rather than defining its own behaviour, so "open settings" does the
     * same thing whether the keyword spotter heard it or the rolling recogniser did. One table
     * decides what a command means.
     */
    data class Command(val id: String) : KeywordAction
}

/**
 * One line for the keyword spotter's keyword list.
 *
 * [tokens] is a BPE token sequence, not text: sherpa-onnx's keyword spotter matches against the
 * model's own modelling units. They were generated ahead of time with the model's `bpe.model` and
 * `sherpa-onnx-cli text2token`, then pasted here, because doing it on device would mean shipping
 * SentencePiece and a 245 KB tokeniser to compute a constant.
 *
 * The risk of hardcoding is that a future model bump silently invalidates them -- unknown tokens do
 * not error, they simply never match, so the feature would quietly stop working. `SpokenKeywordsTest`
 * closes that hole by asserting every token here exists in the shipped vocabulary.
 */
internal data class KeywordEntry(
    /** Returned by the spotter in `KeywordSpotterResult.keyword` via the `@` field. */
    val id: String,
    /** The spoken form, for the chip label and the log. */
    val phrase: String,
    val tokens: String,
    /** Per-keyword detection threshold; null uses the config default. */
    val threshold: Float? = null,
)

/**
 * The phrases the keyword spotter listens for while recording.
 *
 * Two things live here. The **markers** are the point of the feature: saying "start non conformity"
 * opens a tag that the summariser is then told to report as a finding. The **commands** are the
 * existing spoken commands, moved onto the spotter because it is enormously cheaper -- the fallback
 * path re-runs a whole Whisper decode every 1.8 seconds, while this is a 3.3 M-parameter streaming
 * model that consumes audio as it arrives.
 *
 * Several lines can share one id: people do not say the same words twice, and "non-conformity" spoken
 * as one word tokenises differently from "non conformity" spoken as two. Registering both spellings
 * costs one line each and removes a whole class of near-miss.
 *
 * The model is English (gigaspeech). [spokenPhrases] carries the German forms too, for the
 * transcript-scanning fallback used when the spotter has no model for the language being dictated.
 */
object SpokenKeywords {

    const val NC_START = "NC_START"
    const val NC_END = "NC_END"
    const val ACTION_START = "ACTION_START"
    const val ACTION_END = "ACTION_END"

    private val actions: Map<String, KeywordAction> = mapOf(
        NC_START to KeywordAction.Mark(MarkerKind.NonConformity, MarkerEdge.Start),
        NC_END to KeywordAction.Mark(MarkerKind.NonConformity, MarkerEdge.End),
        ACTION_START to KeywordAction.Mark(MarkerKind.Action, MarkerEdge.Start),
        ACTION_END to KeywordAction.Mark(MarkerKind.Action, MarkerEdge.End),
        "CMD_OPEN_SETTINGS" to KeywordAction.Command("open_settings"),
        "CMD_OPEN_MODELS" to KeywordAction.Command("open_models"),
        "CMD_STOP" to KeywordAction.Command("stop_recording"),
        "CMD_DISCARD" to KeywordAction.Command("discard_recording"),
    )

    /**
     * Every keyword, with its pre-computed BPE tokens.
     *
     * `ACTION` on its own gets a stricter threshold than the rest. Unlike "non-conformity", *action*
     * is an ordinary English word, and "END ACTION" is one phoneme from "in action" -- "the pump was
     * in action when we arrived" is a sentence someone doing an inspection might well say. The longer
     * "ACTION ITEM" forms are safe enough for the default and are the phrasing to prefer.
     */
    internal val entries: List<KeywordEntry> = listOf(
        KeywordEntry(NC_START, "start non conformity", "▁START ▁NO N ▁CON FORM ITY"),
        KeywordEntry(NC_START, "start nonconformity", "▁START ▁NO N C ON FORM ITY"),
        KeywordEntry(NC_START, "begin non conformity", "▁BE G IN ▁NO N ▁CON FORM ITY"),
        KeywordEntry(NC_START, "start non conformance", "▁START ▁NO N ▁CON FORM ANCE"),

        KeywordEntry(NC_END, "end non conformity", "▁EN D ▁NO N ▁CON FORM ITY"),
        KeywordEntry(NC_END, "end nonconformity", "▁EN D ▁NO N C ON FORM ITY"),
        KeywordEntry(NC_END, "end non conformance", "▁EN D ▁NO N ▁CON FORM ANCE"),

        KeywordEntry(ACTION_START, "start action item", "▁START ▁A CTION ▁IT E M"),
        KeywordEntry(ACTION_START, "begin action item", "▁BE G IN ▁A CTION ▁IT E M"),
        KeywordEntry(ACTION_START, "start action", "▁START ▁A CTION", threshold = 0.45f),

        KeywordEntry(ACTION_END, "end action item", "▁EN D ▁A CTION ▁IT E M"),
        KeywordEntry(ACTION_END, "end action", "▁EN D ▁A CTION", threshold = 0.45f),

        KeywordEntry("CMD_OPEN_SETTINGS", "open settings", "▁O P EN ▁SE T T ING S"),
        KeywordEntry("CMD_OPEN_MODELS", "open models", "▁O P EN ▁MO D EL S"),
        KeywordEntry("CMD_STOP", "stop recording", "▁ST O P ▁RE C OR D ING"),
        KeywordEntry("CMD_DISCARD", "discard recording", "▁DIS C ARD ▁RE C OR D ING"),
    )

    /**
     * The keyword list in sherpa-onnx's format: `tokens [:boost] [#threshold] @display`.
     *
     * Passed to `KeywordSpotter.createStream(keywords)` rather than written to a file -- the spotter
     * accepts it per stream, so there is no keyword file to keep in sync with this table.
     */
    internal fun keywordsSpec(): String = entries.joinToString("\n") { entry ->
        buildString {
            append(entry.tokens)
            entry.threshold?.let { append(" #").append(it) }
            append(" @").append(entry.id)
        }
    }

    fun actionFor(id: String): KeywordAction? = actions[id]

    /** Human-readable label for the chip shown when a keyword is heard. */
    fun labelFor(id: String): String =
        entries.firstOrNull { it.id == id }?.phrase ?: id

    /**
     * Spoken forms per marker, normalised, for the fallback that scans a finished transcript.
     *
     * Used when the keyword spotter cannot serve the language being dictated -- it is an
     * English-only model, and this app supports German notes. Exact phrase matching only: no fuzzy
     * matching, because without acoustic evidence to lean on, edit distance on ASR output invents
     * markers that were never spoken. It degrades honestly instead -- if the recogniser wrote the
     * words, the marker lands; if it did not, there is no marker.
     *
     * The English forms are here as well as in [entries] so this path still works if the spotter is
     * switched off or its model is absent.
     */
    val spokenPhrases: Map<Pair<MarkerKind, MarkerEdge>, List<String>> = mapOf(
        (MarkerKind.NonConformity to MarkerEdge.Start) to normalizeAll(
            "start non conformity", "start nonconformity", "begin non conformity",
            "start non conformance", "start a non conformity",
            "abweichung beginnen", "beginne abweichung", "start abweichung",
            "nichtkonformität beginnen",
        ),
        (MarkerKind.NonConformity to MarkerEdge.End) to normalizeAll(
            "end non conformity", "end nonconformity", "end non conformance",
            "finish non conformity", "close non conformity",
            "abweichung beenden", "ende abweichung", "abweichung ende",
            "nichtkonformität beenden",
        ),
        (MarkerKind.Action to MarkerEdge.Start) to normalizeAll(
            "start action item", "begin action item", "start action", "begin action",
            "maßnahme beginnen", "beginne maßnahme", "start maßnahme",
        ),
        (MarkerKind.Action to MarkerEdge.End) to normalizeAll(
            "end action item", "end action", "finish action item", "close action item",
            "maßnahme beenden", "ende maßnahme", "maßnahme ende",
        ),
    )

    /**
     * Longest phrases first.
     *
     * The scanner takes the first phrase that matches at a position, so "start action item" has to be
     * tried before "start action" -- otherwise the shorter one wins and the word "item" is left
     * stranded at the head of the tagged text.
     */
    private fun normalizeAll(vararg phrases: String): List<String> = phrases
        .map(::normalizeSpokenText)
        .filter { it.isNotBlank() }
        .distinct()
        .sortedByDescending { it.length }
}
