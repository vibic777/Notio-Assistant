package com.notebudget.app.core.common

import android.content.Context
import java.io.File

/**
 * The black box of the app (D203): when the app closes with an error, what went wrong (the error and where in the code) is
 * written to one small file in the app's own storage, and the next start shows it with a "Copy error text" button, so the
 * person can send it. Only the error is kept, no note content; it never leaves the phone by itself. One file, the last
 * error only; it is deleted when the person closes the message.
 */
object CrashLog {
    private const val FILE = "last_error.txt"

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** The process of the error window (D224): an error there must not open the window again. */
    const val ERROR_PROCESS_SUFFIX = ":error"

    /**
     * Records errors that would close the app and opens the error window ([ErrorActivity], in its own process) at once (D224);
     * the app's process then ends. When the window cannot be opened (the app was in the background), Android closes the app as
     * before and the next start shows the text. Call once, when the app starts.
     */
    fun install(context: Context, version: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val inErrorProcess = (android.app.Application.getProcessName() ?: "").endsWith(ERROR_PROCESS_SUFFIX)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AppLog.fatal("crash", "the app closes: an error on the thread " + thread.name, error) }
            val written = runCatching {
                val text = "Notio " + version + ", Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT +
                    "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" +
                    "thread: " + thread.name + "\n\n" + error.stackTraceToString().take(MAX_CHARS)
                file(app).writeText(text)
            }.isSuccess
            val opened = written && !inErrorProcess && runCatching {
                app.startActivity(
                    android.content.Intent(app, ErrorActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.isSuccess
            if (opened) {
                // No "app has stopped" dialog on top of ours: the process ends quietly, the window lives in its own process.
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Keeps [text] as the last error, for the window at the next start (D230: a native crash or ANR found by [AppLog]). */
    fun write(context: Context, text: String) {
        runCatching { file(context).writeText(text.take(MAX_CHARS)) }
    }

    /** The error of the last run that closed the app, or `null`. */
    fun read(context: Context): String? = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private const val MAX_CHARS = 20_000
}
