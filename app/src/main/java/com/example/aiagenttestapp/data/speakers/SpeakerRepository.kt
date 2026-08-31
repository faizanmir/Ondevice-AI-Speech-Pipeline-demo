package com.example.aiagenttestapp.data.speakers

import android.util.Log
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.DiarizedSegment
import com.example.aiagenttestapp.stt.SpeakerMatchDecision
import com.example.aiagenttestapp.stt.matchSpeaker
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeakerEmbedder
import com.example.aiagenttestapp.stt.averageEmbedding
import com.example.aiagenttestapp.stt.cosineSimilarity
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
    private val settings: SettingsStore,
) {

    private val embedder = SpeakerEmbedder()

    /**
     * The ONNX execution provider the speaker models run on, read per load rather than held.
     *
     * The same value the recogniser and [SpeakerDiarizer] use, so a run's whole sherpa-onnx side is
     * one configuration. The embedder now stays resident between runs -- the diarisation worker no
     * longer releases it -- so [prepareLocked] compares this against [loadedProvider] and reloads on
     * a change. Freshness used to rest on the per-run release; without this check, a provider
     * changed in Settings would silently keep running on the old one until process death.
     */
    private fun provider(): String = settings.settings.value.onnxProvider.slug

    /** Guards the embedder and its index: enrolment and a background transcription can collide. */
    private val lock = Mutex()

    /** Outlives a screen so its non-suspending lifecycle callback can queue a safe release. */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once the index reflects what is in Room. */
    private var indexLoaded = false

    /** The same enrolled takes as the native index, retained so naming can inspect score margins. */
    private var enrolledEmbeddings: Map<String, List<FloatArray>> = emptyMap()

    /**
     * Which bundle the resident embedder came from, so switching models actually switches models.
     *
     * Without it the embedder is loaded once and kept, and picking CAM++ in Settings would go on
     * comparing voices with ERes2Net until the process restarted -- silently, because a wrong-model
     * embedding does not look wrong, it just matches nobody.
     */
    private var loadedBundleId: String? = null

    /** The provider the resident embedder was built on; a Settings change must force a reload. */
    private var loadedProvider: String? = null

    /**
     * The id stamped on voiceprints right now, from whichever embedding bundle is selected.
     *
     * Read per call rather than held, because the selection can change between a run and the next
     * enrolment, and a stale value here would mark new voiceprints with the old model's name --
     * which is the one failure [AudioModelBundle.embeddingModelId] exists to prevent.
     */
    private fun activeEmbeddingModelId(): String =
        audioModels.speaker.embeddingModelId ?: AudioModelCatalog.EMBEDDING_MODEL_ID

    fun observeSpeakers() = dao.observeAll()

    /**
     * How many people this run could actually put a name to.
     *
     * Counts only voiceprints made by the embedding model currently selected. A vector from the
     * other model is not a candidate -- [prepareLocked] filters it out of the index -- so counting
     * it here would be counting somebody who cannot be matched.
     *
     * That distinction decides whether a long recording may be split. Chunking is only sound
     * because naming reunites a person's clusters across chunks; with nobody matchable there is
     * nothing to reunite them, and each chunk would contribute its own unnamed strangers. Switching
     * the embedding model without re-enrolling is exactly that case, and counting rows rather than
     * usable voiceprints would have walked straight into it. See [DiarizationChunks].
     */
    suspend fun enrolledCount(): Int =
        dao.all().count { it.embeddingModelId == activeEmbeddingModelId() }

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

        val bundle = audioModels.speaker
        val provider = provider()
        if (!embedder.isLoaded || loadedBundleId != bundle.id || loadedProvider != provider) {
            embedder.release()
            val loaded = runCatching {
                embedder.load(
                    audioModels.fileFor(bundle, AudioModelCatalog.EMBEDDING),
                    provider = provider,
                )
            }.onFailure { Log.w(TAG, "could not load the speaker embedder", it) }.isSuccess

            if (!loaded) {
                loadedBundleId = null
                loadedProvider = null
                return@withContext false
            }
            loadedBundleId = bundle.id
            loadedProvider = provider
            indexLoaded = false
        }

        if (!indexLoaded) {
            val speakers = dao.all().filter { it.embeddingModelId == activeEmbeddingModelId() }
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
        dao.all().filter { it.embeddingModelId != activeEmbeddingModelId() }

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
                    provider = provider(),
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
                    embeddingModelId = activeEmbeddingModelId(),
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
     * Folds fragment clusters and names the survivors, over **one** set of voiceprints.
     *
     * Folding and naming used to be two calls that each embedded every cluster from its longest
     * turns -- the same longest turns, to the same [LABEL_SAMPLE_SECONDS] cap, producing the same
     * vector. Embedding is most of what the speaker branch costs on a long recording, so paying for
     * it twice was the single largest avoidable expense here. This computes each cluster's
     * voiceprint once and feeds it to both halves:
     *
     *  - **Folding** ([smallClusterRemap]) merges any cluster too short to be a speaker into
     *    whichever real one it sounds most like. It compares clusters against each other, not against
     *    enrolled people, so it improves a recording of strangers as much as one of Bob and Tim.
     *  - **Naming** ([nameClustersByVoiceprint]) matches each survivor against the enrolled voices;
     *    the best match must clear both [MATCH_THRESHOLD] and [MATCH_MARGIN] over the runner-up, or
     *    the cluster stays an unnamed "Speaker N".
     *
     * A survivor that absorbed a fragment is named from its **pre-fold** voiceprint -- the fragment,
     * being under [MIN_CLUSTER_SECONDS], never displaces one of the survivor's own longest turns, so
     * the vector is the one it would have had anyway, and naming a cluster by its confident long
     * turns rather than by a fragment assigned on similarity is if anything the sounder choice.
     *
     * Called once per diarisation chunk. [startingPlaceholder] threads the "Unknown Speaker N"
     * counter across chunks so the numbering is global even though the naming is not; a stranger who
     * appears in two chunks still fragments into two numbers, which is the known cost of chunking and
     * not a regression from the old global pass, which could not match them across chunks either.
     *
     * [extraVoices] closes that gap when there is something to close it with: voices a live session
     * already tracked across this recording, labelled as it labelled them. They are matched exactly
     * like enrolled people, so a stranger the session called "Speaker C" is "Speaker C" in every
     * chunk of the final pass too, instead of a fresh number per chunk. Recorded on this microphone
     * minutes ago, they match their own voice far above the enrolment bar; a different voice does not.
     */
    internal suspend fun foldAndName(
        samples: FloatArray,
        turns: List<DiarizedSegment>,
        startingPlaceholder: Int,
        minClusterSeconds: Float = MIN_CLUSTER_SECONDS,
        extraVoices: Map<String, List<FloatArray>> = emptyMap(),
    ): ClusterAttribution = lock.withLock {
        withContext(Dispatchers.Default) {
            if (turns.isEmpty()) {
                return@withContext ClusterAttribution(turns, emptyMap(), startingPlaceholder)
            }
            val known = knownVoices(extraVoices)
            val folded = foldClustersLocked(samples, turns, minClusterSeconds, known)

            val naming = nameClustersByVoiceprint(
                turns = folded.turns,
                voiceprints = folded.voiceprints,
                enrolled = known,
                threshold = MATCH_THRESHOLD,
                minimumMargin = MATCH_MARGIN,
                unknownPrefix = UNKNOWN_SPEAKER_PREFIX,
                startingPlaceholder = startingPlaceholder,
                tieBreakable = extraVoices.keys - enrolledEmbeddings.keys,
            )
            naming.decisions.forEach { (cluster, decision) -> logDecision(cluster, decision) }

            ClusterAttribution(
                folded.turns,
                naming.names,
                naming.nextPlaceholder,
                folded.voiceprints.filterKeys { it in naming.names },
            )
        }
    }

    /**
     * Folds fragment clusters and **describes** the survivors instead of naming them.
     *
     * The live pipeline's version of [foldAndName]. A batch run names each chunk against enrolment
     * and throws the voiceprints away, because naming is all the stitching it needs. A live session
     * has to carry speaker identity from one chunk to the next with or without enrolment, so it
     * needs the voiceprint itself -- to match against the voices heard so far -- and the enrolment
     * decision as evidence rather than as a verdict. Same fold, same embedding pass, same enrolment
     * comparison; the difference is only what comes back.
     *
     * The placeholder counter is deliberately absent: a live session's unnamed voices are lettered by
     * the tracker, which knows which of them are the same person across chunks. Numbering them here
     * would restart at one in every chunk.
     */
    internal suspend fun profileClusters(
        samples: FloatArray,
        turns: List<DiarizedSegment>,
        minClusterSeconds: Float = MIN_CLUSTER_SECONDS,
        /** Voices the live session already knows, by label: a short cluster matching one is a speaker, not a fragment. */
        knownVoices: Map<String, List<FloatArray>> = emptyMap(),
    ): ClusterProfiles = lock.withLock {
        withContext(Dispatchers.Default) {
            if (turns.isEmpty()) return@withContext ClusterProfiles(turns, emptyList())
            val folded = foldClustersLocked(samples, turns, minClusterSeconds, knownVoices(knownVoices))

            val profiles = clusterSizes(folded.turns).map { (cluster, size) ->
                val voiceprint = folded.voiceprints[cluster]
                val decision = voiceprint?.let {
                    matchSpeaker(it, enrolledEmbeddings, MATCH_THRESHOLD, MATCH_MARGIN)
                }
                decision?.let { logDecision(cluster, it) }
                ClusterProfile(cluster, size, voiceprint, decision)
            }
            ClusterProfiles(folded.turns, profiles)
        }
    }

    private class Folded(val turns: List<DiarizedSegment>, val voiceprints: Map<Int, FloatArray>)

    /** Enrolled voices plus any the caller supplies for this recording, merged by name. Needs [lock]. */
    private fun knownVoices(extra: Map<String, List<FloatArray>>): Map<String, List<FloatArray>> =
        if (extra.isEmpty()) {
            enrolledEmbeddings
        } else {
            (enrolledEmbeddings.keys + extra.keys).associateWith { name ->
                enrolledEmbeddings[name].orEmpty() + extra[name].orEmpty()
            }
        }

    /**
     * The one embedding pass and the fold, shared by [foldAndName] and [profileClusters]. Must be
     * called with [lock] held.
     *
     * Each cluster's voiceprint is computed once from its longest turns and handed to both the fold
     * and whatever comes after it -- naming or profiling. Embedding is most of what the speaker
     * branch costs on a long recording, and this used to be paid twice. A cluster the model cannot
     * describe is left out of the map, which folding and naming both read as "unusable" rather than
     * guessing at it.
     */
    private suspend fun foldClustersLocked(
        samples: FloatArray,
        turns: List<DiarizedSegment>,
        minClusterSeconds: Float,
        known: Map<String, List<FloatArray>>,
    ): Folded {
        val ready = prepareLocked()

        val sizes = clusterSizes(turns)
        val voiceprints = if (!ready) {
            emptyMap()
        } else {
            sizes.keys.mapNotNull { cluster ->
                embedder.embed(concatenate(samples, longestTurnRanges(turns, cluster)))
                    ?.let { cluster to it }
            }.toMap()
        }

        val minSamples = (minClusterSeconds * AudioRecorder.SAMPLE_RATE).toInt()

        // Recognised fragments are people. A cluster too short to be a speaker by size is kept when
        // its voice matches someone known -- an enrolled person, or a voice the live session has
        // already met -- because that match is a stronger fact than its length. Without this the
        // three-second question inside a 35 s live chunk went to whoever was answering. See
        // [smallClusterRemap].
        val protected = if (known.isEmpty()) {
            emptySet()
        } else {
            sizes.filter { it.value < minSamples }.keys.filter { cluster ->
                val print = voiceprints[cluster] ?: return@filter false
                val decision = matchSpeaker(print, known, MATCH_THRESHOLD, MATCH_MARGIN)
                decision.acceptedName?.also { name ->
                    Log.i(
                        TAG,
                        "cluster %d (%.1fs) kept although short -- sounds like %s (%.3f)".format(
                            cluster,
                            sizes.getValue(cluster) / AudioRecorder.SAMPLE_RATE.toFloat(),
                            name,
                            decision.bestScore,
                        ),
                    )
                } != null
            }.toSet()
        }

        val remap = if (voiceprints.isEmpty()) {
            emptyMap()
        } else {
            smallClusterRemap(sizes, voiceprints, minSamples, protected)
        }
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
        val bySize = applyClusterRemap(turns, remap)

        // Second pass, by resemblance: a cluster too big to be a fragment but too small to be a
        // participant, that sounds like a participant. See [lookalikeClusterRemap].
        val survivors = clusterSizes(bySize)
        val lookalike = if (voiceprints.isEmpty()) {
            emptyMap()
        } else {
            lookalikeClusterRemap(survivors, voiceprints, LOOKALIKE_MAX_SHARE, LOOKALIKE_MIN_SIMILARITY, protected)
        }
        lookalike.forEach { (from, to) ->
            val total = survivors.values.sumOf { it.toLong() }.coerceAtLeast(1L)
            Log.i(
                TAG,
                "cluster %d (%.1fs, %.1f%% of the speech) folded into cluster %d (%.1fs) -- sounds like it (%.3f)".format(
                    from,
                    survivors.getValue(from) / AudioRecorder.SAMPLE_RATE.toFloat(),
                    survivors.getValue(from) * 100f / total,
                    to,
                    survivors.getValue(to) / AudioRecorder.SAMPLE_RATE.toFloat(),
                    cosineSimilarity(voiceprints.getValue(from), voiceprints.getValue(to)),
                ),
            )
        }
        return Folded(applyClusterRemap(bySize, lookalike), voiceprints)
    }

    /**
     * The per-cluster match line. Kept because it is what tells a clustering collapse apart from an
     * alignment bug in a log, and the memory note says to keep it.
     */
    private fun logDecision(cluster: Int, decision: SpeakerMatchDecision) {
        Log.i(
            TAG,
            "cluster %d match: best=%s %.3f, runner-up=%s %s, accepted=%s".format(
                cluster,
                decision.bestName ?: "none",
                decision.bestScore,
                decision.runnerUpName ?: "none",
                decision.runnerUpScore?.let { score -> "%.3f".format(score) } ?: "none",
                decision.acceptedName ?: "unknown",
            ),
        )
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
     * Gives the embedder back under real memory pressure.
     *
     * The diarisation worker no longer releases this after each run -- warm, the next run skips the
     * model load -- so the trim callback is what actually frees it now. Launched on [releaseScope]
     * because `onTrimMemory` is a plain callback and [release] must take the lock.
     */
    fun onMemoryPressure() {
        releaseScope.launch { release() }
    }

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

        /**
         * The second fold, by resemblance. A cluster holding under this share of the diarised speech
         * is a candidate for folding into a major cluster it sounds like -- a phantom made of scraps
         * of two voices, not a person. Ten per cent: a real participant in a conversation holds far
         * more; the German benchmark's phantom held 4.5%.
         */
        const val LOOKALIKE_MAX_SHARE = 0.10f

        /**
         * How alike a minor cluster must be to the major one it would join: the same bar as an
         * enrolled match, so "sounds like that person as much as their own enrolment would" is the
         * standard. Deliberately not lower -- two same-gender voices sit at ~0.73 on the benchmark.
         */
        const val LOOKALIKE_MIN_SIMILARITY = 0.6f

        const val MATCH_THRESHOLD = 0.6f

        /** Required lead over the second-best enrolled voice before a name is safe to print. */
        const val MATCH_MARGIN = 0.05f

        /** Audio per cluster used to identify it. Beyond this, more adds accuracy too slowly to pay for. */
        private const val LABEL_SAMPLE_SECONDS = 30
    }
}
