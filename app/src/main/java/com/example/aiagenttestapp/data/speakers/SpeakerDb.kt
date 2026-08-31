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
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
/**
 * [Live] is a run whose transcript is being written **while the audio is still arriving** -- a file
 * played back in real time, or a recording in progress -- and whose blocks are provisional: the live
 * session rewrites them after every chunk, and the batch worker replaces them all when the audio
 * ends. Stored as its name like the others, so an older build that does not know it reads it as
 * [Failed] via the converter's fallback rather than crashing on the row.
 */
enum class DiarizedStatus { Idle, Running, Done, Failed, Live }

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

    /**
     * How long the last finished run took, in wall-clock milliseconds, or null if none has.
     *
     * Worth a column because it is the number the user is actually deciding on. A run here is
     * minutes of the device's full attention, and the only honest way to answer "is a longer
     * recording worth starting right now" is what the last one of this length cost -- which the
     * phase timings in logcat answer for whoever is holding a laptop, and for nobody else.
     *
     * Measured from the worker starting, not from the tap: the queued wait is WorkManager's and
     * varies with whatever else the device is doing, so folding it in would report the scheduler
     * rather than the models. Cleared when a run starts, so a row never shows the previous run's
     * time beside this run's progress bar.
     */
    val runMillis: Long? = null,

    /**
     * How long working out *who* spoke took: segmentation, clustering, folding and naming together.
     *
     * **This and [transcribeMillis] overlap, and do not add up to [runMillis].** The two branches
     * run concurrently by design, so the run costs roughly the longer of them plus reading the
     * file, not their sum. They are stored apart because they are the two independent knobs: one is
     * paid to the diarisation models and answers whether chunking or a coarser window shift is
     * worth it, the other is paid to the recogniser and answers whether a smaller model would do.
     * A single total hides which one to reach for.
     */
    val diariseMillis: Long? = null,

    /** How long transcribing the words took. Concurrent with [diariseMillis]; see its note. */
    val transcribeMillis: Long? = null,

    /**
     * The transcript this recording is scored against, speaker-tagged, or null if none is attached.
     *
     * Held on the recording rather than alongside the run, because a reference outlives any one
     * run: the point of re-running here is to change the expected count or enrol somebody and see
     * the number move, which only works if both runs are scored against the same text.
     *
     * Format is one bracketed label per turn -- `[S1] ... [S2] ...` -- the same shape the
     * benchmark's dialogue references already use, and the shape [Wer]'s tokeniser was taught to
     * strip rather than score as words.
     */
    val referenceText: String? = null,

    /** "en" or "de": which numeral grammar the scorer normalises with, as `wer.py`'s `--lang`. */
    val language: String = "en",

    /**
     * Transcript words over reference words, as a percentage. Read before [werPercent].
     *
     * Stored rather than derived, for the reason the benchmark stores its own: a row keeps the
     * number it was scored with, and the list does not re-normalise a 3,000-word reference on
     * every recomposition. Under about 90% the error rate below it is measuring truncation.
     */
    val coveragePercent: Double? = null,

    /** Number-normalised word error rate against [referenceText]. Null until one is attached. */
    val werPercent: Double? = null,

    /**
     * Share of agreed words filed under the right speaker -- see [SpeakerAccuracy].
     *
     * Separate from [werPercent] because the two failures are separate: hearing the words wrong and
     * giving the right words to the wrong person are different problems with different fixes, and a
     * single blended "accuracy" would hide which of them a run actually has.
     *
     * Null when the reference names no speakers, which is a plain transcript and cannot answer the
     * question -- as distinct from 0.0, which would claim every word went to the wrong person.
     */
    val speakerAccuracyPercent: Double? = null,

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

/**
 * Reads are whole rows; **writes are never**.
 *
 * There is deliberately no `@Update` here. Two writers touch a recording -- the worker running it,
 * and the screen the user is looking at while it runs -- and a whole-row update from either is a
 * read-modify-write over columns it does not own. Whoever wrote second undid the other silently,
 * with no error and nothing in the log: a reference attached mid-run vanished when the run
 * finished, and a score computed while a run was ending put the row back on a progress bar with no
 * worker behind it, which is the state [DiarizeWorker.reconcile] exists to mop up. Narrowing the
 * window was not the fix -- scoring a long reference takes long enough to lose that race on its
 * own, and the window can never be closed by ordering alone.
 *
 * So each write states its own columns: [beginRun] and [finishRun] and [fail] belong to a run,
 * [updateProgress] to its progress, [updateScore] to the reference and what it scored. No run may
 * write the reference or the language -- those belong to the user -- and no score may write the
 * status. Adding an `@Update` back would restore every one of these bugs at once.
 */
@Dao
interface DiarizedDao {

    @Query("SELECT * FROM diarized_recordings ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<DiarizedRecording>>

    @Query("SELECT * FROM diarized_recordings WHERE id = :id")
    suspend fun byId(id: Long): DiarizedRecording?

    @Insert
    suspend fun insert(recording: DiarizedRecording): Long

    @Query("UPDATE diarized_recordings SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    /**
     * Marks a run started, and clears what the last one left behind.
     *
     * The reference and its language survive; the three percentages do not. A score describes the
     * blocks a run produced, and a re-run is about to delete them -- the reason to re-run at all is
     * to see whether the number moves, so leaving the old one beside the new progress bar invites
     * reading it as this run's result. Same argument as [DiarizedRecording.runMillis], which is
     * cleared here for the same reason.
     */
    @Query(
        """
        UPDATE diarized_recordings
        SET status = 'Running', progress = 0.0, error = NULL, runMillis = NULL,
            diariseMillis = NULL, transcribeMillis = NULL,
            coveragePercent = NULL, werPercent = NULL, speakerAccuracyPercent = NULL,
            expectedSpeakers = :expectedSpeakers
        WHERE id = :id
        """,
    )
    suspend fun beginRun(id: Long, expectedSpeakers: Int)

    /** The end of a successful run: what it cost, and what it scored if a reference was attached. */
    @Query(
        """
        UPDATE diarized_recordings
        SET status = 'Done', progress = 1.0, error = NULL, runMillis = :runMillis,
            diariseMillis = :diariseMillis, transcribeMillis = :transcribeMillis,
            coveragePercent = :coveragePercent, werPercent = :werPercent,
            speakerAccuracyPercent = :speakerAccuracyPercent
        WHERE id = :id
        """,
    )
    suspend fun finishRun(
        id: Long,
        runMillis: Long,
        diariseMillis: Long?,
        transcribeMillis: Long?,
        coveragePercent: Double?,
        werPercent: Double?,
        speakerAccuracyPercent: Double?,
    )

    /**
     * A reference arriving, changing, or being taken away, with the numbers it implies.
     *
     * The three percentages travel with the reference rather than being written separately,
     * because a row that keeps the score of a reference it no longer has is worse than a row with
     * no score at all -- it reads as a measured result.
     */
    @Query(
        """
        UPDATE diarized_recordings
        SET referenceText = :referenceText, language = :language,
            coveragePercent = :coveragePercent, werPercent = :werPercent,
            speakerAccuracyPercent = :speakerAccuracyPercent
        WHERE id = :id
        """,
    )
    suspend fun updateScore(
        id: Long,
        referenceText: String?,
        language: String,
        coveragePercent: Double?,
        werPercent: Double?,
        speakerAccuracyPercent: Double?,
    )

    @Query("SELECT * FROM diarized_recordings WHERE status = 'Running'")
    suspend fun running(): List<DiarizedRecording>

    @Query("SELECT * FROM diarized_recordings WHERE status = 'Live'")
    suspend fun live(): List<DiarizedRecording>

    /**
     * Marks a row as a live session and clears the previous run's figures, the way [beginRun] does
     * for a batch run. The reference survives for the same reason it does there.
     */
    @Query(
        """
        UPDATE diarized_recordings
        SET status = 'Live', progress = 0.0, error = NULL, runMillis = NULL,
            diariseMillis = NULL, transcribeMillis = NULL,
            coveragePercent = NULL, werPercent = NULL, speakerAccuracyPercent = NULL
        WHERE id = :id
        """,
    )
    suspend fun beginLive(id: Long)

    /**
     * The length of a recording that was created before it was finished -- a live capture's row
     * exists from the first second so the session has somewhere to write, and learns its duration
     * when the user stops. A non-zero duration is also how the live worker knows the capture ended.
     */
    @Query("UPDATE diarized_recordings SET durationMillis = :durationMillis WHERE id = :id")
    suspend fun setDuration(id: Long, durationMillis: Long)

    @Query("UPDATE diarized_recordings SET status = 'Failed', error = :error WHERE id = :id")
    suspend fun fail(id: Long, error: String)

    @Query("DELETE FROM diarized_recordings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM diarized_blocks ORDER BY startSample ASC")
    fun observeAllBlocks(): Flow<List<DiarizedBlock>>

    @Query("SELECT * FROM diarized_blocks WHERE recordingId = :recordingId ORDER BY startSample ASC")
    suspend fun blocksFor(recordingId: Long): List<DiarizedBlock>

    /** Replaced wholesale on a re-run: a half-updated transcript would mix two runs' attributions. */
    @Query("DELETE FROM diarized_blocks WHERE recordingId = :recordingId")
    suspend fun deleteBlocksFor(recordingId: Long)

    @Insert
    suspend fun insertBlocks(blocks: List<DiarizedBlock>)

    /**
     * Swaps a recording's blocks in one transaction, so an observer of [observeAllBlocks] never sees
     * the recording empty in between. The live session does this after every chunk -- a flicker per
     * chunk would be the whole screen blinking every thirty seconds.
     */
    @Transaction
    suspend fun replaceBlocks(recordingId: Long, blocks: List<DiarizedBlock>) {
        deleteBlocksFor(recordingId)
        insertBlocks(blocks)
    }
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
    version = 4,
    exportSchema = true,
)
@TypeConverters(SpeakerConverters::class)
abstract class SpeakerDatabase : RoomDatabase() {
    abstract fun speakerDao(): SpeakerDao
    abstract fun diarizedDao(): DiarizedDao

    companion object {
        /**
         * How long a run took, added so the row can say it.
         *
         * Nullable and not backfilled: a recording diarised before the column existed has no
         * recorded time, and there is nothing to infer one from. Those rows say nothing about how
         * long they took, which is the truth.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN runMillis INTEGER")
            }
        }

        /**
         * A reference transcript and the three numbers scored against it.
         *
         * [DiarizedRecording.language] is the only one that is not null: every scorer call needs a
         * numeral grammar, and a null there would have to be defaulted at each call site instead of
         * once here. Rows that predate the column get "en", which is what the corpus this was built
         * against is in -- and is visible and changeable on the row, so a German recording is a
         * correction rather than a silently wrong number.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN referenceText TEXT")
                db.execSQL(
                    "ALTER TABLE diarized_recordings ADD COLUMN language TEXT NOT NULL DEFAULT 'en'",
                )
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN coveragePercent REAL")
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN werPercent REAL")
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN speakerAccuracyPercent REAL")
            }
        }

        /**
         * The two phase timings, split out of the single run total.
         *
         * Nullable and not backfilled for the same reason [MIGRATION_1_2] left runMillis alone: a
         * run that finished before the columns existed recorded one number, and splitting it after
         * the fact would be inventing the halves rather than reporting them. Those rows keep their
         * total and show no split.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN diariseMillis INTEGER")
                db.execSQL("ALTER TABLE diarized_recordings ADD COLUMN transcribeMillis INTEGER")
            }
        }
    }
}
