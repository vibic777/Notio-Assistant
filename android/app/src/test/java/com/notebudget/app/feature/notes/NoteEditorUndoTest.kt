package com.notebudget.app.feature.notes

import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Undo / Redo of the text, list and schedule notes (D197). */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorUndoTest {
    private val env = TestEnv()
    private var clock = 10_000L
    private val steps = MutableStateFlow(3)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun note(type: NoteType = NoteType.TEXT): Note {
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        return (env.notes.create(folder.id, "N", type, contentText = "") as DomainResult.Success).value
    }

    private fun vm(id: String) =
        NoteEditorViewModel(id, env.noteRepo, env.notes, autosaveDelayMillis = 0, undoSteps = steps, now = { clock }, coalesceMillis = 1_000)

    @Test
    fun `quick typing is one step, a pause starts the next`() = runTest {
        val vm = vm(note().id)
        assertFalse(vm.canUndo)
        vm.editContent("H"); clock += 100
        vm.editContent("He"); clock += 100
        vm.editContent("Hello"); clock += 2_000
        vm.editContent("Hello world")
        assertTrue(vm.canUndo)

        vm.undo()
        assertEquals("Hello", vm.state.value.content)
        vm.undo()
        assertEquals("", vm.state.value.content)
        assertFalse(vm.canUndo)

        vm.redo()
        vm.redo()
        assertEquals("Hello world", vm.state.value.content)
        assertFalse(vm.canRedo)
    }

    @Test
    fun `a line moved or deleted in the View is a step of its own, even right after typing (D235)`() = runTest {
        val vm = vm(note().id)
        vm.editContent("- [ ] a\n- [ ] b"); clock += 100
        vm.endStep()
        vm.editContent("- [ ] b\n- [ ] a"); clock += 100
        vm.endStep()
        vm.editContent("- [ ] b\n- [ ] a\nmore")
        vm.undo()
        assertEquals("- [ ] b\n- [ ] a", vm.state.value.content)
        vm.undo()
        assertEquals("- [ ] a\n- [ ] b", vm.state.value.content)
        vm.redo()
        assertEquals("- [ ] b\n- [ ] a", vm.state.value.content)
    }

    @Test
    fun `undo of a list brings the text and the structure back together, and is saved`() = runTest {
        val n = note(NoteType.LIST)
        val vm = vm(n.id)
        vm.editContentAndExtras("- [ ] Milk", "{\"v\":1}"); clock += 5_000
        vm.editContentAndExtras("- [x] Milk", "{\"v\":2}")
        vm.undo()
        assertEquals("- [ ] Milk", vm.state.value.content)
        assertEquals("{\"v\":1}", vm.state.value.extras)
        vm.saveNow()
        assertEquals("{\"v\":1}", env.noteRepo.getById(n.id)!!.extras)
    }

    @Test
    fun `the number of steps follows the setting`() = runTest {
        val vm = vm(note().id)
        (1..6).forEach { vm.editContent("v$it"); clock += 5_000 }
        steps.value = 2
        vm.undo(); vm.undo()
        assertEquals("v4", vm.state.value.content)
        assertFalse(vm.canUndo)
    }

    @Test
    fun `a change right after an undo is a step of its own`() = runTest {
        val vm = vm(note().id)
        vm.editContent("a"); clock += 5_000
        vm.editContent("ab")
        vm.undo()
        vm.editContent("ax") // no pause, but it must still be undoable back to "a"
        assertFalse(vm.canRedo)
        vm.undo()
        assertEquals("a", vm.state.value.content)
    }
}
