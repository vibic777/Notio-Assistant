package com.notebudget.app.core.security

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether the database is encrypted right now, and the passphrase that opens it, when there is one. */
data class DatabaseEncryptionState(val enabled: Boolean, val passphrase: String?)

/**
 * The database passphrase (D-encryption), kept the same way as the backup password (D134): sealed with the phone's
 * Keystore key ([SecretBox]) so the person can look at it again in Settings, never written down in plain text, never
 * part of a backup. Unlike the backup password, the person does not type it — the app generates a long random one, since
 * nothing needs to remember it except the phone itself.
 */
class DatabaseEncryptionStore(context: Context, private val box: SecretBox) {
    private val prefs = context.applicationContext.getSharedPreferences("db_encryption", Context.MODE_PRIVATE)

    /**
     * Fast, synchronous read for the one place that needs it before anything else runs: opening the database itself.
     *
     * [DatabaseEncryptionState.passphrase] is `null` whenever [DatabaseEncryptionState.enabled] is `false` — even if a
     * sealed passphrase happens to still be sitting in storage (D186: [generatePassphrase] saves one before the
     * conversion runs, and a failed conversion never calls [setEnabled], so a leftover passphrase from a failed attempt
     * must never leak out here — that bug made `AppContainer.database` try to open the still-plain file through
     * SQLCipher with that stale passphrase, crashing the app on every later launch).
     */
    fun readBlocking(): DatabaseEncryptionState {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val passphrase = if (enabled) prefs.getString(KEY_PASSPHRASE, null)?.let { box.open(it) } else null
        return DatabaseEncryptionState(enabled = enabled && passphrase != null, passphrase = passphrase)
    }

    suspend fun read(): DatabaseEncryptionState = withContext(Dispatchers.IO) { readBlocking() }

    /** Generates a new passphrase and seals it, without marking encryption enabled yet (the conversion does that once it succeeds). */
    suspend fun generatePassphrase(): String = withContext(Dispatchers.IO) {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.getEncoder().withoutPadding().encodeToString(bytes)
        setPassphrase(passphrase)
        passphrase
    }

    /** Seals and remembers a passphrase chosen elsewhere (a raw database file that was just imported already encrypted with it). */
    @android.annotation.SuppressLint("ApplySharedPref") // commit(), not apply(): a secret must be on disk before the caller goes on (a wipe, a conversion); this runs on Dispatchers.IO.
    suspend fun setPassphrase(passphrase: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_PASSPHRASE, box.seal(passphrase)).commit()
        Unit
    }

    /** Set after the file conversion to encrypted (or back to plain) actually finished; never before. */
    @android.annotation.SuppressLint("ApplySharedPref") // commit(), not apply(): a secret must be on disk before the caller goes on (a wipe, a conversion); this runs on Dispatchers.IO.
    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val editor = prefs.edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) editor.remove(KEY_PASSPHRASE)
        editor.commit()
        Unit
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PASSPHRASE = "passphrase"
    }
}
