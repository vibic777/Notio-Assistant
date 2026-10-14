package com.notebudget.app.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.notebudget.app.MainActivity
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.R
import com.notebudget.app.core.common.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Always ready for a sync" as a service (D342; owner, 2026-10-09: "when the switch to accept a sync is on, it works as a service in
 * any mode, accepts a sync, and the confirmation comes up on any screen when one is asked for; when none is asked for, nothing
 * comes up"). While the switch is on the phone listens for a sync whether Notio is on the screen or not; this service is what keeps
 * the process and its port alive, with its notification in the shade as Android demands of such a service.
 *
 * It does no work of its own: nothing polls and no wake lock is held — a port waits for a connection, and the question of another
 * device is followed as it comes. A question that comes while Notio is not on the screen is shown as a notification (and, with the
 * leave to show over other apps, Notio comes up by itself); a sync that was set beforehand on the other device asks nothing (D333)
 * and shows nothing. A phone that sleeps deeply does not answer the network until it wakes: that is Android's saving, left as it is.
 */
class SyncListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            // "Turn off" of the notification: the switch itself goes off, so nothing starts the service again.
            SyncAlways.set(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val shown = runCatching {
            ServiceCompat.startForeground(
                this, STATUS_ID, statusNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        }
        if (shown.isFailure) {
            // Android did not let the service start now (from the background, on some phones): the phone listens while Notio
            // is on the screen, as before, and the service starts with the next opening of the app.
            AppLog.w("sync", "the service that waits for a sync could not start", shown.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SyncAlways.isOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) AppLog.i("sync", "the service waits for a sync in the background")
        running = true
        val engine = (application as NoteBudgetApplication).container.syncEngine
        // Started off the screen (after a restart of the phone): only the port and the announcement, no search of the network (D345).
        if (!appOnScreen) runCatching { engine.lookForDevices(false) }
        runCatching { engine.startListening() }.onFailure { AppLog.e("sync", "the port of the sync could not be opened", it) }
        if (watcher == null) {
            watcher = scope.launch {
                engine.prompt.collect { prompt ->
                    if (prompt == null || appOnScreen) cancelQuestion() else showQuestion(prompt)
                }
            }
        }
        // Killed for memory: Android starts it again, the phone stays ready.
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) AppLog.i("sync", "the service that waits for a sync ended")
        running = false
        watcher?.cancel()
        watcher = null
        scope.cancel()
        cancelQuestion()
        // Off the screen nothing else keeps the port; on the screen the app and its Sync page decide (D323).
        if (!appOnScreen) {
            runCatching { (application as NoteBudgetApplication).container.syncEngine.stopListening() }
        }
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 5,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(STATUS_CHANNEL) == null) {
            // Quiet: it only says that the phone waits.
            manager.createNotificationChannel(
                NotificationChannel(STATUS_CHANNEL, getString(R.string.sync_service_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val off = PendingIntent.getService(
            this, 6, Intent(this, SyncListenService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sync_service_title))
            .setContentText(getString(R.string.sync_service_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.sync_service_off), off)
            .build()
    }

    /** Another device asks (to pair, or for a sync that was not set beforehand) while Notio is not on the screen. */
    @android.annotation.SuppressLint("MissingPermission") // without the permission the system drops the notification
    private fun showQuestion(prompt: SyncPrompt) {
        val name = when (prompt) {
            is SyncPrompt.Pair -> prompt.peerName
            is SyncPrompt.Request -> prompt.peerName
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(QUESTION_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(QUESTION_CHANNEL, getString(R.string.sync_question_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val notification = NotificationCompat.Builder(this, QUESTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sync_question_title, name))
            .setContentText(getString(R.string.sync_question_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // The other device waits a minute and a half for the answer; the notification does not outlive the question.
            .setTimeoutAfter(QUESTION_MILLIS)
            .setContentIntent(openApp())
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(QUESTION_ID, notification) }
        // With the leave to show over other apps Notio comes up by itself, as the alarm screen of a reminder does (D152); the
        // Sync page opens there, where the question is answered (D323).
        if (android.provider.Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        AppLog.i("sync", "a question of another device came while Notio was not on the screen")
    }

    private fun cancelQuestion() {
        runCatching { NotificationManagerCompat.from(this).cancel(QUESTION_ID) }
    }

    companion object {
        private const val ACTION_OFF = "com.notebudget.app.action.SYNC_SERVICE_OFF"
        private const val STATUS_CHANNEL = "sync_ready"
        private const val QUESTION_CHANNEL = "sync_question"
        private const val STATUS_ID = 7101
        private const val QUESTION_ID = 7102
        private const val QUESTION_MILLIS = 90_000L

        /** Notio is on the screen: a question is answered on its Sync page, no notification is needed. */
        @Volatile var appOnScreen: Boolean = false

        @Volatile private var running = false

        /** The service runs and keeps the port open, also off the screen. */
        val keeps: Boolean get() = running

        /**
         * The service follows the switch: started when it is on, ended when it is off. Called when the switch changes, when the
         * app comes onto the screen and after a restart of the phone. `false` when Android did not let it start now.
         */
        fun follow(context: Context): Boolean {
            val app = context.applicationContext
            val intent = Intent(app, SyncListenService::class.java)
            if (!SyncAlways.isOn(app)) {
                if (running) runCatching { app.stopService(intent) }
                return true
            }
            if (running) return true
            return runCatching { app.startForegroundService(intent); true }
                .onFailure { AppLog.w("sync", "the service that waits for a sync was not allowed to start now", it) }.getOrDefault(false)
        }
    }
}
