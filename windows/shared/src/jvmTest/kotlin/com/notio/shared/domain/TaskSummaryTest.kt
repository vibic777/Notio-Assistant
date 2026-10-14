package com.notio.shared.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** The tasks counted for the notification in the shade (Android D266). */
class TaskSummaryTest {
    private fun note(type: NoteType, task: TaskDocument? = null) = Note(
        id = java.util.UUID.randomUUID().toString(), folderId = "f", title = "t", type = type,
        colorHex = null, isPinned = false, isFavorite = false, isTitleBold = false, contentText = null,
        extras = task?.encode(), createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `no task, no summary`() {
        assertNull(TaskSummary.of(listOf(note(NoteType.TEXT)), 1000))
    }

    @Test
    fun `counts all, open and overdue tasks`() {
        val now = 10_000L
        val notes = listOf(
            note(NoteType.TASK, TaskDocument(done = true, end = 1)),
            note(NoteType.TASK, TaskDocument(end = 5_000)),
            note(NoteType.TASK, TaskDocument(end = 50_000)),
            note(NoteType.TASK, TaskDocument()),
            note(NoteType.TEXT),
        )
        assertEquals(TaskSummary(4, 3, 1), TaskSummary.of(notes, now))
    }
}
