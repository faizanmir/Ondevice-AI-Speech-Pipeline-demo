package com.example.aiagenttestapp.data.speakers

import android.util.Log
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeakerEmbedder
import com.example.aiagenttestapp.stt.averageEmbedding
import com.example.aiagenttestapp.stt.cosineSimilarity
import com.example.aiagenttestapp.stt.matchSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why an enrolment take cannot be used. */
enum class TakeProblem {
    /** Barely any speech in it -- eight seconds of room tone is not a voiceprint. */
    TooLittleSpeech,

    /** More than one voice. Someone talked over the take, or a recording was playing nearby. */
    MultipleSpeakers,

    /** The model would not produce a vector: too short, too quiet, or too noisy. */
    NoVoiceprint,
}

/** What one enrolment recording turned out to be. */
class TakeAnalysis(
    val embedding: FloatArray?,
    val speechSeconds: Float,
    val speakerCount: Int,
    val durationMillis: Long,
) {
    val problem: TakeProblem?
        get() = when {
            embedding == null -> TakeProblem.NoVoiceprint
            speakerCount > 1 -> TakeProblem.MultipleSpeakers
            speechSeconds < MIN_SPEECH_SECONDS -> TakeProblem.TooLittleSpeech
            else -> null
        }

    val isUsable: Boolean get() = problem == null

    companion object {
        /**
         * Four seconds of *speech*, not of recording.
         *
         * A voiceprint from one or two words is dominated by whichever phonemes happened to be in them,
         * and matches badly against anything else the person says. Measuring speech rather than
         * wall-clock is what stops a take that is mostly silence from passing.
         */
        const val MIN_SPEECH_SECONDS = 4f
    }
}

sealed interface EnrollResult {
    data class Success(val id: Long) : EnrollResult

    /** The takes do not sound like the same person -- noise, or a second voice. */
    data class TakesDisagree(val similarity: Float) : EnrollResult

    /** This voice already belongs to somebody. Overridable: the user knows better than a threshold. */
    data class SoundsLike(val name: String, val similarity: Float) : EnrollResult

    data class NameTaken(val name: String) : EnrollResult
    data class Failed(val message: String) : EnrollResult
}

/**
 * Enrolled voices: storing them, matching against them, and deciding whether a new enrolment is sound.
 *
 * Room is the record and sherpa's `SpeakerEmbeddingManager` is a search index rebuilt from it -- the
 * manager has no persistence, so treating it as anything but a cache would lose every enrolment on
 * process death.
 *
 * **No enrolment audio is ever stored.** The recordings are turned into embeddings and discarded. An
 * embedding cannot be played back or reversed into speech, which makes it the least this app can hold
 * and still recognise anybody, and a voiceprint is biometric data that deserves that restraint.
 */
class SpeakerRepository(
    private val dao: SpeakerDao,
    private val audioModels: AudioModelRepository,
) {

    private val embedder = SpeakerEmbedder()

    /** Guards the embedder and its index: enrolment and a background transcription can collide. */
    private val lock = Mutex()

    /** Outlives a screen so its non-suspending lifecycle callback can queue a safe release. */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once the index reflects what is in Room. */
    private var indexLoaded = false

    /** The same enrolled takes as the native index, retained so naming can inspect score margins. */
    private var enrolledEmbeddings: Map<String, List<FloatArray>> = emptyMap()

    fun observeSpeakers() = dao.observeAll()

    /** Whether the models this needs are on disk. */
    fun isAvailable(): Boolean = audioModels.isReady(audioModels.speaker)

    /**
     * Loads the embedding model and fills the search index from Room.
     *
     * Samples written by a *different* embedding model are skipped, not silently loaded. Vectors from
     * another model are not detectably wrong -- they simply match nobody -- so without this check
     * swapping the model would look like the app forgetting everyone    it knew.
     */
    suspend fun prepare(): Boolean = lock.withLock { prepareLocked() }

    private suspend fun prepareLocked(): Boolean = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext false

        if (!embedder.isLoaded) {
            val loaded = runCatching {
                embedder.load(audioModels.fileFor(audioModels.speaker, AudioModelCatalog.EMBEDDING))
            }.onFailure { Log.w(TAG, "could not load the speaker embedder", it) }.isSuccess

            if (!loaded) return@withContext false
            indexLoaded = false
        }

        if (!indexLoaded) {
            val speakers = dao.all().filter { it.embeddingModelId == AudioModelCatalog.EMBEDDING_MODEL_ID }
            val enrolled = speakers.associate { speaker ->
                speaker.name to dao.samplesFor(speaker.id).map { it.embedding }
            }
            embedder.setEnrolled(enrolled)
            enrolledEmbeddings = enrolled
            indexLoaded = true
            Log.i(TAG, "speaker index loaded with ${enrolled.size} people")
        }

        true
    }

    /** Speakers whose voiceprints came from a model no longer in use, so re-enrolment can be offered. */
    suspend fun staleSpeakers(): List<SpeakerRecord> =
        dao.all().filter { it.embeddingModelId != AudioModelCatalog.EMBEDDING_MODEL_ID }

    /**
     * Examines one enrolment recording: how much speech it holds, how many voices, and its voiceprint.
     *
     * The pyannote model already downloaded for diarisation does double duty here. It reports speech
     * regions *and* how many speakers it heard, which is exactly the two questions a quality gate needs,
     * so no separate voice-activity model has to be shipped for enrolment.
     */
    suspend fun analyseTake(samples: FloatArray): TakeAnalysis = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!prepareLocked()) {
                return@withContext TakeAnalysis(null, 0f, 0, 0)
            }

            val durationMillis = samples.size * 1000L / AudioRecorder.SAMPLE_RATE
            val diarizer = SpeakerDiarizer()

            val segments = try {
                diarizer.load(
                    segmentationModel = audioModels.fileFor(
                        audioModels.speaker,
                        AudioModelCatalog.SEGMENTATION,
                    ),
                    embeddingModel = audioModels.fileFor(
                        audioModels.speaker,
                        AudioModelCatalog.EMBEDDING,
                    ),
                )
                diarizer.diarize(samples)
            } catch (e: Exception) {
                Log.w(TAG, "could not check the enrolment take", e)
                emptyList()
            } finally {
                diarizer.release()
            }

            val speechSamples = segments.sumOf { (it.endSample - it.startSample).toLong() }
            val speechSeconds = speechSamples.toFloat() / AudioRecorder.SAMPLE_RATE
            val speakerCount = segments.map { it.cluster }.distinct().size

            // The voiceprint comes from the speech regions only. Including the silence would drag the
            // vector toward whatever the room sounds like rather than the person.
            val speechOnly = if (segments.isEmpty()) {
                samples
            } else {
                concatenate(samples, segments.map { it.startSample until it.endSample })
            }

            TakeAnalysis(
                embedding = embedder.embed(speechOnly),
                speechSeconds = speechSeconds,
                speakerCount = speakerCount,
                durationMillis = durationMillis,
            )
        }
    }

    /**
     * Enrols a person from several analysed takes.
     *
     * Two checks before anything is written, both of which exist because a bad voiceprint is worse than
     * no voiceprint -- it produces confident wrong attributions rather than honest "Speaker 2" labels:
     *
     *  - **Do the takes agree with each other?** If they do not, something was wrong with the recording,
     *    and averaging them would bake that in.
     *  - **Does this voice already belong to somebody?** Enrolling one person twice makes both entries
     *    unreliable. Overridable via [allowCollision], because similar-sounding people exist and the user
     *    knows which is which.
     */
    suspend fun enroll(
        name: String,
        takes: List<TakeAnalysis>,
        allowCollision: Boolean = false,
    ): EnrollResult = lock.withLock {
        withContext(Dispatchers.Default) {
            val cleanName = name.trim()
            if (cleanName.isBlank()) return@withContext EnrollResult.Failed("Give this person a name.")

            // A name that parses as an unrecognised speaker would make transcripts ambiguous.
            if (cleanName.matches(Regex("(?i)$UNKNOWN_SPEAKER_PREFIX \\d+"))) {
                return@withContext EnrollResult.Failed(
                    "\"$cleanName\" is reserved for speakers the app could not identify.",
                )
            }

            if (!prepareLocked()) {
                return@withContext EnrollResult.Failed("The speaker models are not ready yet.")
            }

            if (dao.byName(cleanName) != null) return@withContext EnrollResult.NameTaken(cleanName)

            val embeddings = takes.mapNotNull { it.embedding.takeIf { _ -> it.isUsable } }
            if (embeddings.size < MIN_TAKES) {
                return@withContext EnrollResult.Failed(
                    "Record at least $MIN_TAKES usable takes.",
                )
            }

            val agreement = lowestPairwiseSimilarity(embeddings)
            // Logged because the gate is otherwise a yes/no with no way to tell "these really are
            // two people" from "the threshold is wrong". It rejected three takes of one synthetic
            // voice, recorded seconds apart through the same microphone, and without the number
            // there was no way to say which of those two it was.
            Log.i(TAG, "enrolment agreement %.3f from %d takes (threshold %.2f), dims=%s"
                .format(agreement, embeddings.size, TAKE_AGREEMENT_THRESHOLD,
                    embeddings.joinToString(",") { it.size.toString() }))
            if (agreement < TAKE_AGREEMENT_THRESHOLD) {
                return@withContext EnrollResult.TakesDisagree(agreement)
            }

            val mean = averageEmbedding(embeddings)
                ?: return@withContext EnrollResult.Failed("Could not build a voiceprint.")

            if (!allowCollision) {
                embedder.search(mean, COLLISION_THRESHOLD)?.let { existing ->
                    return@withContext EnrollResult.SoundsLike(existing, COLLISION_THRESHOLD)
                }
            }

            val id = dao.insert(
                SpeakerRecord(
                    name = cleanName,
                    createdAtMillis = System.currentTimeMillis(),
                    embeddingModelId = AudioModelCatalog.EMBEDDING_MODEL_ID,
                    dim = mean.size,
                ),
            )

            // Every take stored separately rather than only the average: the sherpa manager compares
            // against all of a person's vectors, which tolerates a voice varying between takes better
            // than a single averaged one.
            dao.insertSamples(
                takes.filter { it.isUsable && it.embedding != null }.map { take ->
                    SpeakerSample(
                        speakerId = id,
                        embedding = take.embedding!!,
                        durationMillis = take.durationMillis,
                        createdAtMillis = System.currentTimeMillis(),
                    )
                },
            )

            indexLoaded = false
            prepareLocked()

            EnrollResult.Success(id)
        }
    }

    /**
     * Removes a speaker and their voiceprints.
     *
     * Notes already recorded keep the name in their transcript text. Deleting someone means "stop
     * recognising this voice", not "rewrite the record of what was said" -- a transcript is evidence, and
     * silently editing old ones would be the wrong kind of tidy.
     */
    suspend fun delete(id: Long) = lock.withLock {
        dao.delete(id) // samples cascade
        indexLoaded = false
        prepareLocked()
        Unit
    }

    /**
     * Puts a name to each diarisation cluster, or a "Speaker N" placeholder.
     *
     * Each cluster is embedded from its own longest turns -- up to [LABEL_SAMPLE_SECONDS] of them --
     * rather than from one arbitrary turn. The best enrolled match must clear both [MATCH_THRESHOLD]
     * and [MATCH_MARGIN] over the runner-up. The native search API exposes only the winning name, so
     * it cannot distinguish a clear result from a near tie; putting a name on the latter produced
     * confident but false attribution.
     */
    suspend fun labelClusters(
        samples: FloatArray,
        turns: List<DiarizedSegment>,
    ): Map<Int, String> = lock.withLock {
        withContext(Dispatchers.Default) {
            if (turns.isEmpty()) return@withContext emptyMap()

            val ready = prepareLocked()
            val labels = mutableMapOf<Int, String>()

            // Numbered by first appearance, so "Speaker 2" is the second person heard rather than
            // whatever index the clustering happened to assign.
            val order = turns.sortedBy { it.startSample }.map { it.cluster }.distinct()

            var placeholder = 0
            for (cluster in order) {
                val decision = if (!ready) {
                    null
                } else {
                    val audio = concatenate(
                        samples,
                        turns.filter { it.cluster == cluster }
                            .sortedByDescending { it.endSample - it.startSample }
                            .fold(mutableListOf<IntRange>()) { taken, turn ->
                                val total = taken.sumOf { it.count() }
                                if (total < LABEL_SAMPLE_SECONDS * AudioRecorder.SAMPLE_RATE) {
                                    taken += turn.startSample until turn.endSample
                                }
                                taken
                            }
                            .sortedBy { it.first },
                    )
                    embedder.embed(audio)?.let { embedding ->
                        matchSpeaker(
                            embedding = embedding,
                            enrolled = enrolledEmbeddings,
                            threshold = MATCH_THRESHOLD,
                            minimumMargin = MATCH_MARGIN,
                        )
                    }
                }

                decision?.let {
                    Log.i(
                        TAG,
                        "cluster %d match: best=%s %.3f, runner-up=%s %s, accepted=%s".format(
                            cluster,
                            it.bestName ?: "none",
                            it.bestScore,
                            it.runnerUpName ?: "none",
                            it.runnerUpScore?.let { score -> "%.3f".format(score) } ?: "none",
                            it.acceptedName ?: "unknown",
                        ),
                    )
                }
                labels[cluster] = decision?.acceptedName
                    ?: "$UNKNOWN_SPEAKER_PREFIX ${++placeholder}"
            }

            labels
        }
    }

    /**
     * Folds clusters too small to be a speaker into the one they sound most like.
     *
     * Runs before anything downstream sees the turns, because every later stage inherits the cluster
     * ids: alignment cuts blocks on them, naming puts one name on each, and a 0.8-second fragment
     * that survives this step is reported to the user as another person who spoke. See
     * [smallClusterRemap] for why sherpa leaves this out and pyannote does not.
     *
     * Needs no enrolled voices -- it compares clusters against each other, not against people -- so
     * it improves a recording of complete strangers just as much as one of Bob and Tim.
     *
     * A cluster's voiceprint comes from its longest turns, up to [LABEL_SAMPLE_SECONDS] of them, for
     * the same reason [labelClusters] does it that way: an average over a cluster's best evidence is
     * steadier than whichever turn happened to come first.
     */
    suspend fun mergeSmallClusters(
        samples: FloatArray,
        turns: List<DiarizedSegment>,
        minClusterSeconds: Float = MIN_CLUSTER_SECONDS,
    ): List<DiarizedSegment> = lock.withLock {
        withContext(Dispatchers.Default) {
            if (turns.isEmpty()) return@withContext turns
            if (!prepareLocked()) return@withContext turns

            val sizes = clusterSizes(turns)
            val minSamples = (minClusterSeconds * AudioRecorder.SAMPLE_RATE).toInt()
            // Nothing is small, so nothing has to be embedded. Worth the check: this runs on every
            // recording and the embedding is the expensive half.
            if (sizes.none { it.value < minSamples }) return@withContext turns

            val centroids = sizes.keys.mapNotNull { cluster ->
                val audio = concatenate(samples, longestTurnRanges(turns, cluster))
                embedder.embed(audio)?.let { cluster to it }
            }.toMap()

            val remap = smallClusterRemap(sizes, centroids, minSamples)
            if (remap.isEmpty()) return@withContext turns

            remap.forEach { (from, to) ->
                Log.i(
                    TAG,
                    "cluster %d (%.1fs) folded into cluster %d (%.1fs) -- too small to be a speaker".format(
                        from,
                        sizes.getValue(from) / AudioRecorder.SAMPLE_RATE.toFloat(),
                        to,
                        sizes.getValue(to) / AudioRecorder.SAMPLE_RATE.toFloat(),
                    ),
                )
            }
            applyClusterRemap(turns, remap)
        }
    }

    /** A cluster's longest turns, capped at [LABEL_SAMPLE_SECONDS], in the order they were spoken. */
    private fun longestTurnRanges(turns: List<DiarizedSegment>, cluster: Int): List<IntRange> =
        turns.filter { it.cluster == cluster }
            .sortedByDescending { it.endSample - it.startSample }
            .fold(mutableListOf<IntRange>()) { taken, turn ->
                if (taken.sumOf { it.count() } < LABEL_SAMPLE_SECONDS * AudioRecorder.SAMPLE_RATE) {
                    taken += turn.startSample until turn.endSample
                }
                taken
            }
            .sortedBy { it.first }

    /**
     * Releases native memory after every operation already holding [lock] has finished.
     *
     * This repository is a singleton shared by enrolment and background diarisation. Releasing
     * without the same mutex used by those operations can free the extractor under a native compute,
     * which aborts the process rather than producing a Kotlin exception.
     */
    suspend fun release() = lock.withLock {
        withContext(Dispatchers.Default) {
            embedder.release()
            enrolledEmbeddings = emptyMap()
            indexLoaded = false
        }
    }

    /** Queues [release] for lifecycle callbacks that cannot suspend, while preserving serialization. */
    fun releaseAsync() {
        releaseScope.launch { release() }
    }

    /** Copies several ranges of [samples] into one contiguous array. */
    private fun concatenate(samples: FloatArray, ranges: List<IntRange>): FloatArray {
        val clamped = ranges
            .map { it.first.coerceIn(0, samples.size) until (it.last + 1).coerceIn(0, samples.size) }
            .filter { it.last >= it.first }

        val total = clamped.sumOf { it.count() }
        if (total <= 0) return FloatArray(0)

        val out = FloatArray(total)
        var at = 0
        clamped.forEach { range ->
            val length = range.count()
            System.arraycopy(samples, range.first, out, at, length)
            at += length
        }
        return out
    }

    /** The least similar pair among the takes -- the weakest link decides whether they agree. */
    private fun lowestPairwiseSimilarity(embeddings: List<FloatArray>): Float {
        if (embeddings.size < 2) return 1f

        var lowest = 1f
        for (i in embeddings.indices) {
            for (j in i + 1 until embeddings.size) {
                lowest = minOf(lowest, cosineSimilarity(embeddings[i], embeddings[j]))
            }
        }
        return lowest
    }

    companion object {
        private const val TAG = "SpeakerRepository"

        /**
         * Prefix for a speaker diarisation found but could not put a name to.
         *
         * "Unknown Speaker 1", not "Speaker 1", and still numbered. Saying only "Unknown Speaker"
         * would read better and destroy the thing diarisation just worked out: with three
         * unrecognised people in the room, one repeated label makes the transcript claim they are
         * the same person. The number says "a distinct voice the app cannot name", which is exactly
         * what was found.
         *
         * Lives here rather than in the notes transcript markup it was borrowed from: this feature no
         * longer writes into a note, and a label this code both *produces* and *refuses as a name*
         * cannot be owned by a module that has no other reason to know about speakers.
         */
        const val UNKNOWN_SPEAKER_PREFIX = "Unknown Speaker"

        /** Three takes: enough for a disagreement to be visible, few enough that people finish. */
        const val REQUIRED_TAKES = 3

        private const val MIN_TAKES = 2

        /**
         * How alike a person's own takes must be.
         *
         * Deliberately loose. The same voice across three readings of different sentences is not
         * especially self-similar, and a strict bar here rejects honest enrolments -- which teaches the
         * user the feature is broken. It only has to catch takes that are *obviously* not one person.
         */
        const val TAKE_AGREEMENT_THRESHOLD = 0.5f

        /** Above this, a new enrolment is probably somebody already on file. Warn, do not refuse. */
        const val COLLISION_THRESHOLD = 0.6f

        /**
         * How sure the app must be before it puts a name on a transcript.
         *
         * Biased high on purpose: "Speaker 2" is a mild inconvenience, while attributing words to a
         * named person who did not say them is a defect in a record someone may act on.
         */
        /**
         * Below this, a cluster is debris rather than a person.
         *
         * pyannote states the same idea as a count of embeddings (`min_cluster_size: 12`); seconds
         * are the honest unit here because this app only sees merged turns, not the per-window
         * embeddings that count refers to. Measured on a two-voice recording the real speakers held
         * 14-71 s each while the debris held 0.8-5.7 s, so the boundary is not delicate.
         *
         * The cost of it being wrong is bounded and one-directional: a genuine third speaker who
         * says less than this is absorbed into whoever they sound most like, rather than being
         * reported. That is the same trade pyannote makes, and the escape hatch is the same one it
         * offers -- tell the pipeline how many speakers to expect.
         */
        const val MIN_CLUSTER_SECONDS = 6f

        const val MATCH_THRESHOLD = 0.6f

        /** Required lead over the second-best enrolled voice before a name is safe to print. */
        const val MATCH_MARGIN = 0.05f

        /** Audio per cluster used to identify it. Beyond this, more adds accuracy too slowly to pay for. */
        private const val LABEL_SAMPLE_SECONDS = 30
    }
}
