package com.example.aiagenttestapp.data.notes

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

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

    /** The speech-to-text output, as the user finally edited it. */
    val transcript: String,

    /** The model's summary, as the user finally edited it. */
    val summary: String,

    val createdAtMillis: Long,

    /** Which language model wrote the summary. Recorded so a bad summary can be attributed. */
    val summarisedBy: String,

    /** How long the recording was, in milliseconds. */
    val durationMillis: Long,
)

@Dao
interface NoteDao {

    /** Newest first: a notes list is read from the top. */
    @Query("SELECT * FROM notes ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Insert
    suspend fun insert(note: Note): Long

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
