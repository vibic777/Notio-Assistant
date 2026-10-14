package com.notio.desktop

import com.notio.shared.data.DbReminderNotes
import com.notio.shared.data.DbReminders
import com.notio.shared.data.NotioDatabase
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.ReminderScheduler
import com.notio.shared.domain.ReminderUseCases
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A reminder ringing now, with the title of its note (the phone's alarm screen, D150). */
data class Ringing(val reminder: Reminder, val noteTitle: String)

/**
 * Reminders on the desktop (Android D10, D122, D130, D150; AGENT.md D25). The rules are the phone's `ReminderUseCases`; the alarms
 * are timers of this process ([ReminderScheduler]), so a reminder rings while Notio runs — with the window open or in the tray
 * (Settings: keep running in the tray). On start every active reminder is armed again and a missed one rings at once, as the
 * phone does after a reboot.
 */
class ReminderService(private val db: NotioDatabase, private val ringMillis: Long = RING_MILLIS) : ReminderScheduler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timers = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "notio-reminders").apply { isDaemon = true } }
        // A cancelled or moved reminder leaves the queue at once, not at its old time days later (D70).
        .apply { removeOnCancelPolicy = true }
    private val jobs = ConcurrentHashMap<String, ScheduledFuture<*>>()
    /** When each armed alarm is due by the wall clock, to re-arm it after the computer slept ([checkClock]). */
    private val dueAt = ConcurrentHashMap<String, Long>()
    private var clockWatch: ScheduledFuture<*>? = null

    /**
     * The battery saver (Android D308): the clock is looked at every five minutes instead of every minute, so after the computer
     * slept a reminder may ring a few minutes late; in step with the phone, whose saver lets its alarms be batched.
     */
    @Volatile var saving: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            synchronized(this) { clockWatch?.cancel(false); clockWatch = null }
            watchClock()
        }

    /**
     * All reminders on or off at once (Android D318): while it is off no timer is armed and nothing rings; the reminders stay as
     * they are. Set through [turn], which takes the timers away or arms them again.
     */
    @Volatile var enabled: Boolean = true

    /** A reminder rings: the id of its note and the note's title (the assistant passes it on to Telegram, D148). */
    var onRing: (String, String) -> Unit = { _, _ -> }

    val useCases = ReminderUseCases(DbReminders(db), DbReminderNotes(db), this, enabled = { enabled })

    /** Off: every timer goes, a melody that plays ends. On: armed as at the start, what was due meanwhile rings now. */
    fun turn(on: Boolean) {
        enabled = on
        com.notio.shared.AppLog.i("reminder", "all reminders " + if (on) "on" else "off")
        if (on) start()
        else scope.launch {
            useCases.disarmAll()
            stopSound()
            onChanged()
        }
    }

    private val _ringing = MutableStateFlow<List<Ringing>>(emptyList())
    /** The reminders ringing now, newest last; each is answered in its alarm window. */
    val ringing: StateFlow<List<Ringing>> = _ringing.asStateFlow()

    /** Told when a reminder changed by itself (fired, moved on), so the screens read again. */
    var onChanged: () -> Unit = {}

    private var sound: Clip? = null

    private val _sounding = MutableStateFlow<String?>(null)
    /** The reminder whose melody plays now, `null` when none (Android D293): the Reminders page, the alarm window and the tray offer Stop. */
    val sounding: StateFlow<String?> = _sounding.asStateFlow()

    /** Stop (Android D293): the melody ends; the reminder stays as it is (still to be confirmed, snoozed or turned off). */
    fun silence() {
        if (stopSound()) com.notio.shared.AppLog.i("reminder", "the melody stopped by Stop")
    }

    @Synchronized
    override fun schedule(reminderId: String, triggerAtMillis: Long) {
        cancel(reminderId)
        // All reminders are off (Android D318): no timer is armed; turning them on arms them all again.
        if (!enabled) return
        arm(reminderId, triggerAtMillis)
        watchClock()
    }

    private fun arm(reminderId: String, triggerAtMillis: Long) {
        val delay = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        dueAt[reminderId] = triggerAtMillis
        jobs.put(reminderId, timers.schedule({ fire(reminderId) }, delay, TimeUnit.MILLISECONDS))?.cancel(false)
    }

    @Synchronized
    override fun cancel(reminderId: String) {
        dueAt.remove(reminderId)
        jobs.remove(reminderId)?.cancel(false)
    }

    /**
     * Timers count the time the computer is awake only (Windows 8+ waits stop while it sleeps), so a reminder armed before the lid
     * of a laptop was closed would ring that much later (the code check of 2026-10-01). While any reminder is armed, one check a minute
     * compares each timer with the wall clock and re-arms those that drifted (after sleep, or a clock set by hand); with none armed
     * nothing runs.
     */
    @Synchronized
    private fun watchClock() {
        if (clockWatch == null && jobs.isNotEmpty()) {
            val every = if (saving) SAVER_CLOCK_CHECK_SECONDS else CLOCK_CHECK_SECONDS
            clockWatch = timers.scheduleWithFixedDelay(::checkClock, every, every, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    private fun checkClock() {
        if (jobs.isEmpty()) { clockWatch?.cancel(false); clockWatch = null; return }
        val now = System.currentTimeMillis()
        for ((id, at) in dueAt) {
            val job = jobs[id] ?: continue
            if (kotlin.math.abs(job.getDelay(TimeUnit.MILLISECONDS) - (at - now)) > CLOCK_DRIFT_MILLIS) arm(id, at)
        }
    }

    override fun canScheduleExact(): Boolean = true

    /** Arms everything again and rings what was missed while Notio was closed. */
    fun start() {
        scope.launch {
            useCases.restoreAll().forEach { ring(it) }
            onChanged()
        }
    }

    /** How many reminders are still to ring (then closing the window keeps Notio in the tray). */
    fun hasActive(): Boolean = jobs.isNotEmpty()

    private fun fire(alarmId: String) {
        synchronized(this) { dueAt.remove(alarmId); jobs.remove(alarmId) }
        scope.launch {
            val prefix = ReminderUseCases.snoozeAlarmId("")
            val fired = if (alarmId.startsWith(prefix)) useCases.onSnoozeFired(alarmId.removePrefix(prefix)) else useCases.onFired(alarmId)
            fired?.let { ring(it) }
            onChanged()
        }
    }

    private fun ring(reminder: Reminder) {
        val title = db.note(reminder.noteId)?.title ?: return
        _ringing.value = _ringing.value.filter { it.reminder.id != reminder.id } + Ringing(reminder, title)
        if (reminder.soundEnabled) playSound(reminder)
        runCatching { onRing(reminder.noteId, title) }
    }

    /**
     * A test alarm in 10 seconds (Android "Test the alarm", D150): the alarm window and the melody as a real reminder rings, without
     * a note or a reminder in the database; answering it changes nothing.
     */
    fun testAlarm(title: String = "Notio") {
        timers.schedule({
            val now = System.currentTimeMillis()
            val test = Reminder(
                id = TEST_ID, noteId = "", type = com.notio.shared.domain.ReminderType.ONE_TIME, triggerAt = now, isEnabled = true,
                status = com.notio.shared.domain.ReminderStatus.FIRED, soundEnabled = true, soundUri = null, vibrationEnabled = false,
                popupEnabled = true, requiresConfirmation = false, repeatUntilConfirmed = false, repeatIntervalMinutes = null,
                createdAt = now, updatedAt = now,
            )
            _ringing.value = _ringing.value.filter { it.reminder.id != TEST_ID } + Ringing(test, title)
            playSound(test)
        }, 10, TimeUnit.SECONDS)
    }

    /** The alarm window answered: Snooze [minutes], or Done / Turn off. */
    fun snooze(r: Ringing, minutes: Int) = answer(r) { useCases.snooze(r.reminder.id, minutes) }

    fun done(r: Ringing) = answer(r) {
        if (r.reminder.requiresConfirmation) useCases.onConfirmed(r.reminder.id)
    }

    /**
     * Reset from the Reminders page (Android D296): the ring of this reminder ends for good — its alarm window closes, its melody
     * stops — and the reminder itself stays as it is (one that waits for Done still waits).
     */
    fun reset(reminderId: String) {
        _ringing.value.firstOrNull { it.reminder.id == reminderId }?.let { r -> answer(r) { } }
    }

    private fun answer(r: Ringing, action: suspend () -> Unit) {
        _ringing.value = _ringing.value.filter { it.reminder.id != r.reminder.id }
        if (_ringing.value.isEmpty()) stopSound()
        if (r.reminder.id == TEST_ID) return
        scope.launch { action(); onChanged() }
    }

    /**
     * The melody: the Windows alarm sound (or the notification one), looping until answered or stopped, and never longer than a
     * minute (Android D293: it is never left playing with no end; the alarm window and the notification stay); the volume is the
     * reminder's (10 - 100 %) and rises from quiet over its fade-in seconds (Android D130). Java's sound has no audio focus, so on
     * the desktop the minute is how it gives way to other sounds.
     */
    @Synchronized
    private fun playSound(reminder: Reminder) {
        stopSound()
        val file = soundFile(reminder.soundUri) ?: return
        runCatching {
            val clip = AudioSystem.getClip()
            clip.open(AudioSystem.getAudioInputStream(file))
            val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            fun setVolume(fraction: Float) {
                gain ?: return
                val db = (20 * log10(fraction.coerceIn(0.01f, 1f).toDouble())).toFloat()
                gain.value = db.coerceIn(gain.minimum, gain.maximum)
            }
            val target = reminder.volumePercent.coerceIn(10, 100) / 100f
            setVolume(if (reminder.rampSeconds > 0) 0.05f else target)
            clip.loop(Clip.LOOP_CONTINUOUSLY)
            sound = clip
            _sounding.value = reminder.id
            timers.schedule({ if (stopSound(clip)) com.notio.shared.AppLog.i("reminder", "the melody ended after a minute") }, ringMillis, TimeUnit.MILLISECONDS)
            if (reminder.rampSeconds > 0) {
                val steps = 20
                for (i in 1..steps) {
                    timers.schedule({ if (sound === clip) setVolume(0.05f + (target - 0.05f) * i / steps) }, reminder.rampSeconds * 1000L * i / steps, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /**
     * Ends the melody; with [only], only when that one still plays (the minute of a melody already replaced does nothing).
     * Returns whether a melody was ended.
     */
    @Synchronized
    private fun stopSound(only: Clip? = null): Boolean {
        if (only != null && sound !== only) return false
        val playing = sound ?: return false
        runCatching { playing.stop(); playing.close() }
        sound = null
        _sounding.value = null
        return true
    }

    fun close() {
        stopSound()
        timers.shutdownNow()
        scope.cancel()
    }

    companion object {
        private const val TEST_ID = "notio-test-alarm"
        /** The longest a melody plays (Android D293, `ReminderRingService.RING_MILLIS`). */
        const val RING_MILLIS = 60_000L
        private const val CLOCK_CHECK_SECONDS = 60L
        private const val SAVER_CLOCK_CHECK_SECONDS = 300L
        private const val CLOCK_DRIFT_MILLIS = 2_000L

        /** The reminder's own WAV file when it is one on this computer, else the Windows alarm (or notification) sound. */
        fun soundFile(uri: String?): File? {
            uri?.let { File(it) }?.takeIf { it.isFile && it.extension.equals("wav", true) }?.let { return it }
            return listOf("Alarm01.wav", "Windows Notify System Generic.wav", "notify.wav")
                .map { File(System.getenv("WINDIR") ?: "C:\\Windows", "Media\\$it") }.firstOrNull { it.isFile }
        }
    }
}

/** Plays a reminder's melody once in the reminder dialog (the phone's preview). */
object ReminderSoundPreview {
    private var clip: Clip? = null

    fun play(uri: String?) {
        stop()
        val file = ReminderService.soundFile(uri) ?: return
        runCatching { clip = AudioSystem.getClip().apply { open(AudioSystem.getAudioInputStream(file)); start() } }
    }

    fun stop() {
        clip?.let { runCatching { it.stop(); it.close() } }
        clip = null
    }
}
