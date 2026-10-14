package com.notebudget.app.core.security

import android.content.Context
import com.notebudget.app.domain.model.AppLockConfig
import com.notebudget.app.domain.usecase.AppLockStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The lock in the app's private storage (§7.7, D124): one JSON text in a preferences file of its own, apart from the
 * settings and the database. It holds hashes only and is never part of a backup (the app has `allowBackup="false"` and
 * the backup code never reads it). A file that cannot be read means "no lock", never a crash.
 */
class SharedPreferencesLockStore(context: Context) : AppLockStore {
    private val prefs = context.applicationContext.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    override suspend fun load(): AppLockConfig = withContext(Dispatchers.IO) {
        prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(AppLockConfig.serializer(), it) }.getOrNull() }
            ?: AppLockConfig()
    }

    @android.annotation.SuppressLint("ApplySharedPref") // commit(), not apply(): a secret must be on disk before the caller goes on (a wipe, a conversion); this runs on Dispatchers.IO.
    override suspend fun save(config: AppLockConfig) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY, json.encodeToString(AppLockConfig.serializer(), config)).commit()
        Unit
    }

    private companion object {
        const val KEY = "config"
    }
}
