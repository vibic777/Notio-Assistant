package com.notebudget.app.core.alarm

import android.content.Context
import com.notebudget.app.domain.model.RingScreen

/**
 * Which ring of its series a reminder is at (D351): the first lights the screen for a minute, the second for 15 seconds, every
 * later one for 5. Kept on this phone in plain preferences, one short line per reminder that rang and was not answered; it is
 * not data of the reminder and travels nowhere. Read and written where an alarm is handled — a broadcast, no time to wait.
 */
object RingSeries {
    private const val PREFS = "ring_series"

    /** The alarm of [reminderId] went off now: the number of this ring, written down for the next one. */
    fun ring(context: Context, reminderId: String, repeatIntervalMinutes: Int?, now: Long = System.currentTimeMillis()): Int = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString(reminderId, null)?.split(':')
        val ring = RingScreen.nextRing(last?.getOrNull(0)?.toIntOrNull() ?: 0, last?.getOrNull(1)?.toLongOrNull() ?: 0L, now, repeatIntervalMinutes)
        // A reminder that does not repeat has no series: nothing is kept for it.
        if (repeatIntervalMinutes?.takeIf { it > 0 } == null) prefs.edit().remove(reminderId).apply()
        else prefs.edit().putString(reminderId, "$ring:$now").apply()
        ring
    }.getOrDefault(1)

    /** The person answered (Done, Turn off, Snooze, opened it): the next ring is a first ring again. */
    fun answered(context: Context, reminderId: String) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(reminderId)) prefs.edit().remove(reminderId).apply()
        }
    }
}
