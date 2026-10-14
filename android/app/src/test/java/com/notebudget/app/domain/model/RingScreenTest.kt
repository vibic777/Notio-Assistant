package com.notebudget.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** How long the alarm screen holds the screen, ring by ring (D351). */
class RingScreenTest {
    private val minute = 60_000L

    @Test
    fun `the first ring holds the screen a minute, the second 15 seconds, every later one 5`() {
        assertEquals(60_000L, RingScreen.holdMillis(1))
        assertEquals(15_000L, RingScreen.holdMillis(2))
        assertEquals(5_000L, RingScreen.holdMillis(3))
        assertEquals(5_000L, RingScreen.holdMillis(50))
        assertEquals("a number that makes no sense is a first ring", 60_000L, RingScreen.holdMillis(0))
    }

    @Test
    fun `rings one repeat interval apart go on a series`() {
        var ring = 0
        var at = 0L
        val rings = (0 until 4).map { i ->
            val now = 1_000_000L + i * 5 * minute
            ring = RingScreen.nextRing(ring, at, now, repeatIntervalMinutes = 5)
            at = now
            ring
        }
        assertEquals(listOf(1, 2, 3, 4), rings)
    }

    @Test
    fun `a ring long after the last one, a reminder that does not repeat and an answered one start anew`() {
        val now = 10_000_000L
        assertEquals("the next day", 1, RingScreen.nextRing(3, now - 24 * 60 * minute, now, 5))
        assertEquals("within two intervals and a minute", 4, RingScreen.nextRing(3, now - 11 * minute, now, 5))
        assertEquals(1, RingScreen.nextRing(3, now - 11 * minute - 1, now, 5))
        assertEquals("no repeat", 1, RingScreen.nextRing(3, now - minute, now, null))
        assertEquals(1, RingScreen.nextRing(3, now - minute, now, 0))
        assertEquals("answered: nothing is kept", 1, RingScreen.nextRing(0, 0, now, 5))
        assertEquals("the clock was set back", 1, RingScreen.nextRing(2, now + minute, now, 5))
    }
}
