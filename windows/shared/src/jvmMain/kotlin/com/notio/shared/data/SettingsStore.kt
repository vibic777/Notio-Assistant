package com.notio.shared.data

import com.notio.shared.transfer.SettingsRecord
import com.notio.shared.transfer.TransferFormat
import java.io.File

/**
 * The non-secret settings that travel in a backup (Android INV-11), kept as the backup's own `SettingsRecord` in a JSON
 * file. The desktop applies only some of them so far (language, theme); the rest are kept so they go back to a phone intact.
 * The file is read once and kept in memory (D72: it was read and parsed again on every look, some of them while the window
 * drew); every write goes through here, so the copy in memory is always what the file holds.
 */
class SettingsStore(private val file: File) {
    @Volatile
    private var cached: SettingsRecord? = null

    fun read(): SettingsRecord = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    @Synchronized
    fun write(settings: SettingsRecord) {
        SafeFile.writeText(file, TransferFormat.json.encodeToString(SettingsRecord.serializer(), settings))
        cached = settings
    }

    private fun load(): SettingsRecord = runCatching {
        SafeFile.readText(file)?.let { TransferFormat.json.decodeFromString(SettingsRecord.serializer(), it) }
    }.getOrNull() ?: SettingsRecord()
}
