package com.notebudget.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.core.common.Clock
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.MediaFiles
import com.notebudget.app.domain.model.MediaRestoreResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.TrashedMedia
import com.notebudget.app.domain.usecase.MediaTrashUseCases
import kotlinx.coroutines.flow.flowOf
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.domain.usecase.TrashRetention
import com.notebudget.app.domain.usecase.TrashUseCases
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

/** One entry of the trash list. [daysLeft] is `null` when nothing expires (retention "never"). */
sealed interface TrashRow {
    val deletedAt: Long
    val daysLeft: Int?

    /** A folder with the notes that were trashed together with it (they are not listed separately). */
    data class FolderRow(
        val folder: Folder,
        val noteCount: Int,
        override val daysLeft: Int?,
        /** The titles of the notes that went with it, one a line: the search of the trash finds them too (D253). */
        val noteTitles: String = "",
    ) : TrashRow {
        override val deletedAt: Long get() = folder.deletedAt ?: 0L
    }

    /** A note trashed on its own; [folderInTrash] means it cannot go back to its folder as it is. */
    data class NoteRow(
        val note: Note,
        val folderName: String?,
        val folderInTrash: Boolean,
        override val daysLeft: Int?,
    ) : TrashRow {
        override val deletedAt: Long get() = note.deletedAt ?: 0L
    }

    /** A photo, video or recording taken out of its note (D156). */
    data class MediaRow(
        val media: TrashedMedia,
        /** The note's title now (or when the item was deleted, if the note is gone). */
        val noteTitle: String,
        val noteState: MediaNoteState,
        val fileExists: Boolean,
        override val daysLeft: Int?,
        /** The item's comment, for the search of the trash (D253). */
        val comment: String = "",
    ) : TrashRow {
        override val deletedAt: Long get() = media.deletedAt
    }
}

/** Where the note of a trashed media item is: it can take the item back only while it is [ACTIVE]. */
enum class MediaNoteState { ACTIVE, IN_TRASH, GONE }

data class TrashUiState(
    val rows: List<TrashRow> = emptyList(),
    /** Every folder and note in the trash, as [TrashUseCases.emptyTrash] would count them. */
    val itemCount: Int = 0,
    val retentionDays: Int = TrashRetention.DEFAULT_DAYS,
    /** Folders a note can be restored into. */
    val activeFolders: List<Folder> = emptyList(),
)

/** Dialogs live in the ViewModel so that they survive rotation. Only ids and display names are kept. */
sealed interface TrashDialog {
    data class PurgeNote(val noteId: String, val title: String) : TrashDialog
    data class PurgeFolder(val folderId: String, val name: String, val noteCount: Int) : TrashDialog
    data class PurgeMedia(val id: String, val title: String) : TrashDialog
    data class EmptyTrash(val itemCount: Int) : TrashDialog

    /** The note's own folder is in the trash: ask which active folder should get it. */
    data class PickFolder(val noteId: String) : TrashDialog
}

sealed interface TrashEvent {
    data class Restored(val name: String) : TrashEvent
    data class Purged(val name: String) : TrashEvent
    data object Emptied : TrashEvent
    data class Failed(val error: DomainError, val restoringFolder: Boolean = false) : TrashEvent
    data class MediaRestoreFailed(val result: MediaRestoreResult) : TrashEvent
}

class TrashViewModel(
    private val folderRepository: FolderRepository,
    private val noteRepository: NoteRepository,
    private val folderUseCases: FolderUseCases,
    private val noteUseCases: NoteUseCases,
    private val trashUseCases: TrashUseCases,
    retentionDays: Flow<Int>,
    private val clock: Clock = Clock.System,
    private val mediaTrash: MediaTrashUseCases? = null,
    private val mediaFiles: MediaFiles? = null,
) : ViewModel() {

    /** The trashed media items and the active notes (to tell where each item's note is). */
    private val mediaSide = combine(
        mediaTrash?.observe() ?: flowOf(emptyList()),
        noteRepository.observeAll(),
    ) { media, activeNotes -> media to activeNotes }

    val state: StateFlow<TrashUiState> = combine(
        folderRepository.observeTrashed(),
        noteRepository.observeTrashed(),
        folderRepository.observeAll(),
        retentionDays,
        mediaSide,
    ) { trashedFolders, trashedNotes, activeFolders, days, (media, activeNotes) ->
        val now = clock.now()
        val folderRows = trashedFolders.map { folder ->
            TrashRow.FolderRow(
                folder = folder,
                noteCount = trashedNotes.count { it.folderId == folder.id },
                daysLeft = TrashRetention.daysLeft(folder.deletedAt ?: now, now, days),
                noteTitles = trashedNotes.filter { it.folderId == folder.id }.joinToString("\n") { it.title },
            )
        }
        val namesById = (activeFolders + trashedFolders).associate { it.id to it.name }
        val noteRows = trashedNotes
            // A note trashed together with its folder is counted in the folder's row.
            .filterNot { note -> trashedFolders.any { it.id == note.folderId && it.deletedAt == note.deletedAt } }
            .map { note ->
                TrashRow.NoteRow(
                    note = note,
                    folderName = namesById[note.folderId],
                    folderInTrash = trashedFolders.any { it.id == note.folderId },
                    daysLeft = TrashRetention.daysLeft(note.deletedAt ?: now, now, days),
                )
            }
        val activeById = activeNotes.associateBy { it.id }
        val trashedById = trashedNotes.associateBy { it.id }
        val mediaRows = media.map { item ->
            val state = when {
                item.noteId in activeById -> MediaNoteState.ACTIVE
                item.noteId in trashedById -> MediaNoteState.IN_TRASH
                else -> MediaNoteState.GONE
            }
            TrashRow.MediaRow(
                media = item,
                noteTitle = (activeById[item.noteId] ?: trashedById[item.noteId])?.title ?: item.noteTitle,
                noteState = state,
                fileExists = mediaFiles?.exists(item.kind, item.id) ?: true,
                daysLeft = TrashRetention.daysLeft(item.deletedAt, now, days),
                comment = commentOf(item.itemJson),
            )
        }
        TrashUiState(
            rows = (folderRows + noteRows + mediaRows).sortedByDescending { it.deletedAt },
            itemCount = trashedFolders.size + trashedNotes.size + media.size,
            retentionDays = days,
            activeFolders = activeFolders,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

    private val _dialog = MutableStateFlow<TrashDialog?>(null)
    val dialog: StateFlow<TrashDialog?> = _dialog.asStateFlow()

    private val _events = Channel<TrashEvent>(Channel.BUFFERED)
    val events: Flow<TrashEvent> = _events.receiveAsFlow()

    fun dismissDialog() {
        _dialog.value = null
    }

    // ---- restore ----

    fun restoreNote(noteId: String) = viewModelScope.launch {
        val title = noteRepository.getByIdIncludingDeleted(noteId)?.title.orEmpty()
        when (val result = noteUseCases.restore(noteId)) {
            is DomainResult.Success -> _events.send(TrashEvent.Restored(title))
            is DomainResult.Failure ->
                if (result.error == DomainError.FOLDER_NOT_FOUND) _dialog.value = TrashDialog.PickFolder(noteId)
                else _events.send(TrashEvent.Failed(result.error))
        }
    }

    fun restoreNoteInto(noteId: String, folderId: String) = viewModelScope.launch {
        _dialog.value = null
        val title = noteRepository.getByIdIncludingDeleted(noteId)?.title.orEmpty()
        when (val result = noteUseCases.restoreInto(noteId, folderId)) {
            is DomainResult.Success -> _events.send(TrashEvent.Restored(title))
            is DomainResult.Failure -> _events.send(TrashEvent.Failed(result.error))
        }
    }

    fun restoreFolder(folderId: String) = viewModelScope.launch {
        val name = folderRepository.getByIdIncludingDeleted(folderId)?.name.orEmpty()
        when (val result = folderUseCases.restore(folderId)) {
            is DomainResult.Success -> _events.send(TrashEvent.Restored(name))
            is DomainResult.Failure -> _events.send(TrashEvent.Failed(result.error, restoringFolder = true))
        }
    }

    fun restoreMedia(row: TrashRow.MediaRow) = viewModelScope.launch {
        when (val result = mediaTrash?.restore(row.media.id)) {
            MediaRestoreResult.RESTORED -> _events.send(TrashEvent.Restored(row.media.title.ifBlank { row.noteTitle }))
            null -> Unit
            else -> _events.send(TrashEvent.MediaRestoreFailed(result))
        }
    }

    fun askPurgeMedia(row: TrashRow.MediaRow) {
        _dialog.value = TrashDialog.PurgeMedia(row.media.id, row.media.title.ifBlank { row.noteTitle })
    }

    // ---- permanent deletion: always confirmed first (INV-6, §1.3) ----

    fun askPurgeNote(note: Note) {
        _dialog.value = TrashDialog.PurgeNote(note.id, note.title)
    }

    fun askPurgeFolder(row: TrashRow.FolderRow) {
        _dialog.value = TrashDialog.PurgeFolder(row.folder.id, row.folder.name, row.noteCount)
    }

    fun askEmptyTrash() {
        val count = state.value.itemCount
        if (count > 0) _dialog.value = TrashDialog.EmptyTrash(count)
    }

    fun confirmDialog() {
        val dialog = _dialog.value ?: return
        _dialog.value = null
        viewModelScope.launch {
            when (dialog) {
                is TrashDialog.PurgeNote -> report(trashUseCases.purgeNote(dialog.noteId), dialog.title)
                is TrashDialog.PurgeFolder -> report(trashUseCases.purgeFolder(dialog.folderId), dialog.name)
                is TrashDialog.PurgeMedia -> {
                    mediaTrash?.purge(dialog.id)
                    _events.send(TrashEvent.Purged(dialog.title))
                }
                is TrashDialog.EmptyTrash -> {
                    trashUseCases.emptyTrash()
                    mediaTrash?.emptyAll()
                    _events.send(TrashEvent.Emptied)
                }
                is TrashDialog.PickFolder -> Unit // answered through restoreNoteInto
            }
        }
    }

    private suspend fun report(result: DomainResult<Unit>, name: String) {
        when (result) {
            is DomainResult.Success -> _events.send(TrashEvent.Purged(name))
            is DomainResult.Failure -> _events.send(TrashEvent.Failed(result.error))
        }
    }
}

/** The comment in the JSON of a trashed media item, or "" (D253). */
internal fun commentOf(itemJson: String): String = runCatching {
    val element = kotlinx.serialization.json.Json.parseToJsonElement(itemJson)
    ((element as? kotlinx.serialization.json.JsonObject)?.get("comment") as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content.orEmpty()
}.getOrDefault("")
