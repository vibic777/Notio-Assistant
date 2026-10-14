package com.notebudget.app.core.common

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's journal (D230): what went wrong, and around the risky places (recording, backups, sync) what happened, one line
 * each, in a small file of the app's own storage (`files/logs/app.log`, the previous one kept as `app.1.log`, [MAX_BYTES] each).
 * Levels [Level]: INFO and WARN for the course of things, ERROR for a failure the app survives, CRITICAL for a failure that
 * loses what the person was doing (a recording, a backup), FATAL for what closes the app. Never a note's content, a password,
 * a code or a key. It never leaves the phone by itself: Settings -> About -> App log shows it with Copy.
 *
 * On start [recordPreviousExit] adds why Android ended the last run (Android 11+): a native crash, "not responding", killed
 * for memory, stopped by the system - the closings the Java crash handler of [CrashLog] cannot see.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // holds only the application context, see [install]
object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR, CRITICAL, FATAL }

    /** How often the journal clears itself (D254); DAILY by default (user, 2026-09-27). */
    enum class ClearPeriod(val days: Int) { NEVER(0), DAILY(1), WEEKLY(7), MONTHLY(30) }

    /**
     * What the person chose in Settings -> About -> App log (D254): whether to keep a journal at all, which levels (each its own tick,
     * all by default), and how often it clears itself. A crash (FATAL) is written whatever is chosen: without it the cause of a
     * closing is never known.
     */
    data class Options(val enabled: Boolean = true, val levels: Set<Level> = CHOOSABLE.toSet(), val clear: ClearPeriod = ClearPeriod.DAILY)

    /** The levels with a tick; FATAL has none, it is always written. */
    val CHOOSABLE = listOf(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.CRITICAL)

    private const val DIR = "logs"
    private const val FILE = "app.log"
    private const val OLD = "app.1.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val PREFS = "app_log"
    private const val KEY_LAST_EXIT = "last_exit_time"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEVELS = "levels"
    private const val KEY_CLEAR = "clear_period"
    private const val KEY_LAST_CLEARED = "last_cleared"
    private const val DAY_MILLIS = 86_400_000L

    @Volatile private var options = Options()
    @Volatile private var lastCleared = 0L
    /** Always the application context (see [install]): it lives as long as the process, so nothing leaks. */
    @Volatile private var app: Context? = null

    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    private val lock = Any()

    /** Call once, first thing when the app starts; lines logged before it are dropped. */
    fun install(context: Context) {
        dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
        app = context.applicationContext
        // The choices are read once here (a tiny file), not on every line.
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            options = Options(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                levels = prefs.getString(KEY_LEVELS, null)?.let { stored ->
                    stored.split(',').mapNotNull { n -> Level.entries.firstOrNull { it.name == n } }.toSet()
                } ?: CHOOSABLE.toSet(),
                clear = prefs.getString(KEY_CLEAR, null)?.let { n -> ClearPeriod.entries.firstOrNull { it.name == n } } ?: ClearPeriod.DAILY,
            )
            lastCleared = prefs.getLong(KEY_LAST_CLEARED, 0L)
            // The first start counts as a clearing: the period runs from here.
            if (lastCleared == 0L) { lastCleared = System.currentTimeMillis(); prefs.edit().putLong(KEY_LAST_CLEARED, lastCleared).apply() }
        }
    }

    fun options(): Options = options

    /** Saves what the person chose (D254) and applies it at once. */
    fun setOptions(next: Options) {
        options = next
        app?.let { c ->
            runCatching {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, next.enabled).putString(KEY_LEVELS, next.levels.joinToString(",") { it.name })
                    .putString(KEY_CLEAR, next.clear.name).apply()
            }
        }
    }

    /** Is a line of [level] written? A crash always is. */
    fun accepts(level: Level, o: Options = options): Boolean = level == Level.FATAL || (o.enabled && level in o.levels)

    /** Is it time for the journal to clear itself? */
    fun clearDue(lastCleared: Long, now: Long, period: ClearPeriod): Boolean = period.days > 0 && now - lastCleared >= period.days * DAY_MILLIS

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)
    fun critical(tag: String, message: String, error: Throwable? = null) = log(Level.CRITICAL, tag, message, error)

    /** What closes the app: written at once on the calling thread, since the process is about to end. */
    fun fatal(tag: String, message: String, error: Throwable? = null) = log(Level.FATAL, tag, message, error, now = true)

    fun log(level: Level, tag: String, message: String, error: Throwable? = null, now: Boolean = false) {
        if (!accepts(level)) return
        val line = format(System.currentTimeMillis(), level, tag, message, error)
        runCatching { android.util.Log.println(priority(level), "Notio/$tag", message + (error?.let { " | $it" } ?: "")) }
        if (now || level == Level.FATAL) append(line) else runCatching { writer.execute { append(line) } }
    }

    /** The whole journal, oldest line first (the previous file, then the current one); empty when there is none. */
    fun read(): String = synchronized(lock) {
        val d = dir ?: return ""
        listOf(File(d, OLD), File(d, FILE)).filter { it.isFile }.joinToString("") { runCatching { it.readText() }.getOrDefault("") }
    }

    fun clear() = synchronized(lock) {
        dir?.let { d -> File(d, OLD).delete(); File(d, FILE).delete() }
        markCleared(System.currentTimeMillis())
        Unit
    }

    private fun markCleared(now: Long) {
        lastCleared = now
        app?.let { c -> runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CLEARED, now).apply() } }
    }

    private fun append(line: String) = synchronized(lock) {
        val d = dir ?: return
        // The clearing by itself (D254) is looked at when a line comes: one comparison, no job of its own (rule 18).
        val now = System.currentTimeMillis()
        if (clearDue(lastCleared, now, options.clear)) {
            File(d, OLD).delete(); File(d, FILE).delete()
            markCleared(now)
        }
        runCatching {
            val file = File(d, FILE)
            if (file.length() > MAX_BYTES) {
                val old = File(d, OLD)
                old.delete()
                file.renameTo(old)
            }
            file.appendText(line)
        }
        Unit
    }

    internal fun format(time: Long, level: Level, tag: String, message: String, error: Throwable?): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(time))
        val sb = StringBuilder().append(stamp).append(' ').append(level.name.padEnd(8)).append(' ').append(tag).append(": ").append(message).append('\n')
        if (error != null) {
            // The error and where it happened: the first lines of each cause are enough and keep the file small.
            var e: Throwable? = error
            var depth = 0
            while (e != null && depth < 4) {
                sb.append("    ").append(if (depth == 0) "" else "caused by ").append(e.javaClass.name).append(": ").append(e.message ?: "").append('\n')
                e.stackTrace.take(if (level == Level.FATAL || level == Level.CRITICAL) 12 else 5).forEach { sb.append("        at ").append(it).append('\n') }
                e = e.cause.takeIf { it !== e }
                depth++
            }
        }
        return sb.toString()
    }

    private fun priority(level: Level) = when (level) {
        Level.DEBUG -> android.util.Log.DEBUG
        Level.INFO -> android.util.Log.INFO
        Level.WARN -> android.util.Log.WARN
        Level.ERROR -> android.util.Log.ERROR
        Level.CRITICAL, Level.FATAL -> android.util.Log.ASSERT
    }

    /**
     * Writes why Android ended the runs since the last start (Android 11+), once each: a native crash or "not responding" as
     * FATAL, a kill for memory or by the system as CRITICAL, the ordinary ends (the person closed the app, an update) as INFO.
     * Returns the text of a native crash or "not responding", for the error window of [CrashLog], or `null`.
     */
    fun recordPreviousExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_LAST_EXIT, 0L)
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.timestamp > seen && !it.processName.endsWith(CrashLog.ERROR_PROCESS_SUFFIX) }
                .sortedBy { it.timestamp }
            if (exits.isEmpty()) return@runCatching null
            prefs.edit().putLong(KEY_LAST_EXIT, exits.maxOf { it.timestamp }).apply()
            var report: String? = null
            exits.forEach { x ->
                val level = when (x.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> Level.FATAL
                    ApplicationExitInfo.REASON_CRASH -> Level.ERROR // already written by the Java handler
                    ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Level.CRITICAL
                    else -> Level.INFO
                }
                val text = "the previous run ended at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(x.timestamp)) +
                    ": " + reasonName(x.reason) + " (status " + x.status + ", importance " + x.importance + ", pss " + x.pss / 1024 + " MB)" +
                    (x.description?.let { ", \"$it\"" } ?: "") + ", process " + x.processName
                if (accepts(level)) append(format(System.currentTimeMillis(), level, "exit", text, null))
                if (level == Level.FATAL) {
                    val trace = if (x.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                        x.traceInputStream?.bufferedReader()?.use { r -> r.readText().take(6000) }
                    }.getOrNull() else null
                    report = "Notio: " + text + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " +
                        Build.MANUFACTURER + " " + Build.MODEL + (trace?.let { "\n\n$it" } ?: "")
                }
            }
            report
        }.getOrNull()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depends on died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for using too much"
        ApplicationExitInfo.REASON_EXIT_SELF -> "ended itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_OTHER -> "ended by the system (other)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission was changed"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the person (or force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "the user was stopped"
        else -> "reason $reason"
    }
}
