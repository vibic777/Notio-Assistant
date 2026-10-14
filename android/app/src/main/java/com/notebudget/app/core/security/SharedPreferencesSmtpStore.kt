package com.notebudget.app.core.security

import android.content.Context
import com.notebudget.app.core.mail.SmtpSettings
import com.notebudget.app.domain.usecase.SmtpStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The mail server settings in the app's private storage (D129), the PASSWORD ENCRYPTED with the Keystore key
 * ([SecretBox]); never part of a backup. A password that cannot be decrypted (another phone) reads as empty, so
 * the person types it again instead of the app failing.
 */
class SharedPreferencesSmtpStore(context: Context, private val box: SecretBox) : SmtpStore {
    private val prefs = context.applicationContext.getSharedPreferences("app_lock_mail", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    override suspend fun load(): SmtpSettings? = withContext(Dispatchers.IO) {
        val text = prefs.getString(KEY, null) ?: return@withContext null
        val stored = runCatching { json.decodeFromString(SmtpSettings.serializer(), text) }.getOrNull() ?: return@withContext null
        stored.copy(password = stored.password.takeIf { it.isNotEmpty() }?.let { box.open(it) }.orEmpty())
    }

    @android.annotation.SuppressLint("ApplySharedPref") // commit(), not apply(): a secret must be on disk before the caller goes on (a wipe, a conversion); this runs on Dispatchers.IO.
    override suspend fun save(settings: SmtpSettings?) = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        if (settings == null) editor.remove(KEY) else {
            val sealed = settings.copy(password = settings.password.takeIf { it.isNotEmpty() }?.let { box.seal(it) }.orEmpty())
            editor.putString(KEY, json.encodeToString(SmtpSettings.serializer(), sealed))
        }
        editor.commit()
        Unit
    }

    private companion object {
        const val KEY = "smtp"
    }
}
