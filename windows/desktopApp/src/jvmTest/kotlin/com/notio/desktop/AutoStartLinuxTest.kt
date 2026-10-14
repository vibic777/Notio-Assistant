package com.notio.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Start with the system" on Linux: the desktop entry of `~/.config/autostart` (D129). */
class AutoStartLinuxTest {
    @Test
    fun `the entry starts this Notio with the argument of an automatic start`() {
        val text = AutoStart.linuxEntryText("Notio (portable)", File("/home/me/My Apps/Notio-1.0-linux-x64.bin"))
        assertTrue(text.startsWith("[Desktop Entry]\nType=Application\n"))
        assertTrue("Name=Notio (portable)\n" in text)
        // The path in quotes: a folder with a space in its name must not cut the command in two.
        assertTrue(text.lines().any { it.startsWith("Exec=\"") && it.endsWith("Notio-1.0-linux-x64.bin\" " + AutoStart.ARG) }, text)
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `another Notio that is still there keeps the start, a gone one or this one is pointed here (D155)`() {
        val dir = java.nio.file.Files.createTempDirectory("notio-autostart").toFile()
        try {
            val here = File(dir, "here/Notio.exe").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(1)) }
            val other = File(dir, "other dir/Notio.exe").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(1)) }
            assertEquals(other, AutoStart.programOf("\"${other.path}\" " + AutoStart.ARG))
            assertEquals(File("/opt/notio/bin/Notio"), AutoStart.programOf("/opt/notio/bin/Notio --autostart"))
            assertFalse(AutoStart.mayRepoint("\"${other.path}\" " + AutoStart.ARG, here))
            assertTrue(AutoStart.mayRepoint("\"${here.path}\"", here))
            assertTrue(AutoStart.mayRepoint("\"${File(dir, "moved/Notio.exe").path}\" " + AutoStart.ARG, here))
            assertTrue(AutoStart.mayRepoint(null, here))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the words of the switch name the system they are shown on`() {
        val shown = AutoStart.systemName("Start with Windows")
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        assertEquals(if (windows) "Start with Windows" else "Start with Linux", shown)
    }
}
