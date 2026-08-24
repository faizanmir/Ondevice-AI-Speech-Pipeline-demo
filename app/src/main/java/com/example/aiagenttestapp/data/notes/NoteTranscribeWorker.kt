package com.example.aiagenttestapp.data.notes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aiagenttestapp.stt.AudioRecorder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.math.absoluteValue

/**
 * Transcribes one recording in a foreground job: find the speech, slice it, transcribe each slice.
 *
 * Why a worker rather than the ViewModel that used to do this. Transcribing a meeting-length
 * recording is minutes of work, and in a ViewModel all of it died the moment the user left the
 * screen -- on a long walkthrough, quite possibly every time. It also had to hold the whole recording
 * on the heap, ~115 MB for half an hour, doubled while copying it. Here the audio is read back from a
 * file and each stage is checkpointed, so a process death costs the current slice rather than the
 * entire run.
 *
 * The note row exists before this starts and is updated as it goes, which is what lets the user leave,
 * come back, and find either a finished transcript or an honest error.
 */
@HiltWorker
class NoteTranscribeWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val noteDao: NoteDao,
    /** The whole transcription lives here, shared with the benchmark runner -- see its KDoc. */
    private val transcriptionRun: TranscriptionRun,
) : CoroutineWorker(context, params) {

    /** WorkManager asks for this before `doWork` in some paths, so it must not depend on any state. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getLong(KEY_NOTE_ID, 0L), 0f)

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        if (noteId < 0) return Result.failure()

        val note = noteDao.byId(noteId) ?: return Result.success() // deleted while queued
        val audio = note.audioPath?.let(::File)

        if (audio == null || !audio.exists()) {
            noteDao.failTranscription(noteId, "The recording is no longer available.")
            return Result.success()
        }

        val checkpoint = TranscriptionCheckpoint.forAudio(audio).apply { load() }

        // From the checkpoint beside the audio, not from this job's input data: a re-enqueued job (see
        // [reconcileOrphans]) has no input data of its own, and losing the markers would silently
        // produce an untagged transcript.
        val request = checkpoint.requestOrNull()
        val markers = request?.markers.orEmpty()
        val excluded = request?.excludedRanges.orEmpty()
        val backend = request?.sttBackend ?: SttBackend.DEFAULT

        // Android 12+ can refuse a foreground start when the app is in the background. Refusing to
        // transcribe over it would be the wrong call -- the work is still worth doing, it just loses the
        // notification and the process-priority boost.
        runCatching { setForeground(foregroundInfo(noteId, 0f)) }

        return try {
            val outcome = transcriptionRun.transcribe(
                audio = audio,
                markers = markers,
                excluded = excluded,
                backend = backend,
                preferredModelId = request?.sttModelId,
                checkpoint = checkpoint,
            ) { progress ->
                noteDao.updateProgress(noteId, progress)
                runCatching { setForeground(foregroundInfo(noteId, progress)) }
            }

            when (outcome) {
                is TranscriptionRun.Outcome.Done -> {
                    noteDao.finishTranscription(
                        id = noteId,
                        transcript = outcome.transcript,
                        durationMillis = outcome.durationMillis,
                        language = outcome.language,
                    )
                    checkpoint.delete()
                    audio.delete()
                }

                is TranscriptionRun.Outcome.NothingRecognised -> {
                    // The audio and checkpoint deliberately survive this branch. It used to fall
                    // through to the deletes above, which destroyed the very file "Try again"
                    // needs: the button still showed (the note row keeps its audioPath), and every
                    // retry then failed with "the recording is no longer available".
                    noteDao.failTranscription(noteId, outcome.message)
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled, not failed. The checkpoint and the audio stay so a retry resumes.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "transcription failed for note $noteId", e)
            noteDao.failTranscription(
                noteId,
                e.message ?: "Could not transcribe the recording.",
            )
            // Success: the *work* is finished and must not be retried, and the note carries the error.
            Result.success()
        }
    }

    private fun foregroundInfo(noteId: Long, progress: Float): ForegroundInfo {
        val channelId = ensureChannel()
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notifId = NOTIF_BASE + (noteId.hashCode().absoluteValue % 1_000)

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Transcribing voice note")
            .setContentText(if (progress > 0f) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, percent, /* indeterminate = */ progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Voice note transcription",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "NoteTranscribeWorker"
        private const val CHANNEL_ID = "note_transcription"

        /** Distinct from the download workers' bases (4200, 5300, 6400). */
        private const val NOTIF_BASE = 7500

        internal const val KEY_NOTE_ID = "noteId"

        const val WORK_TAG = "note-transcription"

        fun uniqueName(noteId: Long) = "note-transcription:$noteId"

        /**
         * Enqueues transcription for a note whose audio -- and checkpoint request -- are already on disk.
         *
         * Only the note id travels in the input data. Everything else is read from the checkpoint file,
         * which is what makes re-enqueueing after WorkManager has lost the job harmless.
         */
        fun enqueue(context: Context, noteId: Long) {
            val request = OneTimeWorkRequestBuilder<NoteTranscribeWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                .addTag(WORK_TAG)
                .addTag(uniqueName(noteId))
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(noteId), ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Deals with notes left mid-transcription by a process that died without a job to resume them.
         *
         * A row stuck on "Transcribing…" with nothing running is worse than an error, because the user
         * cannot tell the difference or act on it. Where the audio survives, the job is simply enqueued
         * again -- the checkpoint means it picks up where it stopped rather than starting over. Where it
         * does not, the note is marked failed so at least it says so.
         */
        /**
         * The directory recordings are captured into, under the app's cache.
         *
         * Named here as well as in the recorder because the sweep has to look in it without the
         * recorder existing -- after a process death there is no ViewModel to ask.
         */
        fun audioDir(cacheDir: File) = File(cacheDir, "note-audio")

        /**
         * How recently a file may have been written and still be treated as finished.
         *
         * The one real hazard in this sweep is adopting a recording that is *currently being made*:
         * audio streams to disk from the moment the user presses record, but the note row is only
         * inserted when they press stop, so a live recording looks exactly like an orphan. A file
         * still being appended to has a fresh mtime, and the writer's buffer flushes every couple of
         * seconds, so a minute of quiet is a wide margin. A recording interrupted inside this window
         * is simply picked up by the next sweep instead.
         */
        private const val SETTLED_MILLIS = 60_000L

        /**
         * Adopts recordings that no note points at, so a process death costs a name rather than the
         * audio.
         *
         * Recordings stream to disk as they are captured, but the note row that owns one is written
         * only when the user stops. Anything that kills the app in between -- a crash, a low-memory
         * kill, the user swiping it away -- therefore used to leave a complete WAV on disk that
         * nothing referenced and nothing would ever read again. This gives those files a note and
         * queues them, which is the whole point of having written them out in the first place.
         *
         * Deliberately conservative about what it deletes: only a file with no audio in it at all,
         * and a `.progress` checkpoint whose recording is already gone. Anything else is somebody's
         * walkthrough.
         */
        suspend fun recoverOrphanedAudio(context: Context, noteDao: NoteDao, cacheDir: File) {
            val files = audioDir(cacheDir).listFiles()?.toList() ?: return

            val plan = planOrphanSweep(
                files = files,
                referenced = noteDao.allAudioPaths().toSet(),
                now = System.currentTimeMillis(),
                sampleCountOf = WavFile::sampleCount,
            )

            plan.delete.forEach { file ->
                file.delete()
                if (file.name.endsWith(".wav")) TranscriptionCheckpoint.forAudio(file).delete()
            }

            for (audio in plan.adopt) {
                val samples = WavFile.sampleCount(audio)
                // The backend comes from the checkpoint the recorder writes when recording starts.
                // Without it this would fall back to ONNX and a Gemma-only device would end up with
                // a note it can never transcribe.
                val request = TranscriptionCheckpoint.forAudio(audio).apply { load() }.requestOrNull()

                val noteId = noteDao.insert(
                    Note(
                        title = RECOVERED_TITLE,
                        transcript = "",
                        summary = "",
                        createdAtMillis = audio.lastModified(),
                        summarisedBy = "none",
                        durationMillis = samples * 1000L / AudioRecorder.SAMPLE_RATE,
                        status = NoteStatus.Transcribing,
                        audioPath = audio.absolutePath,
                    ),
                )

                Log.i(
                    TAG,
                    "recovered an orphaned recording: ${audio.name}, " +
                        "${samples / AudioRecorder.SAMPLE_RATE}s, backend=${request?.sttBackend}",
                )
                enqueue(context, noteId)
            }
        }

        /** What a sweep decided to do, separated from doing it so the rules can be tested. */
        internal data class OrphanSweep(
            /** Recordings to give a note and queue. */
            val adopt: List<File> = emptyList(),
            /** Files worth removing: empty recordings, and checkpoints with no recording left. */
            val delete: List<File> = emptyList(),
        )

        /**
         * Decides what to adopt and what to bin. Pure, because every rule here is a judgement about
         * an ambiguous file on disk and those are miserable to reproduce on a device.
         *
         * The rule that matters most is the one that does nothing: a file written within
         * [SETTLED_MILLIS] is assumed to be a recording still in progress and is left alone.
         * Adopting one would hand the user's live recording to a transcription worker while the
         * recorder was still appending to it.
         */
        internal fun planOrphanSweep(
            files: List<File>,
            referenced: Set<String>,
            now: Long,
            sampleCountOf: (File) -> Int,
        ): OrphanSweep {
            val names = files.map { it.name }.toSet()

            // A checkpoint whose audio has gone is dead weight: the worker only ever opens one by
            // its recording's name, so nothing will read it again.
            val strayCheckpoints = files.filter {
                it.name.endsWith(".progress") && it.name.removeSuffix(".progress") !in names
            }

            val orphans = files
                .filter { it.name.endsWith(".wav") }
                .filter { it.absolutePath !in referenced }
                .filter { now - it.lastModified() >= SETTLED_MILLIS }

            // Nothing was ever captured -- a recording that died before its first chunk landed.
            val (empty, real) = orphans.partition { sampleCountOf(it) <= 0 }

            return OrphanSweep(adopt = real, delete = strayCheckpoints + empty)
        }

        /**
         * Title for a note the sweep rescued.
         *
         * Distinct from the recorder's "Voice note" on purpose: the user never pressed stop on this
         * one, so finding it in the list is a surprise, and the title is the only place to explain
         * why it is there.
         */
        const val RECOVERED_TITLE = "Recovered recording"

        suspend fun reconcileOrphans(context: Context, noteDao: NoteDao) {
            val stuck = noteDao.withStatus(NoteStatus.Transcribing)
            if (stuck.isEmpty()) return

            val workManager = WorkManager.getInstance(context.applicationContext)

            for (note in stuck) {
                val live = runCatching {
                    workManager.getWorkInfosForUniqueWorkFlow(uniqueName(note.id))
                        .first()
                        .any { info -> !info.state.isFinished }
                }.getOrDefault(false)

                if (live) continue

                val audio = note.audioPath?.let(::File)
                if (audio != null && audio.exists()) {
                    Log.i(TAG, "re-enqueueing orphaned transcription for note ${note.id}")
                    enqueue(context, note.id)
                } else {
                    noteDao.failTranscription(
                        note.id,
                        "Transcription was interrupted and the recording is gone.",
                    )
                }
            }
        }
    }
}
