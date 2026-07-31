package com.example.aiagenttestapp.data.speakers

import android.util.Log
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.data.notes.SpeakerDao
import com.example.aiagenttestapp.data.notes.SpeakerRecord
import com.example.aiagenttestapp.data.notes.SpeakerSample
import com.example.aiagenttestapp.data.notes.SpeakerTurn
import com.example.aiagenttestapp.data.notes.TranscriptMarkup
import com.example.aiagenttestapp.stt.AudioRecorder
import com.example.aiagenttestapp.stt.SpeakerDiarizer
import com.example.aiagenttestapp.stt.SpeakerEmbedder
import com.example.aiagenttestapp.stt.averageEmbedding
import com.example.aiagenttestapp.stt.cosineSimilarity
import kotlinx.coroutines.Dispatchers
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

    /** True once the index reflects what is in Room. */
    private var indexLoaded = false

    fun observeSpeakers() = dao.observeAll()

    /** Whether the models this needs are on disk. */
    fun isAvailable(): Boolean = audioModels.isReady(audioModels.speaker)

    /**
     * Loads the embedding model and fills the search index from Room.
     *
     * Samples written by a *different* embedding model are skipped, not silently loaded. Vectors from
     * another model are not detectably wrong -- they simply match nobody -- so without this check
     * swapping the model would look like the app forgetting everyone it knew.
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
            if (cleanName.matches(Regex("(?i)${TranscriptMarkup.UNKNOWN_SPEAKER_PREFIX} \\d+"))) {
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
     * Each cluster is embedded from its own longest turns -- up to [LABEL_SAMPLE_SECONDS] of them -- rather
     * than from one arbitrary turn. Longest first because a ten-second stretch carries far more speaker
     * evidence than five two-second ones, and capping the total keeps this cheap on a long recording.
     */
    suspend fun labelClusters(
        samples: FloatArray,
        turns: List<SpeakerTurn>,
    ): Map<Int, String> = lock.withLock {
        withContext(Dispatchers.Default) {
            if (turns.isEmpty()) return@withContext emptyMap()

            val ready = prepareLocked()
            val labels = mutableMapOf<Int, String>()

            // Numbered by first appearance, so "Speaker 2" is the second person heard rather than
            // whatever index the clustering happened to assign.
            val order = turns.sortedBy { it.range.first }.map { it.cluster }.distinct()

            var placeholder = 0
            for (cluster in order) {
                val name = if (!ready) {
                    null
                } else {
                    val audio = concatenate(
                        samples,
                        turns.filter { it.cluster == cluster }
                            .sortedByDescending { it.range.count() }
                            .fold(mutableListOf<IntRange>()) { taken, turn ->
                                val total = taken.sumOf { it.count() }
                                if (total < LABEL_SAMPLE_SECONDS * AudioRecorder.SAMPLE_RATE) {
                                    taken += turn.range
                                }
                                taken
                            }
                            .sortedBy { it.first },
                    )
                    embedder.embed(audio)?.let { embedder.search(it, MATCH_THRESHOLD) }
                }

                labels[cluster] = name ?: "${TranscriptMarkup.UNKNOWN_SPEAKER_PREFIX} ${++placeholder}"
            }

            labels
        }
    }

    fun release() {
        embedder.release()
        indexLoaded = false
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
        const val MATCH_THRESHOLD = 0.6f

        /** Audio per cluster used to identify it. Beyond this, more adds accuracy too slowly to pay for. */
        private const val LABEL_SAMPLE_SECONDS = 30
    }
}
