package com.notebudget.app.feature.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.core.common.UndoHistory
import com.notebudget.app.domain.usecase.NoteUseCases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteEditorState(
    /** `false` until the note is loaded; [note] stays `null` if it does not exist. */
    val loaded: Boolean = false,
    val note: Note? = null,
    val title: String = "",
    val content: String = "",
    /** The type-specific JSON of the note (a LIST keeps its structure here). */
    val extras: String? = null,
    val error: DomainError? = null,
)

/**
 * Edits the title and text of one note. Changes are saved automatically shortly after the
 * last edit and on demand ([saveNow]). A blank title is not saved and shows an error.
 *
 * The text and the structure (a list, a schedule) can be undone and redone (D197): changes that come quickly one after
 * another (typing a word) are one step; a pause of [coalesceMillis] starts the next. The title is not part of it.
 */
class NoteEditorViewModel(
    private val noteId: String,
    private val notes: NoteRepository,
    private val noteUseCases: NoteUseCases,
    private val autosaveDelayMillis: Long = 500,
    /** How many steps back can be undone (Settings); follows the setting while the note is open. */
    undoSteps: Flow<Int> = flowOf(UndoHistory.DEFAULT_STEPS),
    private val now: () -> Long = System::currentTimeMillis,
    private val coalesceMillis: Long = 1_000,
    /** Outlives the ViewModel: what is not saved yet when the screen goes away is saved there (D294). `null` in some tests. */
    private val appScope: CoroutineScope? = null,
) : ViewModel(), UndoTarget {

    /** Text and structure together: what one undo step brings back. */
    private data class Snapshot(val content: String, val extras: String?)

    private val history = UndoHistory<Snapshot>()
    private var lastEditAt = Long.MIN_VALUE / 2

    override var canUndo by mutableStateOf(false)
        private set
    override var canRedo by mutableStateOf(false)
        private set

    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    private var saveJob: Job? = null

    /**
     * What the editor last loaded or saved of the text and the structure. Compared with the current values to
     * see what the USER changed: another screen (the audio list) may have changed the stored note meanwhile,
     * and that must not be overwritten with the editor's older copy.
     */
    private var savedContent = ""
    private var savedExtras: String? = null

    init {
        viewModelScope.launch { undoSteps.collect { history.limit = it; syncUndo() } }
        viewModelScope.launch {
            val note = notes.getById(noteId)
            savedContent = note?.contentText.orEmpty()
            savedExtras = note?.extras
            _state.value = NoteEditorState(
                loaded = true,
                note = note,
                title = note?.title.orEmpty(),
                content = note?.contentText.orEmpty(),
                extras = note?.extras,
            )
        }
    }

    fun editTitle(title: String) = edit { it.copy(title = title) }

    fun editContent(content: String) = editBody(content, _state.value.extras)

    /** For kinds whose text and structure change together (LIST): one state change, one save. */
    fun editContentAndExtras(content: String, extras: String?) = editBody(content, extras)

    /** A change of the text or the structure: remembered for undo, then saved like any edit. */
    private fun editBody(content: String, extras: String?) {
        val current = _state.value
        if (current.note == null || (current.content == content && current.extras == extras)) return
        val at = now()
        if (at - lastEditAt >= coalesceMillis) history.record(Snapshot(current.content, current.extras))
        lastEditAt = at
        syncUndo()
        edit { it.copy(content = content, extras = extras) }
    }

    override fun undo() {
        val current = _state.value
        val previous = history.undo(Snapshot(current.content, current.extras)) ?: return
        restore(previous)
    }

    override fun redo() {
        val current = _state.value
        val next = history.redo(Snapshot(current.content, current.extras)) ?: return
        restore(next)
    }

    override fun endStep() {
        lastEditAt = Long.MIN_VALUE / 2
    }

    private fun restore(snapshot: Snapshot) {
        // The next change after an undo or redo is a step of its own, however soon it comes.
        lastEditAt = Long.MIN_VALUE / 2
        syncUndo()
        edit { it.copy(content = snapshot.content, extras = snapshot.extras) }
    }

    private fun syncUndo() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun edit(change: (NoteEditorState) -> NoteEditorState) {
        if (_state.value.note == null) return
        _state.update { change(it).copy(error = null) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(autosaveDelayMillis)
            // The wait may be cut short by the next keystroke; a save that has started is never cut in half (D294).
            withContext(NonCancellable) { persist() }
        }
    }

    /** Saves immediately (back button, app going to the background), then runs [afterSave]. */
    fun saveNow(afterSave: () -> Unit = {}) {
        saveJob?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) { persist() }
            afterSave()
        }
    }

    /** The option "hide the content" of the note (D363), from the crossed eye in Edit. */
    fun setHidden(hidden: Boolean) {
        viewModelScope.launch { noteUseCases.setHidden(noteId, hidden) }
    }

    /** The note to the trash from its own screen (D361: the bin in View of a task): what is typed is saved first. */
    fun deleteNote(afterDelete: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val gone = withContext(NonCancellable) { persist(); noteUseCases.delete(noteId) }
            if (gone is DomainResult.Success) afterDelete()
        }
    }

    /** Leaving the screen some other way than Back (the system took the screen away): what is not saved yet goes now (D294). */
    override fun onCleared() {
        val scope = appScope ?: return
        val current = _state.value
        if (current.note == null) return
        if (current.content == savedContent && current.extras == savedExtras && current.title.trim() == current.note.title) return
        scope.launch { persist() }
    }

    private suspend fun persist() {
        val current = _state.value
        val note = current.note ?: return
        // A blank title is never stored: keep the previous one, but still save the text.
        val titleBlank = current.title.isBlank()
        val title = if (titleBlank) note.title else current.title.trim()
        val contentChanged = current.content != savedContent
        val extrasChanged = current.extras != savedExtras
        if (title == note.title && !contentChanged && !extrasChanged) {
            if (titleBlank) _state.update { it.copy(error = DomainError.BLANK_TITLE) }
            return
        }
        val latest = notes.getById(noteId) ?: note
        val edited = latest.copy(
            title = title,
            contentText = if (contentChanged) current.content else latest.contentText,
            extras = if (extrasChanged) current.extras else latest.extras,
        )
        when (val result = noteUseCases.save(edited, withExtras = extrasChanged)) {
            is DomainResult.Success -> {
                savedContent = current.content
                savedExtras = current.extras
                _state.update { it.copy(note = result.value, error = if (titleBlank) DomainError.BLANK_TITLE else null) }
            }
            is DomainResult.Failure -> _state.update { it.copy(error = result.error) }
        }
    }
}
