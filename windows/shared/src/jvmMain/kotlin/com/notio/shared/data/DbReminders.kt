package com.notio.shared.data

import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteRepository
import com.notio.shared.domain.Reminder
import com.notio.shared.domain.ReminderRepository
import com.notio.shared.domain.ReminderStatus

/** The reminders and their notes on the desktop database (the Android Room repositories the reminder rules use). */
class DbReminders(private val db: NotioDatabase) : ReminderRepository {
    override suspend fun getById(id: String): Reminder? = db.reminders().firstOrNull { it.id == id }

    override suspend fun getByNoteIds(noteIds: List<String>): List<Reminder> = noteIds.flatMap { db.remindersOf(it) }

    /** Reminders still to ring: enabled and scheduled or waiting for a confirmation, of notes that are not in the trash. */
    override suspend fun getActive(): List<Reminder> {
        val live = db.notes().filter { it.deletedAt == null }.mapTo(HashSet()) { it.id }
        return db.reminders().filter {
            it.noteId in live && it.isEnabled && (it.status == ReminderStatus.SCHEDULED || it.status == ReminderStatus.AWAITING_CONFIRMATION)
        }
    }

    override suspend fun upsert(reminder: Reminder) = db.upsertReminder(reminder)

    override suspend fun delete(id: String) {
        db.deleteReminder(id)
    }
}

/** The notes a reminder belongs to (a note in the trash has none that ring). */
class DbReminderNotes(private val db: NotioDatabase) : NoteRepository {
    override suspend fun getById(id: String): Note? = db.note(id)?.takeIf { it.deletedAt == null }
}
