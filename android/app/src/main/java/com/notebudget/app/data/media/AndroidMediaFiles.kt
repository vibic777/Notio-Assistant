package com.notebudget.app.data.media

import com.notebudget.app.data.drawing.AudioStore
import com.notebudget.app.domain.model.MediaFiles
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.TrashedMedia
import java.io.File

/** The files of trashed items, over the media store and the audio store. */
class AndroidMediaFiles(private val media: MediaStore, private val audio: AudioStore) : MediaFiles {
    private fun file(kind: String, key: String): File? =
        if (kind == TrashedMedia.AUDIO) audio.fileOf(key) else runCatching { media.file(key, MediaKind.valueOf(kind)) }.getOrNull()

    override fun sizeOf(kind: String, key: String): Long = file(kind, key)?.takeIf { it.isFile }?.length() ?: 0L

    override fun exists(kind: String, key: String): Boolean = file(kind, key)?.let { it.isFile && it.length() > 0 } ?: false

    override suspend fun delete(kind: String, key: String) {
        if (kind == TrashedMedia.AUDIO) audio.delete(key) else runCatching { media.delete(key, MediaKind.valueOf(kind)) }
    }

    /** The file itself, for showing or exporting. */
    fun fileFor(kind: String, key: String): File? = file(kind, key)?.takeIf { it.isFile }
}
