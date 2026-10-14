package com.notio.desktop

import com.notio.shared.AppLog
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit

/**
 * A watch over the window's thread (D72; user, 2026-09-30: "the app lags a little, freezes a bit"): when one event of the window
 * (a click, a key, a frame drawn) keeps the thread longer than [LIMIT_MS], the app log gets how long it took and where the thread
 * was, so a freeze seen by the person can be found from the log. Economical: while the window is idle the watching thread waits
 * on a monitor and never wakes; while events run it looks once per slow event.
 */
object FreezeWatch {
    private const val LIMIT_MS = 200L

    /** At most one line per this long; the freezes between are counted into the next line, so the log stays small. */
    private const val QUIET_MS = 5_000L

    /** Frames of the thread's stack written with a freeze; the app's own frames are what tells the cause. */
    private const val FRAMES = 14

    private val lock = Object()

    /** [System.nanoTime] when the event now being handled began, 0 while none is. */
    @Volatile private var began = 0L

    @Volatile private var edt: Thread? = null

    @Volatile private var installed = false

    // Only the watching thread uses these.
    private var lastLogged = 0L
    private var skipped = 0

    fun install() {
        if (installed) return
        installed = true
        Toolkit.getDefaultToolkit().systemEventQueue.push(object : EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                // An event handled inside another (a modal dialog's own loop) is part of the outer one.
                val outer = began == 0L
                if (outer) {
                    edt = Thread.currentThread()
                    began = System.nanoTime()
                    synchronized(lock) { lock.notify() }
                }
                try {
                    super.dispatchEvent(event)
                } finally {
                    if (outer) began = 0L
                }
            }
        })
        Thread(::watch, "notio-freeze-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun watch() {
        while (true) {
            val start = synchronized(lock) {
                while (began == 0L) lock.wait()
                began
            }
            Thread.sleep(LIMIT_MS)
            if (began != start) continue
            // Still the same event: where the thread is now, then how long the whole event lasts.
            val stack: List<StackTraceElement> = edt?.stackTrace?.toList().orEmpty()
            if (waitingForEvents(stack)) {
                // A file dialog (or another modal loop) is open: the thread waits for the person there, it is not stuck.
                while (began == start) Thread.sleep(LIMIT_MS)
                continue
            }
            while (began == start) Thread.sleep(20)
            val ms = (System.nanoTime() - start) / 1_000_000
            val now = System.currentTimeMillis()
            if (now - lastLogged < QUIET_MS) { skipped++; continue }
            val more = if (skipped > 0) " (and $skipped more since the last line)" else ""
            lastLogged = now
            skipped = 0
            AppLog.w("window", "the window was busy $ms ms$more, at:\n" + describe(stack))
        }
    }

    /** The thread is inside a modal loop waiting for the next event (a dialog open), not working. */
    fun waitingForEvents(stack: List<StackTraceElement>): Boolean =
        stack.any { it.className == "java.awt.EventQueue" && it.methodName == "getNextEvent" }

    /** The top frames, and the first frames of Notio itself if they lie deeper. */
    fun describe(stack: List<StackTraceElement>): String {
        val top = stack.take(FRAMES)
        val own = stack.drop(FRAMES).filter { it.className.startsWith("com.notio.") }.take(6)
        return (top + own).joinToString("\n") { "        at $it" }
    }
}
