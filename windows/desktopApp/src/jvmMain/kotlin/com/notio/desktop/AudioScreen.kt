package com.notio.desktop

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.notio.shared.domain.AudioClip
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
fun AudioScreen(noteId: String, controller: NotioController, modifier: Modifier = Modifier) {
    val context = LocalRes.current
    val vm: AudioViewModel = remember(noteId) { controller.audioViewModel(noteId) }
    // The screen going away finishes a recording and keeps it (the phone: ON_STOP), then the model ends.
    // On the computer leaving the note ends a recording and keeps it (minimised or in the tray it goes on, D255).
    DisposableEffect(vm) { onDispose { if (vm.state.value.isRecording) vm.stopRecording(); vm.onScreenStopped(); vm.clear() } }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val permissionDenied = false
    var editing by remember { mutableStateOf<AudioClip?>(null) }
    var deleting by remember { mutableStateOf<AudioClip?>(null) }
    val selection = rememberSelectionState()
    var confirmDeleteMany by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.clips) { selection.retain(state.clips.map { it.id }) }


    val recordingLimit by controller.appSettings.recordingLimit(com.notio.shared.domain.RecordingKind.AUDIO).collectAsState()
    fun record() {
        // A limit typed for this recording only (D263) goes first, else the one of Settings; the person is told (D251).
        val limit = RecordingLimitOnce.take(com.notio.shared.domain.RecordingKind.AUDIO, recordingLimit)
        vm.startRecording(limit)
        warnRecordingLimit(controller, limit)
    }
    androidx.compose.runtime.LaunchedEffect(state.limitReached) { if (state.limitReached) tellRecordingLimitReached(controller) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(state.isRecording) {
        // Show the recording that is being made.
        if (state.isRecording) listState.animateScrollToItem(state.clips.size)
    }

    // The search of the note (D248): the names as shown and the comments of the recordings.
    val resources = LocalRes.current
    val searchable = remember(state.clips) {
        state.clips.mapIndexed { index, clip ->
            SearchableItem(clip.id, clip.name.ifBlank { resources.str("audio_default_name", index + 1) }, clip.comment)
        }
    }
    ItemSearchEffects(searchable, listState)

    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.picked.size,
                total = state.clips.size,
                onSelectAll = { selection.selectAll(state.clips.map { it.id }) },
                onClear = selection::clear,
                onDelete = { confirmDeleteMany = true },
                onDone = selection::end,
            )
        } else if (state.clips.isNotEmpty() && !state.isRecording) {
            // Android D278: an icon, its name on hover.
            IconAction(AppIcons.Checklist, stringResource("media_select"), { selection.begin() }, Modifier.padding(start = 8.dp))
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
                            stringResource("audio_empty"),
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
                        title = clip.name.ifBlank { stringResource("audio_default_name", index + 1) },
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
                                val target = export?.let { chooseFile(context.str("audio_export_clip"), save = true, name = it.fileName) }
                                if (export == null) controller.say(context.str("export_failed"))
                                else if (target != null) {
                                    val ok = runCatching { withContext(Dispatchers.IO) { target.writeBytes(export.bytes) } }.isSuccess
                                    controller.say(context.str(if (ok) "export_done" else "export_failed"))
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
                                Text(stringResource("audio_default_name", state.clips.size + 1), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource("audio_recording"), color = MaterialTheme.colorScheme.error)
                                Text(formatDuration(state.positionMs), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
                item(key = "footer") { NoteFooter() }
            }
            QuickScroll(listState)
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = when {
                permissionDenied -> "audio_permission_denied"
                state.problem == AudioProblem.RECORD_FAILED -> "audio_error_record"
                state.problem == AudioProblem.NOTHING_RECORDED -> "audio_error_nothing"
                state.problem == AudioProblem.PLAYBACK_FAILED -> "audio_error_playback"
                else -> null
            }
            message?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.isRecording) {
                Button(onClick = vm::stopRecording) { Text(stringResource("audio_stop")) }
            } else {
                Button(
                    onClick = ::record,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource("audio_record")) }
            }
            RecordingLimitLine(recordingLimit, kind = com.notio.shared.domain.RecordingKind.AUDIO)
        }
    }

    if (confirmDeleteMany) {
        ConfirmDeleteMany(
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
            title = { Text(stringResource("audio_delete_title")) },
            text = {
                val index = state.clips.indexOf(clip)
                Text(stringResource("audio_delete_message", clip.name.ifBlank { stringResource("audio_default_name", index + 1) }))
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(clip.id); deleting = null }) {
                    Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource("action_cancel")) } },
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
                    Text(highlighted(title, ItemHitKey(clip.id, 0)), style = MaterialTheme.typography.titleMedium)
                    val time = when {
                        selected -> formatDuration(positionMs) + " / " + formatDuration(durationMs)
                        durationMs > 0 -> formatDuration(durationMs)
                        else -> null
                    }
                    time?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // Play / pause of a clip as the known symbols (Android D278).
                if (!selecting) IconAction(
                    if (playing) AppIcons.PauseCircle else AppIcons.PlayCircle,
                    stringResource(if (playing) "audio_pause" else "audio_play"),
                    onPlay, enabled = canPlay, tint = MaterialTheme.colorScheme.primary,
                )
                if (!selecting) Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource("audio_clip_menu"))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource("audio_edit")) }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource("audio_export_clip")) }, onClick = { menuOpen = false; onExport() })
                        DropdownMenuItem(
                            text = { Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            if (clip.comment.isNotBlank()) {
                Text(highlighted(clip.comment, ItemHitKey(clip.id, 1)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
            }
            clip.place?.let { Text(stringResource("media_place", it.text()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            StampLines(clip.createdAt, clip.updatedAt.takeIf { it > clip.createdAt } ?: 0)
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
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("audio_edit_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(AudioViewModel.MAX_NAME) },
                    label = { Text(stringResource("audio_name_label")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(AudioViewModel.MAX_COMMENT) },
                    label = { Text(stringResource("audio_comment_label")) },
                    minLines = 2,
                    maxLines = 6,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, comment) }) { Text(stringResource("action_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}
