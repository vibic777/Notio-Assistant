package com.notebudget.app.core.notification

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.notebudget.app.domain.model.RingRamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays the melody of a reminder that has its own VOLUME or a FADE-IN (D130): a notification channel can do neither.
 * It runs as a foreground service (media playback) whose notification IS the reminder's notification, plays on the
 * alarm audio stream, loops the melody for at most [RING_MILLIS], and stops when the person silences it, confirms the
 * reminder, or opens the note. If the system will not let it start (no exact-alarm permission), the reminder falls back
 * to the channel's plain sound: late or loud is better than lost.
 *
 * It holds the audio focus while it rings (D293): when the phone's own alarm clock or a call takes the sound, the melody waits
 * (it used to go on under the alarm and after it), and goes on when the sound comes back, within the same [RING_MILLIS]. The
 * reminder that rings now is [ringing], for the Reminders page (a red light and a Stop button); the notification has Stop too.
 */
class ReminderRingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var focus: AudioFocusRequest? = null
    /** The melody waits while another sound (the phone's alarm clock, a call) has the focus. */
    private var pausedByFocus = false
    /** The chosen volume and fade-in of the melody that plays, for the volume it has at any moment. */
    private var volume = 100
    private var ramp = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_STOP_REMOVE) {
            release()
            // "Turn off" / "Done" / "Repeat" must take the notification away too. It is the foreground notification of this
            // service, which nobody else can cancel while the service runs, so the service removes it itself (D154).
            if (intent.action == ACTION_STOP_REMOVE) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            else stopForegroundKeepingNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val notification = intent?.getParcelableExtra<Notification>(EXTRA_NOTIFICATION)
        val reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID)
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, 0) ?: 0
        if (notification == null) { stopSelf(); return START_NOT_STICKY }
        // Android 12+ may refuse a foreground start from the background (e.g. without exact alarms): the reminder then stays a
        // plain notification instead of closing the app (D231).
        // Another reminder still rings: its notification stays in the shade when this one takes the service over (it would be
        // removed with the change of the foreground notification, and that reminder lost from sight).
        if (ringingNow.value != null && ringingNow.value != reminderId) stopForegroundKeepingNotification()
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this, notificationId, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        }
        if (foreground.isFailure) {
            com.notebudget.app.core.common.AppLog.critical("reminder", "the melody could not start: Android refused the foreground service", foreground.exceptionOrNull())
            // The reminder is not lost with its melody (D341): its notification is shown as an ordinary one.
            val mayPost = Build.VERSION.SDK_INT < 33 ||
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (mayPost) runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(notificationId, notification) }
            stopSelf()
            return START_NOT_STICKY
        }
        release()
        volume = intent.getIntExtra(EXTRA_VOLUME, 100)
        ramp = intent.getIntExtra(EXTRA_RAMP, 0)
        // The chosen melody, then the phone's alarm sound, then its notification sound (D341): a melody whose file was deleted or
        // moved must not make the reminder a silent one.
        val chosen = intent.getStringExtra(EXTRA_SOUND)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val sounds = listOfNotNull(chosen, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).distinct()
        val started = sounds.any { uri ->
            val candidate = MediaPlayer()
            runCatching {
                candidate.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                candidate.setDataSource(applicationContext, uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.setVolume(RingRamp.gain(0, ramp, volume), RingRamp.gain(0, ramp, volume))
                candidate.start()
                player = candidate
            }.onFailure {
                com.notebudget.app.core.common.AppLog.w("reminder", "a melody could not be played, the next one is tried", it)
                runCatching { candidate.release() }
            }.isSuccess
        }
        if (!started) { release(); stopForegroundKeepingNotification(); stopSelf(); return START_NOT_STICKY }
        takeFocus()
        com.notebudget.app.core.common.AppLog.i("reminder", "the melody plays")
        ringingNow.value = reminderId
        ReminderNotifier.changed()
        startedAt = System.currentTimeMillis()
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startedAt
                val gain = RingRamp.gain(elapsed, ramp, volume)
                if (!pausedByFocus) runCatching { player?.setVolume(gain, gain) }
                if (elapsed >= RING_MILLIS) {
                    release(); stopForegroundKeepingNotification(); stopSelf()
                } else if (elapsed >= ramp * 1000L) {
                    // The volume is where it stays (D331): one wake-up at the end of the minute, not four a second for a
                    // whole minute — a melody without a fade-in was tended 240 times for nothing.
                    handler.postDelayed(this, RING_MILLIS - elapsed)
                } else {
                    // Battery saver (D308): the melody is tended once a second; a fade-in then rises in coarser steps.
                    handler.postDelayed(this, if (com.notebudget.app.core.common.PowerSaving.isOn(this@ReminderRingService)) SAVER_STEP_MILLIS else STEP_MILLIS)
                }
            }
        })
        return START_NOT_STICKY
    }

    /**
     * Asks for the sound as an alarm does, for a while. Losing it for a while (another alarm, a call): the melody waits; losing it
     * for good: the melody ends (the notification stays); getting it back: the melody goes on.
     */
    private fun takeFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        pausedByFocus = true
                        runCatching { player?.pause() }
                        com.notebudget.app.core.common.AppLog.i("reminder", "the melody waits: another sound took the focus")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                        pausedByFocus = false
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (elapsed >= RING_MILLIS) {
                            // The minute is over (D345): the timer that ends it stands still while the phone sleeps, so a sound
                            // given back late must not start the melody again.
                            release(); stopForegroundKeepingNotification(); stopSelf()
                        } else {
                            // The volume of this moment: the fade-in may have ended while the melody waited, and nothing tends it after.
                            val gain = RingRamp.gain(elapsed, ramp, volume)
                            runCatching { player?.setVolume(gain, gain); player?.start() }
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        com.notebudget.app.core.common.AppLog.i("reminder", "the melody ends: another sound took the focus for good")
                        release(); stopForegroundKeepingNotification(); stopSelf()
                    }
                }
            }, handler)
            .build()
        focus = request
        // Refused only during a call: the melody plays anyway (a reminder must not be lost) and the listener takes over later.
        runCatching { audio.requestAudioFocus(request) }
    }

    private fun release() {
        // For the journal (D345): how long the melody kept the phone awake.
        if (player != null) com.notebudget.app.core.common.AppLog.i("reminder", "the melody ended after " + (System.currentTimeMillis() - startedAt) / 1000 + " s")
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        pausedByFocus = false
        focus?.let { request -> runCatching { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) } }
        focus = null
        ringingNow.value = null
        ReminderNotifier.changed()
    }

    /** The melody ends; the notification stays where the person can see it. */
    private fun stopForegroundKeepingNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTIFICATION = "notification"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_RAMP = "ramp"
        const val EXTRA_REMINDER_ID = "reminder_id"
        private const val ACTION_STOP = "com.notebudget.app.action.STOP_RINGING"
        private const val ACTION_STOP_REMOVE = "com.notebudget.app.action.STOP_RINGING_REMOVE"
        const val RING_MILLIS = 60_000L
        private const val STEP_MILLIS = 250L
        private const val SAVER_STEP_MILLIS = 1_000L

        private val ringingNow = MutableStateFlow<String?>(null)

        /** The id of the reminder whose melody plays now (or waits for the sound), `null` when none (D293). */
        val ringing: StateFlow<String?> = ringingNow.asStateFlow()

        /** `false` when the system does not allow the service to start now. */
        fun start(context: Context, notificationId: Int, notification: Notification, soundUri: String?, volume: Int, ramp: Int, reminderId: String? = null): Boolean = runCatching {
            val intent = Intent(context, ReminderRingService::class.java)
                .putExtra(EXTRA_NOTIFICATION, notification)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .putExtra(EXTRA_SOUND, soundUri)
                .putExtra(EXTRA_VOLUME, volume)
                .putExtra(EXTRA_RAMP, ramp)
            context.startForegroundService(intent)
            true
        }.onFailure { com.notebudget.app.core.common.AppLog.w("reminder", "the melody service was not allowed to start now", it) }.getOrDefault(false)

        /** Stops the melody and removes the notification that belongs to it; harmless when nothing is playing. */
        fun stopAndRemove(context: Context) {
            runCatching { context.startService(Intent(context, ReminderRingService::class.java).setAction(ACTION_STOP_REMOVE)) }
        }

        /**
         * Stop (D293): ends the melody that is playing and any vibration of the app; harmless when there is none. The vibration
         * of the notification itself is one short burst when it appears and never repeats, so nothing of the reminder goes on.
         */
        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ReminderRingService::class.java).setAction(ACTION_STOP)) }
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") context.getSystemService(android.os.Vibrator::class.java)
                vibrator?.cancel()
            }
        }
    }
}
