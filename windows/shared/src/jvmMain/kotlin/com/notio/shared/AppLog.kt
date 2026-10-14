package com.notio.shared

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's journal (Android D230, AGENT.md D28): what went wrong, and around the risky places (backups, sync, exports, reminders)
 * what happened, one line each, in `logs\app.log` of the data folder (the previous one kept as `app.1.log`, [MAX_BYTES] each). The
 * same levels and line format as the phone. Never a note's content, a password, a code or a key. It never leaves the computer by
 * itself: Settings -> About -> App log shows it with Copy.
 */
object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR, CRITICAL, FATAL }

    /** How often the journal clears itself (Android D254); DAILY by default. */
    enum class ClearPeriod(val days: Int) { NEVER(0), DAILY(1), WEEKLY(7), MONTHLY(30) }

    /**
     * What the person chose in Settings -> About -> App log (Android D254): whether to keep a journal at all, which levels (each its own
     * tick, all by default), and how often it clears itself. A crash (FATAL) is written whatever is chosen.
     */
    data class Options(val enabled: Boolean = true, val levels: Set<Level> = CHOOSABLE.toSet(), val clear: ClearPeriod = ClearPeriod.DAILY)

    /** The levels with a tick; FATAL has none, it is always written. */
    val CHOOSABLE = listOf(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.CRITICAL)
    private const val OPTIONS = "log.properties"
    private const val DAY_MILLIS = 86_400_000L

    @Volatile private var options = Options()
    @Volatile private var lastCleared = 0L

    private const val FILE = "app.log"
    private const val OLD = "app.1.log"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    private val lock = Any()

    /** Call once when the app starts; lines logged before it are dropped. */
    fun install(logs: File) {
        dir = logs.apply { mkdirs() }
        // The choices are read once here (a tiny file), not on every line.
        runCatching {
            val p = java.util.Properties().apply { File(logs, OPTIONS).takeIf { it.isFile }?.inputStream()?.use { load(it) } }
            options = Options(
                enabled = p.getProperty("enabled") != "false",
                levels = p.getProperty("levels")?.split(',')?.mapNotNull { n -> Level.entries.firstOrNull { it.name == n } }?.toSet() ?: CHOOSABLE.toSet(),
                clear = p.getProperty("clear")?.let { n -> ClearPeriod.entries.firstOrNull { it.name == n } } ?: ClearPeriod.DAILY,
            )
            lastCleared = p.getProperty("lastCleared")?.toLongOrNull() ?: 0L
            // The first start counts as a clearing: the period runs from here.
            if (lastCleared == 0L) { lastCleared = System.currentTimeMillis(); saveOptions() }
        }
    }

    fun options(): Options = options

    /** Saves what the person chose (D254) and applies it at once. */
    fun setOptions(next: Options) {
        options = next
        saveOptions()
    }

    /** Is a line of [level] written? A crash always is. */
    fun accepts(level: Level, o: Options = options): Boolean = level == Level.FATAL || (o.enabled && level in o.levels)

    /** Is it time for the journal to clear itself? */
    fun clearDue(lastCleared: Long, now: Long, period: ClearPeriod): Boolean = period.days > 0 && now - lastCleared >= period.days * DAY_MILLIS

    private fun saveOptions() {
        val d = dir ?: return
        runCatching {
            val p = java.util.Properties()
            p.setProperty("enabled", options.enabled.toString())
            p.setProperty("levels", options.levels.joinToString(",") { it.name })
            p.setProperty("clear", options.clear.name)
            p.setProperty("lastCleared", lastCleared.toString())
            File(d, OPTIONS).outputStream().use { p.store(it, null) }
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)

    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)
    fun critical(tag: String, message: String, error: Throwable? = null) = log(Level.CRITICAL, tag, message, error)

    /** What closes the app: written at once on the calling thread. */
    fun fatal(tag: String, message: String, error: Throwable? = null) = log(Level.FATAL, tag, message, error, now = true)

    fun log(level: Level, tag: String, message: String, error: Throwable? = null, now: Boolean = false) {
        if (!accepts(level)) return
        val line = format(System.currentTimeMillis(), level, tag, message, error)
        if (now || level == Level.FATAL) append(line) else runCatching { writer.execute { append(line) } }
    }

    /** The whole journal, oldest line first; empty when there is none. */
    fun read(): String = synchronized(lock) {
        val d = dir ?: return ""
        listOf(File(d, OLD), File(d, FILE)).filter { it.isFile }.joinToString("") { runCatching { it.readText() }.getOrDefault("") }
    }

    fun clear() = synchronized(lock) {
        dir?.let { d -> File(d, OLD).delete(); File(d, FILE).delete() }
        lastCleared = System.currentTimeMillis()
        saveOptions()
    }

    /** Waits until the lines logged so far are written (tests, and before the app ends). */
    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    private fun append(line: String) = synchronized(lock) {
        val d = dir ?: return
        runCatching {
            // The clearing by itself (D254) is looked at when a line comes: one comparison, no job of its own.
            val now = System.currentTimeMillis()
            if (clearDue(lastCleared, now, options.clear)) {
                File(d, OLD).delete(); File(d, FILE).delete()
                lastCleared = now
                saveOptions()
            }
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

    fun format(time: Long, level: Level, tag: String, message: String, error: Throwable?): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(time))
        val sb = StringBuilder().append(stamp).append(' ').append(level.name.padEnd(8)).append(' ').append(tag).append(": ").append(message).append('\n')
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 4) {
            sb.append("    ").append(if (depth == 0) "" else "caused by ").append(e.javaClass.name).append(": ").append(e.message ?: "").append('\n')
            e.stackTrace.take(if (level == Level.FATAL || level == Level.CRITICAL) 12 else 5).forEach { sb.append("        at ").append(it).append('\n') }
            e = e.cause.takeIf { it !== e }
            depth++
        }
        return sb.toString()
    }
}

/**
 * The black box (Android D203, D224): an error that closes the app is written to `last_error.txt` with the version and the system,
 * shown at once in an error window with "Copy error text", and again at the next start if the window could not be shown. Only the
 * error, no note content; the file is deleted when the person closes the message.
 */
object CrashLog {
    private const val FILE = "last_error.txt"
    private const val MAX_CHARS = 20_000

    @Volatile private var root: File? = null

    fun install(dataRoot: File) {
        root = dataRoot
    }

    fun text(thread: Thread, error: Throwable): String =
        "Notio " + AppInfo.SHOWN + ", " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" +
            System.getProperty("os.arch") + "), Java " + System.getProperty("java.runtime.version") + "\n" +
            "thread: " + thread.name + "\n\n" + error.stackTraceToString().take(MAX_CHARS)

    fun write(text: String) {
        root?.let { runCatching { it.mkdirs(); File(it, FILE).writeText(text.take(MAX_CHARS)) } }
    }

    fun read(): String? = root?.let { r -> runCatching { File(r, FILE).takeIf { it.isFile }?.readText() }.getOrNull() }

    fun clear() {
        root?.let { runCatching { File(it, FILE).delete() } }
    }
}
