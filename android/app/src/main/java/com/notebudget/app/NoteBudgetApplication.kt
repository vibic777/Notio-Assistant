package com.notebudget.app

import android.app.Application
import com.notebudget.app.core.common.AppContainer

class NoteBudgetApplication : Application() {

    /** Manual dependency container (Hilt may replace it later, see AGENT_EN.md §9.1). */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First of all, so that an error anywhere after it is written down (D203, D230).
        com.notebudget.app.core.common.AppLog.install(this)
        com.notebudget.app.core.common.CrashLog.install(this, runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?")
        // The error window's own process (D224) only shows the text: no database, no reminders, no housekeeping there.
        if ((getProcessName() ?: "").endsWith(com.notebudget.app.core.common.CrashLog.ERROR_PROCESS_SUFFIX)) return
        // Why Android ended the last run: a native crash or "not responding" is shown like a Java crash (D230).
        com.notebudget.app.core.common.AppLog.recordPreviousExit(this)?.let { com.notebudget.app.core.common.CrashLog.write(this, it) }
        com.notebudget.app.core.common.AppLog.i("app", "started, version " + (runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?") +
            ", Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        container = AppContainer(this).also { it.initialize() }
    }
}
