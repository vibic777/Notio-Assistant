package com.notio.shared.domain

import com.notio.shared.data.DbReminderNotes
import com.notio.shared.data.DbReminders
import com.notio.shared.data.NotioDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The on / off switch of a reminder (Android D268), on the desktop database. */
class ReminderSwitchTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val dir: File = Files.createTempDirectory("notio-test").toFile()
    private val db = NotioDatabase.open(File(dir, "notio.db"))
    private var now = 1_800_000_000_000L
    private val alarms = HashMap<String, Long>()
    private val scheduler = object : ReminderScheduler {
        override fun schedule(reminderId: String, triggerAtMillis: Long) { alarms[reminderId] = triggerAtMillis }
        override fun cancel(reminderId: String) { alarms.remove(reminderId) }
        override fun canScheduleExact() = true
    }
    private val repo = DbReminders(db)
    private val reminders = ReminderUseCases(repo, DbReminderNotes(db), scheduler, clock = { now })

    @AfterTest
    fun cleanUp() { db.close(); dir.deleteRecursively() }

    private fun create(draft: ReminderDraft): Reminder = runBlocking {
        db.upsertFolder(Folder("f", "F", "#3366FF", "SOFT", null, 0, null, 0, FeaturedLayout.COMPACT, now, now))
        db.upsertNote(Note("n", "f", "N", NoteType.TEXT, null, false, false, false, null, now, now, null, null))
        (reminders.create("n", draft) as DomainResult.Success).value
    }

    @Test
    fun offRemovesTheAlarmAndKeepsTheTimeOnSetsItBack() = runBlocking {
        val r = create(ReminderDraft(triggerAt = now + 10 * minute))
        val off = (reminders.setEnabled(r.id, false) as DomainResult.Success).value
        assertFalse(off.isEnabled)
        assertEquals(r.triggerAt, off.triggerAt)
        assertNull(alarms[r.id])
        assertTrue(repo.getActive().none { it.id == r.id })
        // Switched off, a late alarm does nothing.
        assertNull(reminders.onFired(r.id))

        val on = (reminders.setEnabled(r.id, true) as DomainResult.Success).value
        assertTrue(on.isEnabled)
        assertEquals(r.triggerAt, alarms[r.id])
    }

    @Test
    fun aOneTimeReminderWhoseTimeWentByCannotBeSwitchedOn() = runBlocking {
        val r = create(ReminderDraft(triggerAt = now + 10 * minute))
        reminders.setEnabled(r.id, false)
        now += 20 * minute
        assertEquals(DomainResult.Failure(DomainError.REMINDER_IN_PAST), reminders.setEnabled(r.id, true))
        assertNull(alarms[r.id])
    }

    @Test
    fun aDailyReminderSwitchedOnAfterItsTimeComesBackAtItsNextDay() = runBlocking {
        val r = create(ReminderDraft(triggerAt = now + 10 * minute, recurrence = Recurrence.WEEKLY, weekdaysMask = RecurrenceRules.ALL_DAYS))
        reminders.setEnabled(r.id, false)
        now += 3 * day
        val on = (reminders.setEnabled(r.id, true) as DomainResult.Success).value
        assertTrue(on.triggerAt > now)
        assertTrue(on.triggerAt - now <= day)
        assertEquals(on.triggerAt, alarms[r.id])
    }
}
