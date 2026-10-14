package com.notio.desktop

import com.notio.shared.AppLog
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The watch over the window's thread (D72): a slow event is written to the app log with where it was, a quick one is not. */
class FreezeWatchTest {
    private val dir = Files.createTempDirectory("notio-freeze").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aSlowEventIsLoggedWithWhereItWas() {
        AppLog.install(dir)
        AppLog.clear()
        FreezeWatch.install()
        repeat(20) { SwingUtilities.invokeAndWait { Thread.sleep(5) } }
        Thread.sleep(400)
        AppLog.flush()
        assertFalse("busy" in AppLog.read(), AppLog.read())

        SwingUtilities.invokeAndWait { slowWork() }
        Thread.sleep(200)
        AppLog.flush()
        val log = AppLog.read()
        assertTrue(Regex("""the window was busy (\d+) ms""").find(log)?.groupValues?.get(1)?.toInt()?.let { it >= 600 } == true, log)
        assertTrue("slowWork" in log, log)
    }

    private fun slowWork() = Thread.sleep(700)

    @Test
    fun aThreadWaitingInsideADialogIsNotStuck() {
        val waiting = listOf(StackTraceElement("java.lang.Object", "wait", null, -1), StackTraceElement("java.awt.EventQueue", "getNextEvent", null, 1))
        assertTrue(FreezeWatch.waitingForEvents(waiting))
        assertFalse(FreezeWatch.waitingForEvents(listOf(StackTraceElement("com.notio.desktop.X", "work", null, 1))))
    }
}
