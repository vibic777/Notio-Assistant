package com.notebudget.app.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D186/D187: a passphrase generated for a conversion that then FAILS must never be readable as if it were live — that
 * exact bug made `AppContainer.database` try to open the still-plaintext file through SQLCipher with a stale
 * passphrase, crashing the app on every later launch, on a real phone, after one failed encryption attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseEncryptionStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = DatabaseEncryptionStore(context, PlainSecretBox())

    @Test
    fun `a generated passphrase from a failed attempt is not exposed until setEnabled(true) is called`() = runBlocking {
        store.generatePassphrase()
        // The conversion "failed": setEnabled(true) is only ever called on success, per SettingsScreen.toggle.
        val state = store.read()
        assertEquals("a failed attempt must not read as enabled", false, state.enabled)
        assertNull("the leftover passphrase must not leak out while not enabled", state.passphrase)
    }

    @Test
    fun `once enabled, the same passphrase is readable again`() = runBlocking {
        val generated = store.generatePassphrase()
        store.setEnabled(true)
        val state = store.read()
        assertEquals(true, state.enabled)
        assertEquals(generated, state.passphrase)
    }

    @Test
    fun `turning it off clears the passphrase so nothing can leak later`() = runBlocking {
        store.generatePassphrase()
        store.setEnabled(true)
        store.setEnabled(false)
        val state = store.read()
        assertEquals(false, state.enabled)
        assertNull(state.passphrase)
    }
}
