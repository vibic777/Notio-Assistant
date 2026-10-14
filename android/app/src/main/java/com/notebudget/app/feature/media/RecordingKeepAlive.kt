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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.notebudget.app.MainActivity
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.R
import com.notebudget.app.core.common.AppLog
import com.notebudget.app.core.notification.OpenNoteRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

/**
 * Keeps a voice or camera recording going while the app is in the background or the phone is locked (D255): a foreground
 * service of the microphone / camera type, started when Record is pressed (Android allows it only from the screen), with a
 * Stop in its notification and a small floating Stop button. The recording itself stays where it is (the voice note's model,
 * the camera window); a Stop pressed outside the app reaches it through [stopRequests].
 */
interface RecordingKeepAlive {
    /** Starts keeping the process alive; false when Android refused (then the recording ends with the screen, as before). */
    fun start(noteId: String, microphone: Boolean, camera: Boolean): Boolean
    fun stop()
    /** Stop pressed in the notification or on the floating button. */
    val stopRequests: Flow<Unit>

    companion object {
        /** Nothing kept alive (tests, and where no recording runs). */
        val NONE = object : RecordingKeepAlive {
            override fun start(noteId: String, microphone: Boolean, camera: Boolean) = false
            override fun stop() = Unit
            override val stopRequests: Flow<Unit> = emptyFlow()
        }
    }
}

/** The real one: [RecordingService]. */
class AndroidRecordingKeepAlive(private val context: Context) : RecordingKeepAlive {
    override fun start(noteId: String, microphone: Boolean, camera: Boolean): Boolean = runCatching {
        val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_NOTE_ID, noteId)
            .putExtra(RecordingService.EXTRA_MICROPHONE, microphone)
            .putExtra(RecordingService.EXTRA_CAMERA, camera)
        ContextCompat.startForegroundService(context, intent)
        true
    }.onFailure { AppLog.e("recording", "the recording could not be kept alive in the background", it) }.getOrDefault(false)

    override fun stop() {
        runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
    }

    override val stopRequests: Flow<Unit> get() = RecordingService.stopRequests
}

class RecordingService : Service() {
    private var floating: View? = null
    private var noteId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> {
                AppLog.i(TAG, "Stop pressed outside the app")
                // Nobody listens any more (the screen is gone): nothing to stop but this service.
                if (!stopRequests.tryEmit(Unit) || stopRequests.subscriptionCount.value == 0) stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val microphone = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
        val camera = intent.getBooleanExtra(EXTRA_CAMERA, false)
        channel()
        val note = notification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = 0
                if (microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (camera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, note, types)
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
        } catch (e: Exception) {
            AppLog.critical(TAG, "the recording could not be put in the foreground", e)
            stopSelf()
            return
        }
        AppLog.i(TAG, "kept alive: microphone $microphone, camera $camera")
        val wanted = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(500) { (application as NoteBudgetApplication).container.settings.screenFloatingStop.first() }
        } != false
        if (wanted && floating == null) showFloatingStop()
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.rec_channel_media), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(OpenNoteRequest.ACTION_OPEN_NOTE)
                .putExtra(OpenNoteRequest.EXTRA_NOTE_ID, noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.rec_notification_title))
            .setContentText(getString(R.string.rec_background_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.rec_action_stop), stop)
            .build()
    }

    /** The same round Stop as the screen recording's (D249), only with the "display over other apps" permission. */
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
                    params.x = startX - dx.toInt(); params.y = startY + dy.toInt()
                    runCatching { windows.updateViewLayout(button, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        AppLog.i(TAG, "the floating Stop button")
                        if (!stopRequests.tryEmit(Unit) || stopRequests.subscriptionCount.value == 0) stopSelf()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { windows.addView(button, params); floating = button }
            .onFailure { AppLog.e(TAG, "the floating Stop button could not be shown", it) }
    }

    override fun onDestroy() {
        floating?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        floating = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.critical(TAG, "the time limit of Android for the foreground service ran out (type $fgsType)")
        stopRequests.tryEmit(Unit)
        stopSelf()
    }

    companion object {
        private const val TAG = "recording"
        const val ACTION_START = "com.notebudget.app.action.RECORDING_START"
        const val ACTION_STOP = "com.notebudget.app.action.RECORDING_STOP"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_MICROPHONE = "microphone"
        const val EXTRA_CAMERA = "camera"
        private const val CHANNEL = "media_recording"
        private const val NOTIFICATION_ID = 7302

        /** Stop pressed outside the app: the voice note or the camera window that records listens here. */
        val stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}
