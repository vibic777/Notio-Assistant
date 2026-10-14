package com.notebudget.app.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Tag
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.usecase.TagUseCases
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A tag and how many notes have it. */
data class TagRow(val tag: Tag, val noteCount: Int)

sealed interface TagsDialog {
    /** A new tag, typed here, that no note has yet (D271). */
    data object Create : TagsDialog

    data class Rename(val tag: Tag) : TagsDialog

    /** Deleting a tag is confirmed first (INV-6): it disappears from every note that has it. */
    data class ConfirmDelete(val row: TagRow) : TagsDialog
}

sealed interface TagsEvent {
    data class Created(val name: String) : TagsEvent
    data class Renamed(val name: String) : TagsEvent
    data class Deleted(val name: String) : TagsEvent
    data class Failed(val error: DomainError) : TagsEvent
}

/** Manage tags (D109): see them all with their note counts, rename, delete. */
class TagsViewModel(
    tagRepository: TagRepository,
    private val useCases: TagUseCases,
) : ViewModel() {

    val rows: StateFlow<List<TagRow>> = combine(tagRepository.observeAll(), tagRepository.observeAssignments()) { tags, byNote ->
        tags.map { tag -> TagRow(tag, byNote.values.count { list -> list.any { it.id == tag.id } }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dialog = MutableStateFlow<TagsDialog?>(null)
    val dialog: StateFlow<TagsDialog?> = _dialog.asStateFlow()

    private val _events = Channel<TagsEvent>(Channel.BUFFERED)
    val events: Flow<TagsEvent> = _events.receiveAsFlow()

    fun askCreate() {
        _dialog.value = TagsDialog.Create
    }

    /** Makes the tag (D271); a name that is taken or empty keeps the dialog open with the reason. */
    fun create(name: String) = viewModelScope.launch {
        when (val result = useCases.create(name)) {
            is DomainResult.Success -> {
                _dialog.value = null
                _events.send(TagsEvent.Created(result.value.name))
            }
            is DomainResult.Failure -> _events.send(TagsEvent.Failed(result.error))
        }
    }

    fun askRename(tag: Tag) {
        _dialog.value = TagsDialog.Rename(tag)
    }

    fun askDelete(row: TagRow) {
        _dialog.value = TagsDialog.ConfirmDelete(row)
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun rename(tag: Tag, name: String) = viewModelScope.launch {
        when (val result = useCases.rename(tag.id, name)) {
            is DomainResult.Success -> {
                _dialog.value = null
                _events.send(TagsEvent.Renamed(result.value.name))
            }
            // The dialog stays open so the name can be fixed.
            is DomainResult.Failure -> _events.send(TagsEvent.Failed(result.error))
        }
    }

    fun confirmDelete() {
        val dialog = _dialog.value as? TagsDialog.ConfirmDelete ?: return
        _dialog.value = null
        viewModelScope.launch {
            when (val result = useCases.delete(dialog.row.tag.id)) {
                is DomainResult.Success -> _events.send(TagsEvent.Deleted(dialog.row.tag.name))
                is DomainResult.Failure -> _events.send(TagsEvent.Failed(result.error))
            }
        }
    }
}
