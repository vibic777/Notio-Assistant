package com.notebudget.app.feature.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.R
import com.notebudget.app.core.common.AppLog
import com.notebudget.app.MainActivity
import com.notebudget.app.core.notification.OpenNoteRequest
import com.notebudget.app.domain.model.AudioSource
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.MediaKind
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the screen recorder is doing, for the note screen that started it. */
sealed interface ScreenState {
    data object Idle : ScreenState
    data class Recording(val noteId: String, val startedAt: Long) : ScreenState
    data class Processing(val noteId: String) : ScreenState
    data class Saved(val noteId: String) : ScreenState
    data class Failed(val noteId: String) : ScreenState
}

/** The mixed sound of a camera video: a WAV file, or `null` when nothing could be recorded. */
class AudioResult(val file: File?)

/**
 * Records the screen (D147). A foreground service, because the recording goes on while the app is in the background: the
 * person starts it, the app steps aside, and a Stop button - in the notification, and a small floating one over all windows
 * when the "display over other apps" permission is given - ends and saves it. Video comes from a virtual display into a
 * [MediaRecorder]; the sound (microphone, the phone's own sound, both) is recorded apart by [AudioCapture]; when the person
 * stops, [RecordingFinisher] puts overlays and sound on the video and adds it to the note.
 */
@UnstableApi
class ScreenRecordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var capture: AudioCapture? = null
    private var rawFile: File? = null
    private var floating: View? = null
    private var noteId: String = ""
    private var audio: AudioSource = AudioSource.NONE
    private var startedAt = 0L
    private var place: GeoPoint? = null
    private var floatingWanted = true
    private var limitMillis = 0L

    /** Stops the recording at its time limit (D251); removed by any other stop. */
    private val limitStop = Runnable {
        if (state.value is ScreenState.Recording) {
            stopRecording("the time limit of the recording")
            com.notebudget.app.core.ui.tellRecordingLimitReached(this)
        }
    }
    private var floatingShownAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) AppLog.i(TAG, "command " + (intent?.action?.substringAfterLast('.') ?: "none") + ", state " + state.value.javaClass.simpleName)
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopRecording("the Stop button (notification or note)")
            ACTION_AUDIO_START -> startAudioOnly(intent)
            ACTION_AUDIO_STOP -> stopAudioOnly()
            else -> if (state.value !is ScreenState.Recording) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun container() = (application as NoteBudgetApplication).container

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: Int, withStop: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(OpenNoteRequest.ACTION_OPEN_NOTE)
                .putExtra(OpenNoteRequest.EXTRA_NOTE_ID, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.rec_notification_title))
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (withStop) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.rec_action_stop), stop)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun start(intent: Intent) {
        if (state.value is ScreenState.Recording) return
        noteId = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        audio = AudioSource.of(intent.getStringExtra(EXTRA_AUDIO))
        // The saved switch decides, read here (D249): in 0.9.55 the button turned off by the app itself came back on the next
        // recording, because only the screen's copy of the switch was handed over. A short read of the settings file.
        val saved = runCatching {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(500) { container().settings.screenFloatingStop.first() } }
        }.getOrNull()
        floatingWanted = intent.getBooleanExtra(EXTRA_FLOATING, true) && saved != false
        limitMillis = intent.getLongExtra(EXTRA_LIMIT, 0L)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        channel()
        val micAllowed = audio.usesMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        AppLog.i(TAG, "start: sound " + audio.name + ", microphone allowed " + micAllowed + ", overlay button allowed " + Settings.canDrawOverlays(this) + ", wanted " + floatingWanted +
            ", restricted in the background " + com.notebudget.app.core.common.BackgroundRestriction.isRestricted(this))
        val note = notification(R.string.rec_notification_text, withStop = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // The microphone type exists from Android 11; on 10 an unknown type makes startForeground fail (D231).
                if (micAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, note, types)
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
        } catch (e: Exception) {
            AppLog.critical(TAG, "the recording service could not come to the foreground", e)
            return fail()
        }
        if (data == null || code == 0 || noteId.isEmpty()) {
            AppLog.critical(TAG, "start without the screen permission (code $code, data " + (data != null) + ", note " + noteId.isNotEmpty() + ")")
            return fail()
        }
        // The floating button comes BEFORE the capture starts (D249): on a Samsung with Android 14 a window that appeared over
        // the screen while it was being captured made Android stop the capture 0.2 s later.
        if (floatingWanted) showFloatingStop()
        var step = "projection"
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(code, data) ?: error("no projection")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.w(TAG, "Android stopped the screen capture (" + since() + ")")
                    main.post {
                        if (state.value is ScreenState.Recording) {
                            explainStop()
                            stopRecording("Android stopped the screen capture")
                        }
                    }
                }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    AppLog.i(TAG, "captured content visible: $isVisible (" + since() + ")")
                }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    AppLog.i(TAG, "captured content size ${width}x$height (" + since() + ")")
                }
            }, main)

            val metrics = resources.displayMetrics
            val scale = minOf(1f, MAX_SIDE / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val w = (metrics.widthPixels * scale).toInt() and 1.inv()
            val h = (metrics.heightPixels * scale).toInt() and 1.inv()
            step = "recorder"
            AppLog.i(TAG, "screen ${metrics.widthPixels}x${metrics.heightPixels} @${metrics.densityDpi} dpi, video ${w}x$h")
            val store = container().mediaStore
            val file = store.newTemp(".mp4").also { rawFile = it }
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, h)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate((w * h * 4).coerceIn(2_000_000, 12_000_000))
            r.setOutputFile(file.absolutePath)
            // The encoder's own errors and limits: before D230 they went unnoticed.
            r.setOnErrorListener { _, what, extra -> AppLog.critical(TAG, "the video recorder failed: what $what, extra $extra (" + since() + ")") }
            r.setOnInfoListener { _, what, extra -> AppLog.w(TAG, "the video recorder says: what $what, extra $extra (" + since() + ")") }
            r.prepare()
            recorder = r
            step = "virtual display"
            display = p.createVirtualDisplay("notio-screen", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            step = "recorder start"
            r.start()
            startedAt = System.currentTimeMillis()
            if (audio != AudioSource.NONE) {
                step = "sound"
                capture = AudioCapture(audio, p, File(cacheDir, "audio")).also { c ->
                    c.start()
                    if (c.failed) AppLog.e(TAG, "a sound source did not start (" + audio.name + "): the video goes on with what started")
                }
            }
            // The place is noted at the start, when the overlay asks for it.
            container().appScope.launch {
                val wantsPlace = container().settings.overlay(MediaKind.SCREEN).first().geo.enabled
                if (wantsPlace) place = LocationProvider.current(applicationContext)
            }
            state.value = ScreenState.Recording(noteId, startedAt)
            if (limitMillis > 0) main.postDelayed(limitStop, limitMillis)
            AppLog.i(TAG, "recording")
        } catch (e: Exception) {
            AppLog.critical(TAG, "the recording could not start (at: $step)", e)
            fail()
        }
    }

    /**
     * Android ended the capture on its own soon after the start (D249). On the user's Samsung the reason was the battery setting of
     * Notio ("Restricted": no work in the background); the person is told what to change instead of guessing.
     */
    private fun explainStop() {
        val restricted = com.notebudget.app.core.common.BackgroundRestriction.isRestricted(this)
        if (!com.notebudget.app.core.common.BackgroundRestriction.explainsStop(System.currentTimeMillis() - startedAt, restricted)) return
        AppLog.critical(TAG, "Android stopped the capture (" + since() + ") while Notio is restricted in the background by the battery settings")
        main.post { android.widget.Toast.makeText(applicationContext, getString(R.string.rec_restricted_stopped), android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun since(): String =
        if (startedAt > 0) "%.1f s after the start".format(java.util.Locale.ROOT, (System.currentTimeMillis() - startedAt) / 1000.0) else "before the start"

    // ---- the phone's own sound for a camera video (D147): the same permission and service, without a picture ----

    @SuppressLint("MissingPermission")
    private fun startAudioOnly(intent: Intent) {
        audio = AudioSource.of(intent.getStringExtra(EXTRA_AUDIO))
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        noteId = ""
        channel()
        val micAllowed = audio.usesMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val note = notification(R.string.rec_notification_text, withStop = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                // The microphone type exists from Android 11; on 10 an unknown type makes startForeground fail (D231).
                if (micAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, note, types)
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
        } catch (e: Exception) {
            AppLog.critical(TAG, "the sound service for a camera video could not come to the foreground", e)
            return finishAudioOnly(null)
        }
        audioResult.value = null
        if (data == null || code == 0) return finishAudioOnly(null)
        try {
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) ?: error("no projection")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.w(TAG, "Android stopped the capture of the phone sound for a camera video")
                    main.post { if (capture != null) stopAudioOnly() }
                }
            }, main)
            capture = AudioCapture(audio, p, File(cacheDir, "audio")).also { it.start() }
        } catch (e: Exception) {
            AppLog.e(TAG, "the phone sound for a camera video could not start", e)
            finishAudioOnly(null)
        }
    }

    private fun stopAudioOnly() {
        val wav = runCatching { capture?.stop() }.getOrNull()
        capture = null
        finishAudioOnly(wav)
    }

    private fun finishAudioOnly(wav: File?) {
        runCatching { projection?.stop() }
        projection = null
        audioResult.value = AudioResult(wav)
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail() {
        cleanUp()
        state.value = ScreenState.Failed(noteId)
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanUp() {
        main.removeCallbacks(limitStop)
        hideFloatingStop()
        runCatching { capture?.stop()?.delete() }
        capture = null
        runCatching { display?.release() }
        display = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun stopRecording(reason: String) {
        val current = state.value as? ScreenState.Recording ?: return
        AppLog.i(TAG, "stop: $reason (" + since() + ")")
        main.removeCallbacks(limitStop)
        hideFloatingStop()
        val wav = runCatching { capture?.stop() }.onFailure { AppLog.e(TAG, "the sound could not be finished", it) }.getOrNull()
        capture = null
        val valid = runCatching { recorder?.stop() }.onFailure { AppLog.critical(TAG, "the video could not be finished (too short or the encoder failed)", it) }.isSuccess
        runCatching { display?.release() }
        display = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { projection?.stop() }
        projection = null
        val raw = rawFile
        if (!valid || raw == null || raw.length() == 0L) {
            AppLog.critical(TAG, "the recording is lost: finished $valid, file " + (raw?.length() ?: -1) + " bytes")
            raw?.delete()
            wav?.delete()
            state.value = ScreenState.Failed(current.noteId)
            stopForegroundCompat()
            stopSelf()
            return
        }
        state.value = ScreenState.Processing(current.noteId)
        (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification(R.string.rec_processing, withStop = false))
        val c = container()
        val finisher = RecordingFinisher(applicationContext, c.mediaStore, c.mediaNoteWriter, c.settings, c.overlayImages)
        c.appScope.launch {
            AppLog.i(TAG, "video " + raw.length() / 1024 + " KB, sound " + (wav?.let { (it.length() / 1024).toString() + " KB" } ?: "none") + "; putting it together")
            val outcome = runCatching { finisher.finish(current.noteId, MediaKind.SCREEN, raw, wav, audio, current.startedAt, place) }
                .onFailure { AppLog.critical(TAG, "the recording could not be put together and saved", it) }.getOrNull()
            if (outcome?.saved == true) AppLog.i(TAG, "saved to the note") else if (outcome != null) AppLog.critical(TAG, "the recording was not saved")
            state.value = if (outcome?.saved == true) ScreenState.Saved(current.noteId) else ScreenState.Failed(current.noteId)
            main.post {
                stopForegroundCompat()
                val done = NotificationCompat.Builder(this@ScreenRecordService, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(getString(R.string.rec_notification_title))
                    .setContentText(getString(if (outcome?.saved == true) R.string.rec_saved else R.string.rec_failed))
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            this@ScreenRecordService, 2,
                            Intent(this@ScreenRecordService, MainActivity::class.java).setAction(OpenNoteRequest.ACTION_OPEN_NOTE)
                                .putExtra(OpenNoteRequest.EXTRA_NOTE_ID, current.noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                    .build()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, done)
                stopSelf()
            }
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * A small round Stop button over every window. It is a window of this app, so a screen recording may show it; on Android
     * 15 and later it is marked as sensitive content, which keeps it out of recordings. Only with the "display over other
     * apps" permission; without it the notification's Stop button is the way.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingStop() {
        if (!Settings.canDrawOverlays(this)) return
        val size = (56 * resources.displayMetrics.density).toInt()
        val button = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#D32F2F")); setStroke(3, Color.WHITE) }
            contentDescription = getString(R.string.rec_action_stop)
            addView(
                View(context).apply { background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 4f } },
                FrameLayout.LayoutParams(size / 3, size / 3, Gravity.CENTER),
            )
            if (Build.VERSION.SDK_INT >= 35) runCatching { setContentSensitivity(View.CONTENT_SENSITIVITY_SENSITIVE) }
        }
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = (160 * resources.displayMetrics.density).toInt() }
        val windows = getSystemService(WindowManager::class.java)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    // Gravity END: the x offset grows towards the left.
                    params.x = startX - dx.toInt(); params.y = startY + dy.toInt()
                    runCatching { windows.updateViewLayout(button, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) stopRecording("the floating Stop button"); true }
                else -> false
            }
        }
        runCatching { windows.addView(button, params); floating = button; floatingShownAt = System.currentTimeMillis() }
            .onSuccess { AppLog.i(TAG, "the floating Stop button is shown") }
            .onFailure { AppLog.e(TAG, "the floating Stop button could not be shown", it) }
    }

    private fun hideFloatingStop() {
        floating?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        floating = null
        floatingShownAt = 0L
    }

    override fun onDestroy() {
        if (state.value is ScreenState.Recording) {
            AppLog.critical(TAG, "the recording service was ended while recording (" + since() + ")")
            cleanUp()
        }
        // The button is shown before the capture now (D249): it must never outlive the service, whatever ended it.
        hideFloatingStop()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.critical(TAG, "the time limit of Android for the foreground service ran out (type $fgsType)")
        if (state.value is ScreenState.Recording) stopRecording("the time limit of Android") else stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.w(TAG, "the app was swiped away from the recent apps (" + state.value.javaClass.simpleName + ")")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        if (state.value is ScreenState.Recording && level >= TRIM_MEMORY_BACKGROUND) AppLog.w(TAG, "Android is short of memory (level $level, " + since() + ")")
        super.onTrimMemory(level)
    }

    companion object {
        private const val ACTION_START = "com.notebudget.app.action.SCREEN_START"
        private const val ACTION_STOP = "com.notebudget.app.action.SCREEN_STOP"
        private const val ACTION_AUDIO_START = "com.notebudget.app.action.AUDIO_START"
        private const val ACTION_AUDIO_STOP = "com.notebudget.app.action.AUDIO_STOP"
        private const val EXTRA_NOTE = "note"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "screen_recording"
        private const val NOTIFICATION_ID = 7301
        private const val MAX_SIDE = 1920f
        private const val EXTRA_FLOATING = "floating"
        private const val EXTRA_LIMIT = "limit"

        private const val TAG = "screen"

        val state = MutableStateFlow<ScreenState>(ScreenState.Idle)

        /** The sound recorded for a camera video, once [stopAudio] has been called; `null` while nothing has come yet. */
        val audioResult = MutableStateFlow<AudioResult?>(null)

        fun startAudio(context: Context, resultCode: Int, data: Intent, audio: AudioSource) {
            audioResult.value = null
            context.startForegroundService(
                Intent(context, ScreenRecordService::class.java).setAction(ACTION_AUDIO_START)
                    .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data).putExtra(EXTRA_AUDIO, audio.name),
            )
        }

        fun stopAudio(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_AUDIO_STOP))
        }

        /** The system dialog that asks the person for permission to capture the screen. */
        fun permissionIntent(context: Context): Intent =
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

        fun start(context: Context, resultCode: Int, data: Intent, noteId: String, audio: AudioSource, floatingStop: Boolean = true, limitMillis: Long = 0L) {
            val intent = Intent(context, ScreenRecordService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data).putExtra(EXTRA_NOTE, noteId).putExtra(EXTRA_AUDIO, audio.name)
                .putExtra(EXTRA_FLOATING, floatingStop).putExtra(EXTRA_LIMIT, limitMillis)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
