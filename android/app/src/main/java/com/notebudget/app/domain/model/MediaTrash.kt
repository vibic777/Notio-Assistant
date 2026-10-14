package com.notebudget.app.domain.model

import kotlinx.coroutines.flow.Flow

/**
 * A photo, video, screen recording or audio recording taken out of its note and put into the trash (D156). The file stays
 * where it was; [itemJson] is the item as it was in the note (a `MediaItem` or an `AudioClip`), [position] its place in the
 * list, [noteTitle] the name of the note at that moment (the note itself may be gone by the time it is looked at).
 * [id] is the key of the file (`<noteId>_<itemId>`, or the note id for the single recording of an old audio note).
 */
data class TrashedMedia(
    val id: String,
    val noteId: String,
    /** `PHOTO`, `VIDEO`, `SCREEN` (a [MediaKind] name) or `AUDIO`. */
    val kind: String,
    val title: String,
    val itemJson: String,
    val position: Int,
    val noteTitle: String,
    val deletedAt: Long,
    val sizeBytes: Long,
) {
    companion object {
        const val AUDIO = "AUDIO"
    }
}

interface MediaTrashRepository {
    fun observeAll(): Flow<List<TrashedMedia>>
    suspend fun getAll(): List<TrashedMedia>
    suspend fun get(id: String): TrashedMedia?
    suspend fun upsert(items: List<TrashedMedia>)
    suspend fun remove(ids: Collection<String>)
    suspend fun replaceAll(items: List<TrashedMedia>)
}

/** What the trash can do with the files of the items (implemented over the real stores; a fake in tests). */
interface MediaFiles {
    fun sizeOf(kind: String, key: String): Long
    fun exists(kind: String, key: String): Boolean
    suspend fun delete(kind: String, key: String)
}

/** Files the note purge and the "replace" import must leave alone: the ones that belong to items in the trash. */
interface TrashProtection {
    suspend fun keysOf(noteId: String): Set<String>
    suspend fun allKeys(): Set<String>
}

enum class MediaRestoreResult { RESTORED, NOTE_IN_TRASH, NOTE_GONE, FILE_MISSING, NOT_FOUND }
