package com.notio.desktop

import androidx.compose.runtime.Composable
import com.notio.shared.data.AttachmentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/*
 * The small Android pieces the phone's drawing screen stands on (AGENT.md D26), for the desktop: the picture and the shapes of a
 * note kept as the backup names them (`drawing.png`, `vector.json`), a ViewModel with its scope, the note "touch".
 */

/** The raster layer of a drawing note: its PNG (the phone's DrawingStore, files/drawings/<id>.png). */
class DrawingStore(private val files: AttachmentStore) {
    /** `null` also for an id that could point outside the folder (never read). */
    suspend fun read(noteId: String): ByteArray? = withContext(Dispatchers.IO) { runCatching { files.file(noteId, NAME)?.readBytes() }.getOrNull() }

    /** Written to a temporary file first; an id that could point outside the folder is refused (nothing written). */
    suspend fun write(noteId: String, bytes: ByteArray) = withContext(Dispatchers.IO) { runCatching { files.write(noteId, NAME, bytes.inputStream()) }; Unit }

    suspend fun delete(noteId: String) = withContext(Dispatchers.IO) { runCatching { files.file(noteId, NAME)?.delete() }; Unit }

    private companion object {
        const val NAME = "drawing.png"
    }
}

/** The vector layer (shapes on layers) of a drawing or blueprint note (the phone's VectorStore). */
class VectorStore(private val files: AttachmentStore) {
    suspend fun read(noteId: String): ByteArray? = withContext(Dispatchers.IO) { files.file(noteId, "vector.json")?.readBytes() }

    suspend fun write(noteId: String, bytes: ByteArray) = withContext(Dispatchers.IO) { files.write(noteId, "vector.json", bytes.inputStream()); Unit }
}

/** What a drawing needs from the note rules: a change of its picture moves "modified" (the phone's NoteUseCases.touch). */
class NoteUseCases(
    private val onTouch: suspend (String) -> Unit,
    /** Saves a note's text and, with `withExtras`, its own JSON (the phone's `NoteUseCases.save`). */
    private val onSave: suspend (com.notio.shared.domain.Note, Boolean) -> Unit = { _, _ -> },
) {
    suspend fun touch(noteId: String) = onTouch(noteId)

    suspend fun save(note: com.notio.shared.domain.Note, withExtras: Boolean = false) = onSave(note, withExtras)
}

/** Reads a note (the phone's `NoteRepository.getById`, only what the ported screens need). */
fun interface NoteLookup {
    suspend fun getById(id: String): com.notio.shared.domain.Note?
}

/** The kinds of the media trash (Android D156). */
object TrashedMedia {
    const val AUDIO = "AUDIO"
}

/**
 * The trash of recordings, photos and videos (Android `MediaTrashUseCases`, D156): an item taken out of its note keeps its file and
 * gets a row in `media_trash` (the backup carries the list); from the trash it goes back into its note or is deleted for good.
 */
class MediaTrash(private val stashRow: suspend (com.notio.shared.transfer.MediaTrashRecord) -> Unit, private val sizeOf: (String, String) -> Long) {
    suspend fun stash(noteId: String, kind: String, key: String, title: String, itemJson: String, position: Int, noteTitle: String) {
        stashRow(
            com.notio.shared.transfer.MediaTrashRecord(
                id = key, noteId = noteId, kind = kind, title = title, itemJson = itemJson, position = position, noteTitle = noteTitle,
                deletedAt = System.currentTimeMillis(), sizeBytes = sizeOf(kind, key),
            ),
        )
    }
}

/** The phone's ViewModel: a scope that ends with the screen ([clear]). */
open class ViewModel {
    val viewModelScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    protected open fun onCleared() {}

    fun clear() {
        onCleared()
        viewModelScope.cancel()
    }
}


