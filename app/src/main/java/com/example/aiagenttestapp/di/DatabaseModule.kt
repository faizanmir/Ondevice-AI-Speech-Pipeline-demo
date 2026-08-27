package com.example.aiagenttestapp.di

import android.content.Context
import androidx.room.Room
import com.example.aiagenttestapp.data.audit.AuditDao
import com.example.aiagenttestapp.data.audit.AuditDatabase
import com.example.aiagenttestapp.data.audit.AuditQueue
import com.example.aiagenttestapp.data.benchmark.BenchmarkClipDao
import com.example.aiagenttestapp.data.benchmark.BenchmarkDatabase
import com.example.aiagenttestapp.data.benchmark.BenchmarkRunDao
import com.example.aiagenttestapp.data.chat.ChatDao
import com.example.aiagenttestapp.data.chat.ChatDatabase
import com.example.aiagenttestapp.data.notes.NoteDao
import com.example.aiagenttestapp.data.notes.NoteFindingDao
import com.example.aiagenttestapp.data.notes.NotesDatabase
import com.example.aiagenttestapp.data.speakers.DiarizedDao
import com.example.aiagenttestapp.data.speakers.SpeakerDao
import com.example.aiagenttestapp.data.speakers.SpeakerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Five separate Room databases, deliberately. Audit, chat, notes, benchmark and speakers each own
 * their schema, so a migration in one never forces a version bump on the others.
 *
 * Speakers is the newest and the one that proves the rule: its two tables used to sit in `notes.db`,
 * and they were deleted along with a rework of the notes schema they had nothing to do with.
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
                AuditDatabase.MIGRATION_6_7,
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
            .addMigrations(
                NotesDatabase.MIGRATION_1_2,
                NotesDatabase.MIGRATION_2_3,
                NotesDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    @Singleton
    fun noteDao(database: NotesDatabase): NoteDao = database.noteDao()

    @Provides
    @Singleton
    fun noteFindingDao(database: NotesDatabase): NoteFindingDao = database.noteFindingDao()

    @Provides
    @Singleton
    fun speakerDatabase(@ApplicationContext context: Context): SpeakerDatabase =
        Room.databaseBuilder(context, SpeakerDatabase::class.java, "speakers.db")
            .addMigrations(SpeakerDatabase.MIGRATION_1_2, SpeakerDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun speakerDao(database: SpeakerDatabase): SpeakerDao = database.speakerDao()

    @Provides
    fun diarizedDao(database: SpeakerDatabase): DiarizedDao = database.diarizedDao()

    @Provides
    @Singleton
    fun benchmarkDatabase(@ApplicationContext context: Context): BenchmarkDatabase =
        Room.databaseBuilder(context, BenchmarkDatabase::class.java, "benchmark.db")
            .addMigrations(BenchmarkDatabase.MIGRATION_1_2).build()

    @Provides
    @Singleton
    fun benchmarkClipDao(database: BenchmarkDatabase): BenchmarkClipDao = database.clipDao()

    @Provides
    @Singleton
    fun benchmarkRunDao(database: BenchmarkDatabase): BenchmarkRunDao = database.runDao()

    @Provides
    @Singleton
    fun chatDatabase(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db").build()

    @Provides
    @Singleton
    fun chatDao(database: ChatDatabase): ChatDao = database.chatDao()
}
