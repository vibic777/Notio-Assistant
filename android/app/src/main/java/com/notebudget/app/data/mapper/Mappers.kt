package com.notebudget.app.data.mapper

import com.notebudget.app.core.database.entity.BudgetNodeEntity
import com.notebudget.app.core.database.entity.FolderEntity
import com.notebudget.app.core.database.entity.NoteEntity
import com.notebudget.app.core.database.entity.ReminderEntity
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Reminder
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.model.ReminderType

fun FolderEntity.toDomain() = Folder(
    id = id,
    name = name,
    colorHex = colorHex,
    styleId = styleId,
    icon = icon,
    sortOrder = sortOrder,
    featuredNoteId = featuredNoteId,
    featuredMetricsMask = featuredMetricsMask,
    featuredLayout = FeaturedLayout.valueOf(featuredLayout),
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    isPinned = isPinned,
    pinnedAt = pinnedAt,
    comment = comment,
    kind = com.notebudget.app.domain.model.FolderKind.of(kind),
)

fun Folder.toEntity() = FolderEntity(
    id = id,
    name = name,
    colorHex = colorHex,
    styleId = styleId,
    icon = icon,
    sortOrder = sortOrder,
    featuredNoteId = featuredNoteId,
    featuredMetricsMask = featuredMetricsMask,
    featuredLayout = featuredLayout.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    isPinned = isPinned,
    pinnedAt = pinnedAt,
    comment = comment,
    kind = kind.name,
)

fun NoteEntity.toDomain() = Note(
    id = id,
    folderId = folderId,
    title = title,
    type = NoteType(type),
    colorHex = colorHex,
    isPinned = isPinned,
    isFavorite = isFavorite,
    isTitleBold = isTitleBold,
    contentText = contentText,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    extras = extras,
    pinnedAt = pinnedAt,
    sortOrder = sortOrder,
    comment = comment,
    hidden = hidden,
)

fun Note.toEntity() = NoteEntity(
    id = id,
    folderId = folderId,
    title = title,
    type = type.id,
    colorHex = colorHex,
    isPinned = isPinned,
    isFavorite = isFavorite,
    isTitleBold = isTitleBold,
    contentText = contentText,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    extras = extras,
    pinnedAt = pinnedAt,
    sortOrder = sortOrder,
    comment = comment,
    hidden = hidden,
)

fun BudgetNodeEntity.toDomain() = BudgetNode(
    id = id,
    noteId = noteId,
    parentId = parentId,
    title = title,
    kind = BudgetNodeKind.valueOf(kind),
    manualSumMinor = manualSumMinor,
    manualGoalMinor = manualGoalMinor,
    comment = comment,
    cachedSumMinor = cachedSumMinor,
    cachedGoalMinor = cachedGoalMinor,
    cachedRemainderMinor = cachedRemainderMinor,
    isExpanded = isExpanded,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    quantity = quantity,
)

fun BudgetNode.toEntity() = BudgetNodeEntity(
    id = id,
    noteId = noteId,
    parentId = parentId,
    title = title,
    kind = kind.name,
    manualSumMinor = manualSumMinor,
    manualGoalMinor = manualGoalMinor,
    comment = comment,
    cachedSumMinor = cachedSumMinor,
    cachedGoalMinor = cachedGoalMinor,
    cachedRemainderMinor = cachedRemainderMinor,
    isExpanded = isExpanded,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    quantity = quantity,
)

fun ReminderEntity.toDomain() = Reminder(
    id = id,
    noteId = noteId,
    type = ReminderType.valueOf(type),
    triggerAt = triggerAt,
    isEnabled = isEnabled,
    status = ReminderStatus.valueOf(status),
    soundEnabled = soundEnabled,
    soundUri = soundUri,
    vibrationEnabled = vibrationEnabled,
    popupEnabled = popupEnabled,
    requiresConfirmation = requiresConfirmation,
    repeatUntilConfirmed = repeatUntilConfirmed,
    repeatIntervalMinutes = repeatIntervalMinutes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    volumePercent = volumePercent,
    rampSeconds = rampSeconds,
    repeatCount = repeatCount,
    repeatsDone = repeatsDone,
    recurrence = runCatching { com.notebudget.app.domain.model.Recurrence.valueOf(recurrence) }.getOrDefault(com.notebudget.app.domain.model.Recurrence.NONE),
    weekdaysMask = weekdaysMask,
    monthDay = monthDay,
    snoozedUntil = snoozedUntil,
)

fun Reminder.toEntity() = ReminderEntity(
    id = id,
    noteId = noteId,
    type = type.name,
    triggerAt = triggerAt,
    isEnabled = isEnabled,
    status = status.name,
    soundEnabled = soundEnabled,
    soundUri = soundUri,
    vibrationEnabled = vibrationEnabled,
    popupEnabled = popupEnabled,
    requiresConfirmation = requiresConfirmation,
    repeatUntilConfirmed = repeatUntilConfirmed,
    repeatIntervalMinutes = repeatIntervalMinutes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    volumePercent = volumePercent,
    rampSeconds = rampSeconds,
    repeatCount = repeatCount,
    repeatsDone = repeatsDone,
    recurrence = recurrence.name,
    weekdaysMask = weekdaysMask,
    monthDay = monthDay,    snoozedUntil = snoozedUntil,
)
