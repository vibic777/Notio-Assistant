package com.notio.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.notio.shared.lock.AppLockGate
import com.notio.shared.lock.AppLockUseCases

/** The app lock of the window (the phone's `container.appLock` / `container.lockGate`), for the ported lock screens. */
val LocalAppLock = staticCompositionLocalOf<AppLockUseCases> { error("no app lock") }
val LocalLockGate = staticCompositionLocalOf<AppLockGate> { error("no lock gate") }

/**
 * The phone's fingerprint / face unlock (Android `BiometricAuth`) as **Windows Hello** (AGENT.md D37): the system's own face, fingerprint
 * or PIN dialog through `Windows.Security.Credentials.UI.UserConsentVerifier` (WinRT, called by the built-in PowerShell, so nothing is
 * bundled). Where Hello is not set up the option is shown as unavailable, as on a phone without a sensor.
 */
object BiometricAuth {
    private const val PRELUDE = "Add-Type -AssemblyName System.Runtime.WindowsRuntime; " +
        "${'$'}null = [Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime]; " +
        "${'$'}m = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]; "

    /** Checked once in the background at start; `false` until the answer is there. */
    @Volatile private var available: Boolean? = null

    init {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Thread({
            available = run(
                PRELUDE + "${'$'}t = ${'$'}m.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerifierAvailability]).Invoke(${'$'}null, @([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync())); ${'$'}t.Wait(-1) | Out-Null; ${'$'}t.Result",
            )?.trim() == "Available"
        }, "notio-hello").apply { isDaemon = true; start() }
    }

    fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Any?): Boolean = available == true

    /** Shows the Windows Hello dialog with [title]; [onSuccess] runs (on the UI thread) when the person is verified. */
    fun prompt(@Suppress("UNUSED_PARAMETER") activity: Any?, title: String, @Suppress("UNUSED_PARAMETER") cancel: String, onSuccess: () -> Unit) {
        if (available != true) return
        Thread({
            val message = title.replace("'", "''")
            val result = run(
                PRELUDE + "${'$'}t = ${'$'}m.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerificationResult]).Invoke(${'$'}null, @([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync('$message'))); ${'$'}t.Wait(-1) | Out-Null; ${'$'}t.Result",
            )?.trim()
            if (result == "Verified") javax.swing.SwingUtilities.invokeLater(onSuccess)
        }, "notio-hello-verify").apply { isDaemon = true; start() }
    }

    private fun run(script: String): String? = runCatching {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        // The answer is a few words; a PowerShell that hangs is ended after two minutes instead of keeping this thread and itself
        // alive for good (the whole-code pass, round 2: the output used to be read first, which waits for the process to end).
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val out = process.inputStream.bufferedReader().use { it.readText() }
        out.lines().lastOrNull { it.isNotBlank() }
    }.getOrNull()
}

/** Windows 10 2004+: `WDA_EXCLUDEFROMCAPTURE` keeps the window out of screenshots and captures; off gives it back. */
fun setExcludedFromCapture(window: java.awt.Window, excluded: Boolean) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
    runCatching {
        val hwnd = com.sun.jna.Native.getWindowPointer(window) ?: return
        com.sun.jna.NativeLibrary.getInstance("user32").getFunction("SetWindowDisplayAffinity").invokeInt(arrayOf(hwnd, if (excluded) 0x11 else 0))
    }
}

/** The controller of the window, for ported screens that need a little more than their state (files, texts). */
val LocalController = androidx.compose.runtime.staticCompositionLocalOf<NotioController> { error("no controller") }
