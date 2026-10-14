package com.notebudget.app.core.security

import android.content.Context
import com.notebudget.app.data.transfer.BackupPasswordSettings
import com.notebudget.app.data.transfer.BackupPasswordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The backup password in the app's private storage (D134), ENCRYPTED with the Keystore key ([SecretBox]) so the person can
 * look at it again. Never part of a backup. A password that cannot be opened (another phone) reads as empty: the person
 * types a new one instead of the app failing.
 */
class SharedPreferencesBackupPasswordStore(context: Context, private val box: SecretBox) : BackupPasswordStore {
    private val prefs = context.applicationContext.getSharedPreferences("backup_password", Context.MODE_PRIVATE)

    override suspend fun load(): BackupPasswordSettings = withContext(Dispatchers.IO) {
        val password = prefs.getString(KEY_PASSWORD, null)?.let { box.open(it) }.orEmpty()
        BackupPasswordSettings(enabled = prefs.getBoolean(KEY_ENABLED, false) && password.isNotEmpty(), password = password)
    }

    @android.annotation.SuppressLint("ApplySharedPref") // commit(), not apply(): a secret must be on disk before the caller goes on (a wipe, a conversion); this runs on Dispatchers.IO.
    override suspend fun save(settings: BackupPasswordSettings) = withContext(Dispatchers.IO) {
        val editor = prefs.edit().putBoolean(KEY_ENABLED, settings.enabled)
        if (settings.password.isEmpty()) editor.remove(KEY_PASSWORD) else editor.putString(KEY_PASSWORD, box.seal(settings.password))
        editor.commit()
        Unit
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PASSWORD = "password"
    }
}
