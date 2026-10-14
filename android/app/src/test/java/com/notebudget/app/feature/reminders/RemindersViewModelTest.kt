package com.notebudget.app.feature.reminders

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.ReminderDraft
import com.notebudget.app.domain.model.ReminderStatus
import com.notebudget.app.domain.usecase.TestEnv
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The reminder screen (D122): what it lists, in which order, and what each action does to the alarms. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemindersViewModelTest {
    private val env = TestEnv()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = RemindersViewModel(env.reminderRepo, env.noteRepo, env.reminders)

    private suspend fun note(title: String): Note {
        val folder = env.folderRepo.getAll().firstOrNull() ?: (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, title, NoteType.TEXT) as DomainResult.Success).value
    }

    private val minute = 60_000L

    @Test
    fun `upcoming reminders come first, soonest first, then finished ones, latest first`() = runTest {
        val a = note("A"); val b = note("B"); val c = note("C")
        val now = env.clock.current
        val soon = (env.reminders.create(a.id, ReminderDraft(triggerAt = now + 10 * minute)) as DomainResult.Success).value
        val later = (env.reminders.create(b.id, ReminderDraft(triggerAt = now + 60 * minute)) as DomainResult.Success).value
        val cancelled = (env.reminders.create(c.id, ReminderDraft(triggerAt = now + 30 * minute)) as DomainResult.Success).value
        env.reminders.cancel(cancelled.id)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }

        assertEquals(listOf(soon.id, later.id, cancelled.id), vm.rows.value.map { it.reminder.id })
        assertEquals(listOf("A", "B", "C"), vm.rows.value.map { it.noteTitle })
        assertEquals(listOf(true, true, false), vm.rows.value.map { it.isActive })
    }

    @Test
    fun `creating puts the alarm on and a time in the past is refused with the reason`() = runTest {
        val n = note("N")
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }
        var done = 0

        vm.create(n.id, ReminderDraft(triggerAt = env.clock.current + 5 * minute)) { done++ }
        assertEquals(1, done)
        assertEquals(1, env.scheduler.alarms.size)
        assertEquals(1, vm.rows.value.size)

        val events = mutableListOf<ReminderEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.create(n.id, ReminderDraft(triggerAt = env.clock.current - minute)) { done++ }
        assertEquals("the dialog must stay open", 1, done)
        assertEquals(listOf<ReminderEvent>(ReminderEvent.Failed(DomainError.REMINDER_IN_PAST)), events)
        assertEquals(1, env.scheduler.alarms.size)
    }

    @Test
    fun `changing a reminder moves its alarm, cancelling and deleting remove it`() = runTest {
        val n = note("N")
        val created = (env.reminders.create(n.id, ReminderDraft(triggerAt = env.clock.current + 5 * minute)) as DomainResult.Success).value
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }

        val newTime = env.clock.current + 90 * minute
        vm.update(created.id, ReminderDraft(triggerAt = newTime, vibrationEnabled = false)) {}
        assertEquals(newTime, env.scheduler.alarms[created.id])
        assertEquals(false, vm.rows.value.single().reminder.vibrationEnabled)

        vm.cancel(created.id)
        assertTrue(env.scheduler.alarms.isEmpty())
        assertEquals(ReminderStatus.CANCELLED, vm.rows.value.single().reminder.status)

        vm.delete(created.id)
        assertTrue(vm.rows.value.isEmpty())
    }

    @Test
    fun `confirming a reminder that waits for it completes it and stops the repeat`() = runTest {
        val n = note("N")
        val created = (env.reminders.create(
            n.id,
            ReminderDraft(triggerAt = env.clock.current + 5 * minute, requiresConfirmation = true, repeatUntilConfirmed = true, repeatIntervalMinutes = 5),
        ) as DomainResult.Success).value
        env.clock.current += 6 * minute
        env.reminders.onFired(created.id)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {} }
        assertEquals(ReminderStatus.AWAITING_CONFIRMATION, vm.rows.value.single().reminder.status)
        assertTrue(vm.rows.value.single().isActive)

        vm.confirm(created.id)

        assertEquals(ReminderStatus.COMPLETED, vm.rows.value.single().reminder.status)
        assertTrue(env.scheduler.alarms.isEmpty())
    }

    @Test
    fun `the reminders of a note in the trash are not listed and the note choices are sorted by title`() = runTest {
        val a = note("banana"); val b = note("Apple"); val gone = note("gone")
        env.reminders.create(gone.id, ReminderDraft(triggerAt = env.clock.current + 5 * minute))
        env.notes.delete(gone.id)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.rows.collect {}; }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notes.collect {} }

        assertTrue(vm.rows.value.isEmpty())
        assertEquals(listOf("Apple", "banana"), vm.notes.value.map { it.title })
        assertEquals(setOf(a.id, b.id), vm.notes.first().map { it.id }.toSet())
    }

    @Test
    fun `a day and a time of the person's zone become the right instant, and the suggestion is a full hour ahead`() {
        val zone = ZoneId.of("Europe/Moscow")
        val millis = ReminderTime.toMillis(LocalDate.of(2026, 9, 21), LocalTime.of(9, 30), zone)
        assertEquals(LocalDateTime.of(2026, 9, 21, 9, 30), ReminderTime.toDateTime(millis, zone))
        assertEquals(java.time.Instant.parse("2026-09-21T06:30:00Z").toEpochMilli(), millis)

        val now = ReminderTime.toMillis(LocalDate.of(2026, 9, 21), LocalTime.of(10, 5), zone)
        assertEquals(LocalDateTime.of(2026, 9, 21, 11, 0), ReminderTime.suggested(now, zone)) // 10:05 + 30 min = 10:35 -> 11:00
        val late = ReminderTime.toMillis(LocalDate.of(2026, 9, 21), LocalTime.of(10, 40), zone)
        assertEquals(LocalDateTime.of(2026, 9, 21, 12, 0), ReminderTime.suggested(late, zone))    // 11:10 -> 12:00
        val midnight = ReminderTime.toMillis(LocalDate.of(2026, 9, 21), LocalTime.of(23, 50), zone)
        assertEquals(LocalDateTime.of(2026, 9, 22, 1, 0), ReminderTime.suggested(midnight, zone))
    }
}
