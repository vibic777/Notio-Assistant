package com.notebudget.app.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.data.media.MediaNoteWriter
import com.notebudget.app.data.media.MediaStore
import com.notebudget.app.domain.model.ExportNaming
import com.notebudget.app.domain.model.FormatPreferences
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.repository.NoteRepository
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MediaUiState(val loaded: Boolean = false, val items: List<MediaItem> = emptyList())

/** A file ready to be saved to a place the person picks: the file itself (never read whole into memory) and its name. */
class MediaExport(val file: File, val fileName: String)

/**
 * One photo, camera-video or screen-recording note (D147): the list of items, taken from the note itself so it also follows
 * what the screen recorder service adds while this screen is open; renaming, commenting, deleting and exporting.
 */
class MediaViewModel(
    private val noteId: String,
    val kind: MediaKind,
    private val notes: NoteRepository,
    private val writer: MediaNoteWriter,
    val store: MediaStore,
    private val formats: FormatPreferences? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val defaultName: (Int) -> String = { "Item $it" },
) : ViewModel() {

    val state: StateFlow<MediaUiState> = notes.observeById(noteId)
        .map { MediaUiState(loaded = true, items = MediaDocument.parse(it?.extras).items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaUiState())

    fun key(item: MediaItem) = MediaDocument.fileKey(noteId, item.id)

    fun file(item: MediaItem): File? = store.existing(key(item), kind)

    fun edit(itemId: String, name: String, comment: String) {
        // Finished even when the screen goes away in the middle of it (D294).
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            writer.change(noteId, itemId, nowMillis()) { it.copy(name = name.trim().take(MAX_NAME), comment = comment.trim().take(MAX_COMMENT)) }
        }
    }

    fun deleteMany(ids: Set<String>) {
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) { writer.delete(noteId, kind, ids) }
    }

    /** The item's file with the name the export settings ask for (D139); `null` when the file is gone. */
    suspend fun prepareExport(item: MediaItem): MediaExport? {
        val file = file(item) ?: return null
        val note = notes.getById(noteId)
        val naming = formats?.exportNaming?.first() ?: ExportNaming()
        val number = state.value.items.indexOf(item) + 1
        val title = item.name.ifBlank { defaultName(number) }
        val base = naming.baseName(
            note?.title.orEmpty(), title, note?.createdAt ?: item.createdAt, maxOf(item.updatedAt, item.createdAt), nowMillis(),
            fallback = kind.name.lowercase(),
        )
        return MediaExport(file, base + "." + kind.extension)
    }

    companion object {
        const val MAX_NAME = 80
        const val MAX_COMMENT = 2000
    }
}
