package com.notebudget.app.core.security

import android.content.Context

/**
 * Erases the app's own data (D-lockout): the opt-in, off-by-default consequence of [com.notebudget.app.domain.model.LockoutMode.WIPE]
 * once too many wrong tries in a row are made. Deliberately implemented as plain file deletion of the app's own private
 * directories — the same thing "Clear storage" in system settings does — rather than a system API, because
 * `ActivityManager.clearApplicationUserData()` is not reliably available to an app to call on itself across Android
 * versions without extra permission; deleting files the app already fully owns needs none.
 */
fun interface AppDataWiper {
    fun wipe()
}

/** The real implementation: deletes the files, then ends the process so nothing keeps running against half-deleted state. */
class AndroidAppDataWiper(private val context: Context) : AppDataWiper {
    override fun wipe() {
        val app = context.applicationContext
        runCatching { app.filesDir?.deleteRecursively() }
        runCatching { app.cacheDir?.deleteRecursively() }
        runCatching { app.getExternalFilesDir(null)?.deleteRecursively() }
        runCatching { app.noBackupFilesDir?.deleteRecursively() }
        runCatching {
            val dataDir = app.filesDir?.parentFile // .../files -> the app's private data root
            dataDir?.resolve("databases")?.deleteRecursively()
            dataDir?.resolve("shared_prefs")?.deleteRecursively()
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
