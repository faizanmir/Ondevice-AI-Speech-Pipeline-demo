package com.example.aiagenttestapp.di

import android.content.Context
import androidx.room.Room
import com.example.aiagenttestapp.data.audit.AuditDao
import com.example.aiagenttestapp.data.audit.AuditDatabase
import com.example.aiagenttestapp.data.audit.AuditQueue
import com.example.aiagenttestapp.data.chat.ChatDao
import com.example.aiagenttestapp.data.chat.ChatDatabase
import com.example.aiagenttestapp.data.notes.NoteDao
import com.example.aiagenttestapp.data.notes.NoteFindingDao
import com.example.aiagenttestapp.data.notes.NotesDatabase
import com.example.aiagenttestapp.data.notes.SpeakerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Three separate Room databases, deliberately. Audit, chat and notes each own their schema, so a
 * migration in one never forces a version bump on the others.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun auditDatabase(@ApplicationContext context: Context): AuditDatabase =
        Room.databaseBuilder(context, AuditDatabase::class.java, "audit.db")
            .addMigrations(
                AuditDatabase.MIGRATION_1_2,
                AuditDatabase.MIGRATION_2_3,
                AuditDatabase.MIGRATION_3_4,
                AuditDatabase.MIGRATION_4_5,
                AuditDatabase.MIGRATION_5_6,
            )
            .build()

    @Provides
    @Singleton
    fun auditDao(database: AuditDatabase): AuditDao = database.auditDao()

    /** The durable, per-document audit queue: chunks are checkpointed to Room so a run resumes. */
    @Provides
    @Singleton
    fun auditQueue(@ApplicationContext context: Context, dao: AuditDao) = AuditQueue(context, dao)

    @Provides
    @Singleton
    fun notesDatabase(@ApplicationContext context: Context): NotesDatabase =
        Room.databaseBuilder(context, NotesDatabase::class.java, "notes.db")
            .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3)
            .build()

    @Provides
    @Singleton
    fun noteDao(database: NotesDatabase): NoteDao = database.noteDao()

    @Provides
    @Singleton
    fun noteFindingDao(database: NotesDatabase): NoteFindingDao = database.noteFindingDao()

    @Provides
    @Singleton
    fun speakerDao(database: NotesDatabase): SpeakerDao = database.speakerDao()

    @Provides
    @Singleton
    fun chatDatabase(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db").build()

    @Provides
    @Singleton
    fun chatDao(database: ChatDatabase): ChatDao = database.chatDao()
}
