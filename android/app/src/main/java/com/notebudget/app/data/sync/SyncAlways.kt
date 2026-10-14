package com.notebudget.app.data.sync

import android.content.Context
import com.notebudget.app.core.common.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Always ready for a sync" (D323; owner, 2026-10-06: "a tick that turns on a standing sync service, off by default; when it is
 * on, the device is ready and waits for a sync"). One switch of this phone, off by default, kept in plain preferences (it is
 * read as the app comes onto the screen). On: the phone listens for a sync all the time Notio is on the screen, not only on the
 * Sync page. It does not listen in the background: the app does no background work but alarms and recordings (rule 18), so a
 * phone in the pocket costs nothing; the standing service of a workplace is the computer's.
 */
object SyncAlways {
    private const val PREFS = "sync"
    private const val KEY = "always"
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
        AppLog.i("sync", "always ready for a sync: " + if (on) "on" else "off")
    }
}
