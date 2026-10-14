package com.notebudget.app.feature.notes

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    private val env = TestEnv()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun note(type: NoteType = NoteType.TEXT): Note {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "Old", type, contentText = "old text") as DomainResult.Success).value
    }

    private fun vm(id: String) = NoteEditorViewModel(id, env.noteRepo, env.notes, autosaveDelayMillis = 500)

    @Test
    fun `loads the note title and content`() = runTest {
        val n = note()
        val vm = vm(n.id)
        assertTrue(vm.state.value.loaded)
        assertEquals("Old", vm.state.value.title)
        assertEquals("old text", vm.state.value.content)
    }

    @Test
    fun `text typed just before the screen goes away without Back is still saved (D294)`() = runTest {
        val n = note(NoteType.LIST)
        val vm = NoteEditorViewModel(n.id, env.noteRepo, env.notes, autosaveDelayMillis = 500, appScope = this)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("editor", vm)

        vm.editContentAndExtras("- [x] Milk", "{\"structure\":2}") // the autosave waits 500 ms
        store.clear() // the screen is taken away before that: no Back, no saveNow
        testScheduler.advanceUntilIdle()

        val stored = env.noteRepo.getById(n.id)!!
        assertEquals("- [x] Milk", stored.contentText)
        assertEquals("{\"structure\":2}", stored.extras)
    }

    @Test
    fun `a list saves its text and its structure together`() = runTest {
        val n = note(NoteType.LIST)
        val vm = vm(n.id)
        assertNull(vm.state.value.extras)

        vm.editContentAndExtras("- [ ] Milk — 2 L", "{\"structure\":1}")
        vm.saveNow()

        val stored = env.noteRepo.getById(n.id)!!
        assertEquals("- [ ] Milk — 2 L", stored.contentText)
        assertEquals("{\"structure\":1}", stored.extras)
    }

    @Test
    fun `an edit of the text alone never overwrites structure that was saved elsewhere`() = runTest {
        val n = note(NoteType.LIST)
        env.notes.updateExtras(n.id, "E1")
        val vm = vm(n.id)
        assertEquals("E1", vm.state.value.extras)

        env.notes.updateExtras(n.id, "E2") // e.g. an import or another editor changed it meanwhile
        vm.editContent("new text")
        vm.saveNow()

        val stored = env.noteRepo.getById(n.id)!!
        assertEquals("new text", stored.contentText)
        assertEquals("E2", stored.extras)
    }

    @Test
    fun `renaming a list does not disturb its structure`() = runTest {
        val n = note(NoteType.LIST)
        env.notes.updateExtras(n.id, "E1")
        val vm = vm(n.id)

        vm.editTitle("New name")
        vm.saveNow()

        val stored = env.noteRepo.getById(n.id)!!
        assertEquals("New name", stored.title)
        assertEquals("E1", stored.extras)
        assertEquals("old text", stored.contentText)
    }

    @Test
    fun `a missing note loads as null and ignores edits`() = runTest {
        val vm = vm("nope")
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.note)
        vm.editContent("x")
        assertEquals("", vm.state.value.content)
    }

    @Test
    fun `edits are autosaved after the delay only`() = runTest {
        val n = note()
        val vm = vm(n.id)
        vm.editContent("new text")
        assertEquals("old text", env.noteRepo.getById(n.id)?.contentText)
        advanceTimeBy(600)
        assertEquals("new text", env.noteRepo.getById(n.id)?.contentText)
    }

    @Test
    fun `saveNow saves immediately and then runs the callback`() = runTest {
        val n = note()
        val vm = vm(n.id)
        vm.editTitle("  Renamed ")
        var done = false
        vm.saveNow { done = true }
        assertTrue(done)
        assertEquals("Renamed", env.noteRepo.getById(n.id)?.title)
    }

    @Test
    fun `a blank title keeps the old title but still saves the text and shows an error`() = runTest {
        val n = note()
        val vm = vm(n.id)
        vm.editTitle("   ")
        vm.editContent("kept")
        vm.saveNow()
        val saved = env.noteRepo.getById(n.id)!!
        assertEquals("Old", saved.title)
        assertEquals("kept", saved.contentText)
        assertEquals(DomainError.BLANK_TITLE, vm.state.value.error)
    }
}
