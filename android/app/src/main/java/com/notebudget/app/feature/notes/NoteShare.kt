package com.notebudget.app.feature.notes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.notebudget.app.R
import com.notebudget.app.core.common.AppContainer
import com.notebudget.app.domain.model.AudioDocument
import com.notebudget.app.domain.model.MediaDocument
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * "Share" of a note (D216): the phone's own share sheet, which lists every app that takes what is shared — Telegram, WhatsApp,
 * MAX, imo, mail, and so on — so no app is built in. What goes out depends on the kind:
 *  - text, Word, list, budget, schedule: the title and the note as plain text;
 *  - drawing, blueprint: the picture as PNG;
 *  - photo, camera video, screen recording, audio: the files of all its items, as they are (nothing is copied or re-encoded).
 * Files leave only through a temporary read permission of the app's FileProvider, for this one share.
 */
object NoteShare {

    /** What to hand to the share sheet, or `null` when the note has nothing to share (an empty media note). */
    suspend fun intentFor(context: Context, container: AppContainer, note: Note): Intent? = withContext(Dispatchers.IO) {
        val media = MediaKind.of(note.type)
        when {
            media != null -> files(
                context,
                MediaDocument.parse(note.extras).items.mapNotNull { container.mediaStore.existing(MediaDocument.fileKey(note.id, it.id), media) },
                media.mime, note.title,
            )
            note.type == NoteType.AUDIO -> {
                val doc = AudioDocument.forNote(note.extras, container.audioStore.fileIfExists(note.id) != null)
                files(context, doc.clips.mapNotNull { container.audioStore.fileIfExists(AudioDocument.fileKey(note.id, it.id)) }, "audio/mp4", note.title)
            }
            note.type == NoteType.DRAWING || note.type == NoteType.BLUEPRINT -> {
                val format = container.noteFileService.formatsFor(note).firstOrNull { it.key == "png" } ?: return@withContext null
                val exported = container.noteFileService.export(note, format)
                val file = File(sharedDir(context), exported.fileName).apply { writeBytes(exported.bytes) }
                files(context, listOf(file), exported.mimeType, note.title)
            }
            else -> {
                // Plain text travels best: every messenger shows it as a message.
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, note.title)
                    putExtra(Intent.EXTRA_TEXT, textOf(container, note))
                }
            }
        }
    }

    /**
     * "Copy" of a note (D362): what "Share" hands over goes to the clipboard instead - the title and the note as plain text, or
     * the picture of a drawing / blueprint as PNG (through the FileProvider; the file waits in the cache until the next copy).
     * `false` - nothing to copy.
     */
    suspend fun copy(context: Context, container: AppContainer, note: Note): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (note.type == NoteType.DRAWING || note.type == NoteType.BLUEPRINT) {
                val format = container.noteFileService.formatsFor(note).firstOrNull { it.key == "png" } ?: return@runCatching false
                val exported = container.noteFileService.export(note, format)
                val dir = File(context.cacheDir, "shared/clipboard").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, exported.fileName).apply { writeBytes(exported.bytes) }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val clip = ClipData.newUri(context.contentResolver, note.title, uri)
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip) ?: return@runCatching false
            } else {
                com.notebudget.app.core.ui.copyText(context, note.title, textOf(container, note))
            }
            true
        }.getOrDefault(false)
    }

    /** [copy] for a note known by its id (the View of a drawing). */
    suspend fun copyById(context: Context, container: AppContainer, noteId: String): Boolean {
        val note = container.noteRepository.observeById(noteId).first() ?: return false
        return copy(context, container, note)
    }

    /** The title and the note as plain text: what a messenger shows as a message. */
    private suspend fun textOf(container: AppContainer, note: Note): String {
        val formats = container.noteFileService.formatsFor(note)
        val format = formats.firstOrNull { it.key == "txt" } ?: formats.firstOrNull { it.mimeType == "text/plain" }
        val body = format?.let { container.noteFileService.export(note, it).bytes.toString(Charsets.UTF_8) } ?: note.contentText.orEmpty()
        return if (body.startsWith(note.title)) body else note.title + "\n\n" + body
    }

    /** Opens the share sheet for [note]; says so when there is nothing to share. */
    suspend fun share(context: Context, container: AppContainer, note: Note) {
        val intent = runCatching { intentFor(context, container, note) }.getOrNull()
        if (intent == null) {
            android.widget.Toast.makeText(context, context.getString(R.string.share_nothing), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_title))
        runCatching { context.startActivity(chooser) }
    }

    private fun files(context: Context, files: List<File>, mime: String, title: String): Intent? {
        if (files.isEmpty()) return null
        val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = ClipData.newRawUri(title, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = ClipData.newRawUri(title, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Pictures made for a share wait here; the folder is emptied before each new one. */
    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
}
