package com.notebudget.app.feature.audio

import com.notebudget.app.core.ui.QuickScroll
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.domain.model.AudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `m:ss`, or `h:mm:ss` for long clips. */
fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Audio note editor (D112): a list of recordings. Record adds a clip; every clip has its own name and comment,
 * plays, exports and deletes on its own. The microphone permission is requested when the user first taps Record.
 */
@Composable
fun AudioScreen(noteId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: AudioViewModel = appViewModel {
        AudioViewModel(
            noteId = noteId,
            notes = it.noteRepository,
            store = it.audioStore,
            noteUseCases = it.noteUseCases,
            recorder = MediaRecorderEngine(it.appContext),
            player = MediaPlayerEngine(),
            cacheDir = it.appContext.cacheDir,
            appScope = it.appScope,
            keepAlive = com.notebudget.app.feature.media.AndroidRecordingKeepAlive(it.appContext),
            defaultName = { n -> it.appContext.getString(R.string.audio_default_name, n) },
            formats = it.settings,
            trash = it.mediaTrash,
            placeProvider = {
                if (it.settings.audioPlace.first()) com.notebudget.app.feature.media.LocationProvider.current(it.appContext) else null
            },
            recordingLimit = it.settings.recordingLimit(com.notebudget.app.domain.model.RecordingKind.AUDIO),
        )
    }
    val limitContainer = com.notebudget.app.core.ui.rememberAppContainer()
    val recordingLimit by remember { limitContainer.settings.recordingLimit(com.notebudget.app.domain.model.RecordingKind.AUDIO) }
        .collectAsStateWithLifecycle(initialValue = com.notebudget.app.domain.model.RecordingLimit.DEFAULT)
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var permissionDenied by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AudioClip?>(null) }
    var deleting by remember { mutableStateOf<AudioClip?>(null) }
    var pendingExport by remember { mutableStateOf<AudioExport?>(null) }
    val selection = com.notebudget.app.core.ui.rememberSelectionState()
    var confirmDeleteMany by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.clips) { selection.retain(state.clips.map { it.id }) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) vm.startRecording(com.notebudget.app.core.ui.RecordingLimitOnce.take(com.notebudget.app.domain.model.RecordingKind.AUDIO, recordingLimit))
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri: Uri? ->
        val export = pendingExport
        pendingExport = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(export.bytes) } ?: error("Cannot open $uri")
                }
            }.isSuccess
            Toast.makeText(context, context.getString(if (ok) R.string.export_done else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun record() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        // The person is told when it will stop by itself (D251).
        com.notebudget.app.core.ui.warnRecordingLimit(context, com.notebudget.app.core.ui.RecordingLimitOnce.get(com.notebudget.app.domain.model.RecordingKind.AUDIO) ?: recordingLimit)
        if (granted) vm.startRecording(com.notebudget.app.core.ui.RecordingLimitOnce.take(com.notebudget.app.domain.model.RecordingKind.AUDIO, recordingLimit)) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    androidx.compose.runtime.LaunchedEffect(state.limitReached) { if (state.limitReached) com.notebudget.app.core.ui.tellRecordingLimitReached(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.onScreenStopped() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(state.isRecording) {
        // Show the recording that is being made.
        if (state.isRecording) listState.animateScrollToItem(state.clips.size)
    }

    // The search of the note (D248): the names as shown and the comments of the recordings.
    val resources = androidx.compose.ui.platform.LocalResources.current
    val searchable = remember(state.clips, resources) {
        state.clips.mapIndexed { index, clip ->
            com.notebudget.app.core.ui.SearchableItem(clip.id, clip.name.ifBlank { resources.getString(R.string.audio_default_name, index + 1) }, clip.comment)
        }
    }
    com.notebudget.app.core.ui.ItemSearchEffects(searchable, listState)

    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            com.notebudget.app.core.ui.SelectionBar(
                count = selection.picked.size,
                total = state.clips.size,
                onSelectAll = { selection.selectAll(state.clips.map { it.id }) },
                onClear = selection::clear,
                onDelete = { confirmDeleteMany = true },
                onDone = selection::end,
            )
        } else if (state.clips.isNotEmpty() && !state.isRecording) {
            // D278: an icon, a long press says "Select".
            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Checklist, stringResource(R.string.media_select), { selection.begin() }, Modifier.padding(start = 8.dp))
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loaded && state.clips.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.audio_empty),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                itemsIndexed(state.clips, key = { _, clip -> clip.id }) { index, clip ->
                    val selected = state.selectedId == clip.id
                    ClipCard(
                        clip = clip,
                        title = clip.name.ifBlank { stringResource(R.string.audio_default_name, index + 1) },
                        selected = selected,
                        playing = selected && state.isPlaying,
                        positionMs = if (selected) state.positionMs else 0,
                        durationMs = if (selected) state.durationMs else clip.durationMs,
                        canPlay = !state.isRecording,
                        selecting = selection.active,
                        checked = clip.id in selection.picked,
                        onToggle = { selection.toggle(clip.id) },
                        onPlay = { vm.togglePlayback(clip.id) },
                        onSeek = vm::seekTo,
                        onEdit = { editing = clip },
                        onExport = {
                            scope.launch {
                                val export = vm.prepareExport(clip.id)
                                if (export == null) {
                                    Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                                } else {
                                    pendingExport = export
                                    exportLauncher.launch(export.fileName)
                                }
                            }
                        },
                        onDelete = { deleting = clip },
                    )
                }
                if (state.isRecording) {
                    // The recording in progress is its own card at the end, with its own timer (D117).
                    item(key = "recording-now") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.audio_default_name, state.clips.size + 1), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.audio_recording), color = MaterialTheme.colorScheme.error)
                                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
                item(key = "footer") { com.notebudget.app.core.ui.NoteFooter() }
            }
            QuickScroll(listState)
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = when {
                permissionDenied -> R.string.audio_permission_denied
                state.problem == AudioProblem.RECORD_FAILED -> R.string.audio_error_record
                state.problem == AudioProblem.NOTHING_RECORDED -> R.string.audio_error_nothing
                state.problem == AudioProblem.PLAYBACK_FAILED -> R.string.audio_error_playback
                else -> null
            }
            message?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.isRecording) {
                Button(onClick = vm::stopRecording) { Text(stringResource(R.string.audio_stop)) }
            } else {
                Button(
                    onClick = ::record,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.audio_record)) }
                com.notebudget.app.core.ui.RecordingLimitLine(recordingLimit, kind = com.notebudget.app.domain.model.RecordingKind.AUDIO)
            }
        }
    }

    if (confirmDeleteMany) {
        com.notebudget.app.core.ui.ConfirmDeleteMany(
            count = selection.picked.size,
            onConfirm = { vm.deleteMany(selection.picked); confirmDeleteMany = false; selection.end() },
            onDismiss = { confirmDeleteMany = false },
        )
    }

    editing?.let { clip ->
        ClipEditDialog(
            clip = clip,
            onConfirm = { name, comment ->
                vm.rename(clip.id, name)
                vm.setComment(clip.id, comment)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.audio_delete_title)) },
            text = {
                val index = state.clips.indexOf(clip)
                Text(stringResource(R.string.audio_delete_message, clip.name.ifBlank { stringResource(R.string.audio_default_name, index + 1) }))
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(clip.id); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ClipCard(
    clip: AudioClip,
    title: String,
    selected: Boolean,
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    canPlay: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(if (selecting) Modifier.fillMaxWidth().clickable(onClick = onToggle) else Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggle() })
                }
                Column(Modifier.weight(1f)) {
                    Text(com.notebudget.app.core.ui.highlighted(title, com.notebudget.app.core.ui.ItemHitKey(clip.id, 0)), style = MaterialTheme.typography.titleMedium)
                    val time = when {
                        selected -> formatDuration(positionMs) + " / " + formatDuration(durationMs)
                        durationMs > 0 -> formatDuration(durationMs)
                        else -> null
                    }
                    time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // Play / pause of a clip as the known symbols (D278).
                if (!selecting) com.notebudget.app.core.ui.IconAction(
                    if (playing) com.notebudget.app.core.ui.AppIcons.PauseCircle else com.notebudget.app.core.ui.AppIcons.PlayCircle,
                    stringResource(if (playing) R.string.audio_pause else R.string.audio_play),
                    onPlay,
                    enabled = canPlay,
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
                if (!selecting) Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.audio_clip_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_edit)) }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_export_clip)) }, onClick = { menuOpen = false; onExport() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            if (clip.comment.isNotBlank()) {
                Text(com.notebudget.app.core.ui.highlighted(clip.comment, com.notebudget.app.core.ui.ItemHitKey(clip.id, 1)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
            }
            clip.place?.let { Text(stringResource(R.string.media_place, it.text()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            com.notebudget.app.core.ui.StampLines(clip.createdAt, clip.updatedAt.takeIf { it > clip.createdAt } ?: 0)
            if (selected) {
                Slider(
                    value = positionMs.toFloat(),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ClipEditDialog(clip: AudioClip, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(clip.id) { mutableStateOf(clip.name) }
    var comment by remember(clip.id) { mutableStateOf(clip.comment) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(AudioViewModel.MAX_NAME) },
                    label = { Text(stringResource(R.string.audio_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(AudioViewModel.MAX_COMMENT) },
                    label = { Text(stringResource(R.string.audio_comment_label)) },
                    minLines = 2,
                    maxLines = 6,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, comment) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
