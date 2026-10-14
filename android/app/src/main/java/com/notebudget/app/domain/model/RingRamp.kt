package com.notebudget.app.domain.model

/** The volume of a melody that fades in (D130); pure, so the curve is tested. */
object RingRamp {
    /** The melody never starts at a full stop: this share of the chosen volume is heard at once. */
    const val START_SHARE = 0.05f

    /**
     * The gain (0..1) [elapsedMillis] after the start, for a chosen [volumePercent] (10..100) and a fade-in of [rampSeconds]
     * (0 = the chosen volume at once): a straight climb from [START_SHARE] of the volume to all of it.
     */
    fun gain(elapsedMillis: Long, rampSeconds: Int, volumePercent: Int): Float {
        val target = volumePercent.coerceIn(10, 100) / 100f
        if (rampSeconds <= 0) return target
        val progress = (elapsedMillis.coerceAtLeast(0).toFloat() / (rampSeconds * 1000f)).coerceIn(0f, 1f)
        return target * (START_SHARE + (1f - START_SHARE) * progress)
    }
}

/**
 * How long the alarm screen holds the phone's screen on (D351): a ring nobody answers lights it for less each time, because
 * a reminder that repeats — until Done, or with extra rings — would else light it for a minute at every ring, without an end.
 * Pure, so the rule is tested.
 */
object RingScreen {
    const val FIRST_MILLIS = 60_000L
    const val SECOND_MILLIS = 15_000L
    const val LATER_MILLIS = 5_000L

    /** [ring] is the number of the ring in its series, from 1. */
    fun holdMillis(ring: Int): Long = when {
        ring <= 1 -> FIRST_MILLIS
        ring == 2 -> SECOND_MILLIS
        else -> LATER_MILLIS
    }

    /**
     * The number of a ring that comes at [now], when the one before it of the same reminder was number [lastRing] at [lastAt]
     * (`0`: there was none, or it was answered). A ring within two repeat intervals of the last one (and a minute, for an alarm
     * that came late) goes on the series; a reminder that does not repeat, a ring long after, a clock set back start a new one.
     */
    fun nextRing(lastRing: Int, lastAt: Long, now: Long, repeatIntervalMinutes: Int?): Int {
        val interval = repeatIntervalMinutes?.takeIf { it > 0 } ?: return 1
        if (lastRing <= 0 || lastAt <= 0) return 1
        val gap = now - lastAt
        return if (gap in 0..(interval * 2L * 60_000L + 60_000L)) lastRing + 1 else 1
    }
}
