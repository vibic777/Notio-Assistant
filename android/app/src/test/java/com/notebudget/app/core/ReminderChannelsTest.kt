package com.notebudget.app.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.notebudget.app.core.notification.NotificationChannels
import com.notebudget.app.core.security.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The channel of a reminder follows its sound and vibration (D129), and the authenticator address fits a QR code. */
class ReminderChannelsTest {
    @Test
    fun `every sound and vibration has its own channel and the same choice always the same one`() {
        val default = NotificationChannels.idFor(null, true, true)
        assertEquals(default, NotificationChannels.idFor("", true, true))
        assertEquals(default, NotificationChannels.idFor("   ", true, true))
        assertNotEquals(default, NotificationChannels.idFor(null, true, false))
        val melody = NotificationChannels.idFor("content://media/internal/audio/media/42", true, true)
        assertNotEquals(default, melody)
        assertEquals(melody, NotificationChannels.idFor("content://media/internal/audio/media/42", true, true))
        assertNotEquals(melody, NotificationChannels.idFor("content://media/internal/audio/media/43", true, true))
        assertNotEquals(melody, NotificationChannels.idFor("content://media/internal/audio/media/42", true, false))
    }

    @Test
    fun `no sound is one channel whatever melody was chosen before it was switched off`() {
        val silent = NotificationChannels.idFor("content://media/x", false, false)
        assertEquals(silent, NotificationChannels.idFor(null, false, false))
        assertNotEquals(silent, NotificationChannels.idFor(null, false, true))
        assertTrue(silent.startsWith("rem2_"))
    }

    @Test
    fun `the authenticator address is small enough for a QR code`() {
        val uri = Totp.uri("Notio by vibic", "блокировка", Totp.newSecret())
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0)
        assertTrue(matrix.width in 21..80 && matrix.width == matrix.height)
        assertTrue("some modules are dark", (0 until matrix.width).any { x -> (0 until matrix.height).any { y -> matrix.get(x, y) } })
    }
}
