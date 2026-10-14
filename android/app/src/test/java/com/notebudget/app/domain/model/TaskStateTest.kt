package com.notebudget.app.domain.model

import com.notebudget.app.domain.usecase.NoteSearch
import com.notebudget.app.domain.usecase.SearchQuery
import com.notebudget.app.domain.usecase.SearchScope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status and the priority of a task (D337), the sort and the filters by them (D338). */
class TaskStateTest {
    private fun task(id: String, doc: TaskDocument?, type: NoteType = NoteType.TASK, title: String = id) = Note(
        id = id, folderId = "f", title = title, type = type, colorHex = null, isPinned = false, isFavorite = false,
        isTitleBold = false, contentText = null, createdAt = 0, updatedAt = 0, extras = doc?.encode(),
    )

    @Test
    fun `a status is kept, ends the task or not, and an older task is read by its tick`() {
        val paused = TaskDocument(end = 10).with(TaskState.PAUSED)
        assertEquals(TaskState.PAUSED, TaskDocument.decode(paused.encode()).state)
        assertFalse(paused.done)
        assertTrue(paused.overdue(now = 11))
        // Done, Closed and Cancelled end a task: the tick an older Notio knows is set, nothing is overdue.
        for (ended in listOf(TaskState.DONE, TaskState.CLOSED, TaskState.CANCELLED)) {
            val t = paused.with(ended)
            assertTrue(t.done)
            assertFalse(t.overdue(now = 11))
            assertEquals(ended, TaskDocument.decode(t.encode()).state)
        }
        assertFalse(paused.with(TaskState.DONE).with(TaskState.IN_PROGRESS).done)
        // A task made before the statuses, or saved by an older Notio: only the tick.
        assertEquals(TaskState.DONE, TaskDocument.decode("""{"done":true}""").state)
        assertEquals(TaskState.IN_PROGRESS, TaskDocument.decode("""{"comment":"x"}""").state)
        assertEquals(TaskState.IN_PROGRESS, TaskDocument.decode(null).state)
        // A status this Notio does not know is read by the tick too.
        assertEquals(TaskState.DONE, TaskDocument.decode("""{"done":true,"status":"ARCHIVED"}""").state)
        assertEquals(TaskState.CREATED, TaskDocument.NEW.state)
    }

    @Test
    fun `a priority is digits alone, from 0 to 65535`() {
        assertEquals("", TaskDocument.priorityText(""))
        assertEquals("", TaskDocument.priorityText("abc -,."))
        assertEquals("0", TaskDocument.priorityText("0"))
        assertEquals("0", TaskDocument.priorityText("000"))
        assertEquals("7", TaskDocument.priorityText("007"))
        assertEquals("12", TaskDocument.priorityText("1a2"))
        assertEquals("12", TaskDocument.priorityText("-12"))
        assertEquals("12", TaskDocument.priorityText("1.2"))
        assertEquals("65535", TaskDocument.priorityText("65535"))
        assertEquals("65535", TaskDocument.priorityText("65536"))
        assertEquals("65535", TaskDocument.priorityText("99999999999999999999"))
        assertNull(TaskDocument.priorityOf(""))
        assertEquals(0, TaskDocument.priorityOf("0"))
        assertEquals(42, TaskDocument.priorityOf("42"))
        // Kept in the note; a number out of range in a file from elsewhere is brought into it.
        assertEquals(42, TaskDocument.decode(TaskDocument(priority = 42).encode()).priority)
        assertNull(TaskDocument.decode(TaskDocument().encode()).priority)
        assertEquals(TaskDocument.MAX_PRIORITY, TaskDocument.decode("""{"priority":700000}""").priority)
        assertEquals(0, TaskDocument.decode("""{"priority":-3}""").priority)
    }

    @Test
    fun `the tick completes a task and gives its status back, filters, searches and sorts (D358)`() {
        val paused = TaskDocument(priority = 3).with(TaskState.PAUSED)
        assertFalse(paused.ended)
        val ticked = paused.ticked(true)
        assertEquals(TaskState.DONE, ticked.state)
        assertTrue(ticked.ended && ticked.done)
        assertEquals(3, ticked.priority)
        // Kept in the note, so the status comes back after the app was closed too.
        assertEquals(TaskState.PAUSED, TaskDocument.decode(ticked.encode()).ticked(false).state)
        assertNull(ticked.ticked(false).before)
        // The same tick again changes nothing.
        assertEquals(ticked, ticked.ticked(true))
        assertEquals(paused, paused.ticked(false))
        // Ended by its status and ended otherwise after: the status before the first end is the one given back.
        assertEquals(TaskState.PAUSED, paused.with(TaskState.CLOSED).with(TaskState.CANCELLED).ticked(false).state)
        assertTrue(paused.with(TaskState.CANCELLED).ended)
        // A task of an older Notio (the tick alone) has no status to give back: In work.
        assertEquals(TaskState.IN_PROGRESS, TaskDocument.decode("""{"done":true}""").ticked(false).state)
        assertEquals(TaskState.DONE, TaskDocument.decode("""{"comment":"x"}""").ticked(true).state)

        val notes = listOf(
            task("done", TaskDocument().with(TaskState.DONE), title = "call"),
            task("open", TaskDocument().with(TaskState.PAUSED), title = "call back"),
            task("closed", TaskDocument().with(TaskState.CLOSED), title = "old"),
            task("text", null, NoteType.TEXT, title = "call notes"),
        )
        assertEquals(listOf("done", "closed"), notes.filter { TaskFilter.matches(it, emptySet(), true) }.map { it.id })
        assertEquals(listOf("open"), notes.filter { TaskFilter.matches(it, emptySet(), false) }.map { it.id })
        assertEquals(listOf("done"), notes.filter { TaskFilter.matches(it, setOf(TaskState.DONE, TaskState.PAUSED), true) }.map { it.id })
        assertEquals(4, notes.count { TaskFilter.matches(it, emptySet(), null) })
        assertEquals(2, TaskFilter.doneOf(TaskFilter.counts(notes)))
        // The search: the tick alone makes a search, and narrows one by words.
        assertTrue(SearchQuery(taskDone = false).active)
        assertFalse(SearchQuery(scope = SearchScope.FOLDERS, taskDone = false).active)
        assertEquals(listOf("open"), NoteSearch.filterNotes(notes, emptyMap(), SearchQuery(taskDone = false)).map { it.id })
        assertEquals(listOf("done"), NoteSearch.filterNotes(notes, emptyMap(), SearchQuery(text = "call", taskDone = true)).map { it.id })
        // The sort: the open ones first, the completed ones after, what is not a task last both ways.
        fun sorted(descending: Boolean) = Sorting.notes(notes, SortOption(SortKey.DONE, descending)).map { it.id }
        assertEquals(listOf("open", "done", "closed", "text"), sorted(false))
        assertEquals(listOf("done", "closed", "open", "text"), sorted(true))
        assertTrue(SortKey.DONE in SortOption.TASK_KEYS)
        assertEquals(SortOption(SortKey.DONE, false), SortOption.decode("DONE:asc", SortOption.NOTES_DEFAULT))
    }

    @Test
    fun `tasks are sorted by status and by priority, what has none is last both ways`() {
        val notes = listOf(
            task("done", TaskDocument(priority = 5).with(TaskState.DONE)),
            task("created", TaskDocument(priority = 300).with(TaskState.CREATED)),
            task("paused", TaskDocument().with(TaskState.PAUSED)),
            task("text", null, NoteType.TEXT),
        )
        fun sorted(key: SortKey, descending: Boolean) = Sorting.notes(notes, SortOption(key, descending)).map { it.id }
        assertEquals(listOf("created", "paused", "done", "text"), sorted(SortKey.STATUS, false))
        assertEquals(listOf("done", "paused", "created", "text"), sorted(SortKey.STATUS, true))
        assertEquals(listOf("done", "created", "paused", "text"), sorted(SortKey.PRIORITY, false))
        assertEquals(listOf("created", "done", "paused", "text"), sorted(SortKey.PRIORITY, true))
        assertTrue(SortOption.TASK_KEYS.containsAll(SortOption.NOTE_KEYS + listOf(SortKey.STATUS, SortKey.PRIORITY)))
        assertEquals(SortOption(SortKey.PRIORITY, true), SortOption.decode(SortOption(SortKey.PRIORITY, true).encode(), SortOption.NOTES_DEFAULT))
    }

    @Test
    fun `a folder of tasks and the search are narrowed by status`() {
        val notes = listOf(
            task("a", TaskDocument().with(TaskState.PAUSED), title = "call"),
            task("b", TaskDocument().with(TaskState.DONE), title = "call back"),
            task("c", TaskDocument(done = true), title = "old"),
            task("n", null, NoteType.TEXT, title = "call notes"),
        )
        assertEquals(mapOf(TaskState.PAUSED to 1, TaskState.DONE to 2), TaskFilter.counts(notes))
        assertEquals(4, notes.count { TaskFilter.matches(it, emptySet()) })
        assertEquals(listOf("b", "c"), notes.filter { TaskFilter.matches(it, setOf(TaskState.DONE)) }.map { it.id })
        assertEquals(listOf("a", "b", "c"), notes.filter { TaskFilter.matches(it, setOf(TaskState.DONE, TaskState.PAUSED)) }.map { it.id })

        // Chosen statuses are a search by themselves, and narrow one with words.
        val byState = SearchQuery(taskStates = setOf(TaskState.PAUSED))
        assertTrue(byState.active)
        assertEquals(listOf("a"), NoteSearch.filterNotes(notes, emptyMap(), byState).map { it.id })
        val words = SearchQuery(text = "call", taskStates = setOf(TaskState.DONE))
        assertEquals(listOf("b"), NoteSearch.filterNotes(notes, emptyMap(), words).map { it.id })
        assertEquals(listOf("a", "b", "n"), NoteSearch.filterNotes(notes, emptyMap(), SearchQuery(text = "call")).map { it.id })
        // A search of folders is not one because of a status.
        assertFalse(SearchQuery(scope = SearchScope.FOLDERS, taskStates = setOf(TaskState.DONE)).active)
    }
}
