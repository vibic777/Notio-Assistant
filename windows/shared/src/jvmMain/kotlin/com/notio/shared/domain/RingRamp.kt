package com.notio.shared.domain

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
