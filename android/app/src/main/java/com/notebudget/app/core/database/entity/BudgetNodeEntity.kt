package com.notebudget.app.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Table `budget_node`, AGENT_EN.md §7.5 / §8. Money is `Long` minor units. */
@Entity(
    tableName = "budget_node",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BudgetNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["note_id"]), Index(value = ["parent_id"])],
)
data class BudgetNodeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val title: String,
    /** `BudgetNodeKind.name`. */
    val kind: String,
    @ColumnInfo(name = "manual_sum_minor") val manualSumMinor: Long = 0,
    @ColumnInfo(name = "manual_goal_minor") val manualGoalMinor: Long = 0,
    val comment: String? = null,
    @ColumnInfo(name = "cached_sum_minor") val cachedSumMinor: Long = 0,
    @ColumnInfo(name = "cached_goal_minor") val cachedGoalMinor: Long = 0,
    @ColumnInfo(name = "cached_remainder_minor") val cachedRemainderMinor: Long = 0,
    @ColumnInfo(name = "is_expanded") val isExpanded: Boolean = true,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Schema version 3 (D94): how many times a leaf repeats. */
    @ColumnInfo(name = "quantity", defaultValue = "1") val quantity: Int = 1,
)
