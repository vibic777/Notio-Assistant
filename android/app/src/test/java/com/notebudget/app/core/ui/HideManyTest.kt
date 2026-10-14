package com.notebudget.app.core.ui

import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The eyes of the "Select" mode of a folder (D364): which picked notes a press changes. */
class HideManyTest {
    private val env = TestEnv()

    @Test
    fun `only picked notes of the kinds that hide and not yet so are changed`() = runTest {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        fun ok(r: DomainResult<com.notebudget.app.domain.model.Note>) = (r as DomainResult.Success).value
        val text = ok(env.notes.create(folder.id, "Text", NoteType.TEXT))
        val list = ok(env.notes.create(folder.id, "List", NoteType.LIST))
        val audio = ok(env.notes.create(folder.id, "Audio", NoteType.AUDIO))
        val other = ok(env.notes.create(folder.id, "Not picked", NoteType.TEXT))
        ok(env.notes.setHidden(list.id, true))
        val notes = listOf(text, env.noteRepo.getById(list.id)!!, audio, other)
        val picked = setOf(text.id, list.id, audio.id)

        assertEquals(listOf(text.id), HideMany.targets(notes, picked, hidden = true))
        assertEquals(listOf(list.id), HideMany.targets(notes, picked, hidden = false))
        assertEquals(emptyList<String>(), HideMany.targets(notes, emptySet(), hidden = true))
    }
}
