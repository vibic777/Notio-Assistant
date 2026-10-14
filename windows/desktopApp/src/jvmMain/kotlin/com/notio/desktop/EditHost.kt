package com.notio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.notio.shared.domain.Folder
import com.notio.shared.domain.Note

/** The editing dialogs, opened from menus on any screen. */
sealed interface EditDialog {
    data object NewFolder : EditDialog
    data class EditFolder(val folder: Folder) : EditDialog
    data class FolderComment(val folder: Folder) : EditDialog
    data class DeleteFolder(val folder: Folder, val noteCount: Int) : EditDialog
    data class NewNote(val folderId: String) : EditDialog
    data class NoteComment(val note: Note) : EditDialog
    data class NoteColor(val note: Note) : EditDialog
    data class MoveNote(val note: Note) : EditDialog
    data class DeleteNote(val note: Note) : EditDialog
    data class NoteTags(val note: Note) : EditDialog
    data object Sort : EditDialog
    data class Featured(val folder: Folder) : EditDialog
    data class PurgeNote(val note: Note) : EditDialog
    data class PurgeFolder(val folder: Folder, val noteCount: Int) : EditDialog
    data class RestoreInto(val note: Note) : EditDialog
    data class EmptyTrash(val count: Int) : EditDialog
    data class ExportNote(val note: Note) : EditDialog
}

@Composable
fun EditDialogHost(s: Strings, state: UiState, controller: NotioController, dialog: EditDialog?, close: () -> Unit) {
    val active = state.folders.map { it.folder }
    when (dialog) {
        null -> Unit
        EditDialog.NewFolder -> FolderEditorDialog(s, null, close, showKind = true, existingNames = remember(state.folders) { state.folders.map { it.folder.name } }) { name, color, style, icon, kind, err ->
            controller.createFolder(name, color, style, icon, err, kind, close)
        }
        is EditDialog.EditFolder -> FolderEditorDialog(s, dialog.folder, close) { name, color, style, icon, _, err ->
            controller.updateFolder(dialog.folder.id, name, color, style, icon, err, close)
        }
        is EditDialog.FolderComment -> CommentDialog(s, s.commentFolderTitle, dialog.folder.comment, close) {
            controller.setFolderComment(dialog.folder.id, it); close()
        }
        is EditDialog.DeleteFolder -> DeleteFolderDialog(s, dialog.folder, dialog.noteCount, active.filter { it.id != dialog.folder.id }, close) { mode, target ->
            controller.deleteFolder(dialog.folder.id, mode, target); close()
        }
        is EditDialog.NewNote -> NewNoteDialog(
            s, close,
            // In a folder of tasks "New" makes a task (D239): no list of kinds.
            tasksOnly = state.folders.firstOrNull { it.folder.id == dialog.folderId }?.folder?.kind == com.notio.shared.domain.FolderKind.TASKS,
            // The ready name takes a number no note of the folder has (D264); the list shown is of the open folder.
            existingTitles = remember(state.notes, dialog.folderId) {
                if (state.selectedFolderId == dialog.folderId) state.notes.map { it.title } else controller.noteTitlesOf(dialog.folderId)
            },
        ) { title, type, err -> controller.createNote(dialog.folderId, title, type, err, close) }
        is EditDialog.NoteComment -> CommentDialog(s, s.commentNoteTitle, dialog.note.comment, close) {
            controller.setNoteComment(dialog.note.id, it); close()
        }
        is EditDialog.NoteColor -> NoteColorDialog(s, dialog.note.colorHex, close) { controller.setNoteColor(dialog.note.id, it); close() }
        is EditDialog.MoveNote -> FolderPickDialog(s, s.moveNoteTitle, s.moveNoteHint, active, dialog.note.folderId, close) {
            controller.moveNote(dialog.note.id, it); close()
        }
        EditDialog.Sort -> SortDialog(
            state.folderSort, state.noteSort,
            tasks = state.folders.firstOrNull { it.folder.id == state.selectedFolderId }?.folder?.kind == com.notio.shared.domain.FolderKind.TASKS,
            onFolderSort = { controller.setFolderSort(it) },
            onNoteSort = { option, all -> controller.setNoteSort(option, all) },
            onDismiss = close,
        )
        is EditDialog.Featured -> FeaturedDialog(
            dialog.folder, remember(dialog.folder.id) { controller.notesOf(dialog.folder.id) }, null,
            onConfirm = { id, mask, layout -> controller.setFeatured(dialog.folder.id, id, mask, layout); close() },
            onDismiss = close,
        )
        is EditDialog.NoteTags -> NoteTagsDialog(
            state.allTags, state.tags[dialog.note.id].orEmpty(),
            onCreate = { name, picked -> controller.createTag(name, picked) },
            onConfirm = { controller.setNoteTags(dialog.note.id, it); close() },
            onDismiss = close,
        )
        is EditDialog.DeleteNote -> ConfirmDialog(s, s.deleteNoteTitle, s.deleteNoteBody(dialog.note.title), s.delete, close) {
            controller.trashNote(dialog.note.id); close()
        }
        is EditDialog.PurgeNote -> ConfirmDialog(s, s.purgeNoteTitle, s.purgeNoteBody(dialog.note.title), s.deleteForever, close) {
            controller.purgeNote(dialog.note.id); close()
        }
        is EditDialog.PurgeFolder -> ConfirmDialog(s, s.purgeFolderTitle, s.purgeFolderBody(dialog.folder.name, dialog.noteCount), s.deleteForever, close) {
            controller.purgeFolder(dialog.folder.id); close()
        }
        is EditDialog.RestoreInto -> FolderPickDialog(s, s.pickFolderTitle, s.pickFolderHint, active, null, close) {
            controller.restoreNote(dialog.note.id, it); close()
        }
        is EditDialog.ExportNote -> ExportNoteDialog(s, dialog.note, controller, close)
        is EditDialog.EmptyTrash -> ConfirmDialog(s, s.emptyTrashTitle, s.emptyTrashBody(dialog.count), s.emptyTrashAction, close) {
            controller.emptyTrash(); close()
        }
    }
}

/** A ⋮ button with a menu of plain text items; `null` actions are left out. */
@Composable
fun MoreMenu(s: Strings, items: List<Pair<String, (() -> Unit)?>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = s.more) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (text, action) ->
                if (text == "-") HorizontalDivider()
                else if (action != null) DropdownMenuItem(
                    text = { Text(text, color = if (text == s.delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

/** The menu of a folder card (Android FolderMenu): edit, pin, comment, delete. */
@Composable
fun FolderMenu(s: Strings, item: FolderItem, controller: NotioController, open: (EditDialog) -> Unit) {
    MoreMenu(s, folderMenuItems(s, item, controller, open))
}

/** The items of a folder's menu: the ⋮ button shows them, a screen reader offers them as the card's actions (Android D274 step 2). */
fun folderMenuItems(s: Strings, item: FolderItem, controller: NotioController, open: (EditDialog) -> Unit): List<Pair<String, (() -> Unit)?>> {
    val f = item.folder
    return listOf(
            s.editAction to { open(EditDialog.EditFolder(f)) },
            (if (f.isPinned) s.unpin else s.pin) to { controller.setFolderPinned(f.id, !f.isPinned) },
            s.commentAction to { open(EditDialog.FolderComment(f)) },
            controller.res.str("featured_menu") to { open(EditDialog.Featured(f)) },
            controller.res.str("media_select") to { controller.beginSelection(f.id) },
    ) + assistantMenuItem(controller, f.id) + listOf(
            // In the folders' own order a folder can go one place earlier or later without dragging (Android D141).
            controller.res.str("folder_move_earlier") to controller.folderMove(f.id, -1),
            controller.res.str("folder_move_later") to controller.folderMove(f.id, 1),
            "-" to null,
            s.delete to { open(EditDialog.DeleteFolder(f, item.noteCount)) },
        )
}

/** The menu of a note (Android NoteCard menu): pin, bold title, comment, colour, move, delete. */
@Composable
fun NoteMenu(s: Strings, note: Note, controller: NotioController, open: (EditDialog) -> Unit) {
    MoreMenu(s, noteMenuItems(s, note, controller, open))
}

/** The items of a note's menu: the ⋮ button shows them, a screen reader offers them as the card's actions (Android D274 step 2). */
fun noteMenuItems(s: Strings, note: Note, controller: NotioController, open: (EditDialog) -> Unit): List<Pair<String, (() -> Unit)?>> =
        listOf(
            (if (note.isPinned) s.unpin else s.pin) to { controller.togglePinned(note.id) },
            (if (note.isTitleBold) s.normalTitle else s.boldTitle) to { controller.toggleBoldTitle(note.id) },
            controller.res.str("action_tags") to { open(EditDialog.NoteTags(note)) },
            controller.res.str("action_reminders") to { controller.openReminders(note.id) },
            s.commentAction to { open(EditDialog.NoteComment(note)) },
            s.noteColorTitle to { open(EditDialog.NoteColor(note)) },
            s.moveAction to { open(EditDialog.MoveNote(note)) },
            controller.res.str("action_clone") to { controller.cloneNote(note.id) },
            controller.res.str("action_share") to { controller.shareNote(note.id) },
            controller.res.str("export_note_action") to { open(EditDialog.ExportNote(note)) },
        ) + assistantMenuItem(controller, note.id) + listOf(
            // One place earlier / later without dragging (Android D274 step 4: a button for every mouse gesture), in own order only.
            controller.res.str("folder_move_earlier") to controller.noteMove(note.id, -1),
            controller.res.str("folder_move_later") to controller.noteMove(note.id, 1),
            "-" to null,
            s.delete to { open(EditDialog.DeleteNote(note)) },
        )

/**
 * "Open to the assistant" / "Close to the assistant" in the menu of a note or a folder (D147), while the assistant is turned on:
 * the switch of the page Settings -> Assistant -> Access to notes, at hand. Closing leaves "Reminders to Telegram" as it is.
 */
fun assistantMenuItem(controller: NotioController, id: String): List<Pair<String, (() -> Unit)?>> {
    val desk = controller.assistant
    if (!desk.enabled) return emptyList()
    val flags = desk.access[id] ?: com.notio.shared.assistant.AssistantAccess.NONE
    val open = flags and com.notio.shared.assistant.AssistantAccess.ASSISTANT != 0
    val next = if (open) flags and com.notio.shared.assistant.AssistantAccess.REMINDERS_TELEGRAM else flags or com.notio.shared.assistant.AssistantAccess.ASSISTANT
    return listOf(controller.res.str(if (open) "assistant_menu_close" else "assistant_menu_open") to { desk.setAccess(listOf(id), next) })
}

/** The menu of a note in the trash: restore (asking where when its folder is gone too) and delete for good. */
@Composable
fun TrashNoteMenu(s: Strings, note: Note, controller: NotioController, open: (EditDialog) -> Unit) {
    MoreMenu(
        s,
        listOf(
            s.restore to { if (controller.folderIsActive(note.folderId)) controller.restoreNote(note.id) else open(EditDialog.RestoreInto(note)) },
            s.deleteForever to { open(EditDialog.PurgeNote(note)) },
        ),
    )
}

/** "Export as" (Android: the export sheet of a note): the formats of the note's kind, then where to save the file. */
@Composable
fun ExportNoteDialog(s: Strings, note: Note, controller: NotioController, close: () -> Unit) {
    val formats = remember(note.id) { controller.exportFormats(note) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        title = { Text(controller.res.str("export_note_title")) },
        text = {
            androidx.compose.foundation.layout.Column {
                formats.forEach { format ->
                    TextButton(
                        onClick = {
                            close()
                            chooseFile(controller.res.str("export_note_action"), save = true, name = controller.suggestedNoteFileName(note, format))
                                ?.let { controller.exportNote(note.id, format, it) }
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    ) { Text(controller.res.str(format.label), modifier = androidx.compose.ui.Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(s.cancel) } },
    )
}

/** The system's Open / Save dialog; `null` when cancelled. */
fun chooseFile(title: String, save: Boolean, name: String? = null): java.io.File? {
    val owner = java.awt.Window.getWindows().firstOrNull { it.isActive } as? java.awt.Frame
    val fd = java.awt.FileDialog(owner, title, if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
    if (name != null) fd.file = name
    fd.isVisible = true
    return fd.file?.let { java.io.File(fd.directory, it) }
}
