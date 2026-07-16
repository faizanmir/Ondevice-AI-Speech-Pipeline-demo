package com.example.aiagenttestapp.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiagenttestapp.AppContainer
import com.example.aiagenttestapp.data.notes.Note
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(private val container: AppContainer) : ViewModel() {

    val notes: StateFlow<List<Note>> = container.noteDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { container.noteDao.delete(id) }
    }
}
