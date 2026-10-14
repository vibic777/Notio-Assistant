package com.notebudget.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table `budget_log` (schema v9, D166): the log of a budget note. It goes with the note when the note is deleted for good.
 * Since v13 (D282) a line knows the item or group it is about ([nodeId]), so it can be undone from the log.
 */
@Entity(
    tableName = "budget_log",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["note_id"])],
)
data class BudgetLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "logged_at") val at: Long,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "old_value") val oldValue: Long?,
    @ColumnInfo(name = "new_value") val newValue: Long?,
    val detail: String?,
    @ColumnInfo(name = "node_id") val nodeId: String? = null,
)
