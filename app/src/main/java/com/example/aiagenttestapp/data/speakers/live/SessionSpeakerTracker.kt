package com.example.aiagenttestapp.data.speakers.live

import com.example.aiagenttestapp.stt.cosineSimilarity

/** What one chunk's diarisation said about one of its clusters, after folding. */
data class ClusterObservation(
    val cluster: Int,
    val samples: Int,
    /** The cluster's voiceprint, or null when the embedder could not describe it. */
    val voiceprint: FloatArray?,
    /** The enrolled person naming accepted for this cluster, if any. */
    val enrolledName: String?,
)

/**
 * One voice heard so far in a live session.
 *
 * [voiceprint] is the duration-weighted mean of every observation bound to this speaker, so it drifts
 * towards how the person sounds *in this recording* rather than staying pinned to one chunk.
 */
class SessionSpeaker internal constructor(
    val id: Int,
    voiceprint: FloatArray,
    samples: Int,
    enrolledName: String?,
) {
    var voiceprint: FloatArray = voiceprint
        private set
    var samples: Int = samples
        private set
    var enrolledName: String? = enrolledName
        private set

    /** The enrolled name once one has matched, otherwise a session letter. */
    val label: String get() = enrolledName ?: "Speaker ${letter(id)}"

    internal fun absorb(observation: ClusterObservation) {
        observation.voiceprint?.let { print ->
            val total = (samples + observation.samples).coerceAtLeast(1).toFloat()
            val merged = FloatArray(voiceprint.size) { i ->
                (voiceprint[i] * samples + print[i] * observation.samples) / total
            }
            voiceprint = normalise(merged)
        }
        samples += observation.samples
        observation.enrolledName?.let { enrolledName = it }
    }

    private companion object {
        fun letter(id: Int): String {
            var n = id
            val out = StringBuilder()
            do {
                out.insert(0, 'A' + n % 26)
                n = n / 26 - 1
            } while (n >= 0)
            return out.toString()
        }

        fun normalise(v: FloatArray): FloatArray {
            var norm = 0.0
            for (x in v) norm += (x * x).toDouble()
            val scale = if (norm > 0) (1.0 / Math.sqrt(norm)).toFloat() else 1f
            return FloatArray(v.size) { v[it] * scale }
        }
    }
}

/**
 * Carries speaker identity across the chunks of a live session.
 *
 * The batch pipeline joins its chunks by **name**: every chunk is matched to the enrolled voiceprints
 * independently, and two clusters that both came back "Bob" are one person. That needs enrolment,
 * and it needs the whole recording to have been diarised before anything is shown. Live has neither:
 * chunks arrive one at a time and most rooms hold someone who never enrolled. So this keeps its own
 * roster -- one [SessionSpeaker] per distinct voice heard so far, each with a running voiceprint --
 * and every new chunk's clusters are matched against that roster, not only against enrolment.
 *
 * The rules, in the order they are applied to a chunk:
 *
 *  1. A cluster naming accepted (it cleared the enrolment threshold and margin) binds to the session
 *     speaker already carrying that name; failing that, to the closest unnamed session speaker, which
 *     is thereby **named** -- so "Speaker A" from chunk 1 becomes "Bob" the moment chunk 3 recognises
 *     him, and every earlier block follows, because labels are resolved at write time; failing that,
 *     a new speaker is opened under the name.
 *  2. The remaining clusters, largest first, bind to the most similar session speaker if the
 *     similarity clears [threshold] and beats the runner-up by [margin] -- the same shape as enrolled
 *     naming, with a lower bar because both sides were recorded on the same microphone in the same
 *     room. Otherwise a new lettered speaker is opened.
 *  3. Within one chunk, two clusters never bind to the same *unnamed* speaker: the diariser has
 *     already said they are different voices, and that verdict is better evidence than a close cosine.
 *     Two clusters that both matched the same enrolled name are allowed to -- that is
 *     over-segmentation of one person, and naming is how the batch path heals it too.
 *
 * Labels are provisional by construction and the batch pass replaces them; this exists so the
 * provisional view is *consistent* -- the same voice keeps the same letter -- not so it is final.
 */
class SessionSpeakerTracker(
    private val threshold: Float = SESSION_MATCH_THRESHOLD,
    private val margin: Float = SESSION_MATCH_MARGIN,
) {
    private val roster = mutableListOf<SessionSpeaker>()

    val speakers: List<SessionSpeaker> get() = roster

    /**
     * Binds this chunk's clusters to session speakers, opening new ones as needed.
     *
     * @return cluster id to session speaker id. A cluster with neither a voiceprint nor an accepted
     *   name is absent -- there is nothing to match it with -- and the caller treats it as unattributed.
     */
    fun assign(observations: List<ClusterObservation>): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        val claimed = HashSet<Int>()

        val named = observations.filter { it.enrolledName != null }.sortedByDescending { it.samples }
        for (o in named) {
            val name = o.enrolledName!!
            val speaker = roster.firstOrNull { it.enrolledName == name }
                ?: closestUnnamed(o.voiceprint, claimed)
                ?: open(o)
            speaker.absorb(o)
            result[o.cluster] = speaker.id
            claimed += speaker.id
        }

        val unnamed = observations
            .filter { it.enrolledName == null && it.voiceprint != null }
            .sortedByDescending { it.samples }
        for (o in unnamed) {
            val ranked = roster
                .filter { it.id !in claimed }
                .map { it to cosineSimilarity(o.voiceprint!!, it.voiceprint) }
                .sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)
            val accepted = best != null &&
                best.second >= threshold &&
                (runnerUp == null || best.second - runnerUp.second >= margin)
            val speaker = if (accepted) best!!.first else open(o)
            speaker.absorb(o)
            result[o.cluster] = speaker.id
            claimed += speaker.id
        }
        return result
    }

    /** Current label for every session speaker, by id. */
    fun labels(): Map<Int, String> = roster.associate { it.id to it.label }

    private fun closestUnnamed(voiceprint: FloatArray?, claimed: Set<Int>): SessionSpeaker? {
        if (voiceprint == null) return null
        return roster
            .filter { it.enrolledName == null && it.id !in claimed }
            .map { it to cosineSimilarity(voiceprint, it.voiceprint) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun open(o: ClusterObservation): SessionSpeaker {
        val print = o.voiceprint ?: FloatArray(0)
        val speaker = SessionSpeaker(roster.size, print, 0, o.enrolledName)
        roster += speaker
        return speaker
    }

    companion object {
        /**
         * Lower than enrolment's 0.6 on purpose: an enrolled voiceprint was recorded in another room
         * on another day, a session voiceprint minutes ago on this microphone, so the same person
         * scores higher here and a stranger does not.
         */
        const val SESSION_MATCH_THRESHOLD = 0.55f
        const val SESSION_MATCH_MARGIN = 0.05f
    }
}
