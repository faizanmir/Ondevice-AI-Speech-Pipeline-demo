package com.example.aiagenttestapp.data.speakers

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An enrolled voice.
 *
 * [embeddingModelId] and [dim] are recorded per speaker so that changing the embedding model is
 * *detectable*. Voiceprints from a different model are not wrong-looking, they simply do not match
 * anything -- so without this the app would silently stop recognising everyone and blame the audio.
 * With it, the mismatch is visible and the fix ("re-enrol") can be offered.
 */
@Entity(tableName = "speakers", indices = [Index(value = ["name"], unique = true)])
data class SpeakerRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val createdAtMillis: Long,

    val embeddingModelId: String,

    val dim: Int,
)

/**
 * One recorded voiceprint for a speaker. Several per person, from separate takes.
 *
 * The embedding only -- never the audio it came from. An embedding is not reversible to speech, which
 * makes it the least the app can hold and still do the job. Enrolment recordings are discarded the
 * moment they have been turned into one of these.
 */
@Entity(
    tableName = "speaker_samples",
    foreignKeys = [
        ForeignKey(
            entity = SpeakerRecord::class,
            parentColumns = ["id"],
            childColumns = ["speakerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("speakerId")],
)
data class SpeakerSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val speakerId: Long,

    val embedding: FloatArray,

    val durationMillis: Long,

    val createdAtMillis: Long,
) {
    // A FloatArray in a data class gives reference equality through generated equals/hashCode, which
    // silently breaks any list diffing or set membership this ends up in.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerSample) return false
        return id == other.id &&
            speakerId == other.speakerId &&
            durationMillis == other.durationMillis &&
            createdAtMillis == other.createdAtMillis &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + speakerId.hashCode()
        result = 31 * result + durationMillis.hashCode()
        result = 31 * result + createdAtMillis.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/** A speaker with the voiceprints belonging to them. */
data class SpeakerWithSamples(
    val speaker: SpeakerRecord,
    val samples: List<SpeakerSample>,
)

/**
 * Where a recording stands.
 *
 * [Idle] is the state a row is *born* in, and its absence was a dead end. Without it a fresh row
 * defaulted to [Running], so importing a file put an indeterminate progress bar on screen for a run
 * that had never been started -- and the detail pane hides its Run button while a row is running, so
 * there was no way to start one either. The bar spun forever and nothing was ever identified.
 */
enum class DiarizedStatus { Idle, Running, Done, Failed }

/**
 * One recording put through diarisation and transcription.
 *
 * [audioPath] is kept after the run finishes, unlike a voice note's, and that is the point of the
 * difference: the whole value of this screen is being able to re-run the same audio when a speaker
 * comes out wrong -- a different expected-speaker count, or after enrolling the person the model
 * called "Speaker 2". A note is finished when its transcript is; this is not.
 */
@Entity(tableName = "diarized_recordings")
data class DiarizedRecording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** What the user recognises it by: the imported file's name, or a stamp for a live take. */
    val name: String,

    val audioPath: String,

    val durationMillis: Long,

    val createdAtMillis: Long,

    val status: DiarizedStatus = DiarizedStatus.Idle,

    val progress: Float = 0f,

    /**
     * How many people the user says are in the recording, or 0 for "work it out".
     *
     * Stored per recording rather than read from settings at run time, so a re-run reproduces what
     * the row describes instead of whatever the picker happens to say later.
     */
    val expectedSpeakers: Int = 0,

    val error: String? = null,
)

/** One stretch of one speaker's words, as shown on screen. */
@Entity(
    tableName = "diarized_blocks",
    foreignKeys = [
        ForeignKey(
            entity = DiarizedRecording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
data class DiarizedBlock(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val recordingId: Long,

    val startSample: Int,

    val endSample: Int,

    /** The diarisation cluster, or [SpeakerAlignment.UNATTRIBUTED]. */
    val cluster: Int,

    /**
     * The name shown against this block, resolved when the run finished.
     *
     * Written down rather than looked up on read. A block says who the app believed was speaking
     * *at the time it decided*; re-resolving on every read would silently rewrite old transcripts
     * whenever someone is enrolled or deleted, which is the one thing a record of who said what
     * must not do.
     */
    val speakerName: String,

    val text: String,
)

@Dao
interface SpeakerDao {

    @Query("SELECT * FROM speakers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SpeakerRecord>>

    @Query("SELECT * FROM speakers ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<SpeakerRecord>

    @Query("SELECT * FROM speakers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): SpeakerRecord?

    @Insert
    suspend fun insert(speaker: SpeakerRecord): Long

    @Query("DELETE FROM speakers WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertSamples(samples: List<SpeakerSample>)

    @Query("SELECT * FROM speaker_samples WHERE speakerId = :speakerId")
    suspend fun samplesFor(speakerId: Long): List<SpeakerSample>

    @Query("SELECT * FROM speaker_samples")
    suspend fun allSamples(): List<SpeakerSample>
}

@Dao
interface DiarizedDao {

    @Query("SELECT * FROM diarized_recordings ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<DiarizedRecording>>

    @Query("SELECT * FROM diarized_recordings WHERE id = :id")
    suspend fun byId(id: Long): DiarizedRecording?

    @Insert
    suspend fun insert(recording: DiarizedRecording): Long

    @Update
    suspend fun update(recording: DiarizedRecording)

    @Query("UPDATE diarized_recordings SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    @Query("SELECT * FROM diarized_recordings WHERE status = 'Running'")
    suspend fun running(): List<DiarizedRecording>

    @Query("UPDATE diarized_recordings SET status = 'Failed', error = :error WHERE id = :id")
    suspend fun fail(id: Long, error: String)

    @Query("DELETE FROM diarized_recordings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM diarized_blocks ORDER BY startSample ASC")
    fun observeAllBlocks(): Flow<List<DiarizedBlock>>

    /** Replaced wholesale on a re-run: a half-updated transcript would mix two runs' attributions. */
    @Query("DELETE FROM diarized_blocks WHERE recordingId = :recordingId")
    suspend fun deleteBlocksFor(recordingId: Long)

    @Insert
    suspend fun insertBlocks(blocks: List<DiarizedBlock>)
}

internal object SpeakerConverters {

    @TypeConverter
    fun statusToString(value: DiarizedStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): DiarizedStatus =
        DiarizedStatus.entries.firstOrNull { it.name == value } ?: DiarizedStatus.Failed

    /**
     * Explicit little-endian, not the platform default.
     *
     * The byte order is pinned because these blobs outlive the process that wrote them. Relying on the
     * platform's order would make a database written on one architecture unreadable on another -- the
     * floats would come back byte-swapped, which is not an error, just a voiceprint that matches
     * nobody.
     */
    @TypeConverter
    fun embeddingToBytes(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        value.forEach(buffer::putFloat)
        return buffer.array()
    }

    @TypeConverter
    fun bytesToEmbedding(value: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(value.size / Float.SIZE_BYTES) { buffer.float }
    }
}

/**
 * Its own database, per the house rule in DatabaseModule: each feature owns its schema.
 *
 * These two tables used to live in `notes.db`, and moving them out is the point rather than tidying.
 * They were carried along by every migration the notes feature needed while sharing none of its
 * churn -- and when the notes schema was reworked they went with it, which is how an enrolled-voice
 * feature came to be deleted by a change that had nothing to do with voices. A separate file cannot
 * be removed by accident that way.
 *
 * Starting at version 1 with no migration from the notes tables. Nothing is lost that anyone had:
 * enrolment never ran on a device, so there are no voiceprints in the field to carry over, and
 * inventing a migration for data that cannot exist would be a code path no device will ever take.
 */
@Database(
    entities = [
        SpeakerRecord::class,
        SpeakerSample::class,
        DiarizedRecording::class,
        DiarizedBlock::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(SpeakerConverters::class)
abstract class SpeakerDatabase : RoomDatabase() {
    abstract fun speakerDao(): SpeakerDao
    abstract fun diarizedDao(): DiarizedDao
}
