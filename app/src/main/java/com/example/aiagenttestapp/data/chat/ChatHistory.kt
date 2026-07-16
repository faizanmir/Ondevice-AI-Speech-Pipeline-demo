package com.example.aiagenttestapp.data.chat

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.example.aiagent.engine.core.HistoryTurn
import kotlinx.coroutines.flow.Flow

/**
 * A persisted chat with one model.
 *
 * Kept in its own database, separate from notes.db: chats and notes share nothing but the Room
 * dependency, and a separate file means neither ever forces a migration on the other.
 */
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    /** Derived from the first user message, for a future history list. */
    val title: String,
    val createdAtMillis: Long,
    /** Bumped on every new message, so "the model's latest chat" is a cheap ORDER BY. */
    val updatedAtMillis: Long,
    /**
     * Rolling summary of the turns too old to keep verbatim. Empty until the summary-buffer step
     * fills it; present now so adding that never needs a schema migration.
     */
    val summary: String = "",
)

/**
 * One turn in a [Conversation]. [role] uses the same strings the chat templates and [HistoryTurn]
 * use ("user" / "assistant"), so a stored message maps to model context without translation.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class StoredMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAtMillis: Long,
)

/** A conversation with its messages, in one query. Messages come back unordered -- sort by id. */
data class ConversationWithMessages(
    @Embedded val conversation: Conversation,
    @Relation(parentColumn = "id", entityColumn = "conversationId")
    val messages: List<StoredMessage>,
)

fun StoredMessage.toHistoryTurn(): HistoryTurn = HistoryTurn(role, content)

@Dao
interface ChatDao {

    @Insert
    suspend fun insertConversation(conversation: Conversation): Long

    @Insert
    suspend fun insertMessage(message: StoredMessage): Long

    @Query("UPDATE conversations SET updatedAtMillis = :ts WHERE id = :id")
    suspend fun touchConversation(id: Long, ts: Long)

    @Query("UPDATE conversations SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    /** A specific conversation with its messages -- what reopening from the history list loads. */
    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversationById(id: Long): ConversationWithMessages?

    /** For a future "chat history" list -- newest first. */
    @Query("SELECT * FROM conversations ORDER BY updatedAtMillis DESC")
    fun observeConversations(): Flow<List<Conversation>>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)
}

@Database(entities = [Conversation::class, StoredMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
