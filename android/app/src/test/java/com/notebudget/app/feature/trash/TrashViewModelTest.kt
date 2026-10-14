package com.notebudget.app.feature.trash

import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.FolderDeleteMode
import com.notebudget.app.domain.usecase.TestEnv
import com.notebudget.app.domain.usecase.TrashRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val env = TestEnv()
    private val retention = MutableStateFlow(TrashRetention.DAYS_30)
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        TrashViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes, env.trash, retention, env.clock)

    private suspend fun folder(name: String): Folder =
        (env.folders.create(name, "#3366FF", "SOFT") as DomainResult.Success).value

    private suspend fun note(folderId: String, title: String, type: NoteType = NoteType.TEXT): Note =
        (env.notes.create(folderId, title, type) as DomainResult.Success).value

    private fun TrashViewModel.collectIn(scope: kotlinx.coroutines.CoroutineScope, testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler) {
        scope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    }

    @Test
    fun `an empty trash has no rows`() = runTest {
        folder("F")
        val vm = viewModel()
        vm.collectIn(backgroundScope, testScheduler)
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(0, vm.state.value.itemCount)
    }

    @Test
    fun `a trashed note is listed with its folder and days left, newest first`() = runTest {
        val f = folder("Work")
        val older = note(f.id, "older")
        val newer = note(f.id, "newer")
        env.clock.current = 100 * day
        env.notes.delete(older.id)
        env.clock.current = 110 * day
        env.notes.delete(newer.id)
        env.clock.current = 112 * day

        val vm = viewModel()
        vm.collectIn(backgroundScope, testScheduler)

        val rows = vm.state.value.rows.filterIsInstance<TrashRow.NoteRow>()
        assertEquals(listOf("newer", "older"), rows.map { it.note.title })
        assertEquals(listOf("Work", "Work"), rows.map { it.folderName })
        assertEquals(listOf(false, false), rows.map { it.folderInTrash })
        assertEquals(listOf(28, 18), rows.map { it.daysLeft })
        assertEquals(2, vm.state.value.itemCount)
    }

    @Test
    fun `a folder row counts its notes and hides the notes trashed with it`() = runTest {
        val a = folder("A")
        val b = folder("B")
        note(b.id, "in b 1")
        note(b.id, "in b 2")
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)

        val vm = viewModel()
        vm.collectIn(backgroundScope, testScheduler)

        val row = vm.state.value.rows.single() as TrashRow.FolderRow
        assertEquals("B", row.folder.name)
        assertEquals(2, row.noteCount)
        assertEquals(3, vm.state.value.itemCount) // the folder and its two notes
        assertEquals(listOf(a.id), vm.state.value.activeFolders.map { it.id })
    }

    @Test
    fun `a note trashed before its folder is listed on its own and flagged`() = runTest {
        folder("A")
        val b = folder("B")
        val early = note(b.id, "early")
        env.clock.current += day
        env.notes.delete(early.id)
        env.clock.current += day
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)

        val vm = viewModel()
        vm.collectIn(backgroundScope, testScheduler)

        val notes = vm.state.value.rows.filterIsInstance<TrashRow.NoteRow>()
        assertEquals("early", notes.single().note.title)
        assertTrue(notes.single().folderInTrash)
        assertEquals("B", notes.single().folderName)
        assertEquals(1, vm.state.value.rows.filterIsInstance<TrashRow.FolderRow>().size)
    }

    @Test
    fun `retention never shows no countdown and changing it updates the rows`() = runTest {
        val f = folder("F")
        val n = note(f.id, "n")
        env.notes.delete(n.id)
        val vm = viewModel()
        vm.collectIn(backgroundScope, testScheduler)
        assertNotNull((vm.state.value.rows.single() as TrashRow.NoteRow).daysLeft)

        retention.value = TrashRetention.NEVER
        assertNull((vm.state.value.rows.single() as TrashRow.NoteRow).daysLeft)
        assertEquals(TrashRetention.NEVER, vm.state.value.retentionDays)
    }

    // ---- restore ----

    @Test
    fun `restoring a note puts it back and reports its title`() = runTest {
        val f = folder("F")
        val n = note(f.id, "Milk")
        env.notes.delete(n.id)
        val vm = viewModel()
        val events = ArrayList<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }

        vm.restoreNote(n.id)

        assertEquals(listOf<TrashEvent>(TrashEvent.Restored("Milk")), events)
        assertNotNull(env.noteRepo.getById(n.id))
    }

    @Test
    fun `restoring a note whose folder is trashed asks for a folder, then restores it there`() = runTest {
        val a = folder("A")
        val b = folder("B")
        val n = note(b.id, "orphan")
        env.notes.delete(n.id)
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)
        val vm = viewModel()
        val events = ArrayList<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }

        vm.restoreNote(n.id)
        assertEquals(TrashDialog.PickFolder(n.id), vm.dialog.value)
        assertTrue(events.isEmpty())

        vm.restoreNoteInto(n.id, a.id)
        assertNull(vm.dialog.value)
        assertEquals(listOf<TrashEvent>(TrashEvent.Restored("orphan")), events)
        assertEquals(a.id, env.noteRepo.getById(n.id)!!.folderId)
    }

    @Test
    fun `restoring a folder brings its notes back`() = runTest {
        folder("A")
        val b = folder("B")
        val n = note(b.id, "inside")
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)
        val vm = viewModel()
        val events = ArrayList<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }

        vm.restoreFolder(b.id)

        assertEquals(listOf<TrashEvent>(TrashEvent.Restored("B")), events)
        assertNotNull(env.folderRepo.getById(b.id))
        assertNotNull(env.noteRepo.getById(n.id))
    }

    @Test
    fun `restoring a folder whose name is taken again is refused with a folder-specific failure`() = runTest {
        folder("A")
        val b = folder("Shared")
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)
        folder("Shared") // the name is used again
        val vm = viewModel()
        val events = ArrayList<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }

        vm.restoreFolder(b.id)

        assertEquals(listOf<TrashEvent>(TrashEvent.Failed(DomainError.DUPLICATE_FOLDER_NAME, restoringFolder = true)), events)
        assertNotNull(env.folderRepo.getTrashed().find { it.id == b.id })
    }

    // ---- permanent deletion needs a confirmation ----

    @Test
    fun `deleting forever asks first and changes nothing until confirmed`() = runTest {
        val f = folder("F")
        val n = note(f.id, "Secret")
        env.notes.delete(n.id)
        val vm = viewModel()
        val events = ArrayList<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }

        vm.askPurgeNote(env.noteRepo.getByIdIncludingDeleted(n.id)!!)
        assertEquals(TrashDialog.PurgeNote(n.id, "Secret"), vm.dialog.value)
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(n.id))

        vm.dismissDialog()
        assertNull(vm.dialog.value)
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(n.id)) // cancelled: still there

        vm.askPurgeNote(env.noteRepo.getByIdIncludingDeleted(n.id)!!)
        vm.confirmDialog()
        assertNull(env.noteRepo.getByIdIncludingDeleted(n.id))
        assertEquals(listOf<TrashEvent>(TrashEvent.Purged("Secret")), events)
        assertEquals(listOf(n.id), env.cleaned.map { it.id })
    }

    @Test
    fun `deleting a folder forever names its note count in the confirmation`() = runTest {
        folder("A")
        val b = folder("B")
        note(b.id, "x")
        note(b.id, "y")
        env.folders.delete(b.id, FolderDeleteMode.DELETE_NOTES)
        val vm = viewModel()
        vm.collectIn(backgroundScope, testScheduler)

        vm.askPurgeFolder(vm.state.value.rows.single() as TrashRow.FolderRow)
        assertEquals(TrashDialog.PurgeFolder(b.id, "B", 2), vm.dialog.value)
        vm.confirmDialog()
        assertNull(env.folderRepo.getByIdIncludingDeleted(b.id))
        assertTrue(env.noteRepo.getTrashed().isEmpty())
    }

    @Test
    fun `emptying the trash needs a confirmation and is ignored when there is nothing to empty`() = runTest {
        val f = folder("F")
        val vm = viewModel()
        val events = ArrayList<TrashEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        vm.collectIn(backgroundScope, testScheduler)

        vm.askEmptyTrash()
        assertNull(vm.dialog.value) // empty: nothing to ask

        val a = note(f.id, "a")
        val b = note(f.id, "b")
        env.notes.delete(a.id)
        env.notes.delete(b.id)
        vm.askEmptyTrash()
        assertEquals(TrashDialog.EmptyTrash(2), vm.dialog.value)
        assertEquals(2, env.noteRepo.getTrashed().size)

        vm.confirmDialog()
        assertTrue(env.noteRepo.getTrashed().isEmpty())
        assertEquals(listOf<TrashEvent>(TrashEvent.Emptied), events)
    }
}
