package com.notio.desktop

import com.notio.shared.domain.LimitUnit
import com.notio.shared.domain.RecordingKind
import com.notio.shared.domain.RecordingLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A limit for one recording only (Android D263): it is taken by the next recording of its kind, the one after goes by Settings. */
class RecordingLimitOnceTest {
    @Test
    fun theLimitOfOneRecordingIsTakenOnce() {
        val settings = RecordingLimit.DEFAULT
        val once = RecordingLimit(30, LimitUnit.SECONDS)
        RecordingLimitOnce.set(RecordingKind.AUDIO, once)
        assertEquals(settings, RecordingLimitOnce.take(RecordingKind.VIDEO, settings), "another kind is not touched")
        assertEquals(once, RecordingLimitOnce.take(RecordingKind.AUDIO, settings))
        assertEquals(settings, RecordingLimitOnce.take(RecordingKind.AUDIO, settings), "the next one goes by Settings")
        // "Back to Settings" forgets it.
        RecordingLimitOnce.set(RecordingKind.SCREEN, RecordingLimit.NONE)
        RecordingLimitOnce.set(RecordingKind.SCREEN, null)
        assertNull(RecordingLimitOnce.get(RecordingKind.SCREEN))
    }
}
