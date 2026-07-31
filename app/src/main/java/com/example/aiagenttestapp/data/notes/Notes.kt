package com.example.aiagenttestapp.data.notes

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aiagenttestapp.functions.MarkerKind
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where a note is in its life.
 *
 * Only exists because transcription moved into a background worker. Before that, a note appeared in the
 * database exactly once, fully formed, at the moment the user pressed Save -- there was nothing to
 * describe. Now a recording is durable the instant it stops, so the row is there while the work is
 * still happening and the list has to be honest about what the user is looking at.
 */
enum class NoteStatus {
    /** Audio captured, the worker is transcribing it. Nothing to read yet. */
    Transcribing,

    /** Transcript ready, waiting for the user to check it and summarise. */
    Draft,

    /** The user reviewed and saved it. */
    Ready,
}

/** Whether a finding came from a marker the user spoke, or from the model reading the transcript. */
enum class FindingSource {
    /** The user explicitly tagged it. Guaranteed to survive summarisation. */
    Tagged,

    /** The model found it in untagged text. Worth showing, worth doubting. */
    Inferred,
}

/**
 * A saved note: what was said, and what the model made of it.
 *
 * Both the transcript and the summary are stored, not just the summary. The transcript is the
 * evidence -- it is what the user actually said, and once it is thrown away there is no way to tell
 * whether the model's summary was faithful or invented. Keeping both is what makes the summary
 * checkable after the fact.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,

    /**
     * The speech-to-text output, as the user finally edited it.
     *
     * Carries speaker prefixes and tag markers in the format [TranscriptMarkup] defines, so a
     * transcript is self-describing: who said what, and which parts the user flagged.
     */
    val transcript: String,

    /** The model's summary, as the user finally edited it. */
    val summary: String,

    val createdAtMillis: Long,

    /** Which language model wrote the summary. Recorded so a bad summary can be attributed. */
    val summarisedBy: String,

    /** How long the recording was, in milliseconds. */
    val durationMillis: Long,

    /**
     * ISO 639 code of the language the recogniser heard ("de", "en"), or null for notes saved
     * before this existed and recordings where no language was reported. The summary was written
     * in this language.
     */
    val language: String? = null,

    val status: NoteStatus = NoteStatus.Ready,

    /**
     * Where the captured audio sits while the note is being transcribed; null once it is not needed.
     *
     * On the row rather than only in the worker's input data because *deleting* has to clean it up. A
     * note discarded mid-transcription would otherwise leave a hundred megabytes of WAV in the cache
     * with nothing left pointing at it.
     */
    val audioPath: String? = null,

    /**
     * 0..1 transcription progress.
     *
     * Persisted rather than read from WorkManager's in-memory progress, which is lost when the process
     * dies -- precisely the case a durable worker exists to survive.
     */
    val transcribeProgress: Float = 0f,

    /** Why transcription failed, when it did. Null otherwise. */
    val error: String? = null,
)

/**
 * A non-conformity or an action, extracted from a note.
 *
 * Stored structurally as well as inside the summary text so the list can count them, and so a tagged
 * item stays attributable to whoever spoke it. [source] is what lets the UI distinguish "you said
 * this" from "the model thinks this" -- a distinction worth keeping in an inspection record.
 */
@Entity(
    tableName = "note_findings",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class NoteFinding(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val noteId: Long,

    val kind: MarkerKind,

    val text: String,

    val source: FindingSource,

    /** Who it belongs to -- the speaker whose turn the marker fell in, when known. */
    val owner: String? = null,

    /** Display order, so a re-read reproduces the order the model reported. */
    val orderIndex: Int = 0,
)

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

@Dao
interface NoteDao {

    /** Newest first: a notes list is read from the top. */
    @Query("SELECT * FROM notes ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<Note?>

    @Insert
    suspend fun insert(note: Note): Long

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        UPDATE notes SET transcript = :transcript, durationMillis = :durationMillis,
            language = :language, status = :status, transcribeProgress = 1.0,
            audioPath = NULL, error = NULL
        WHERE id = :id
        """,
    )
    suspend fun finishTranscription(
        id: Long,
        transcript: String,
        durationMillis: Long,
        language: String?,
        status: NoteStatus = NoteStatus.Draft,
    )

    @Query("UPDATE notes SET transcribeProgress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    @Query("UPDATE notes SET status = :status, error = :error, audioPath = NULL WHERE id = :id")
    suspend fun failTranscription(id: Long, error: String, status: NoteStatus = NoteStatus.Draft)

    /** Commits the user's reviewed note. */
    @Query(
        """
        UPDATE notes SET title = :title, transcript = :transcript, summary = :summary,
            summarisedBy = :summarisedBy, status = :status, audioPath = NULL
        WHERE id = :id
        """,
    )
    suspend fun save(
        id: Long,
        title: String,
        transcript: String,
        summary: String,
        summarisedBy: String,
        status: NoteStatus = NoteStatus.Ready,
    )

    /**
     * Notes left mid-transcription by a process that died without a worker to resume them.
     *
     * Used at startup to mark them failed rather than leave a row spinning for ever. A note stuck on
     * "Transcribing…" with nothing running is worse than an honest error, because the user has no way
     * to tell the difference or to act on it.
     */
    @Query("SELECT * FROM notes WHERE status = :status")
    suspend fun withStatus(status: NoteStatus = NoteStatus.Transcribing): List<Note>
}

@Dao
interface NoteFindingDao {

    @Query("SELECT * FROM note_findings WHERE noteId = :noteId ORDER BY orderIndex ASC")
    fun observeForNote(noteId: Long): Flow<List<NoteFinding>>

    @Query("SELECT * FROM note_findings WHERE noteId = :noteId ORDER BY orderIndex ASC")
    suspend fun forNote(noteId: Long): List<NoteFinding>

    @Query("SELECT * FROM note_findings ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<NoteFinding>>

    @Insert
    suspend fun insertAll(findings: List<NoteFinding>)

    @Query("DELETE FROM note_findings WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long)
}

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

/** Stores enums as their name and float vectors as little-endian blobs. */
object NotesConverters {

    @TypeConverter
    fun statusToString(value: NoteStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): NoteStatus =
        NoteStatus.entries.firstOrNull { it.name == value } ?: NoteStatus.Ready

    @TypeConverter
    fun kindToString(value: MarkerKind): String = value.name

    @TypeConverter
    fun stringToKind(value: String): MarkerKind =
        MarkerKind.entries.firstOrNull { it.name == value } ?: MarkerKind.NonConformity

    @TypeConverter
    fun sourceToString(value: FindingSource): String = value.name

    @TypeConverter
    fun stringToSource(value: String): FindingSource =
        FindingSource.entries.firstOrNull { it.name == value } ?: FindingSource.Inferred

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

@Database(
    entities = [Note::class, NoteFinding::class, SpeakerRecord::class, SpeakerSample::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(NotesConverters::class)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun noteFindingDao(): NoteFindingDao
    abstract fun speakerDao(): SpeakerDao

    companion object {
        /** v1 -> v2: notes gained the detected-language column. Nullable, so old rows read null. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN language TEXT")
            }
        }

        /**
         * v2 -> v3: speakers, findings, and the lifecycle columns background transcription needs.
         *
         * One migration for all four rather than three separate ones. The speaker tables are unused
         * until enrolment ships, but an empty table costs nothing and three chained migrations are
         * three chances to get a schema hash wrong.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing notes are finished notes: Ready, no audio, fully transcribed.
                db.execSQL(
                    "ALTER TABLE notes ADD COLUMN status TEXT NOT NULL DEFAULT 'Ready'",
                )
                db.execSQL("ALTER TABLE notes ADD COLUMN audioPath TEXT")
                db.execSQL(
                    "ALTER TABLE notes ADD COLUMN transcribeProgress REAL NOT NULL DEFAULT 0.0",
                )
                db.execSQL("ALTER TABLE notes ADD COLUMN error TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `note_findings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `noteId` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `owner` TEXT,
                        `orderIndex` INTEGER NOT NULL,
                        FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_note_findings_noteId` " +
                        "ON `note_findings` (`noteId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `speakers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `embeddingModelId` TEXT NOT NULL,
                        `dim` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_speakers_name` ON `speakers` (`name`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `speaker_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `speakerId` INTEGER NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `durationMillis` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        FOREIGN KEY(`speakerId`) REFERENCES `speakers`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_speaker_samples_speakerId` " +
                        "ON `speaker_samples` (`speakerId`)",
                )
            }
        }
    }
}
