package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

/** Start with Windows (D61) against the real HKCU Run key, as a portable Notio; a value the user already has is never touched. */
class AutoStartTest {
    private val run = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private val name = "Notio (portable)"

    @Test
    fun onRepairOff() {
        // The registry of Windows; the Linux way (a desktop entry) is AutoStartLinuxTest.
        org.junit.Assume.assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assumeFalse(Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, run, name))
        val first = Files.createTempDirectory("notio-autostart").toFile()
        val moved = Files.createTempDirectory("notio-autostart").toFile()
        val before = System.getProperty("jpackage.app-path")
        try {
            fun portableAt(dir: File) = File(dir, "Notio.exe").also { it.writeBytes(ByteArray(1)); File(dir, AppPaths.PORTABLE_MARKER).writeText("") }
            val exe = portableAt(first)
            System.setProperty("jpackage.app-path", exe.path)
            assertTrue(AutoStart.available)
            assertFalse(AutoStart.isOn())
            assertTrue(AutoStart.set(true))
            assertTrue(AutoStart.isOn())
            assertEquals("\"${exe.path}\" --autostart", Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, run, name))
            // A second copy while the first is still there (a build's run, a test build): the value stays with the first (D155).
            val exe2 = portableAt(moved)
            System.setProperty("jpackage.app-path", exe2.path)
            AutoStart.repair()
            assertEquals("\"${exe.path}\" --autostart", Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, run, name))
            // The training run of the build never touches it, even when the first is gone.
            check(exe.delete())
            System.setProperty(AutoStart.KEEP, "true")
            AutoStart.repair()
            assertEquals("\"${exe.path}\" --autostart", Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, run, name))
            System.clearProperty(AutoStart.KEEP)
            // The portable folder moved (the first Notio.exe is gone): the next start points the value at the new Notio.exe.
            AutoStart.repair()
            assertEquals("\"${exe2.path}\" --autostart", Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, run, name))
            assertTrue(AutoStart.set(false))
            assertFalse(AutoStart.isOn())
        } finally {
            System.clearProperty(AutoStart.KEEP)
            runCatching { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, run, name) }
            if (before == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", before)
            first.deleteRecursively(); moved.deleteRecursively()
        }
    }
}
