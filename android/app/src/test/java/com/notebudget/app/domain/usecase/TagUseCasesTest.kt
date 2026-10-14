package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagUseCasesTest {
    private val env = TestEnv()

    private fun failure(r: DomainResult<*>) = (r as DomainResult.Failure).error
    private suspend fun tag(name: String) = (env.tags.create(name) as DomainResult.Success).value

    private suspend fun note(title: String = "N"): Note {
        val folder = env.folderRepo.getAll().firstOrNull() ?: (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, title, NoteType.TEXT) as DomainResult.Success).value
    }

    private suspend fun namesOf(noteId: String) = env.tagRepo.getAssignments()[noteId].orEmpty().map { it.name }

    @Test
    fun `a name is cleaned - hash, edge spaces and repeated spaces go`() = runBlocking {
        assertEquals("Work trip", tag("  #Work    trip ").name)
        assertEquals("Work trip", TagUseCases.clean("#  Work \t trip"))
    }

    @Test
    fun `a blank name or one over 32 characters is refused`() = runBlocking {
        assertEquals(DomainError.INVALID_TAG_NAME, failure(env.tags.create("   ")))
        assertEquals(DomainError.INVALID_TAG_NAME, failure(env.tags.create("#")))
        assertEquals(DomainError.INVALID_TAG_NAME, failure(env.tags.create("x".repeat(33))))
        assertTrue(env.tags.create("x".repeat(32)) is DomainResult.Success)
    }

    @Test
    fun `names are unique ignoring case, also for Cyrillic`() = runBlocking {
        tag("Work")
        tag("Работа")
        assertEquals(DomainError.DUPLICATE_TAG_NAME, failure(env.tags.create("work")))
        assertEquals(DomainError.DUPLICATE_TAG_NAME, failure(env.tags.create("#WORK")))
        assertEquals(DomainError.DUPLICATE_TAG_NAME, failure(env.tags.create("работа")))
        assertEquals(2, env.tagRepo.getAll().size)
    }

    @Test
    fun `typing an existing tag just picks it, a new one is made`() = runBlocking {
        val work = tag("Work")
        assertEquals(work, (env.tags.getOrCreate("work") as DomainResult.Success).value)
        val fresh = (env.tags.getOrCreate("Home") as DomainResult.Success).value
        assertEquals("Home", fresh.name)
        assertEquals(2, env.tagRepo.getAll().size)
    }

    @Test
    fun `renaming keeps uniqueness and reports unknown tags`() = runBlocking {
        val a = tag("A")
        tag("B")
        assertEquals(DomainError.DUPLICATE_TAG_NAME, failure(env.tags.rename(a.id, "b")))
        assertEquals(DomainError.INVALID_TAG_NAME, failure(env.tags.rename(a.id, " ")))
        assertEquals(DomainError.TAG_NOT_FOUND, failure(env.tags.rename("nope", "X")))
        assertEquals("A2", (env.tags.rename(a.id, "A2") as DomainResult.Success).value.name)
        // Changing only the case of its own name is allowed.
        assertEquals("a2", (env.tags.rename(a.id, "a2") as DomainResult.Success).value.name)
    }

    @Test
    fun `a note can have any number of tags and setting replaces them exactly`() = runBlocking {
        val n = note()
        val a = tag("A"); val b = tag("B"); val c = tag("C")

        assertTrue(env.tags.setNoteTags(n.id, setOf(a.id, b.id, c.id)) is DomainResult.Success)
        assertEquals(listOf("A", "B", "C"), namesOf(n.id))

        env.tags.setNoteTags(n.id, setOf(b.id))
        assertEquals(listOf("B"), namesOf(n.id))

        env.tags.setNoteTags(n.id, emptySet())
        assertTrue(namesOf(n.id).isEmpty())
    }

    @Test
    fun `unknown tags and unknown notes are refused and nothing changes`() = runBlocking {
        val n = note()
        val a = tag("A")
        env.tags.setNoteTags(n.id, setOf(a.id))
        assertEquals(DomainError.TAG_NOT_FOUND, failure(env.tags.setNoteTags(n.id, setOf(a.id, "ghost"))))
        assertEquals(DomainError.NOTE_NOT_FOUND, failure(env.tags.setNoteTags("missing", setOf(a.id))))
        assertEquals(listOf("A"), namesOf(n.id))
    }

    @Test
    fun `deleting a tag takes it off every note and keeps the notes`() = runBlocking {
        val n1 = note("one"); val n2 = note("two")
        val a = tag("A"); val b = tag("B")
        env.tags.setNoteTags(n1.id, setOf(a.id, b.id))
        env.tags.setNoteTags(n2.id, setOf(a.id))

        assertTrue(env.tags.delete(a.id) is DomainResult.Success)

        assertEquals(listOf("B"), namesOf(n1.id))
        assertTrue(namesOf(n2.id).isEmpty())
        assertEquals(2, env.noteRepo.getAllIncludingDeleted().size)
        assertEquals(DomainError.TAG_NOT_FOUND, failure(env.tags.delete(a.id)))
    }

    @Test
    fun `import by names makes the missing tags, reuses the existing ones and ignores bad names`() = runBlocking {
        val n = note()
        val existing = tag("Work")

        env.tags.assignByNames(n.id, listOf("work", "New", "  ", "x".repeat(40), "#New"))

        assertEquals(listOf("New", "Work"), namesOf(n.id))
        assertEquals(existing.id, env.tagRepo.getAll().first { it.name == "Work" }.id)
        assertEquals(2, env.tagRepo.getAll().size)
    }

    @Test
    fun `a note that is purged for good loses its tags`() = runBlocking {
        val n = note()
        val a = tag("A")
        env.tags.setNoteTags(n.id, setOf(a.id))
        env.notes.delete(n.id)
        env.trash.purgeNote(n.id)
        assertTrue(env.tagRepo.getAssignments().isEmpty())
        assertEquals(1, env.tagRepo.getAll().size) // the tag itself stays
    }

    @Test
    fun `unused tags can be swept after a restore`() = runBlocking {
        val n = note()
        val used = tag("Used"); tag("Unused")
        env.tags.setNoteTags(n.id, setOf(used.id))
        env.tagRepo.deleteUnused()
        assertEquals(listOf("Used"), env.tagRepo.getAll().map { it.name })
    }
}
