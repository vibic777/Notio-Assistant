package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.domain.model.AudioClip
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaRestoreResult
import com.notebudget.app.domain.model.MediaFiles
import com.notebudget.app.domain.model.MediaTrashRepository
import com.notebudget.app.domain.model.TrashProtection
import com.notebudget.app.domain.model.TrashedMedia
import com.notebudget.app.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * The trash of photos, videos, screen recordings and audio recordings (D156). Deleting such an item takes it out of its note
 * but keeps its file; from the trash it can go back into the note (at its old place), be exported, or be deleted for good.
 * The trash outlives the note: when the note is deleted for good the items stay, and can then only be exported or deleted.
 * A backup carries the list of the trash but never its files, so after an import on the same phone an item can be restored
 * as long as its file is still there.
 */
class MediaTrashUseCases(
    private val trash: MediaTrashRepository,
    private val notes: NoteRepository,
    private val noteUseCases: NoteUseCases,
    private val files: MediaFiles,
    private val clock: Clock = Clock.System,
) : TrashProtection {

    fun observe(): Flow<List<TrashedMedia>> = trash.observeAll()

    suspend fun all(): List<TrashedMedia> = trash.getAll()

    /** Puts one item into the trash; the caller has taken it out of the note's list. */
    suspend fun stash(noteId: String, kind: String, key: String, title: String, itemJson: String, position: Int, noteTitle: String) {
        trash.upsert(
            listOf(
                TrashedMedia(
                    id = key, noteId = noteId, kind = kind, title = title, itemJson = itemJson, position = position,
                    noteTitle = noteTitle, deletedAt = clock.now(), sizeBytes = files.sizeOf(kind, key),
                ),
            ),
        )
    }

    /** Puts the item back into its note at its old place. */
    suspend fun restore(id: String): MediaRestoreResult {
        val row = trash.get(id) ?: return MediaRestoreResult.NOT_FOUND
        val note = notes.getById(row.noteId)
            ?: return if (notes.getByIdIncludingDeleted(row.noteId) != null) MediaRestoreResult.NOTE_IN_TRASH else MediaRestoreResult.NOTE_GONE
        if (!files.exists(row.kind, row.id)) return MediaRestoreResult.FILE_MISSING
        if (row.kind == TrashedMedia.AUDIO) {
            val doc = AudioDocument.forNote(note.extras, legacyFileExists = files.exists(TrashedMedia.AUDIO, note.id) && row.id != note.id)
            val clip = runCatching { JSON.decodeFromString(AudioClip.serializer(), row.itemJson) }.getOrNull() ?: return MediaRestoreResult.NOT_FOUND
            if (doc.clips.none { it.id == clip.id }) {
                val clips = doc.clips.toMutableList().also { it.add(row.position.coerceIn(0, it.size), clip) }
                val next = AudioDocument(clips = clips)
                noteUseCases.save(note.copy(contentText = next.searchText(), extras = next.encode()), withExtras = true)
            }
        } else {
            val doc = MediaDocument.parse(note.extras)
            val item = runCatching { JSON.decodeFromString(MediaItem.serializer(), row.itemJson) }.getOrNull() ?: return MediaRestoreResult.NOT_FOUND
            if (doc.items.none { it.id == item.id }) {
                val items = doc.items.toMutableList().also { it.add(row.position.coerceIn(0, it.size), item) }
                val next = MediaDocument(items = items)
                noteUseCases.save(note.copy(contentText = next.searchText(), extras = next.encode()), withExtras = true)
            }
        }
        trash.remove(listOf(id))
        return MediaRestoreResult.RESTORED
    }

    /** Deletes the item and its file for good. */
    suspend fun purge(id: String) {
        val row = trash.get(id) ?: return
        files.delete(row.kind, row.id)
        trash.remove(listOf(id))
    }

    /** Removes what has been in the trash longer than [retentionDays] (0 = never); returns how many. */
    suspend fun purgeExpired(retentionDays: Int): Int {
        val cutoff = TrashRetention.cutoff(clock.now(), retentionDays) ?: return 0
        val old = trash.getAll().filter { it.deletedAt <= cutoff }
        old.forEach { files.delete(it.kind, it.id) }
        trash.remove(old.map { it.id })
        return old.size
    }

    suspend fun emptyAll(): Int {
        val rows = trash.getAll()
        rows.forEach { files.delete(it.kind, it.id) }
        trash.remove(rows.map { it.id })
        return rows.size
    }

    // ---- backup (D156): the list travels, the files do not ----

    /** After a full restore: the list of the backup replaces the trash; files of items that are no longer listed go. */
    suspend fun replaceFromBackup(rows: List<TrashedMedia>) {
        val before = trash.getAll()
        trash.replaceAll(rows)
        val keep = rows.map { it.id }.toSet()
        before.filter { it.id !in keep }.forEach { files.delete(it.kind, it.id) }
    }

    /** After a merge: items of the backup that this phone does not have in its trash are added. */
    suspend fun mergeFromBackup(rows: List<TrashedMedia>) {
        val have = trash.getAll().map { it.id }.toSet()
        trash.upsert(rows.filter { it.id !in have })
    }

    override suspend fun keysOf(noteId: String): Set<String> = trash.getAll().filter { it.noteId == noteId }.map { it.id }.toSet()

    override suspend fun allKeys(): Set<String> = trash.getAll().map { it.id }.toSet()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
