package com.notebudget.app.data.media

import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.usecase.NoteUseCases
import java.io.File

/**
 * Adds, changes and removes the items of a media note (D147). Used by the note screen and by the screen-recording service,
 * which may finish a recording when no screen of the app is open. The list of items is written into `note.extras` (and a
 * text copy of the names and comments into `contentText`, for search) after every change, and the file always goes in first,
 * so a note never lists an item whose file is missing.
 */
class MediaNoteWriter(
    private val notes: NoteRepository,
    private val noteUseCases: NoteUseCases,
    private val store: MediaStore,
    /** Where deleted items go (D156); without it they are deleted for good. */
    private val trash: com.notebudget.app.domain.usecase.MediaTrashUseCases? = null,
) {
    suspend fun document(noteId: String): MediaDocument = MediaDocument.parse(notes.getById(noteId)?.extras)

    private suspend fun persist(noteId: String, items: List<MediaItem>): Boolean {
        val note = notes.getById(noteId) ?: return false
        val document = MediaDocument(items = items)
        noteUseCases.save(note.copy(contentText = document.searchText(), extras = document.encode()), withExtras = true)
        return true
    }

    /**
     * Stores a finished recording ([source], moved into place) or picture ([bytes]) as a new item at the end of the list.
     * `false` when the note is gone or the file could not be stored (nothing is added then).
     */
    suspend fun add(noteId: String, kind: MediaKind, item: MediaItem, source: File?, bytes: ByteArray?, thumbnail: ByteArray?): Boolean {
        if (notes.getById(noteId) == null) return false
        val key = MediaDocument.fileKey(noteId, item.id)
        val stored = when {
            source != null -> store.commit(source, key, kind)
            bytes != null -> store.write(key, kind, bytes)
            else -> false
        }
        if (!stored) return false
        thumbnail?.let { store.writeThumb(key, it) }
        if (persist(noteId, document(noteId).items + item)) return true
        store.delete(key, kind)
        return false
    }

    /** Applies [edit] to the item [itemId] and stamps it as changed at [now]. */
    suspend fun change(noteId: String, itemId: String, now: Long, edit: (MediaItem) -> MediaItem) {
        val items = document(noteId).items
        if (items.none { it.id == itemId }) return
        persist(noteId, items.map { if (it.id == itemId) edit(it).copy(updatedAt = now) else it })
    }

    /** Takes items out of the note: into the trash, whose files stay (D156); without a trash they and their files are deleted. */
    suspend fun delete(noteId: String, kind: MediaKind, itemIds: Set<String>) {
        val items = document(noteId).items
        val gone = items.filter { it.id in itemIds }
        if (gone.isEmpty()) return
        if (trash != null) {
            val noteTitle = notes.getById(noteId)?.title.orEmpty()
            val json = kotlinx.serialization.json.Json { encodeDefaults = false }
            gone.forEach { item ->
                trash.stash(
                    noteId, kind.name, MediaDocument.fileKey(noteId, item.id), item.name,
                    json.encodeToString(MediaItem.serializer(), item), items.indexOf(item), noteTitle,
                )
            }
        }
        persist(noteId, items.filterNot { it.id in itemIds })
        if (trash == null) gone.forEach { store.delete(MediaDocument.fileKey(noteId, it.id), kind) }
    }
}
