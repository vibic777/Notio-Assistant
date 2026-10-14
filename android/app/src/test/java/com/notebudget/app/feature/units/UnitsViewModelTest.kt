package com.notebudget.app.feature.units

import com.notebudget.app.domain.model.CustomUnit
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.ListDocument
import com.notebudget.app.domain.model.ListEntry
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.TestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class UnitsViewModelTest {
    private val env = TestEnv()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = UnitsViewModel(env.unitRepo, env.units)

    private fun kotlinx.coroutines.test.TestScope.collect(vm: UnitsViewModel): MutableList<UnitsEvent> {
        val events = ArrayList<UnitsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.custom.collect {} }
        return events
    }

    @Test
    fun `adding a unit closes the dialog, lists it and reports it`() = runTest {
        val vm = viewModel()
        val events = collect(vm)
        vm.askAdd()
        assertEquals(UnitsDialog.Add, vm.dialog.value)

        vm.add("packs", listOf("kg"))

        assertNull(vm.dialog.value)
        assertEquals(listOf("packs"), vm.custom.value.map { it.name })
        assertEquals(listOf<UnitsEvent>(UnitsEvent.Added("packs")), events)
    }

    @Test
    fun `a duplicate name keeps the dialog open and says why`() = runTest {
        env.unitRepo.setCustom(listOf(CustomUnit("u1", "Packs")))
        val vm = viewModel()
        val events = collect(vm)
        vm.askAdd()

        vm.add("packs", emptyList())

        assertEquals(UnitsDialog.Add, vm.dialog.value) // still open so the name can be fixed
        assertEquals(listOf<UnitsEvent>(UnitsEvent.Failed(DomainError.DUPLICATE_UNIT_NAME)), events)
        assertEquals(1, vm.custom.value.size)
    }

    @Test
    fun `a built-in name is refused`() = runTest {
        val vm = viewModel()
        val events = collect(vm)
        vm.add("Kilograms", listOf("kg", "Kilograms"))
        assertEquals(listOf<UnitsEvent>(UnitsEvent.Failed(DomainError.DUPLICATE_UNIT_NAME)), events)
    }

    @Test
    fun `deleting asks first and changes nothing until confirmed`() = runTest {
        val unit = CustomUnit("u1", "Packs")
        env.unitRepo.setCustom(listOf(unit))
        val vm = viewModel()
        val events = collect(vm)

        vm.askDelete(unit)
        assertEquals(UnitsDialog.ConfirmDelete(unit), vm.dialog.value)
        assertEquals(1, vm.custom.value.size)

        vm.dismissDialog()
        assertNull(vm.dialog.value)
        assertEquals(1, vm.custom.value.size) // cancelled

        vm.askDelete(unit)
        vm.confirmDelete()
        assertTrue(vm.custom.value.isEmpty())
        assertEquals(listOf<UnitsEvent>(UnitsEvent.Deleted("Packs")), events)
    }

    @Test
    fun `a unit that a list still uses is kept and the reason is reported`() = runTest {
        val unit = CustomUnit("u1", "Packs")
        env.unitRepo.setCustom(listOf(unit))
        val folder = (env.folders.create("F", "#3366FF", "SOFT") as DomainResult.Success).value
        val note = (env.notes.create(folder.id, "L", NoteType.LIST) as DomainResult.Success).value
        val encoded = ListDocument(entries = listOf(ListEntry.newItem().copy(unit = "u1"))).encode({ it }, emptyMap())
        env.notes.save(note.copy(contentText = encoded.contentText, extras = encoded.extras), withExtras = true)

        val vm = viewModel()
        val events = collect(vm)
        vm.askDelete(unit)
        vm.confirmDelete()

        assertEquals(listOf<UnitsEvent>(UnitsEvent.Failed(DomainError.UNIT_IN_USE)), events)
        assertEquals(listOf(unit), vm.custom.value)
    }
}
