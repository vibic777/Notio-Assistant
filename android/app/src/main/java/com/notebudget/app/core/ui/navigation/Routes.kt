package com.notebudget.app.core.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (Navigation Compose 2.8+). */
@Serializable
data object Home

@Serializable
data class NoteEditor(val noteId: String)

/** [section] and [entry] open a page of the settings and scroll to an option (e.g. the trash links to its own setting). */
@Serializable
data class Settings(val section: String? = null, val entry: String? = null)

@Serializable
data object Tags

@Serializable
data class Reminders(val noteId: String? = null)

@Serializable
data object AppLock

@Serializable
data object Backgrounds

@Serializable
data object Units

@Serializable
data object Currencies

@Serializable
data object Trash

@Serializable
data object Help

/** The calendar; from a reminder notification (D267) it opens on the day of [at] with the reminder [reminderId] marked. */
@Serializable
data class Calendar(val at: Long = 0L, val reminderId: String = "")
