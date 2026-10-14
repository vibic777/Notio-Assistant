package com.notebudget.app.feature.home

import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.usecase.FolderDeleteMode
import com.notebudget.app.domain.usecase.TestEnv
import com.notebudget.app.feature.folders.FolderDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
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
class HomeViewModelTest {
    private val env = TestEnv()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = HomeViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes)

    private suspend fun folder(name: String): Folder =
        (env.folders.create(name, "#3366FF", "SOFT") as DomainResult.Success).value

    private suspend fun note(folderId: String, title: String, type: NoteType = NoteType.TEXT): Note =
        (env.notes.create(folderId, title, type) as DomainResult.Success).value

    @Test
    fun `the first folder is selected by default and shows only its notes`() = runTest {
        val a = folder("A")
        val b = folder("B")
        note(a.id, "in A")
        note(b.id, "in B")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals(a.id, vm.state.value.selectedFolderId)
        assertEquals(listOf("in A"), vm.state.value.notes.map { it.title })
        assertEquals(listOf(1, 1), vm.state.value.folders.map { it.noteCount })

        vm.selectFolder(b.id)
        assertEquals(listOf("in B"), vm.state.value.notes.map { it.title })
    }

    @Test
    fun `pinned notes come first and the one pinned last is the top one, then the newest`() = runTest {
        val f = folder("A")
        env.clock.current = 1_000
        val old = note(f.id, "old")
        env.clock.current = 2_000
        val fresh = note(f.id, "fresh")
        env.clock.current = 3_000
        val first = note(f.id, "pinned first")
        val second = note(f.id, "pinned second")
        env.clock.current = 4_000
        env.notes.save(first.copy(isPinned = true))
        env.clock.current = 5_000
        env.notes.save(second.copy(isPinned = true))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals(listOf("pinned second", "pinned first", "fresh", "old"), vm.state.value.notes.map { it.title })
        assertEquals(setOf(old.id, fresh.id), vm.state.value.notes.takeLast(2).map { it.id }.toSet())

        // Unpinning puts a note back among the others.
        env.notes.save(env.noteRepo.getById(second.id)!!.copy(isPinned = false))
        assertEquals("pinned first", vm.state.value.notes.first().title)
        assertTrue(vm.state.value.notes.none { it.isPinned && it.id == second.id })
    }

    @Test
    fun `deleting the selected folder falls back to another folder`() = runTest {
        val a = folder("A")
        val b = folder("B")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.selectFolder(b.id)

        vm.deleteFolder(b.id, FolderDeleteMode.MOVE_NOTES, null)

        assertEquals(a.id, vm.state.value.selectedFolderId)
        assertEquals(1, vm.state.value.folders.size)
    }

    @Test
    fun `creating a folder selects it and a duplicate name reports an error in the dialog`() = runTest {
        folder("Work")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.showDialog(HomeDialog.NewFolder)

        vm.createFolder(FolderDraft(name = "work"))
        assertEquals(com.notebudget.app.domain.model.DomainError.DUPLICATE_FOLDER_NAME, vm.dialogError.value)
        assertEquals(HomeDialog.NewFolder, vm.dialog.value)

        vm.createFolder(FolderDraft(name = "Home"))
        assertNull(vm.dialogError.value)
        assertNull(vm.dialog.value)
        assertEquals("Home", vm.state.value.folders.first { it.folder.id == vm.state.value.selectedFolderId }.folder.name)
    }

    @Test
    fun `deleting the picked notes of a folder trashes only those and undo brings them all back`() = runTest {
        val f = folder("A")
        val other = folder("B")
        val one = note(f.id, "one"); val two = note(f.id, "two"); val three = note(f.id, "three")
        val elsewhere = note(other.id, "elsewhere")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.selectFolder(f.id)
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.deleteNotes(listOf(one.id, three.id))

        assertEquals(listOf("two"), vm.state.value.notes.map { it.title })
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(one.id)?.deletedAt)
        assertNull(env.noteRepo.getByIdIncludingDeleted(elsewhere.id)?.deletedAt)
        assertEquals(listOf(HomeEvent.NotesTrashed(listOf(one.id, three.id))), events)

        vm.undoNotesDelete(listOf(one.id, three.id))
        assertEquals(setOf("one", "two", "three"), vm.state.value.notes.map { it.title }.toSet())
    }

    @Test
    fun `a note with a waiting reminder is marked, and the mark goes when the reminder is cancelled`() = runTest {
        val f = folder("A")
        val with = note(f.id, "with"); val without = note(f.id, "without")
        val vm = HomeViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes, reminders = env.reminderRepo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.notesWithReminder.isEmpty())

        val reminder = (env.reminders.create(with.id, com.notebudget.app.domain.model.ReminderDraft(triggerAt = env.clock.now() + 60_000)) as DomainResult.Success).value

        assertEquals(setOf(with.id), vm.state.value.notesWithReminder)
        assertTrue(without.id !in vm.state.value.notesWithReminder)

        env.reminders.cancel(reminder.id)
        assertTrue(vm.state.value.notesWithReminder.isEmpty())
    }

    @Test
    fun `deleting a note trashes it and undo restores it`() = runTest {
        val f = folder("A")
        val n = note(f.id, "keep me")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.deleteNote(n.id)
        assertEquals(emptyList<Note>(), vm.state.value.notes)
        assertNotNull(env.noteRepo.getByIdIncludingDeleted(n.id)?.deletedAt)

        vm.undoNoteDelete(n.id)
        assertEquals(listOf("keep me"), vm.state.value.notes.map { it.title })
    }

    @Test
    fun `a new note is first of its folder in My order and the screen is told to go to it (D257)`() = runTest {
        val f = folder("A")
        note(f.id, "old one"); note(f.id, "old two")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.selectFolder(f.id)
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.showDialog(HomeDialog.NewNote)

        vm.createNote("New", NoteType.TEXT)

        val made = env.noteRepo.getByFolder(f.id).single { it.title == "New" }
        assertEquals(made.id, (events.single() as HomeEvent.NoteCreated).noteId)
        val byOwnOrder = env.noteRepo.getByFolder(f.id).sortedBy { it.sortOrder }.map { it.title }
        assertEquals("New", byOwnOrder.first())
        assertNull(vm.dialog.value)
    }

    @Test
    fun `a blank note title reports an error and keeps the dialog open`() = runTest {
        folder("A")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.showDialog(HomeDialog.NewNote)

        vm.createNote("  ", NoteType.TEXT)

        assertEquals(com.notebudget.app.domain.model.DomainError.BLANK_TITLE, vm.dialogError.value)
        assertEquals(HomeDialog.NewNote, vm.dialog.value)
    }

    @Test
    fun `a note colour can be set, changed to any RGB colour and cleared, and the dialog closes`() = runTest {
        val f = folder("F")
        val n = note(f.id, "N")
        val vm = viewModel()
        vm.showDialog(HomeDialog.NoteColor(n.id))

        vm.setNoteColor(n, 0xFFD32F2F.toInt())
        assertEquals("#D32F2F", env.noteRepo.getById(n.id)!!.colorHex)
        assertNull(vm.dialog.value)

        vm.setNoteColor(env.noteRepo.getById(n.id)!!, 0xFF123456.toInt())
        assertEquals("#123456", env.noteRepo.getById(n.id)!!.colorHex)

        vm.setNoteColor(env.noteRepo.getById(n.id)!!, null) // "Folder colour"
        assertNull(env.noteRepo.getById(n.id)!!.colorHex)
    }

    // ---- search and tags (D109) ----

    private fun searchViewModel() = HomeViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes, env.tagRepo, env.tags)

    private fun kotlinx.coroutines.test.TestScope.collectState(vm: HomeViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    @Test
    fun `a note search looks in ALL folders, not only the selected one, and closing it restores the folder view`() = runTest {
        val a = folder("A"); val b = folder("B")
        note(a.id, "Rome trip"); note(b.id, "Rome hotel"); note(b.id, "Paris")
        val vm = searchViewModel()
        collectState(vm)
        assertEquals(listOf("Rome trip"), vm.state.value.notes.map { it.title }) // folder A only

        vm.toggleSearch()
        vm.setSearchText("rome")

        assertTrue(vm.state.value.searching)
        assertEquals(setOf("Rome trip", "Rome hotel"), vm.state.value.notes.map { it.title }.toSet())
        assertEquals(2, vm.state.value.folders.size) // notes search does not touch the folder cards

        vm.toggleSearch() // close: what was typed is forgotten
        assertEquals(false, vm.state.value.searching)
        assertEquals("", vm.state.value.query.text)
        assertEquals(listOf("Rome trip"), vm.state.value.notes.map { it.title })
    }

    @Test
    fun `a folder search narrows the folder cards by name and moves the selection to a result`() = runTest {
        folder("Family finances"); val work = folder("Work"); folder("Семья")
        note(work.id, "task")
        val vm = searchViewModel()
        collectState(vm)
        vm.selectFolder(work.id)

        vm.toggleSearch()
        vm.setSearchScope(com.notebudget.app.domain.usecase.SearchScope.FOLDERS)
        vm.setSearchText("семь")

        assertEquals(listOf("Семья"), vm.state.value.folders.map { it.folder.name })
        assertEquals(vm.state.value.folders.single().folder.id, vm.state.value.selectedFolderId)
        vm.setSearchText("fam")
        assertEquals(listOf("Family finances"), vm.state.value.folders.map { it.folder.name })
        vm.setSearchText("zzz")
        assertTrue(vm.state.value.folders.isEmpty())
    }

    @Test
    fun `tags are put on a note, shown, and filter the search - a note must have all the chosen tags`() = runTest {
        val f = folder("F")
        val n1 = note(f.id, "one"); val n2 = note(f.id, "two"); note(f.id, "three")
        val vm = searchViewModel()
        collectState(vm)

        var work: com.notebudget.app.domain.model.Tag? = null
        var home: com.notebudget.app.domain.model.Tag? = null
        vm.createTag("#Work") { work = it }
        vm.createTag("Home") { home = it }
        vm.createTag("work") { assertEquals(work, it) } // typing an existing tag picks it
        vm.setNoteTags(n1.id, setOf(work!!.id, home!!.id))
        vm.setNoteTags(n2.id, setOf(work!!.id))

        assertEquals(listOf("Home", "Work"), vm.state.value.tagsByNote.getValue(n1.id).map { it.name })
        assertEquals(listOf("Home", "Work"), vm.state.value.tags.map { it.name })

        vm.toggleSearch()
        vm.toggleSearchTag(work!!.id)
        assertEquals(setOf("one", "two"), vm.state.value.notes.map { it.title }.toSet())
        vm.toggleSearchTag(home!!.id)
        assertEquals(listOf("one"), vm.state.value.notes.map { it.title }) // both tags
        vm.toggleSearchTag(home!!.id)
        vm.toggleSearchTag(work!!.id)
        assertEquals(false, vm.state.value.searching) // no tag chosen, nothing typed: not active
    }

    // ---- sorting, manual order and the featured note (D121) ----

    private class FakeSort(
        override val folderSort: kotlinx.coroutines.flow.MutableStateFlow<com.notebudget.app.domain.model.SortOption> =
            kotlinx.coroutines.flow.MutableStateFlow(com.notebudget.app.domain.model.SortOption.FOLDERS_DEFAULT),
        override val noteSort: kotlinx.coroutines.flow.MutableStateFlow<com.notebudget.app.domain.model.SortOption> =
            kotlinx.coroutines.flow.MutableStateFlow(com.notebudget.app.domain.model.SortOption.NOTES_DEFAULT),
    ) : com.notebudget.app.domain.model.SortPreferences {
        override suspend fun setFolderSort(option: com.notebudget.app.domain.model.SortOption) { folderSort.value = option }
        override suspend fun setNoteSort(option: com.notebudget.app.domain.model.SortOption) { noteSort.value = option }

        val perFolder = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.notebudget.app.domain.model.SortOption>>(emptyMap())
        override val folderNoteSorts: kotlinx.coroutines.flow.Flow<Map<String, com.notebudget.app.domain.model.SortOption>> get() = perFolder
        override suspend fun setNoteSortFor(folderId: String, option: com.notebudget.app.domain.model.SortOption, applyToAll: Boolean) {
            if (applyToAll) { noteSort.value = option; perFolder.value = emptyMap() } else perFolder.value = perFolder.value + (folderId to option)
        }
    }

    private fun fullViewModel(sort: FakeSort = FakeSort()) =
        HomeViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes, env.tagRepo, env.tags, sort, env.budgetRepo)

    @Test
    fun `the chosen sort orders the notes and the folders`() = runTest {
        val a = folder("Beta"); val b = folder("alpha")
        note(a.id, "zeta"); note(a.id, "Alpha")
        val sort = FakeSort()
        val vm = fullViewModel(sort)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals(listOf("Beta", "alpha"), vm.state.value.folders.map { it.folder.name }) // manual = creation order
        vm.setFolderSort(com.notebudget.app.domain.model.SortOption(com.notebudget.app.domain.model.SortKey.NAME, false))
        assertEquals(listOf("alpha", "Beta"), vm.state.value.folders.map { it.folder.name })

        vm.selectFolder(a.id)
        vm.setNoteSort(com.notebudget.app.domain.model.SortOption(com.notebudget.app.domain.model.SortKey.NAME, false))
        assertEquals(listOf("Alpha", "zeta"), vm.state.value.notes.map { it.title })
        vm.setNoteSort(com.notebudget.app.domain.model.SortOption(com.notebudget.app.domain.model.SortKey.NAME, true))
        assertEquals(listOf("zeta", "Alpha"), vm.state.value.notes.map { it.title })
    }

    @Test
    fun `the sort of the notes belongs to the folder unless it is applied to all folders (D158)`() = runTest {
        val a = folder("A"); val b = folder("B")
        note(a.id, "zeta"); note(a.id, "Alpha"); note(b.id, "yak"); note(b.id, "Beta")
        val sort = FakeSort()
        val vm = fullViewModel(sort)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        val nameAsc = com.notebudget.app.domain.model.SortOption(com.notebudget.app.domain.model.SortKey.NAME, false)
        val nameDesc = com.notebudget.app.domain.model.SortOption(com.notebudget.app.domain.model.SortKey.NAME, true)

        vm.selectFolder(a.id)
        vm.setNoteSort(nameAsc)
        assertEquals(listOf("Alpha", "zeta"), vm.state.value.notes.map { it.title })
        assertEquals(nameAsc, vm.state.value.noteSort)

        vm.selectFolder(b.id)
        assertEquals("the other folder keeps the general sort", com.notebudget.app.domain.model.SortOption.NOTES_DEFAULT, vm.state.value.noteSort)

        vm.setNoteSort(nameDesc, applyToAll = true)
        assertEquals(listOf("yak", "Beta"), vm.state.value.notes.map { it.title })
        vm.selectFolder(a.id)
        assertEquals("applied to all: folder A follows too", listOf("zeta", "Alpha"), vm.state.value.notes.map { it.title })
    }

    @Test
    fun `the app opens on the folder the person was in and remembers each change (D161)`() = runTest {
        val a = folder("A"); val b = folder("B"); val c = folder("C")
        val store = object : com.notebudget.app.domain.model.FolderSelectionStore {
            val last = kotlinx.coroutines.flow.MutableStateFlow<String?>(b.id)
            override val lastFolderId: kotlinx.coroutines.flow.Flow<String?> get() = last
            override suspend fun setLastFolder(folderId: String) { last.value = folderId }
        }
        val vm = HomeViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes, env.tagRepo, env.tags, FakeSort(), env.budgetRepo, store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals("opens on the remembered folder, not the first", b.id, vm.state.value.selectedFolderId)

        vm.selectFolder(c.id)
        assertEquals(c.id, store.last.value)
        assertEquals(c.id, vm.state.value.selectedFolderId)

        store.last.value = "gone"
        val again = HomeViewModel(env.folderRepo, env.noteRepo, env.folders, env.notes, env.tagRepo, env.tags, FakeSort(), env.budgetRepo, store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { again.state.collect {} }
        assertEquals("a folder that is gone falls back to the first", a.id, again.state.value.selectedFolderId)
    }

    @Test
    fun `a folder can be moved earlier and later in the manual order`() = runTest {
        val a = folder("A"); val b = folder("B"); val c = folder("C")
        val vm = fullViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(listOf("A", "B", "C"), vm.state.value.folders.map { it.folder.name })

        vm.moveFolder(c.id, -1)
        assertEquals(listOf("A", "C", "B"), vm.state.value.folders.map { it.folder.name })
        vm.moveFolder(a.id, 1)
        assertEquals(listOf("C", "A", "B"), vm.state.value.folders.map { it.folder.name })
        vm.moveFolder(c.id, -1) // already first: nothing happens
        vm.moveFolder(b.id, 1)  // already last
        assertEquals(listOf("C", "A", "B"), vm.state.value.folders.map { it.folder.name })
    }

    @Test
    fun `a featured budget shows the chosen values on its folder card and follows the budget`() = runTest {
        val f = folder("Money")
        val budget = note(f.id, "Trip", NoteType.BUDGET)
        val root = env.budgetRepo.getNodes(budget.id).single().id
        val leaf = (env.budget.addNode(budget.id, root, "Hotel", com.notebudget.app.domain.model.BudgetNodeKind.LEAF) as DomainResult.Success).value
        env.budget.updateLeaf(budget.id, leaf.id, sumMinor = 10_000, goalMinor = 25_000)
        val vm = fullViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertNull(vm.state.value.folders.single().featured)

        vm.setFeatured(
            f.id, budget.id,
            com.notebudget.app.domain.model.FeaturedMetric.maskOf(com.notebudget.app.domain.model.FeaturedMetric.SUM, com.notebudget.app.domain.model.FeaturedMetric.REMAINDER),
            com.notebudget.app.domain.model.FeaturedLayout.FULL,
        )

        val info = vm.state.value.folders.single().featured!!
        assertEquals("Trip", info.title)
        assertTrue(info.isBudget)
        assertEquals(10_000L, info.sumMinor)
        assertNull("goal was not chosen", info.goalMinor)
        assertEquals(15_000L, info.remainderMinor)

        env.budget.updateLeaf(budget.id, leaf.id, sumMinor = 20_000, goalMinor = 25_000)
        assertEquals(20_000L, vm.state.value.folders.single().featured!!.sumMinor)
    }

    @Test
    fun `a featured text note shows only its title, and deleting the note clears the card`() = runTest {
        val f = folder("F")
        val text = note(f.id, "Plan")
        val vm = fullViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.setFeatured(f.id, text.id, 7, com.notebudget.app.domain.model.FeaturedLayout.COMPACT)
        val info = vm.state.value.folders.single().featured!!
        assertEquals("Plan", info.title)
        assertEquals(false, info.isBudget)
        assertNull(info.sumMinor)

        env.notes.delete(text.id)
        assertNull(vm.state.value.folders.single().featured)
    }

    @Test
    fun `a note of another folder cannot be featured and the dialog shows the reason`() = runTest {
        val a = folder("A"); val b = folder("B")
        val other = note(b.id, "elsewhere")
        val vm = fullViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.showDialog(HomeDialog.Featured(a.id))

        vm.setFeatured(a.id, other.id, 0, com.notebudget.app.domain.model.FeaturedLayout.TITLE_ONLY)

        assertNotNull(vm.dialogError.value)
        assertNotNull("the dialog stays open", vm.dialog.value)
        assertNull(vm.state.value.folders.first { it.folder.id == a.id }.featured)
    }
}
