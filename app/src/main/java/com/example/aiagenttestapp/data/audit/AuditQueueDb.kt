package com.example.aiagenttestapp.data.audit

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a queued document. Stored as the enum name. */
enum class AuditStatus { QUEUED, ANALYSING, DONE, FAILED, CANCELLED }

/**
 * One document in the audit queue, plus its final per-document report once analysed.
 *
 * The queue lives in its own database (audit.db), separate from chats and notes, so none of them
 * ever force a migration on the others. The chunks that back a document ([AuditChunkEntity]) are
 * materialised at enqueue time and processed with checkpointing, so a killed-and-restarted worker
 * resumes from the last finished chunk rather than from scratch.
 */
@Entity(tableName = "audit_documents")
data class AuditDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Pinned at enqueue, so a later model change never invalidates this document's chunk sizes. */
    val modelId: String,
    /**
     * [AuditMode] name, pinned at enqueue for the same reason as [modelId]: the mode decides the
     * prompt, and so the reserve, and so where the chunk boundaries fell. Re-reading a document's
     * checkpointed sections under the other mode's assumptions would silently mis-size every one.
     */
    val mode: String = AuditMode.DETAILED.name,
    /**
     * Whether this document gets a written summary -- and, with it, whether extraction is asked for
     * the per-section facts a summary is written from.
     *
     * One flag for both because the two are the same decision: facts are rendered nowhere and feed
     * nothing else, so facts without a summary is output nobody reads, and a summary without facts
     * cannot be written. Turning it off is where the saving is: the summary is one turn, the facts
     * are several lines on every section.
     *
     * Pinned at enqueue for the same reason as [mode] and [modelId]: dropping the facts shortens
     * the preamble, which changes the reserve, which is where the chunk boundaries fell.
     */
    val includeSummary: Boolean = true,
    val status: String,
    val chunkCount: Int,
    /**
     * Characters of the document that were never chunked, because it needed more sections than
     * [AuditQueue.MAX_CHUNKS] allows. 0 for every document that fits, which is nearly all of them.
     *
     * Recorded at enqueue and carried into the finished report. The cap used to be applied with a
     * bare take(n): the tail was dropped and nothing anywhere said so, so the report described a
     * partial read of a document in exactly the same words it uses for a complete one.
     */
    val truncatedChars: Int = 0,
    val chunksDone: Int = 0,
    /** The reduce (summary) step is running. */
    val summarising: Boolean = false,
    /**
     * Wall-clock milliseconds actually spent analysing this document, accumulated across runs rather
     * than derived from created/updated timestamps -- so queue waiting, backgrounding and a
     * process-killed-then-resumed run all report the real time the model spent on it.
     */
    val analysisMillis: Long = 0,
    /** The finished report as JSON ([AuditResultCodec]); null until DONE. */
    val resultJson: String? = null,
    val error: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * One chunk of a document. [findingsJson] is the checkpoint: null until the chunk is processed, then
 * that chunk's findings, keyed by row so a reprocessed chunk overwrites rather than double-counts.
 */
@Entity(
    tableName = "audit_chunks",
    foreignKeys = [
        ForeignKey(
            entity = AuditDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("docId")],
)
data class AuditChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val chunkIndex: Int,
    val text: String,
    val findingsJson: String? = null,
    val done: Boolean = false,
)

@Dao
abstract class AuditDao {

    @Insert
    abstract suspend fun insertDocument(doc: AuditDocumentEntity): Long

    @Insert
    abstract suspend fun insertChunks(chunks: List<AuditChunkEntity>)

    /** Inserts the document and its chunks atomically, so a document never exists without its chunks. */
    @Transaction
    open suspend fun enqueue(doc: AuditDocumentEntity, chunksFor: (docId: Long) -> List<AuditChunkEntity>): Long {
        val id = insertDocument(doc)
        insertChunks(chunksFor(id))
        return id
    }

    @Query("SELECT * FROM audit_documents ORDER BY createdAtMillis DESC")
    abstract fun observeDocuments(): Flow<List<AuditDocumentEntity>>

    @Query("SELECT * FROM audit_documents WHERE id = :id")
    abstract fun observeDocument(id: Long): Flow<AuditDocumentEntity?>

    /** Oldest document still needing work -- QUEUED, or ANALYSING and interrupted mid-run (resume). */
    @Query(
        "SELECT * FROM audit_documents WHERE status IN ('QUEUED', 'ANALYSING') " +
            "ORDER BY createdAtMillis ASC LIMIT 1",
    )
    abstract suspend fun nextPending(): AuditDocumentEntity?

    @Query("SELECT status FROM audit_documents WHERE id = :id")
    abstract suspend fun statusOf(id: Long): String?

    @Query("SELECT * FROM audit_chunks WHERE docId = :docId AND done = 0 ORDER BY chunkIndex ASC")
    abstract suspend fun pendingChunks(docId: Long): List<AuditChunkEntity>

    @Query("SELECT findingsJson FROM audit_chunks WHERE docId = :docId AND done = 1 ORDER BY chunkIndex ASC")
    abstract suspend fun chunkFindings(docId: Long): List<String?>

    @Query("UPDATE audit_chunks SET findingsJson = :json, done = 1 WHERE id = :id")
    abstract suspend fun completeChunk(id: Long, json: String)

    /** Recomputes chunksDone from the chunk rows (drift-free across crashes) and re-emits the doc. */
    @Query(
        "UPDATE audit_documents SET " +
            "chunksDone = (SELECT COUNT(*) FROM audit_chunks WHERE docId = :id AND done = 1), " +
            "updatedAtMillis = :ts WHERE id = :id",
    )
    abstract suspend fun refreshProgress(id: Long, ts: Long)

    @Query("UPDATE audit_documents SET status = :status, error = :error, updatedAtMillis = :ts WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String, error: String?, ts: Long)

    @Query("UPDATE audit_documents SET summarising = :summarising, updatedAtMillis = :ts WHERE id = :id")
    abstract suspend fun setSummarising(id: Long, summarising: Boolean, ts: Long)

    /**
     * Adds the time spent since the last checkpoint. Accumulating (rather than storing a start time)
     * is what makes the total survive a resumed run: each slice is banked as soon as it is earned.
     */
    @Query("UPDATE audit_documents SET analysisMillis = analysisMillis + :deltaMillis WHERE id = :id")
    abstract suspend fun addElapsed(id: Long, deltaMillis: Long)

    @Query(
        "UPDATE audit_documents SET status = 'DONE', resultJson = :json, summarising = 0, " +
            "analysisMillis = analysisMillis + :deltaMillis, " +
            "updatedAtMillis = :ts WHERE id = :id",
    )
    abstract suspend fun setResult(id: Long, json: String, deltaMillis: Long, ts: Long)

    @Query("DELETE FROM audit_documents WHERE id = :id")
    abstract suspend fun deleteDocument(id: Long)
}

@Database(entities = [AuditDocumentEntity::class, AuditChunkEntity::class], version = 7, exportSchema = false)
abstract class AuditDatabase : RoomDatabase() {

    abstract fun auditDao(): AuditDao

    companion object {
        /**
         * Adds includeSummary. Existing documents default to 1 -- true -- because that is how every
         * one of them was actually produced: the choice did not exist when they were queued, and a
         * report that already carries a summary must not start describing itself as one without.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE audit_documents ADD COLUMN includeSummary INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /** Adds analysisMillis. Existing documents report 0, which the UI reads as "unknown". */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit_documents ADD COLUMN analysisMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the severity-grading checkpoint. Nullable, so in-flight documents just start ungraded. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit_documents ADD COLUMN gradedJson TEXT")
            }
        }

        /**
         * Adds truncatedChars. Existing rows default to 0 -- which is a claim that nothing was
         * dropped, and for a document enqueued by a build that truncated silently that claim may be
         * wrong. There is no way to recover the number after the fact; documents enqueued from here
         * on carry the truth, and older ones are no worse off than they already were.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit_documents ADD COLUMN truncatedChars INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the audit mode. Existing rows default to DETAILED, which is not a guess -- it is the
         * only read this app could perform before quick mode existed, so every stored document was
         * produced that way.
         */
        /**
         * Drops gradedJson, the severity-grading checkpoint, along with the pass that wrote it.
         *
         * A table recreation rather than an ALTER: DROP COLUMN needs SQLite 3.35, which is Android
         * 14 and above, and this app runs from 31. Every other column is copied, so a document
         * queued or half-analysed across the upgrade keeps its place -- only the dead checkpoint
         * goes, and nothing reads it any more.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE audit_documents_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        mode TEXT NOT NULL DEFAULT 'DETAILED',
                        status TEXT NOT NULL,
                        chunkCount INTEGER NOT NULL,
                        truncatedChars INTEGER NOT NULL DEFAULT 0,
                        chunksDone INTEGER NOT NULL DEFAULT 0,
                        summarising INTEGER NOT NULL DEFAULT 0,
                        analysisMillis INTEGER NOT NULL DEFAULT 0,
                        resultJson TEXT,
                        error TEXT,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO audit_documents_new
                        (id, name, modelId, mode, status, chunkCount, truncatedChars, chunksDone,
                         summarising, analysisMillis, resultJson, error, createdAtMillis,
                         updatedAtMillis)
                    SELECT id, name, modelId, mode, status, chunkCount, truncatedChars, chunksDone,
                           summarising, analysisMillis, resultJson, error, createdAtMillis,
                           updatedAtMillis
                    FROM audit_documents
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE audit_documents")
                db.execSQL("ALTER TABLE audit_documents_new RENAME TO audit_documents")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE audit_documents ADD COLUMN mode TEXT NOT NULL DEFAULT " +
                        "'${AuditMode.DETAILED.name}'",
                )
            }
        }
    }
}
