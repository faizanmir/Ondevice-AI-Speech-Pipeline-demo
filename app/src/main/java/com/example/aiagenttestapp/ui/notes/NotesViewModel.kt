package com.example.aiagenttestapp.ui.notes

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.data.notes.Note
import com.example.aiagenttestapp.data.notes.NoteFinding
import com.example.aiagenttestapp.data.notes.NoteTranscribeWorker
import com.example.aiagenttestapp.ui.mvi.MviViewModel
import com.example.aiagenttestapp.ui.mvi.UiIntent
import com.example.aiagenttestapp.ui.mvi.UiState
import kotlinx.coroutines.launch
import com.example.aiagenttestapp.data.notes.NoteDao
import com.example.aiagenttestapp.data.notes.NoteFindingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class NotesUiState(
    val notes: List<Note> = emptyList(),
    /**
     * Findings per note id.
     *
     * Observed as one stream and grouped here rather than queried per note: a list of twenty notes
     * would otherwise open twenty flows, and the whole findings table is a few hundred short rows.
     */
    val findings: Map<Long, List<NoteFinding>> = emptyMap(),
) : UiState

sealed interface NotesIntent : UiIntent {
    data class Delete(val id: Long) : NotesIntent
}

@HiltViewModel
class NotesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val noteFindingDao: NoteFindingDao,
) : MviViewModel<NotesUiState, NotesIntent, Nothing>(NotesUiState()) {

    init {
        noteDao.observeAll().collectIntoState { notes -> copy(notes = notes) }
        noteFindingDao.observeAll().collectIntoState { findings ->
            copy(findings = findings.groupBy { it.noteId })
        }

        // This list is where a note stranded mid-transcription would be seen, so it is where the app
        // owes the user an answer about it: resumed if the audio is still there, marked failed if not.
        // Either way, never left spinning against nothing.
        viewModelScope.launch {
            runCatching { NoteTranscribeWorker.reconcileOrphans(context, noteDao) }
        }
    }

    override fun reduce(intent: NotesIntent) = when (intent) {
        is NotesIntent.Delete -> delete(intent.id)
    }

    /**
     * Deletes a note, and the captured audio with it.
     *
     * A note discarded while it was still being transcribed still has its WAV on disk -- up to a
     * hundred megabytes of it -- and nothing else points at that file once the row is gone. Findings
     * are removed by the foreign key's cascade.
     */
    private fun delete(id: Long) {
        viewModelScope.launch {
            noteDao.byId(id)?.audioPath?.let { path ->
                runCatching { File(path).delete() }
            }
            noteDao.delete(id)
        }
    }
}
