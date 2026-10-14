package com.notebudget.app.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.notebudget.app.core.ui.NoticeHost
import com.notebudget.app.core.ui.showNotice
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.ItemHitKey
import com.notebudget.app.core.ui.ItemSearchEffects
import com.notebudget.app.core.ui.LocalNoteSearch
import com.notebudget.app.core.ui.NoteSearch
import com.notebudget.app.core.ui.NoteSearchBar
import com.notebudget.app.core.ui.SearchableItem
import com.notebudget.app.core.ui.TextSearch
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.highlighted
import com.notebudget.app.core.ui.snippet
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.usecase.TrashRetention
import java.text.DateFormat
import java.util.Date

/**
 * The trash (D29, D65, D93): deleted notes and folders wait here until they are restored, deleted
 * for good, or removed automatically after the retention time set in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    viewModel: TrashViewModel = appViewModel {
        TrashViewModel(
            it.folderRepository, it.noteRepository, it.folderUseCases, it.noteUseCases,
            it.trashUseCases, it.settings.trashRetentionDays,
            mediaTrash = it.mediaTrash, mediaFiles = it.mediaFiles,
        )
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // The search of the trash (D253): names of folders, notes and items, the text of notes, the notes gone with a folder, comments.
    val search = remember { NoteSearch() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val searchable = remember(state.rows) { state.rows.map { SearchableItem(keyOf(it), titleOf(it), detailOf(it)) } }
    androidx.activity.compose.BackHandler(enabled = search.open) { search.close() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // In a job of its own: a newer message takes the place of the one shown, nothing waits for it (D360).
            launch {
                val message = when (event) {
                    is TrashEvent.Restored -> context.getString(R.string.trash_restored, event.name)
                    is TrashEvent.Purged -> context.getString(R.string.trash_purged, event.name)
                    is TrashEvent.MediaRestoreFailed -> context.getString(
                        when (event.result) {
                            com.notebudget.app.domain.model.MediaRestoreResult.NOTE_IN_TRASH -> R.string.trash_media_cannot_restore_trash
                            com.notebudget.app.domain.model.MediaRestoreResult.NOTE_GONE -> R.string.trash_media_cannot_restore_gone
                            else -> R.string.trash_media_cannot_restore_file
                        },
                    )
                    TrashEvent.Emptied -> context.getString(R.string.trash_emptied)
                    is TrashEvent.Failed ->
                        if (event.restoringFolder && event.error == DomainError.DUPLICATE_FOLDER_NAME) {
                            context.getString(R.string.trash_restore_duplicate_name)
                        } else {
                            context.getString(event.error.messageRes())
                        }
                }
                snackbarHostState.showNotice(message)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalNoteSearch provides search) {
    ItemSearchEffects(searchable, listState, before = 1)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
          Column {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { if (search.open) search.close() else search.open = true }, enabled = state.rows.isNotEmpty()) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = stringResource(R.string.trash_search))
                    }
                    TextButton(onClick = viewModel::askEmptyTrash, enabled = state.itemCount > 0) {
                        Text(stringResource(R.string.trash_empty_action))
                    }
                },
            )
            if (search.open) NoteSearchBar(search, hint = stringResource(R.string.trash_search))
          }
        },
        snackbarHost = { NoticeHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.rows.isEmpty()) {
            Column(Modifier.padding(innerPadding)) {
                RetentionInfo(state.retentionDays, onOpenSettings, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                EmptyTrash(Modifier)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "retention") { RetentionInfo(state.retentionDays, onOpenSettings) }
                items(state.rows, key = { row -> keyOf(row) }) { row ->
                    when (row) {
                        is TrashRow.FolderRow -> FolderTrashCard(
                            row,
                            onRestore = { viewModel.restoreFolder(row.folder.id) },
                            onDelete = { viewModel.askPurgeFolder(row) },
                        )
                        is TrashRow.NoteRow -> NoteTrashCard(
                            row,
                            onRestore = { viewModel.restoreNote(row.note.id) },
                            onDelete = { viewModel.askPurgeNote(row.note) },
                        )
                        is TrashRow.MediaRow -> MediaTrashCard(
                            row,
                            onRestore = { viewModel.restoreMedia(row) },
                            onDelete = { viewModel.askPurgeMedia(row) },
                        )
                    }
                }
            }
        }
    }

    }

    when (val d = dialog) {
        null -> Unit
        is TrashDialog.PurgeNote -> ConfirmDialog(
            title = stringResource(R.string.trash_purge_note_title),
            message = stringResource(R.string.trash_purge_note_body, d.title),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        is TrashDialog.PurgeMedia -> ConfirmDialog(
            title = stringResource(R.string.trash_purge_media_title),
            message = stringResource(R.string.trash_purge_media_body, d.title),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        is TrashDialog.PurgeFolder -> ConfirmDialog(
            title = stringResource(R.string.trash_purge_folder_title),
            message = if (d.noteCount == 0) {
                stringResource(R.string.trash_purge_folder_body_empty, d.name)
            } else {
                pluralStringResource(R.plurals.trash_purge_folder_body, d.noteCount, d.name, d.noteCount)
            },
            confirmLabel = stringResource(R.string.trash_delete_forever),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        is TrashDialog.EmptyTrash -> ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            message = pluralStringResource(R.plurals.trash_empty_confirm_body, d.itemCount, d.itemCount),
            confirmLabel = stringResource(R.string.trash_empty_action),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        is TrashDialog.PickFolder -> PickFolderDialog(
            folders = state.activeFolders.map { it.id to it.name },
            onPick = { viewModel.restoreNoteInto(d.noteId, it) },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun EmptyTrash(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.trash_empty_state), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.trash_empty_state_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** How long the trash keeps things, and a way to the setting that changes it (D140). */
@Composable
private fun RetentionInfo(days: Int, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            if (days <= TrashRetention.NEVER) stringResource(R.string.trash_retention_never)
            else pluralStringResource(R.plurals.trash_retention_info, days, days),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.trash_open_settings)) }
    }
}

@Composable
private fun NoteTrashCard(row: TrashRow.NoteRow, onRestore: () -> Unit, onDelete: () -> Unit) {
    val typeLabel = stringResource(rememberAppContainer().noteTypes.definitionFor(row.note.type).labelRes)
    val folderLine = when {
        row.folderName == null -> null
        row.folderInTrash -> stringResource(R.string.trash_note_folder_in_trash, row.folderName)
        else -> stringResource(R.string.trash_note_in_folder, row.folderName)
    }
    TrashCard(
        key = keyOf(row),
        title = row.note.title,
        detail = detailOf(row),
        lines = listOfNotNull(typeLabel, folderLine, deletedLine(row)),
        onRestore = onRestore,
        onDelete = onDelete,
    )
}

/**
 * A photo, video, screen recording or audio recording in the trash (D156): what it is, where it came from, how big it is,
 * and what can be done: put back (only while its note is there and its file is on the phone), export the file, delete for good.
 */
@Composable
private fun MediaTrashCard(row: TrashRow.MediaRow, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = rememberAppContainer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val kindLabel = stringResource(
        when (row.media.kind) {
            "PHOTO" -> R.string.note_type_photo
            "VIDEO" -> R.string.note_type_video
            "SCREEN" -> R.string.note_type_screen
            else -> R.string.note_type_audio
        },
    )
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<java.io.File, String>?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = pending
        pending = null
        if (uri == null || job == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> job.first.inputStream().use { it.copyTo(out) } } ?: error("Cannot open $uri")
                }
            }.isSuccess
            android.widget.Toast.makeText(context, context.getString(if (ok) R.string.export_done else R.string.export_failed), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val size = android.text.format.Formatter.formatShortFileSize(context, row.media.sizeBytes)
    val where = when (row.noteState) {
        com.notebudget.app.feature.trash.MediaNoteState.ACTIVE -> stringResource(R.string.trash_media_from_note, row.noteTitle)
        com.notebudget.app.feature.trash.MediaNoteState.IN_TRASH -> stringResource(R.string.trash_media_note_in_trash, row.noteTitle)
        com.notebudget.app.feature.trash.MediaNoteState.GONE -> stringResource(R.string.trash_media_note_gone, row.noteTitle)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(highlighted(row.media.title.ifBlank { kindLabel }, ItemHitKey(keyOf(row), 0)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            FoundDetail(keyOf(row), row.comment)
            Text("$kindLabel · $size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!row.fileExists) Text(stringResource(R.string.trash_media_file_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(deletedLine(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // D278: restore, save to a file and delete for good as icons (a long press says which); deleting still asks first.
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.RestoreFromTrash, stringResource(R.string.trash_restore), onRestore, enabled = row.noteState == com.notebudget.app.feature.trash.MediaNoteState.ACTIVE && row.fileExists)
                com.notebudget.app.core.ui.IconAction(
                    com.notebudget.app.core.ui.AppIcons.FileDownload,
                    stringResource(R.string.trash_export),
                    enabled = row.fileExists,
                    onClick = {
                        scope.launch {
                            val file = container.mediaFiles.fileFor(row.media.kind, row.media.id) ?: return@launch
                            val naming = container.settings.exportNaming.first()
                            val ext = file.extension.ifBlank { "bin" }
                            val name = naming.baseName(row.noteTitle, row.media.title.ifBlank { kindLabel }, row.media.deletedAt, row.media.deletedAt, System.currentTimeMillis()) + "." + ext
                            pending = file to name
                            launcher.launch(name)
                        }
                    },
                )
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.DeleteForever, stringResource(R.string.trash_delete_forever), onDelete, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun FolderTrashCard(row: TrashRow.FolderRow, onRestore: () -> Unit, onDelete: () -> Unit) {
    TrashCard(
        key = keyOf(row),
        title = row.folder.name,
        detail = row.noteTitles,
        lines = listOf(
            stringResource(R.string.trash_folder_label) + " · " +
                pluralStringResource(R.plurals.trash_folder_notes, row.noteCount, row.noteCount),
            deletedLine(row),
        ),
        onRestore = onRestore,
        onDelete = onDelete,
    )
}

/** "Deleted 20 Sep 2026 · 12 days left". */
@Composable
private fun deletedLine(row: TrashRow): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(row.deletedAt))
    val deleted = stringResource(R.string.trash_deleted_on, date)
    val left = row.daysLeft ?: return deleted
    val leftText = if (left == 0) stringResource(R.string.trash_expiring_soon)
    else pluralStringResource(R.plurals.trash_days_left, left, left)
    return "$deleted · $leftText"
}

@Composable
private fun TrashCard(key: String, title: String, detail: String, lines: List<String>, onRestore: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(highlighted(title, ItemHitKey(key, 0)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            FoundDetail(key, detail)
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.RestoreFromTrash, stringResource(R.string.trash_restore), onRestore)
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.DeleteForever, stringResource(R.string.trash_delete_forever), onDelete, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PickFolderDialog(folders: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_pick_folder_title)) },
        text = {
            Column {
                Text(stringResource(R.string.trash_pick_folder_hint), style = MaterialTheme.typography.bodyMedium)
                folders.forEach { (id, name) ->
                    TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The key of a row in the list and in the search. */
private fun keyOf(row: TrashRow): String = when (row) {
    is TrashRow.FolderRow -> "f-" + row.folder.id
    is TrashRow.NoteRow -> "n-" + row.note.id
    is TrashRow.MediaRow -> "m-" + row.media.id
}

private fun titleOf(row: TrashRow): String = when (row) {
    is TrashRow.FolderRow -> row.folder.name
    is TrashRow.NoteRow -> row.note.title
    is TrashRow.MediaRow -> row.media.title
}

/** What else of a row is searched: the text of a note, the notes gone with a folder, the comment of an item. */
private fun detailOf(row: TrashRow): String = when (row) {
    is TrashRow.FolderRow -> row.noteTitles
    is TrashRow.NoteRow -> row.note.contentText.orEmpty()
    is TrashRow.MediaRow -> row.comment
}

/** While the search finds something in the text of a row: that piece, the words marked (D253). */
@Composable
private fun FoundDetail(key: String, detail: String) {
    val search = LocalNoteSearch.current ?: return
    val pattern = search.activePattern ?: return
    if (detail.isEmpty()) return
    val current = search.currentHit
    val piece = remember(detail, pattern, current) { TextSearch.snippet(detail, pattern, ItemHitKey(key, 1), current) } ?: return
    Text(piece, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}
