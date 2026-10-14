package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time limit of recordings (D251). */
class RecordingLimitTest {
    @Test
    fun `four minutes by default, zero means until stopped`() {
        assertEquals(240_000L, RecordingLimit.DEFAULT.millis)
        assertTrue(RecordingLimit.NONE.unlimited)
        assertEquals(0L, RecordingLimit.NONE.millis)
        assertEquals(30_000L, RecordingLimit(30, LimitUnit.SECONDS).millis)
        assertEquals(7_200_000L, RecordingLimit(2, LimitUnit.HOURS).millis)
        // The largest: 65535 hours does not overflow.
        assertEquals(65_535L * 3_600_000L, RecordingLimit(65_535, LimitUnit.HOURS).millis)
    }

    @Test
    fun `stored and read back, anything unreadable is the default`() {
        val limit = RecordingLimit(90, LimitUnit.SECONDS)
        assertEquals(limit, RecordingLimit.decode(limit.encode()))
        assertEquals(RecordingLimit.DEFAULT, RecordingLimit.decode(null))
        assertEquals(RecordingLimit.DEFAULT, RecordingLimit.decode("x:MINUTES"))
        assertEquals(RecordingLimit.DEFAULT, RecordingLimit.decode("5:WEEKS"))
        assertEquals(RecordingLimit(65_535, LimitUnit.MINUTES), RecordingLimit.decode("999999:MINUTES"))
    }

    @Test
    fun `what is typed is kept within 0 and 65535`() {
        assertEquals(12, RecordingLimit.parseAmount("12"))
        assertEquals(65_535, RecordingLimit.parseAmount("70000"))
        assertEquals(5, RecordingLimit.parseAmount("5 min"))
        assertEquals(0, RecordingLimit.parseAmount("0"))
        assertNull(RecordingLimit.parseAmount(""))
    }
}
