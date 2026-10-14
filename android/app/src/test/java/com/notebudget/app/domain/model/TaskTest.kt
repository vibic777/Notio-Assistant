package com.notebudget.app.domain.model

import com.notebudget.app.data.transfer.toDomain
import com.notebudget.app.data.transfer.toRecord
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/** Folders of tasks and the task kind (D239). */
class TaskTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 9) = LocalDateTime.of(y, m, d, h, 0).toInstant(zone).toEpochMilli()

    @Test
    fun `a task keeps its fields, knows when it is overdue and refuses an end before its start`() {
        val t = TaskDocument(comment = "call", start = at(2026, 9, 1), end = at(2026, 9, 3), done = false)
        assertEquals(t, TaskDocument.decode(t.encode()))
        assertTrue(t.overdue(at(2026, 9, 4)))
        assertFalse(t.overdue(at(2026, 9, 2)))
        assertFalse(t.copy(done = true).overdue(at(2026, 9, 4)))
        assertFalse(t.copy(end = at(2026, 8, 30)).valid)
        assertEquals(TaskDocument(), TaskDocument.decode("not json"))
        assertEquals(TaskDocument(), TaskDocument.decode(null))
    }

    @Test
    fun `the calendar shows a task on every day from its start to its end, cut to the month`() {
        val task = ScheduledTask("n", "Trip", at(2026, 9, 29), at(2026, 10, 2), done = false, colorArgb = null)
        val sept = CalendarPlan.tasks(listOf(task), YearMonth.of(2026, 9), zone)
        assertEquals(listOf(29, 30), sept.keys.map { it.dayOfMonth }.sorted())
        val oct = CalendarPlan.tasks(listOf(task), YearMonth.of(2026, 10), zone)
        assertEquals(listOf(1, 2), oct.keys.map { it.dayOfMonth }.sorted())
        assertEquals(CalendarEventKind.TASK, oct.getValue(LocalDate.of(2026, 10, 1)).single().kind)
        // Only a start: that one day.
        val one = CalendarPlan.tasks(listOf(task.copy(end = task.start)), YearMonth.of(2026, 9), zone)
        assertEquals(listOf(29), one.keys.map { it.dayOfMonth })
        // The search of the calendar sees it once.
        assertEquals(1, CalendarPlan.all(emptyList(), emptyMap(), emptyList(), listOf(task)).size)
    }

    @Test
    fun `a folder of tasks is made and kept, also through a backup record`() = runTest {
        val env = TestEnv()
        val folder = (env.folders.create("Work", "#3366FF", "SOFT", kind = FolderKind.TASKS) as DomainResult.Success).value
        assertEquals(FolderKind.TASKS, env.folderRepo.getById(folder.id)!!.kind)
        val record = folder.toRecord()
        assertEquals("TASKS", record.kind)
        assertEquals(FolderKind.TASKS, record.toDomain().kind)
        // An older file without the field: a folder of notes.
        assertEquals(FolderKind.NOTES, record.copy(kind = "").toDomain().kind)
    }
}
