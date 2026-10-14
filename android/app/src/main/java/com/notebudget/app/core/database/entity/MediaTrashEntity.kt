package com.notebudget.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Table `media_trash` (schema v8, D156): an item of a media or audio note that was deleted; its file stays on the phone. */
@Entity(tableName = "media_trash", indices = [Index(value = ["note_id"])])
data class MediaTrashEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "item_json") val itemJson: String,
    val position: Int,
    @ColumnInfo(name = "note_title") val noteTitle: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
)
