package com.notio.shared.sync

import com.notio.shared.data.NotioDatabase
import com.notio.shared.transfer.BackupService
import com.notio.shared.transfer.ImportMode
import java.io.File
import java.io.OutputStream

/** What sync moves (Android AppContainer.syncEngine.data): the active folders, the backup stream of some of them, its import. */
class DesktopSyncData(private val db: NotioDatabase, private val backup: BackupService, private val afterImport: () -> Unit = {}) : SyncData {
    override suspend fun folders(): List<FolderInfo> = db.folders().filter { it.deletedAt == null }.map { FolderInfo(it.id, it.name) }

    override suspend fun export(out: OutputStream, folderIds: Set<String>?) = export(out, folderIds, reminders = true)

    override suspend fun export(out: OutputStream, folderIds: Set<String>?, reminders: Boolean) {
        backup.export(out, password = null, folderIds = folderIds, withReminders = reminders)
    }

    /** The counts in the phone's order: folders added, folders merged, notes added, notes updated, notes skipped, files. */
    override suspend fun import(file: File, mode: ImportMode): List<Int> = import(file, mode, reminders = true)

    override suspend fun import(file: File, mode: ImportMode, reminders: Boolean): List<Int> = import(file, mode, reminders, folders = null)

    override suspend fun import(file: File, mode: ImportMode, reminders: Boolean, folders: Set<String>?): List<Int> {
        val r = backup.importFrom({ file.inputStream() }, mode, withReminders = reminders, replaceFolders = folders)
        afterImport()
        return listOf(r.stats.foldersAdded, r.stats.foldersMerged, r.stats.notesAdded, r.stats.notesUpdated, r.stats.notesSkipped, r.filesRestored)
    }
}
