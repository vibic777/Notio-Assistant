package com.notebudget.app.core.common

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app log (D230): levels, the error and where it happened, FATAL written at once, clearing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLogTest {
    @Before
    fun setUp() {
        AppLog.install(ApplicationProvider.getApplicationContext())
        AppLog.clear()
    }

    @After
    fun tearDown() = AppLog.clear()

    @Test
    fun lineHasTimeLevelTagAndMessage() {
        val line = AppLog.format(0L, AppLog.Level.CRITICAL, "screen", "the video could not be finished", null)
        assertTrue(line, Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} CRITICAL screen: the video could not be finished\n$""").matches(line))
    }

    @Test
    fun errorAndItsCauseAreWritten() {
        val error = IllegalStateException("outer", RuntimeException("inner"))
        val text = AppLog.format(0L, AppLog.Level.ERROR, "t", "m", error)
        assertTrue(text, text.contains("java.lang.IllegalStateException: outer"))
        assertTrue(text, text.contains("caused by java.lang.RuntimeException: inner"))
        assertTrue(text, text.contains("        at "))
    }

    @Test
    fun fatalIsOnDiskAtOnceAndClearEmptiesTheLog() {
        AppLog.fatal("crash", "the app closes", RuntimeException("boom"))
        val text = AppLog.read()
        assertTrue(text, text.contains("FATAL") && text.contains("crash: the app closes") && text.contains("boom"))
        AppLog.clear()
        assertEquals("", AppLog.read())
    }

    @Test
    fun otherLevelsArriveThroughTheWriterThread() {
        AppLog.i("sync", "discovery: seen Tab")
        val deadline = System.currentTimeMillis() + 5_000
        while (!AppLog.read().contains("discovery: seen Tab") && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(AppLog.read().contains("INFO     sync: discovery: seen Tab"))
    }

    @Test
    fun `the ticked levels are written, a crash always, nothing else when the log is off (D254)`() {
        val all = AppLog.Options()
        AppLog.Level.entries.forEach { assertTrue(it.name, AppLog.accepts(it, all)) }
        val errorsOnly = AppLog.Options(levels = setOf(AppLog.Level.ERROR, AppLog.Level.CRITICAL))
        assertTrue(AppLog.accepts(AppLog.Level.ERROR, errorsOnly))
        org.junit.Assert.assertFalse(AppLog.accepts(AppLog.Level.INFO, errorsOnly))
        org.junit.Assert.assertFalse(AppLog.accepts(AppLog.Level.DEBUG, errorsOnly))
        val off = AppLog.Options(enabled = false)
        org.junit.Assert.assertFalse(AppLog.accepts(AppLog.Level.CRITICAL, off))
        assertTrue(AppLog.accepts(AppLog.Level.FATAL, off))
        assertTrue(AppLog.accepts(AppLog.Level.FATAL, AppLog.Options(levels = emptySet())))
    }

    @Test
    fun `the log clears itself after its period, never when never (D254)`() {
        val day = 86_400_000L
        assertTrue(AppLog.clearDue(0, day, AppLog.ClearPeriod.DAILY))
        org.junit.Assert.assertFalse(AppLog.clearDue(0, day - 1, AppLog.ClearPeriod.DAILY))
        org.junit.Assert.assertFalse(AppLog.clearDue(0, 6 * day, AppLog.ClearPeriod.WEEKLY))
        assertTrue(AppLog.clearDue(0, 7 * day, AppLog.ClearPeriod.WEEKLY))
        assertTrue(AppLog.clearDue(0, 30 * day, AppLog.ClearPeriod.MONTHLY))
        org.junit.Assert.assertFalse(AppLog.clearDue(0, 1_000 * day, AppLog.ClearPeriod.NEVER))
        // Daily is the default (user, 2026-09-27), with every level ticked.
        org.junit.Assert.assertEquals(AppLog.ClearPeriod.DAILY, AppLog.Options().clear)
        org.junit.Assert.assertEquals(AppLog.CHOOSABLE.toSet(), AppLog.Options().levels)
    }
}
