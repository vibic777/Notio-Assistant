package com.notio.shared

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app log settings (Android D254; the phone's `AppLogTest` cases on the desktop's `AppLog`). */
class AppLogOptionsTest {
    private val dir = Files.createTempDirectory("notio-log").toFile()

    @AfterTest
    fun cleanUp() {
        AppLog.setOptions(AppLog.Options())
        dir.deleteRecursively()
    }

    @Test
    fun theTickedLevelsAreWrittenACrashAlwaysNothingElseWhenTheLogIsOff() {
        val all = AppLog.Options()
        AppLog.Level.entries.forEach { assertTrue(AppLog.accepts(it, all), it.name) }
        val errorsOnly = AppLog.Options(levels = setOf(AppLog.Level.ERROR, AppLog.Level.CRITICAL))
        assertTrue(AppLog.accepts(AppLog.Level.ERROR, errorsOnly))
        assertFalse(AppLog.accepts(AppLog.Level.INFO, errorsOnly))
        assertFalse(AppLog.accepts(AppLog.Level.DEBUG, errorsOnly))
        val off = AppLog.Options(enabled = false)
        assertFalse(AppLog.accepts(AppLog.Level.CRITICAL, off))
        assertTrue(AppLog.accepts(AppLog.Level.FATAL, off))
        assertTrue(AppLog.accepts(AppLog.Level.FATAL, AppLog.Options(levels = emptySet())))
    }

    @Test
    fun theLogClearsItselfAfterItsPeriodNeverWhenNever() {
        val day = 86_400_000L
        assertTrue(AppLog.clearDue(0, day, AppLog.ClearPeriod.DAILY))
        assertFalse(AppLog.clearDue(0, day - 1, AppLog.ClearPeriod.DAILY))
        assertFalse(AppLog.clearDue(0, 6 * day, AppLog.ClearPeriod.WEEKLY))
        assertTrue(AppLog.clearDue(0, 7 * day, AppLog.ClearPeriod.WEEKLY))
        assertTrue(AppLog.clearDue(0, 30 * day, AppLog.ClearPeriod.MONTHLY))
        assertFalse(AppLog.clearDue(0, 1_000 * day, AppLog.ClearPeriod.NEVER))
        assertEquals(AppLog.ClearPeriod.DAILY, AppLog.Options().clear)
        assertEquals(AppLog.CHOOSABLE.toSet(), AppLog.Options().levels)
    }

    @Test
    fun theChoicesAreKeptAndActAtOnce() {
        AppLog.install(dir)
        AppLog.setOptions(AppLog.Options(levels = setOf(AppLog.Level.ERROR), clear = AppLog.ClearPeriod.WEEKLY))
        AppLog.i("test", "not written")
        AppLog.e("test", "written")
        AppLog.flush()
        val text = AppLog.read()
        assertFalse(text.contains("not written"))
        assertTrue(text.contains("written"))
        // Read again at the next start.
        AppLog.install(dir)
        assertEquals(setOf(AppLog.Level.ERROR), AppLog.options().levels)
        assertEquals(AppLog.ClearPeriod.WEEKLY, AppLog.options().clear)
    }
}
