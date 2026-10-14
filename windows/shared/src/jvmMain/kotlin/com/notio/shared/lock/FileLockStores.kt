package com.notio.shared.lock

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

private fun writeAtomically(file: File, text: String) {
    com.notio.shared.data.SafeFile.writeText(file, text)
}

/**
 * The lock in the data folder (the phone's `SharedPreferencesLockStore`, AGENT.md D37): only salted hashes and the sealed
 * authenticator key are kept (INV-9); never part of a backup or a sync.
 */
class FileLockStore(private val file: File) : AppLockStore {
    /**
     * A second copy of the lock (the code check of 2026-10-01): a damaged or lost `lock.json` used to read as "no lock" and opened
     * the app without its password. Now the copy is read then; only when neither reads is the app opened, with a critical log line
     * (nobody could unlock it otherwise; the data itself is guarded by the database encryption, not by this file).
     */
    private val copy = File(file.path + ".copy")

    private fun read(f: File): AppLockConfig? =
        runCatching { com.notio.shared.data.SafeFile.readText(f)?.let { JSON.decodeFromString(AppLockConfig.serializer(), it) } }.getOrNull()

    override suspend fun load(): AppLockConfig = withContext(Dispatchers.IO) {
        read(file)?.let { config ->
            // A lock saved by an older Notio has no copy yet.
            if (config.enabled && !copy.isFile) runCatching { writeAtomically(copy, JSON.encodeToString(AppLockConfig.serializer(), config)) }
            return@withContext config
        }
        val saved = read(copy)
        if (saved != null) {
            com.notio.shared.AppLog.w("lock", "lock.json did not read; its copy was taken")
            runCatching { writeAtomically(file, JSON.encodeToString(AppLockConfig.serializer(), saved)) }
            return@withContext saved
        }
        if (file.exists() || copy.exists()) com.notio.shared.AppLog.critical("lock", "neither lock.json nor its copy reads; the lock is off")
        AppLockConfig()
    }

    override suspend fun save(config: AppLockConfig) = withContext(Dispatchers.IO) {
        val text = JSON.encodeToString(AppLockConfig.serializer(), config)
        writeAtomically(file, text)
        writeAtomically(copy, text)
    }
}

/** The mail server of the recovery code (the phone's `SharedPreferencesSmtpStore`): the password sealed for this Windows user. */
class FileSmtpStore(private val file: File, private val box: SecretBox) : SmtpStore {
    override suspend fun load(): SmtpSettings? = withContext(Dispatchers.IO) {
        val stored = runCatching { JSON.decodeFromString(SmtpSettings.serializer(), com.notio.shared.data.SafeFile.readText(file)!!) }.getOrNull() ?: return@withContext null
        // A password that cannot be opened (another computer or user) reads as empty: the person types it again.
        stored.copy(password = stored.password.takeIf { it.isNotEmpty() }?.let { box.open(it) }.orEmpty())
    }

    override suspend fun save(settings: SmtpSettings?) = withContext(Dispatchers.IO) {
        if (settings == null) { file.delete(); return@withContext }
        val sealed = settings.copy(password = settings.password.takeIf { it.isNotEmpty() }?.let { box.seal(it) }.orEmpty())
        writeAtomically(file, JSON.encodeToString(SmtpSettings.serializer(), sealed))
    }
}
