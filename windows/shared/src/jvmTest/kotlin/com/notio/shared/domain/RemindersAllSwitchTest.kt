package com.notio.shared.domain

import com.notio.shared.data.DbReminderNotes
import com.notio.shared.data.DbReminders
import com.notio.shared.data.NotioDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** All reminders on or off at once (Android D318): off, nothing is armed and nothing fires; on, they come back as after a restart. */
class RemindersAllSwitchTest {
    private val minute = 60_000L
    private val dir: File = Files.createTempDirectory("notio-test").toFile()
    private val db = NotioDatabase.open(File(dir, "notio.db"))
    private var now = 1_800_000_000_000L
    private var allOn = true
    private val alarms = HashMap<String, Long>()
    private val scheduler = object : ReminderScheduler {
        // As the schedulers of the apps do: with all reminders off no alarm is set.
        override fun schedule(reminderId: String, triggerAtMillis: Long) { if (allOn) alarms[reminderId] = triggerAtMillis }
        override fun cancel(reminderId: String) { alarms.remove(reminderId) }
        override fun canScheduleExact() = true
    }
    private val repo = DbReminders(db)
    private val reminders = ReminderUseCases(repo, DbReminderNotes(db), scheduler, clock = { now }, enabled = { allOn })

    @AfterTest
    fun cleanUp() { db.close(); dir.deleteRecursively() }

    private fun create(inMinutes: Int): Reminder = runBlocking {
        db.upsertFolder(Folder("f", "F", "#3366FF", "SOFT", null, 0, null, 0, FeaturedLayout.COMPACT, now, now))
        db.upsertNote(Note("n", "f", "N", NoteType.TEXT, null, false, false, false, null, now, now, null, null))
        (reminders.create("n", ReminderDraft(triggerAt = now + inMinutes * minute)) as DomainResult.Success).value
    }

    @Test
    fun offTakesEveryAlarmAwayAndNothingFires() = runBlocking {
        val soon = create(10)
        val later = create(60)
        assertEquals(setOf(soon.id, later.id), alarms.keys)

        allOn = false
        reminders.disarmAll()
        assertTrue(alarms.isEmpty())
        // A late alarm, a restart of the app, an import: nothing rings and nothing is armed.
        now += 20 * minute
        assertNull(reminders.onFired(soon.id))
        assertTrue(reminders.restoreAll().isEmpty())
        assertTrue(alarms.isEmpty())
        // The reminders themselves are as they were.
        assertEquals(ReminderStatus.SCHEDULED, repo.getById(soon.id)!!.status)
        assertTrue(repo.getById(soon.id)!!.isEnabled)
    }

    @Test
    fun onArmsWhatIsStillToComeAndRingsWhatWasDueMeanwhile() = runBlocking {
        val soon = create(10)
        val later = create(60)
        allOn = false
        reminders.disarmAll()
        now += 20 * minute

        allOn = true
        val due = reminders.restoreAll()
        assertEquals(listOf(soon.id), due.map { it.id })
        assertEquals(later.triggerAt, alarms[later.id])
    }

    @Test
    fun aReminderMadeWhileAllAreOffIsArmedWhenTheyComeBack() = runBlocking {
        allOn = false
        val made = create(30)
        assertTrue(alarms.isEmpty())
        allOn = true
        assertTrue(reminders.restoreAll().isEmpty())
        assertEquals(made.triggerAt, alarms[made.id])
    }
}
