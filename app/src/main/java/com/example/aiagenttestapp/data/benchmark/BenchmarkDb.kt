package com.example.aiagenttestapp.data.benchmark

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * An imported benchmark clip: the audio and the reference transcript it will be scored against.
 *
 * Both are *copies*, taken at import. The app never persists a SAF grant (no screen here does),
 * so holding the picked Uri would mean a clip that stops working the next time the process is
 * recreated -- and copying also makes runs reproducible when the file in Downloads is later
 * edited or removed. The audio copy lives under `filesDir/benchmark`, not cache: a reference
 * corpus that evaporates under storage pressure invalidates the comparisons built on it.
 */
@Entity(tableName = "clips")
data class BenchmarkClip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The picked file's display name, so the user recognises the clip they imported. */
    val name: String,

    /** Absolute path of the app-private 16 kHz mono WAV copy. */
    val audioPath: String,

    /** The reference text, verbatim -- `say` scripts with `[[slnc]]` directives score fine. */
    val referenceText: String,

    /** "en" or "de": which numeral grammar [Wer] normalises with, as wer.py's `--lang`. */
    val language: String,

    val durationMillis: Long,
    val createdAtMillis: Long,
)

enum class BenchmarkRunStatus { Running, Done, Failed }

/**
 * One transcription of a clip under one settings snapshot.
 *
 * Runs accumulate rather than replace, because the whole point is comparing them: 16x against
 * No-delay, XNNPACK against CPU, each row naming what it ran under. [settingsSummary] and
 * [backend] are captured at *enqueue* -- a run must describe the settings it actually used, not
 * whatever Settings says when the row is read back later.
 */
@Entity(
    tableName = "runs",
    foreignKeys = [
        ForeignKey(
            entity = BenchmarkClip::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clipId")],
)
data class BenchmarkRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val clipId: Long,
    val startedAtMillis: Long,
    val status: BenchmarkRunStatus = BenchmarkRunStatus.Running,

    /** Human-readable settings snapshot, e.g. "Android · 16× · VAD on". */
    val settingsSummary: String,

    /** The [com.example.aiagenttestapp.data.notes.SttBackend] slug this run is pinned to. */
    val backend: String,

    /** The resolved Gemma model id when [backend] is the Gemma path; null otherwise. */
    val sttModelId: String? = null,

    val progress: Float = 0f,
    val transcript: String = "",

    val rawWerPercent: Double? = null,
    val normalisedWerPercent: Double? = null,
    val substitutions: Int? = null,
    val deletions: Int? = null,
    val insertions: Int? = null,
    val referenceWords: Int? = null,

    /**
     * Hypothesis words over reference words, as a percentage. Read before [normalisedWerPercent].
     *
     * Stored rather than derived so an old row keeps the number it was scored with, and so the list
     * can show it without re-normalising every transcript on every recomposition. Under ~90% the
     * run is truncated and its WER is a measure of what is missing, not of what was heard.
     */
    val coveragePercent: Double? = null,

    /** Character error rate on the normalised text -- the cross-check for a suspicious WER. */
    val cerPercent: Double? = null,

    /** Display-ready "3× 'ref' → 'hyp'" lines, newline-joined -- the normalised pass's top pairs. */
    val topPairs: String = "",

    /**
     * How long the run actually took, in wall-clock milliseconds.
     *
     * Not `now - startedAtMillis`, and the difference matters: [startedAtMillis] is stamped at
     * *enqueue*, so it includes however long WorkManager sat on the job. This is measured inside the
     * worker, which is what makes two rows comparable and what the real-time factor divides into.
     *
     * Null on a run that has none to report -- one still going, and one interrupted by a process
     * death, where the honest answer is that nobody knows how far it got before the process died.
     */
    val wallMillis: Long? = null,

    val error: String? = null,
)

@Dao
interface BenchmarkClipDao {

    @Query("SELECT * FROM clips ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<BenchmarkClip>>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun byId(id: Long): BenchmarkClip?

    @Insert
    suspend fun insert(clip: BenchmarkClip): Long

    /** Runs go with it via the foreign key's cascade. The audio file is the caller's problem. */
    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BenchmarkRunDao {

    @Query("SELECT * FROM runs ORDER BY startedAtMillis DESC")
    fun observeAll(): Flow<List<BenchmarkRun>>

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun byId(id: Long): BenchmarkRun?

    @Insert
    suspend fun insert(run: BenchmarkRun): Long

    @Update
    suspend fun update(run: BenchmarkRun)

    @Query("UPDATE runs SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    @Query("SELECT * FROM runs WHERE status = 'Running'")
    suspend fun running(): List<BenchmarkRun>

    /**
     * [wallMillis] is nullable and explicit at every call site rather than defaulted, because the
     * two kinds of failure genuinely differ: one that threw knows how long it ran, and one found
     * abandoned at startup does not. Defaulting would let a caller quietly pick the wrong one.
     */
    @Query("UPDATE runs SET status = 'Failed', error = :error, wallMillis = :wallMillis WHERE id = :id")
    suspend fun fail(id: Long, error: String, wallMillis: Long?)
}

internal class BenchmarkConverters {
    @TypeConverter
    fun fromStatus(value: BenchmarkRunStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): BenchmarkRunStatus =
        BenchmarkRunStatus.entries.firstOrNull { it.name == value } ?: BenchmarkRunStatus.Failed
}

/**
 * Its own database, per the house rule in DatabaseModule: each feature owns its schema, so
 * benchmark churn never forces a version bump on notes, audit or chat.
 */
@Database(
    entities = [BenchmarkClip::class, BenchmarkRun::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(BenchmarkConverters::class)
abstract class BenchmarkDatabase : RoomDatabase() {
    abstract fun clipDao(): BenchmarkClipDao
    abstract fun runDao(): BenchmarkRunDao

    companion object {
        /**
         * Coverage and CER, added when this rig adopted a second party's scoring protocol -- which
         * reads coverage *first*, because a truncated run otherwise reports as an accuracy number.
         *
         * Nullable, and deliberately not backfilled: rows scored before the columns existed have no
         * honest value to put there, and inventing one by re-scoring now would mix numbers produced
         * by two different builds under one heading.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN coveragePercent REAL")
                db.execSQL("ALTER TABLE runs ADD COLUMN cerPercent REAL")
            }
        }
    }
}
