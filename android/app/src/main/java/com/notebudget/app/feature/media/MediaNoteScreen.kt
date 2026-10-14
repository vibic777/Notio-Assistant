package com.notebudget.app.feature.media

import com.notebudget.app.core.ui.QuickScroll
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.notebudget.app.R
import com.notebudget.app.core.ui.ConfirmDeleteMany
import com.notebudget.app.core.ui.SelectionBar
import com.notebudget.app.core.ui.StampLines
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.core.ui.rememberSelectionState
import com.notebudget.app.domain.model.AudioSource
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.OverlaySettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `m:ss`, or `h:mm:ss` for long recordings. */
fun mediaDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The editor of a photo, camera-video or screen-recording note (D147): a list of items, each with a small picture, a name,
 * a comment, its stamps and the place; tap to look at it (full screen, swipe between photos, turn the phone), play a video
 * in the note or full screen; pick several with check marks to delete them; and, at the bottom, what makes a new item:
 * the camera, or the screen recorder (the app steps aside, a Stop button ends the recording). Overlays are set here too.
 */
@OptIn(ExperimentalLayoutApi::class, UnstableApi::class)
@Composable
fun MediaNoteScreen(noteId: String, kind: MediaKind, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: MediaViewModel = appViewModel {
        MediaViewModel(
            noteId, kind, it.noteRepository, it.mediaNoteWriter, it.mediaStore, it.settings,
            defaultName = { n -> it.appContext.getString(R.string.media_default_name, n) },
        )
    }
    val container = com.notebudget.app.core.ui.rememberAppContainer()
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by ScreenRecordService.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val selection = rememberSelectionState()
    var confirmDeleteMany by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var deleting by remember { mutableStateOf<MediaItem?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var showOverlay by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(AudioSource.NONE) }
    // The floating Stop button of a screen recording (D249): a switch, since on some phones Android stops the capture because of it.
    val floatingStop by container.settings.screenFloatingStop.collectAsStateWithLifecycle(initialValue = true)
    // How long a recording may last (D251): shown under the button, said when it starts, handed to the recorder.
    val limitKind = if (kind == MediaKind.SCREEN) com.notebudget.app.domain.model.RecordingKind.SCREEN else com.notebudget.app.domain.model.RecordingKind.VIDEO
    val recordingLimit by remember(limitKind) { container.settings.recordingLimit(limitKind) }
        .collectAsStateWithLifecycle(initialValue = com.notebudget.app.domain.model.RecordingLimit.DEFAULT)
    var pendingExport by remember { mutableStateOf<MediaExport?>(null) }
    val overlay by container.settings.overlay(kind).collectAsStateWithLifecycle(initialValue = OverlaySettings())
    LaunchedEffect(state.items) { selection.retain(state.items.map { it.id }) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(kind.mime)) { uri: Uri? ->
        val export = pendingExport
        pendingExport = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> export.file.inputStream().use { it.copyTo(out) } } ?: error("Cannot open $uri")
                }
            }.isSuccess
            Toast.makeText(context, context.getString(if (ok) R.string.export_done else R.string.export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- permissions and the two ways to make an item ----
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // A permission that was refused only takes its own feature away (no sound, no place); the rest still works.
        val next = afterPermissions
        afterPermissions = null
        next?.invoke()
    }
    fun withPermissions(wanted: List<String>, action: () -> Unit) {
        val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { afterPermissions = action; permissionLauncher.launch(missing.toTypedArray()) }
    }
    fun locationPermissions() = if (overlay.geo.enabled) listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList()

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // A limit typed for this recording only (D263) goes first, else the one of Settings.
            val limit = com.notebudget.app.core.ui.RecordingLimitOnce.take(limitKind, recordingLimit)
            com.notebudget.app.core.ui.warnRecordingLimit(context, limit)
            ScreenRecordService.start(context, result.resultCode, data, noteId, audio, floatingStop, limit.millis)
            // The app steps aside: the person goes to what is to be recorded. Noted in the log (D249: when does Android stop it?).
            com.notebudget.app.core.common.AppLog.i("screen", "the app steps aside to the background")
            context.findActivity()?.moveTaskToBack(true)
        }
    }
    // Notio restricted in the background by the battery settings (D249): the recording would stop as soon as the person leaves.
    var restrictedWarning by remember { mutableStateOf(false) }
    fun startScreenAnyway() {
        val wanted = buildList {
            if (audio.usesMic) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            addAll(locationPermissions())
        }
        withPermissions(wanted) { projectionLauncher.launch(ScreenRecordService.permissionIntent(context)) }
    }
    fun startScreen() {
        if (com.notebudget.app.core.common.BackgroundRestriction.isRestricted(context)) restrictedWarning = true else startScreenAnyway()
    }
    if (restrictedWarning) {
        com.notebudget.app.core.ui.AppAlertDialog(
            onDismissRequest = { restrictedWarning = false },
            title = { Text(stringResource(R.string.rec_restricted_title)) },
            text = { Text(stringResource(R.string.rec_restricted_text)) },
            confirmButton = {
                TextButton(onClick = { restrictedWarning = false; com.notebudget.app.core.common.BackgroundRestriction.openSettings(context) }) {
                    Text(stringResource(R.string.rec_restricted_open))
                }
            },
            dismissButton = { TextButton(onClick = { restrictedWarning = false; startScreenAnyway() }) { Text(stringResource(R.string.rec_restricted_anyway)) } },
        )
    }
    fun openCamera() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            if (kind == MediaKind.VIDEO && audio.usesMic) add(Manifest.permission.RECORD_AUDIO)
            addAll(locationPermissions())
        }
        withPermissions(wanted) { showCamera = true }
    }

    val busy = screen is ScreenState.Recording || screen is ScreenState.Processing
    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionBar(
                count = selection.picked.size, total = state.items.size,
                onSelectAll = { selection.selectAll(state.items.map { it.id }) }, onClear = selection::clear,
                onDelete = { confirmDeleteMany = true }, onDone = selection::end,
            )
        } else if (state.items.isNotEmpty()) {
            // D278: an icon, a long press says "Select".
            com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Checklist, stringResource(R.string.media_select), { selection.begin() }, Modifier.padding(start = 8.dp))
        }

        // The search of the note (D248): the names as shown and the comments of the items.
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val resources = androidx.compose.ui.platform.LocalResources.current
        val searchable = remember(state.items, resources) {
            state.items.mapIndexed { index, item ->
                com.notebudget.app.core.ui.SearchableItem(item.id, item.name.ifBlank { resources.getString(R.string.media_default_name, index + 1) }, item.comment)
            }
        }
        com.notebudget.app.core.ui.ItemSearchEffects(searchable, listState)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loaded && state.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(if (kind == MediaKind.PHOTO) R.string.media_empty_photo else if (kind == MediaKind.VIDEO) R.string.media_empty_video else R.string.media_empty_screen),
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                }
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    ItemCard(
                        vm = vm, item = item, title = item.name.ifBlank { stringResource(R.string.media_default_name, index + 1) },
                        selecting = selection.active, checked = item.id in selection.picked, playing = playingId == item.id,
                        onToggle = { selection.toggle(item.id) },
                        onOpen = { if (kind.isVideo) playingId = if (playingId == item.id) null else item.id else viewing = index },
                        onFullScreen = { viewing = index },
                        onEdit = { editing = item },
                        onExport = {
                            scope.launch {
                                val export = vm.prepareExport(item)
                                if (export == null) Toast.makeText(context, context.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                                else { pendingExport = export; exportLauncher.launch(export.fileName) }
                            }
                        },
                        onDelete = { deleting = item },
                    )
                }
                item(key = "footer") { com.notebudget.app.core.ui.NoteFooter() }
            }
            QuickScroll(listState)
        }

        // ---- what makes a new item ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (kind != MediaKind.PHOTO) AudioChoice(audio, onChange = { audio = it }, enabled = !busy)
            if (kind == MediaKind.SCREEN || kind == MediaKind.VIDEO) {
                // The whole line switches, not only the small switch: easier for a finger.
                Row(
                    Modifier.toggleable(value = floatingStop, enabled = !busy, role = androidx.compose.ui.semantics.Role.Switch) { on -> scope.launch { container.settings.setScreenFloatingStop(on) } },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Switch(checked = floatingStop, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.rec_floating_switch), style = MaterialTheme.typography.bodyMedium)
                }
                if (!floatingStop) {
                    Text(stringResource(R.string.rec_floating_off_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            when (val s = screen) {
                is ScreenState.Recording -> if (kind == MediaKind.SCREEN && s.noteId == noteId) {
                    Text(stringResource(R.string.rec_recording), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { ScreenRecordService.stop(context) }) { Text(stringResource(R.string.rec_action_stop)) }
                }
                is ScreenState.Processing -> if (kind == MediaKind.SCREEN && s.noteId == noteId) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.rec_processing))
                    }
                }
                is ScreenState.Failed -> if (kind == MediaKind.SCREEN && s.noteId == noteId) Text(stringResource(R.string.rec_failed), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            if (!busy || kind != MediaKind.SCREEN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (kind == MediaKind.SCREEN) startScreen() else openCamera() },
                        colors = if (kind == MediaKind.PHOTO) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        enabled = !busy,
                    ) { Text(stringResource(if (kind == MediaKind.PHOTO) R.string.media_take_photo else if (kind == MediaKind.VIDEO) R.string.media_record_video else R.string.media_record_screen)) }
                    OutlinedButton(onClick = { showOverlay = true }) { Text(stringResource(R.string.overlay_title)) }
                }
                if (kind != MediaKind.PHOTO) com.notebudget.app.core.ui.RecordingLimitLine(recordingLimit, kind = limitKind)
            }
            val resumed = com.notebudget.app.core.ui.rememberResumeCount()
            val overlayAllowed = androidx.compose.runtime.remember(resumed) { Settings.canDrawOverlays(context) }
            if ((kind == MediaKind.SCREEN || kind == MediaKind.VIDEO) && floatingStop && !overlayAllowed) {
                Text(stringResource(R.string.rec_floating_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)))
                }) { Text(stringResource(R.string.rec_floating_allow)) }
            }
        }
    }

    if (showOverlay) {
        OverlayDialog(kind, overlay, onSave = { scope.launch { container.settings.setOverlay(kind, it) }; showOverlay = false }, onDismiss = { showOverlay = false })
    }
    if (showCamera) {
        CameraCapture(noteId = noteId, kind = kind, audio = audio, onClose = { showCamera = false })
    }
    viewing?.let { start ->
        MediaViewer(vm = vm, items = state.items, startIndex = start.coerceIn(0, (state.items.size - 1).coerceAtLeast(0)), onClose = { viewing = null })
    }
    if (confirmDeleteMany) {
        ConfirmDeleteMany(selection.picked.size, onConfirm = { vm.deleteMany(selection.picked); confirmDeleteMany = false; selection.end() }, onDismiss = { confirmDeleteMany = false })
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.media_delete_title)) },
            text = { Text(stringResource(R.string.media_delete_message)) },
            confirmButton = { TextButton(onClick = { vm.deleteMany(setOf(item.id)); deleting = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    editing?.let { item -> ItemEditDialog(item, onConfirm = { name, comment -> vm.edit(item.id, name, comment); editing = null }, onDismiss = { editing = null }) }
}

/** none / microphone / system / microphone + system; the phone's own sound needs Android 10. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioChoice(current: AudioSource, onChange: (AudioSource) -> Unit, enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.rec_sound), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = buildList {
                add(AudioSource.NONE to R.string.rec_sound_none)
                add(AudioSource.MIC to R.string.rec_sound_mic)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(AudioSource.SYSTEM to R.string.rec_sound_system)
                    add(AudioSource.BOTH to R.string.rec_sound_both)
                }
            }
            options.forEach { (source, label) -> FilterChip(current == source, { onChange(source) }, { Text(stringResource(label)) }, enabled = enabled) }
        }
    }
}

@Composable
private fun ItemCard(
    vm: MediaViewModel, item: MediaItem, title: String, selecting: Boolean, checked: Boolean, playing: Boolean,
    onToggle: () -> Unit, onOpen: () -> Unit, onFullScreen: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    Card(if (selecting) Modifier.fillMaxWidth().clickable(onClick = onToggle) else Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(com.notebudget.app.core.ui.highlighted(title, com.notebudget.app.core.ui.ItemHitKey(item.id, 0)), style = MaterialTheme.typography.titleMedium)
                    if (item.durationMs > 0) Text(mediaDuration(item.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!selecting) Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.audio_clip_menu)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_edit)) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.media_full_screen)) }, onClick = { menu = false; onFullScreen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.audio_export_clip)) }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            val file = vm.file(item)
            if (playing && file != null) {
                InlineVideo(file, Modifier.fillMaxWidth().height(220.dp))
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Fullscreen, stringResource(R.string.media_full_screen), onFullScreen)
            } else {
                Thumbnail(vm, item, Modifier.fillMaxWidth().height(180.dp).clickable(enabled = !selecting, onClick = onOpen), showPlay = vm.kind.isVideo)
            }
            if (item.comment.isNotBlank()) Text(com.notebudget.app.core.ui.highlighted(item.comment, com.notebudget.app.core.ui.ItemHitKey(item.id, 1)), style = MaterialTheme.typography.bodyMedium)
            item.place?.let { Text(stringResource(R.string.media_place, it.text()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            StampLines(item.createdAt, item.updatedAt.takeIf { it > item.createdAt } ?: 0)
        }
    }
}

/** The small picture of an item; made from the file the first time it is needed and kept next to it. */
@Composable
private fun Thumbnail(vm: MediaViewModel, item: MediaItem, modifier: Modifier, showPlay: Boolean) {
    val bitmap by produceState<ImageBitmap?>(null, item.id) { value = withContext(Dispatchers.IO) { loadThumbnail(vm, item) } }
    Box(modifier.background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        if (showPlay) Text("▶", color = Color.White, style = MaterialTheme.typography.displaySmall)
    }
}

private fun loadThumbnail(vm: MediaViewModel, item: MediaItem): ImageBitmap? = loadMediaThumbnail(vm.store, vm.kind, vm.key(item))

/** A picture decoded no bigger than needed for [longest] pixels, so a 12-megapixel photo does not fill the memory. */
private fun decodeSampled(file: File, longest: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun InlineVideo(file: File, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

/**
 * Full screen (D147): photos in a pager (swipe, pinch to zoom, double tap resets), a video with its controls. It follows the
 * turn of the phone: the app does not lock the orientation, and the dialog fills the window whichever way it is.
 */
@Composable
private fun MediaViewer(vm: MediaViewModel, items: List<MediaItem>, startIndex: Int, onClose: () -> Unit) {
    if (items.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(initialPage = startIndex) { items.size }
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = true) { page ->
                val item = items[page]
                val file = vm.file(item)
                if (file == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.export_failed), color = Color.White) }
                } else if (vm.kind.isVideo) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            VideoView(context).apply {
                                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                                setVideoURI(Uri.fromFile(file))
                                setOnPreparedListener { start() }
                            }
                        },
                        onRelease = { it.stopPlayback() },
                    )
                } else {
                    ZoomableImage(file)
                }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕ " + stringResource(R.string.action_close), color = Color.White) }
                Spacer(Modifier.weight(1f))
                if (items.size > 1) Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, modifier = Modifier.padding(end = 12.dp))
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { decodeSampled(file, 2600)?.asImageBitmap() } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offsetX = 0f; offsetY = 0f })
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY },
            )
        }
    }
}

@Composable
private fun ItemEditDialog(item: MediaItem, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var comment by remember(item.id) { mutableStateOf(item.comment) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(MediaViewModel.MAX_NAME) }, label = { Text(stringResource(R.string.audio_name_label)) }, singleLine = true)
                OutlinedTextField(comment, { comment = it.take(MediaViewModel.MAX_COMMENT) }, label = { Text(stringResource(R.string.audio_comment_label)) }, minLines = 2, maxLines = 6)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, comment) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
