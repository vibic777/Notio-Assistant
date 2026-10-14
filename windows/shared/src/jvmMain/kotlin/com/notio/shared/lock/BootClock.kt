package com.notio.shared.lock

/**
 * Milliseconds since the computer started, sleep included: a clock the person cannot set (Android's `SystemClock.elapsedRealtime`).
 * The lock keeps its wait after wrong tries on it too (Android D290), so moving the date forward does not end the wait.
 * Windows: `GetTickCount64`. Elsewhere, or if the call fails: the JVM's own monotonic clock, which starts anew with every run of
 * Notio — the wait then falls back to the wall clock alone after a restart, as before.
 */
object BootClock {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun millis(): Long {
        if (windows) runCatching { return com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64() }
        return System.nanoTime() / 1_000_000
    }
}
