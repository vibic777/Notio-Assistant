package com.notebudget.app.feature.media

import com.notebudget.app.core.common.AppLog
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.notebudget.app.core.ui.tip.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.notebudget.app.R
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.AudioSource
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.MediaItem
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.OverlaySettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One camera the phone has: its id, what it looks at, and a name to show. */
private class CameraChoice(val id: String, val label: String)

/**
 * The camera (D147): a live picture, the choice of the camera (back, front, and any other the phone offers), a shutter for a
 * photo or a record button for a video with its sound (none, microphone, the phone's own sound, both). The overlays set for
 * the note kind are laid over the result. A video keeps being filmed while the phone is turned: the window does not restart.
 * Photos can be taken one after another; the video button records until it is pressed again.
 */
@OptIn(ExperimentalCamera2Interop::class, UnstableApi::class)
@Composable
fun CameraCapture(noteId: String, kind: MediaKind, audio: AudioSource, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val screenLifecycle = LocalLifecycleOwner.current.lifecycle
    // The camera is bound to its OWN lifecycle (D255): it follows the screen, except that while a video is being recorded it
    // stays on when the app is minimised or the phone locked, so the recording goes on (with the foreground service below).
    val cameraLifecycle = remember { CameraLifecycle() }
    val lifecycleOwner = cameraLifecycle
    val keepAlive = remember { AndroidRecordingKeepAlive(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    // How long a camera video may last (D251): CameraX itself ends the file cleanly at the limit.
    val videoLimit by remember { container.settings.recordingLimit(com.notebudget.app.domain.model.RecordingKind.VIDEO) }
        .collectAsStateWithLifecycle(initialValue = com.notebudget.app.domain.model.RecordingLimit.DEFAULT)

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameras by remember { mutableStateOf<List<CameraChoice>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var busy by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var saved by remember { mutableIntStateOf(0) }
    val overlay by container.settings.overlay(kind).collectAsState(OverlaySettings())

    LaunchedEffect(Unit) {
        val p = suspendCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }
        provider = p
        val infos = p.availableCameraInfos
        val counts = HashMap<Int, Int>()
        cameras = infos.map { info ->
            val id = Camera2CameraInfo.from(info).cameraId
            val facing = info.lensFacing
            val n = (counts[facing] ?: 0) + 1
            counts[facing] = n
            val name = when (facing) {
                CameraSelector.LENS_FACING_BACK -> context.getString(R.string.camera_back)
                CameraSelector.LENS_FACING_FRONT -> context.getString(R.string.camera_front)
                else -> context.getString(R.string.camera_other)
            }
            CameraChoice(id, if (n > 1) "$name $n" else name)
        }
        selectedId = cameras.firstOrNull { it.label == context.getString(R.string.camera_back) }?.id ?: cameras.firstOrNull()?.id
    }

    // Binds the live picture and the use case of the kind to the chosen camera.
    LaunchedEffect(provider, selectedId) {
        val p = provider ?: return@LaunchedEffect
        val id = selectedId ?: return@LaunchedEffect
        if (recording != null) return@LaunchedEffect
        val selector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
            .build()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        p.unbindAll()
        runCatching {
            if (kind == MediaKind.PHOTO) {
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                imageCapture = capture
                videoCapture = null
                p.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            } else {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture
                imageCapture = null
                p.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            }
        }.onFailure {
            AppLog.critical("camera", "the camera could not be opened (" + kind.name + ", camera " + id + ")", it)
            Toast.makeText(context, context.getString(R.string.camera_failed), Toast.LENGTH_LONG).show()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            provider?.unbindAll()
            keepAlive.stop()
            cameraLifecycle.destroy()
        }
    }
    // The screen's own state reaches the camera only when no video is being recorded.
    val isRecording = recording != null
    androidx.compose.runtime.DisposableEffect(screenLifecycle, isRecording) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            cameraLifecycle.follow(screenLifecycle.currentState, keepOn = isRecording)
        }
        screenLifecycle.addObserver(observer)
        onDispose { screenLifecycle.removeObserver(observer) }
    }
    // Stop pressed in the notification or on the floating button (D255).
    LaunchedEffect(Unit) { keepAlive.stopRequests.collect { recording?.stop() } }
    LaunchedEffect(recording) {
        seconds = 0
        while (recording != null) { delay(1000); seconds++ }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingProjection?.invoke(if (result.resultCode == Activity.RESULT_OK) result else null)
        pendingProjection = null
    }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        val temp = container.mediaStore.newTemp(".jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(temp).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val whenMillis = System.currentTimeMillis()
                    scope.launch {
                        // A failure (out of memory on a huge picture, a full disk) is a "could not save", never a crash (rule 18).
                        val ok = withContext(Dispatchers.Default) {
                            runCatching { savePhoto(context, container, noteId, temp, overlay, whenMillis) }
                                .onFailure { AppLog.critical("camera", "the photo could not be saved", it) }
                                .getOrDefault(false)
                        }
                        temp.delete()
                        busy = false
                        if (ok) saved++ else Toast.makeText(context, context.getString(R.string.camera_failed), Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    temp.delete()
                    busy = false
                    Toast.makeText(context, context.getString(R.string.camera_failed), Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    fun beginVideo(withSystemAudio: Boolean) {
        val capture = videoCapture ?: return
        val temp = container.mediaStore.newTemp(".mp4")
        // A limit typed for this recording only (D263) goes first, else the one of Settings.
        val limit = com.notebudget.app.core.ui.RecordingLimitOnce.take(com.notebudget.app.domain.model.RecordingKind.VIDEO, videoLimit)
        val options = FileOutputOptions.Builder(temp).apply { if (limit.millis > 0) setDurationLimitMillis(limit.millis) }.build()
        com.notebudget.app.core.ui.warnRecordingLimit(context, limit)
        var pending = capture.output.prepareRecording(context, options)
        val micPermitted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // A plain microphone goes into the video by the camera itself; anything with the phone's own sound is put on afterwards.
        if (audio == AudioSource.MIC && micPermitted) {
            @Suppress("MissingPermission")
            pending = pending.withAudioEnabled()
        }
        val startedAt = System.currentTimeMillis()
        // Kept going in the background and with the screen off (D255).
        keepAlive.start(noteId, microphone = audio == AudioSource.MIC && micPermitted, camera = true)
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                keepAlive.stop()
                cameraLifecycle.follow(screenLifecycle.currentState, keepOn = false)
                if (event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                    AppLog.i("camera", "the time limit of the video was reached, " + temp.length() / 1024 + " KB")
                    com.notebudget.app.core.ui.tellRecordingLimitReached(context)
                } else if (event.hasError()) AppLog.critical("camera", "the video recording ended with error " + event.error + " (" + temp.length() / 1024 + " KB written)", event.cause)
                else AppLog.i("camera", "video recorded, " + temp.length() / 1024 + " KB")
                recording = null
                busy = true
                container.appScope.launch {
                    val wav = if (withSystemAudio) {
                        ScreenRecordService.stopAudio(context)
                        withTimeoutOrNull(6_000) { ScreenRecordService.audioResult.first { it != null } }?.file
                    } else null
                    val place = if (overlay.geo.enabled) LocationProvider.current(context) else null
                    val finisher = RecordingFinisher(context.applicationContext, container.mediaStore, container.mediaNoteWriter, container.settings, container.overlayImages)
                    val outcome = if (event.hasError() && temp.length() == 0L) null else runCatching {
                        finisher.finish(noteId, kind, temp, wav, audio, startedAt, place)
                    }.onFailure { AppLog.critical("camera", "the video could not be put together and saved", it) }.getOrNull()
                    if (outcome?.saved != true) AppLog.critical("camera", "the video was not saved")
                    withContext(Dispatchers.Main) {
                        busy = false
                        if (outcome?.saved == true) saved++ else Toast.makeText(context, context.getString(R.string.camera_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun toggleVideo() {
        val current = recording
        if (current != null) { current.stop(); return }
        if (audio.usesSystem) {
            // The phone's own sound needs the permission to capture the screen, whose dialog comes first.
            pendingProjection = { result ->
                if (result?.data != null) {
                    ScreenRecordService.startAudio(context, result.resultCode, result.data!!, audio)
                    scope.launch { delay(700); beginVideo(withSystemAudio = true) }
                }
            }
            projectionLauncher.launch(ScreenRecordService.permissionIntent(context))
        } else {
            beginVideo(withSystemAudio = false)
        }
    }

    Dialog(onDismissRequest = { if (recording == null && !busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = recording == null && !busy)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, enabled = recording == null && !busy) { Text("✕ " + stringResource(R.string.action_close), color = Color.White) }
                Box(Modifier.weight(1f))
                if (cameras.size > 1) {
                    Box {
                        OutlinedButton(onClick = { menuOpen = true }, enabled = recording == null && !busy) {
                            Text(cameras.firstOrNull { it.id == selectedId }?.label ?: stringResource(R.string.camera_choose), color = Color.White)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            cameras.forEach { c -> DropdownMenuItem(text = { Text(c.label) }, onClick = { selectedId = c.id; menuOpen = false }) }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved > 0) Text(stringResource(R.string.camera_saved_count, saved), color = Color.White)
                if (recording != null) Text(mediaDuration(seconds * 1000), color = Color.Red, style = MaterialTheme.typography.titleLarge)
                if (busy) {
                    CircularProgressIndicator(color = Color.White)
                } else if (kind == MediaKind.PHOTO) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).padding(6.dp).clip(CircleShape).background(Color(0xFFEEEEEE)).clickable { takePhoto() })
                } else {
                    Button(
                        onClick = { toggleVideo() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth(0.6f),
                    ) { Text(stringResource(if (recording == null) R.string.rec_start else R.string.rec_action_stop)) }
                }
            }
        }
    }
}

private var pendingProjection: ((androidx.activity.result.ActivityResult?) -> Unit)? = null

/** A photo file from the camera becomes an item: turned upright, overlays drawn, a small picture made, added to the note. */
private suspend fun savePhoto(context: Context, container: com.notebudget.app.core.common.AppContainer, noteId: String, temp: File, overlay: OverlaySettings, whenMillis: Long): Boolean {
    val place: GeoPoint? = if (overlay.geo.enabled) LocationProvider.current(context) else null
    val logo = OverlayRenderer.loadLogo(overlay.image.file.takeIf { it.isNotBlank() }?.let { container.overlayImages.file(it) }, overlay, 2000)
    val result = PhotoComposer.compose(temp, overlay, OverlayInputs(whenMillis, place, logo)) ?: return false
    val item = MediaItem(id = MediaItem.newId(), createdAt = whenMillis, width = result.width, height = result.height, place = place)
    return container.mediaNoteWriter.add(noteId, MediaKind.PHOTO, item, source = null, bytes = result.jpeg, thumbnail = result.thumbnail)
}

/**
 * The lifecycle the camera is bound to (D255): it follows the screen ([follow]), but [follow] with keepOn holds it resumed
 * while a video is being recorded, so that CameraX does not close the camera when the app goes to the background.
 */
private class CameraLifecycle : androidx.lifecycle.LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    fun follow(screen: androidx.lifecycle.Lifecycle.State, keepOn: Boolean) {
        if (registry.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) return
        val next = when {
            keepOn -> androidx.lifecycle.Lifecycle.State.RESUMED
            screen == androidx.lifecycle.Lifecycle.State.DESTROYED -> androidx.lifecycle.Lifecycle.State.CREATED
            screen.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) -> androidx.lifecycle.Lifecycle.State.RESUMED
            screen.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) -> androidx.lifecycle.Lifecycle.State.STARTED
            else -> androidx.lifecycle.Lifecycle.State.CREATED
        }
        if (registry.currentState != next) registry.currentState = next
    }

    fun destroy() {
        if (registry.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED) {
            if (registry.currentState == androidx.lifecycle.Lifecycle.State.INITIALIZED) registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
            registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        }
    }
}
