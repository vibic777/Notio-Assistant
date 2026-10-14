package com.notio.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.notio.desktop.media.VideoReader
import com.notio.shared.domain.MediaDocument
import com.notio.shared.domain.MediaItem
import com.notio.shared.domain.MediaKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** `m:ss`, or `h:mm:ss` for long recordings. */
fun mediaDuration(ms: Int): String = formatDuration(ms)

/**
 * A photo, camera-video or screen-recording note (Android `MediaNoteScreen`, D147; AGENT.md D36): the list of items, each with its
 * picture, name, comment, stamps; a photo opens full screen (arrows between photos), a video plays in the note; several are picked
 * with check boxes to delete; at the bottom what makes a new item — the camera of the computer or the screen recorder (Notio steps
 * aside, a small Stop window stays over the others), with the microphone when asked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaNoteScreen(noteId: String, kind: MediaKind, controller: NotioController, modifier: Modifier = Modifier) {
    val res = LocalRes.current
    val detail = controller.state.value.detail?.takeIf { it.note.id == noteId }
    val items = remember(detail?.note?.extras) { MediaDocument.parse(detail?.note?.extras).items }
    val selection = rememberSelectionState()
    var confirmDeleteMany by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var deleting by remember { mutableStateOf<MediaItem?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var withMic by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(false) }
    val screen = controller.screenRecording
    LaunchedEffect(items) { selection.retain(items.map { it.id }) }

    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.picked.size, total = items.size,
                onSelectAll = { selection.selectAll(items.map { it.id }) }, onClear = selection::clear,
                onDelete = { confirmDeleteMany = true }, onDone = selection::end,
            )
        } else if (items.isNotEmpty()) {
            // Android D278: an icon, its name on hover.
            IconAction(AppIcons.Checklist, stringResource("media_select"), { selection.begin() }, Modifier.padding(start = 8.dp))
        }
        // The search of the note (D248): the names as shown and the comments of the items.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val resources = LocalRes.current
        val searchable = remember(items) {
            items.mapIndexed { index, item -> SearchableItem(item.id, item.name.ifBlank { resources.str("media_default_name", index + 1) }, item.comment) }
        }
        ItemSearchEffects(searchable, listState)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (detail != null && items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (kind == MediaKind.PHOTO) "media_empty_photo" else if (kind == MediaKind.VIDEO) "media_empty_video" else "media_empty_screen"),
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                }
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val file = controller.mediaItemFile(noteId, kind, item)
                    ItemCard(
                        kind = kind, item = item, file = file, title = item.name.ifBlank { stringResource("media_default_name", index + 1) },
                        selecting = selection.active, checked = item.id in selection.picked, playing = playingId == item.id,
                        onToggle = { selection.toggle(item.id) },
                        onOpen = { if (kind.isVideo) playingId = if (playingId == item.id) null else item.id else viewing = index },
                        onFullScreen = { viewing = index },
                        onEdit = { editing = item },
                        onExport = {
                            val name = controller.mediaExportName(noteId, kind, item, index)
                            if (file == null) controller.say(res.str("export_failed"))
                            else chooseFile(res.str("audio_export_clip"), save = true, name = name)?.let { target ->
                                val ok = runCatching { file.copyTo(target, overwrite = true) }.isSuccess
                                controller.say(res.str(if (ok) "export_done" else "export_failed"))
                            }
                        },
                        onDelete = { deleting = item },
                    )
                }
                item(key = "footer") { NoteFooter() }
            }
            QuickScroll(listState)
        }

        // ---- what makes a new item ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val busy = screen.recording || screen.saving
            if (kind != MediaKind.PHOTO) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource("rec_sound"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(!withMic, { withMic = false }, { Text(stringResource("rec_sound_none")) }, enabled = !busy)
                        FilterChip(withMic, { withMic = true }, { Text(stringResource("rec_sound_mic")) }, enabled = !busy)
                    }
                }
            }
            if (kind == MediaKind.SCREEN && screen.noteId == noteId) {
                when {
                    screen.recording -> Text(stringResource("rec_recording"), color = MaterialTheme.colorScheme.error)
                    screen.saving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource("rec_processing"))
                    }
                    screen.failed -> Text(stringResource("rec_failed"), color = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (kind == MediaKind.SCREEN) controller.startScreenRecording(noteId, withMic) else showCamera = true },
                    colors = if (kind == MediaKind.PHOTO) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !busy,
                ) { Text(stringResource(if (kind == MediaKind.PHOTO) "media_take_photo" else if (kind == MediaKind.VIDEO) "media_record_video" else "media_record_screen")) }
                // The overlays laid over new photos and videos of this kind (D148, D40).
                OutlinedButton(onClick = { showOverlay = true }, enabled = !busy) { Text(stringResource("overlay_title")) }
                // On a computer a picture is often already a file: it can be added from one too (AGENT.md D36).
                if (kind == MediaKind.PHOTO) OutlinedButton(onClick = {
                    chooseFile(res.str("media_take_photo"), save = false)?.let { controller.addPhotoFromFile(noteId, it) }
                }) { Text(stringResource("bg_picture")) }
            }
            // How long a video / screen recording may last (D251), under the Record button.
            if (kind != MediaKind.PHOTO) {
                val limit by controller.appSettings.recordingLimit(if (kind == MediaKind.SCREEN) com.notio.shared.domain.RecordingKind.SCREEN else com.notio.shared.domain.RecordingKind.VIDEO).collectAsState()
                RecordingLimitLine(limit, kind = if (kind == MediaKind.SCREEN) com.notio.shared.domain.RecordingKind.SCREEN else com.notio.shared.domain.RecordingKind.VIDEO)
            }
        }
    }

    if (showCamera) CameraDialog(noteId, kind, withMic, controller) { showCamera = false }
    if (showOverlay) {
        OverlayDialog(kind, controller.overlaySettings(kind), onSave = { controller.setOverlaySettings(kind, it); showOverlay = false }, onDismiss = { showOverlay = false })
    }
    viewing?.let { start ->
        MediaViewer(controller, noteId, kind, items, start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { viewing = null }
    }
    if (confirmDeleteMany) {
        ConfirmDeleteMany(selection.picked.size, onConfirm = {
            controller.deleteMediaItems(noteId, kind, selection.picked); confirmDeleteMany = false; selection.end()
        }, onDismiss = { confirmDeleteMany = false })
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource("media_delete_title")) },
            text = { Text(stringResource("media_delete_message")) },
            confirmButton = { TextButton(onClick = { controller.deleteMediaItems(noteId, kind, setOf(item.id)); deleting = null }) { Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource("action_cancel")) } },
        )
    }
    editing?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        var comment by remember(item.id) { mutableStateOf(item.comment) }
        com.notio.desktop.EditorDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource("audio_edit_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource("audio_name_label")) }, singleLine = true)
                    OutlinedTextField(comment, { comment = it.take(2000) }, label = { Text(stringResource("audio_comment_label")) }, minLines = 2, maxLines = 6)
                }
            },
            confirmButton = { TextButton(onClick = { controller.editMediaItem(noteId, item.id, name, comment); editing = null }) { Text(stringResource("action_save")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource("action_cancel")) } },
        )
    }
}

@Composable
private fun ItemCard(
    kind: MediaKind, item: MediaItem, file: File?, title: String, selecting: Boolean, checked: Boolean, playing: Boolean,
    onToggle: () -> Unit, onOpen: () -> Unit, onFullScreen: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    Card(if (selecting) Modifier.fillMaxWidth().clickable(onClick = onToggle) else Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(highlighted(title, ItemHitKey(item.id, 0)), style = MaterialTheme.typography.titleMedium)
                    if (item.durationMs > 0) Text(mediaDuration(item.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!selecting) Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource("audio_clip_menu")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource("audio_edit")) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource("media_full_screen")) }, onClick = { menu = false; onFullScreen() })
                        DropdownMenuItem(text = { Text(stringResource("audio_export_clip")) }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text(stringResource("action_delete"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (playing && file != null) {
                VideoView(file, Modifier.fillMaxWidth().height(320.dp))
                IconAction(AppIcons.Fullscreen, stringResource("media_full_screen"), onFullScreen)
            } else {
                Thumbnail(file, kind.isVideo, Modifier.fillMaxWidth().height(220.dp).clickable(enabled = !selecting, onClick = onOpen))
            }
            if (item.comment.isNotBlank()) Text(highlighted(item.comment, ItemHitKey(item.id, 1)), style = MaterialTheme.typography.bodyMedium)
            item.place?.let { Text(stringResource("media_place", it.text()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            StampLines(item.createdAt, item.updatedAt.takeIf { it > item.createdAt } ?: 0)
        }
    }
}

@Composable
private fun Thumbnail(file: File?, video: Boolean, modifier: Modifier) {
    // What the cache has at once; the file (changed or not yet read) off the UI thread — no disk access while composing.
    val first = remember(file?.path) { file?.let { PictureCache.peek(MediaThumbnails.key(it)) } }
    val bitmap by produceState(first, file?.path) {
        file?.let { withContext(Dispatchers.IO) { MediaThumbnails.get(it, video) } }?.let { value = it }
    }
    Box(modifier.background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (video) Text("▶", color = Color.White, style = MaterialTheme.typography.displaySmall)
    }
}

/**
 * A video playing in place (the phone's VideoView): the pictures by Media Foundation at their moments, the sound by the recordings'
 * player; a click pauses and goes on.
 */
@Composable
fun VideoView(file: File, modifier: Modifier) {
    var frame by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    var paused by remember(file.path) { mutableStateOf(false) }
    var restart by remember(file.path) { mutableIntStateOf(0) }
    val player = remember(file.path) { DesktopPlayer() }
    DisposableEffect(file.path) { onDispose { player.release() } }
    LaunchedEffect(file.path, paused, restart) {
        if (paused) { player.pause(); return@LaunchedEffect }
        val from = player.positionMs.toLong()
        val hasSound = withContext(Dispatchers.IO) { player.durationMs > 0 || player.load(file) {} }
        withContext(Dispatchers.IO) {
            VideoReader.open(file).use { reader ->
                if (hasSound) { player.seekTo(from.toInt()); player.start() }
                val t0 = System.currentTimeMillis() - from
                while (isActive) {
                    val f = reader.read() ?: break
                    if (f.timeMs < from) continue
                    val wait = f.timeMs - (System.currentTimeMillis() - t0)
                    if (wait > 0) delay(wait)
                    frame = f.toImageBitmap()
                }
            }
        }
        if (!paused) { player.pause(); player.seekTo(0) }
    }
    Box(modifier.background(Color.Black).clickable { paused = !paused }, contentAlignment = Alignment.Center) {
        frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (paused) Text("▶", color = Color.White, style = MaterialTheme.typography.displaySmall)
    }
}

/** Full screen (D147): photos one by one (← → or the buttons), a video playing; Esc closes. */
@Composable
private fun MediaViewer(controller: NotioController, noteId: String, kind: MediaKind, items: List<MediaItem>, startIndex: Int, onClose: () -> Unit) {
    if (items.isEmpty()) return
    var page by remember { mutableIntStateOf(startIndex) }
    val state = rememberDialogState(size = DpSize(1000.dp, 760.dp), position = WindowPosition(Alignment.Center))
    DialogWindow(
        onCloseRequest = onClose, state = state, title = items[page].name.ifBlank { stringResource("media_default_name", page + 1) },
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) false
            else when (e.key) {
                Key.Escape -> { onClose(); true }
                Key.DirectionLeft -> { if (page > 0) page--; true }
                Key.DirectionRight -> { if (page < items.lastIndex) page++; true }
                else -> false
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val file = controller.mediaItemFile(noteId, kind, items[page])
            if (file == null) {
                Text(stringResource("export_failed"), color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (kind.isVideo) {
                VideoView(file, Modifier.fillMaxSize())
            } else {
                val bitmap by produceState<ImageBitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { MediaThumbnails.get(file, false, 2600) } }
                bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕ " + stringResource("action_close"), color = Color.White) }
                Spacer(Modifier.weight(1f))
                if (items.size > 1) {
                    // Words for a screen reader (Android D274 step 1): the arrows alone say nothing.
                    val previousLabel = stringResource("desktop_previous")
                    val nextLabel = stringResource("desktop_next")
                    TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0, modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("←", color = Color.White) }
                    Text("${page + 1} / ${items.size}", color = Color.White)
                    TextButton(onClick = { if (page < items.lastIndex) page++ }, enabled = page < items.lastIndex, modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("→", color = Color.White) }
                }
            }
        }
    }
}

/** The camera (Android `CameraCapture`): its picture live, Take photo / Record and Stop, the camera to use when there are several. */
@Composable
private fun CameraDialog(noteId: String, kind: MediaKind, withMic: Boolean, controller: NotioController, onClose: () -> Unit) {
    val res = LocalRes.current
    val names = remember { runCatching { com.notio.desktop.media.Webcam.names() }.getOrDefault(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var saved by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    // The recording stops and is saved by itself after its limit (D251); a Stop before ends this wait.
    val videoLimit by controller.appSettings.recordingLimit(com.notio.shared.domain.RecordingKind.VIDEO).collectAsState()
    var thisLimit by remember { mutableStateOf(videoLimit) }
    val session = remember(index) {
        runCatching { CameraSession(index) }.onFailure { failed = true; com.notio.shared.AppLog.e("media", "the camera could not be opened", it) }.getOrNull()
    }
    // Stop pressed on the floating button or in the tray menu (D255): the video is kept the ordinary way.
    androidx.compose.runtime.LaunchedEffect(session) {
        controller.recordingKeeper.stopRequests.collect {
            if (recording && session?.isRecording == true) {
                session.stopRecording()?.let { controller.addCapturedMedia(noteId, kind, it); saved++ }
                recording = false
                controller.recordingKeeper.stop()
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(recording, session) {
        val ms = thisLimit.millis
        if (!recording || ms <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(ms)
        if (recording && session?.isRecording == true) {
            com.notio.shared.AppLog.i("media", "the time limit of the video was reached")
            session.stopRecording()?.let { controller.addCapturedMedia(noteId, kind, it); saved++ }
            recording = false
            controller.recordingKeeper.stop()
            tellRecordingLimitReached(controller)
        }
    }
    DisposableEffect(session) {
        session?.onFrame = { f -> frame = f.toImageBitmap() }
        onDispose {
            session?.let { s -> if (s.isRecording) s.stopRecording()?.let { controller.addCapturedMedia(noteId, kind, it) }; s.close() }
            controller.recordingKeeper.stop()
        }
    }
    val state = rememberDialogState(size = DpSize(900.dp, 720.dp), position = WindowPosition(Alignment.Center))
    DialogWindow(onCloseRequest = onClose, state = state, title = res.str(if (kind == MediaKind.PHOTO) "media_take_photo" else "media_record_video")) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    names.isEmpty() || failed -> Text(res.str("camera_failed"), color = Color.White)
                    else -> frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        ?: CircularProgressIndicator()
                }
            }
            if (names.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(res.str("camera_choose"), color = Color.White)
                names.forEachIndexed { i, n -> FilterChip(index == i, { if (!recording) index = i }, { Text(n) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                if (saved > 0) Text(res.str("camera_saved_count", saved), color = Color.White)
                if (kind == MediaKind.PHOTO) {
                    Button(enabled = session != null, onClick = {
                        session?.latest?.let { f -> controller.addPhoto(noteId, f); saved++ }
                    }) { Text(res.str("media_take_photo")) }
                } else if (!recording) {
                    Button(enabled = session != null, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
                        session?.let { s ->
                            runCatching { s.startRecording(controller.newMediaTempFile("mp4"), withMic, controller.videoOverlay(kind)) }.onSuccess {
                                recording = true
                                controller.recordingKeeper.start(noteId, microphone = withMic, camera = true)
                                // A limit typed for this recording only (D263) goes first, else the one of Settings.
                                thisLimit = RecordingLimitOnce.take(com.notio.shared.domain.RecordingKind.VIDEO, videoLimit)
                                warnRecordingLimit(controller, thisLimit)
                            }
                        }
                    }) { Text(res.str("rec_start")) }
                } else {
                    Button(onClick = {
                        session?.stopRecording()?.let { controller.addCapturedMedia(noteId, kind, it); saved++ }
                        recording = false
                        controller.recordingKeeper.stop()
                    }) { Text(res.str("rec_action_stop")) }
                    Text(res.str("rec_recording"), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onClose) { Text(res.str("action_close"), color = Color.White) }
            }
        }
    }
}
