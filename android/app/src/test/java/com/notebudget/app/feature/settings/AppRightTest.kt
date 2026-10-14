package com.notebudget.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rights of the page "Rights" (D354): which a phone knows, which the app can take back itself. */
class AppRightTest {
    @Test
    fun `a right an older Android does not know is not a right of that phone`() {
        assertFalse("before Android 13 notifications are not asked for", AppRight.NOTIFICATIONS.known(32))
        assertTrue(AppRight.NOTIFICATIONS.known(33))
        assertFalse(AppRight.EXACT_ALARMS.known(30))
        assertTrue(AppRight.EXACT_ALARMS.known(31))
        assertFalse(AppRight.FULL_SCREEN.known(33))
        assertTrue(AppRight.FULL_SCREEN.known(34))
        for (right in listOf(AppRight.OVERLAY, AppRight.MICROPHONE, AppRight.CAMERA, AppRight.LOCATION)) assertTrue(right.name, right.known(28))
    }

    @Test
    fun `a right the phone asks about has its permissions, a leave of the settings has none`() {
        for (right in AppRight.entries) {
            assertEquals(right.name, right.kind == AppRight.Kind.ASKED, right.permissions.isNotEmpty())
        }
        assertEquals("the place is two permissions of Android", 2, AppRight.LOCATION.permissions.size)
    }

    @Test
    fun `the app gives a right up itself from Android 13 on`() {
        assertFalse(AppRight.canRevokeItself(32))
        assertTrue(AppRight.canRevokeItself(33))
    }
}
