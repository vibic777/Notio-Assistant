package com.notebudget.app.core.common

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The battery saver of the app (D308, as in FitEasy): one switch of this phone, kept beside the app's other device settings and
 * never part of a backup or a sync. While it is on, the app asks the phone for nothing that must happen to the minute:
 * reminders and budget merges are set as alarms the system may batch with others (a few minutes late rather than a wake-up of
 * their own), a ringing melody is tended once a second instead of four times, and the decorative movement is off.
 *
 * Read where there is no time to wait for a settings flow (an alarm being set from a broadcast), so it lives in plain
 * preferences with its value kept in memory.
 */
object PowerSaving {
    private const val PREFS = "power"
    private const val KEY = "economy"
    private val state = MutableStateFlow<Boolean?>(null)
    private val shown = MutableStateFlow(false)

    /** For the screens: follows the switch. */
    val enabled: StateFlow<Boolean> get() = shown

    fun isOn(context: Context): Boolean {
        state.value?.let { return it }
        val on = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false) }.getOrDefault(false)
        state.value = on
        shown.value = on
        return on
    }

    fun set(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        state.value = on
        shown.value = on
        AppLog.i("power", "battery saver " + if (on) "on" else "off")
    }
}
