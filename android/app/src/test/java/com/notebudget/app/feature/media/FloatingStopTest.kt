package com.notebudget.app.feature.media

import com.notebudget.app.core.common.BackgroundRestriction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why Android ended a screen capture right after its start (D249). */
class FloatingStopTest {
    @Test
    fun `a quick stop while Notio is restricted in the background is explained by it, nothing else is`() {
        // The user's Samsung: Android ended the capture 0.5 - 0.8 s after the start; Notio was "Restricted" in its battery settings.
        assertTrue(BackgroundRestriction.explainsStop(sinceStart = 700, restricted = true))
        assertTrue(BackgroundRestriction.explainsStop(sinceStart = 5_000, restricted = true))
        assertFalse(BackgroundRestriction.explainsStop(sinceStart = 60_000, restricted = true))
        assertFalse(BackgroundRestriction.explainsStop(sinceStart = 700, restricted = false))
    }
}
