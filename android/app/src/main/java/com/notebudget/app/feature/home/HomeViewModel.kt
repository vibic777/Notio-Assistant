package com.notebudget.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.BudgetNode
import com.notebudget.app.domain.model.FeaturedLayout
import com.notebudget.app.domain.model.FeaturedMetric
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.SortOption
import com.notebudget.app.domain.model.SortPreferences
import com.notebudget.app.domain.model.Sorting
import com.notebudget.app.domain.repository.BudgetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.domain.model.Tag
import com.notebudget.app.domain.repository.TagRepository
import com.notebudget.app.domain.usecase.NoteSearch
import com.notebudget.app.domain.usecase.SearchQuery
import com.notebudget.app.domain.usecase.SearchScope
import com.notebudget.app.domain.usecase.TagUseCases
import kotlinx.coroutines.flow.flowOf
import com.notebudget.app.domain.repository.FolderRepository
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.usecase.FolderDeleteMode
import com.notebudget.app.domain.usecase.FolderUseCases
import com.notebudget.app.domain.usecase.NoteUseCases
import com.notebudget.app.feature.folders.FolderDraft
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What a folder card shows about its featured note (D121, §6.4): the note's title and, for a budget, the chosen
 * metrics of its root (`null` = not shown). [layout] says how much to show.
 */
data class FeaturedInfo(
    val noteId: String,
    val title: String,
    val isBudget: Boolean,
    val layout: FeaturedLayout,
    val sumMinor: Long?,
    val goalMinor: Long?,
    val remainderMinor: Long?,
)

data class FolderItem(val folder: Folder, val noteCount: Int, val featured: FeaturedInfo? = null)

data class HomeUiState(
    val folders: List<FolderItem> = emptyList(),
    /** The folder whose notes are shown; always one of [folders] when that is not empty. */
    val selectedFolderId: String? = null,
    val notes: List<Note> = emptyList(),
    /** Every tag, by name (D109). */
    val tags: List<Tag> = emptyList(),
    /** noteId -> its tags. */
    val tagsByNote: Map<String, List<Tag>> = emptyMap(),
    val query: SearchQuery = SearchQuery(),
    val searchOpen: Boolean = false,
    /** A search is open AND has something to look for: [notes] are then the results from ALL folders. */
    val searching: Boolean = false,
    val folderSort: SortOption = SortOption.FOLDERS_DEFAULT,
    val noteSort: SortOption = SortOption.NOTES_DEFAULT,
    /** The notes of every folder, for the featured-note chooser. */
    val notesByFolder: Map<String, List<Note>> = emptyMap(),
    /** Notes that have a reminder waiting (D169): their cards show a bell. */
    val notesWithReminder: Set<String> = emptySet(),
    /** The folder on show is a folder of tasks; the statuses chosen above it and how many of its tasks have each (D338). */
    val tasksFolder: Boolean = false,
    val taskFilter: Set<com.notebudget.app.domain.model.TaskState> = emptySet(),
    /** The filter above a folder of tasks by the tick (D358): completed, still open, or `null` — both. */
    val taskDone: Boolean? = null,
    val taskCounts: Map<com.notebudget.app.domain.model.TaskState, Int> = emptyMap(),
    /** There is a task at all: the search offers the statuses. */
    val hasTasks: Boolean = false,
)

/** Dialogs live in the ViewModel so that they survive rotation. Only ids are kept. */
sealed interface HomeDialog {
    data object NewFolder : HomeDialog
    data class EditFolder(val folderId: String) : HomeDialog
    data class DeleteFolder(val folderId: String) : HomeDialog
    data object NewNote : HomeDialog
    data class MoveNote(val noteId: String) : HomeDialog
    data class NoteColor(val noteId: String) : HomeDialog
    data class NoteTags(val noteId: String) : HomeDialog
    data class DeleteNote(val noteId: String) : HomeDialog
    data class Featured(val folderId: String) : HomeDialog
    data object Sort : HomeDialog
    data class NoteComment(val noteId: String) : HomeDialog
    data class FolderComment(val folderId: String) : HomeDialog
}

sealed interface HomeEvent {
    data class Failed(val error: DomainError) : HomeEvent
    data class NoteTrashed(val noteId: String) : HomeEvent
    /** A note was cloned (D179); [title] is the name of the clone. */
    data class NoteCloned(val title: String) : HomeEvent
    /** Several notes of a folder were deleted with check marks (D167). */
    data class NotesTrashed(val noteIds: List<String>) : HomeEvent
    /** The content of several picked notes was hidden or no longer hidden (D364); [noteIds] are the ones that changed. */
    data class NotesHidden(val noteIds: List<String>, val hidden: Boolean) : HomeEvent
    data class FolderTrashed(val folderId: String) : HomeEvent
    /** A note was made (D257): the list goes to it and marks it for a moment. */
    data class NoteCreated(val noteId: String) : HomeEvent
}

class HomeViewModel(
    private val folderRepository: FolderRepository,
    noteRepository: NoteRepository,
    private val folderUseCases: FolderUseCases,
    private val noteUseCases: NoteUseCases,
    tagRepository: TagRepository? = null,
    private val tagUseCases: TagUseCases? = null,
    private val sorting: SortPreferences? = null,
    budgets: BudgetRepository? = null,
    /** Remembers the folder the person was in, and opens on it next time (D161). */
    private val selection: com.notebudget.app.domain.model.FolderSelectionStore? = null,
    /** Tells which notes have a reminder waiting, for the bell on their cards (D169). */
    reminders: com.notebudget.app.domain.repository.ReminderRepository? = null,
    /** Makes clones of notes (D179). */
    private val cloner: com.notebudget.app.data.transfer.NoteCloneService? = null,
    /**
     * Where the lists are filtered and sorted (D200). The app passes a background dispatcher: with thousands of notes this work
     * ran on the main thread after every change, the autosave of an open note included. `null` = where the flows run (tests).
     */
    computeDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
) : ViewModel() {

    private val selectedFolderId = MutableStateFlow<String?>(null)

    init {
        // The folder of the last session, if it is still there (the list falls back to the first folder otherwise).
        viewModelScope.launch {
            val last = selection?.lastFolderId?.first()
            if (last != null && selectedFolderId.value == null) selectedFolderId.value = last
        }
    }
    private val query = MutableStateFlow(SearchQuery())
    private val searchOpen = MutableStateFlow(false)
    private val taskFilter = MutableStateFlow(emptySet<com.notebudget.app.domain.model.TaskState>())
    private val taskDone = MutableStateFlow<Boolean?>(null)

    /** The notes that have a reminder still to ring or to be confirmed; nothing more is shown about it. */
    private val remindedNotes: Flow<Set<String>> = reminders?.observeAll()?.map { list ->
        list.filter {
            it.isEnabled && (it.status == com.notebudget.app.domain.model.ReminderStatus.SCHEDULED ||
                it.status == com.notebudget.app.domain.model.ReminderStatus.AWAITING_CONFIRMATION)
        }.map { it.noteId }.toSet()
    } ?: flowOf(emptySet())

    private class Base(val folders: List<Folder>, val counts: Map<String, Int>, val notes: List<Note>, val selected: String?)
    private class TagData(val all: List<Tag>, val byNote: Map<String, List<Tag>>)
    private class Extra(
        val folderSort: SortOption, val noteSort: SortOption, val roots: Map<String, BudgetNode?>,
        val perFolder: Map<String, SortOption>, val reminded: Set<String>,
    )

    /** The root node of every featured budget note, kept up to date (their values show on the folder cards). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun featuredRoots(folders: FolderRepository, budgets: BudgetRepository?): Flow<Map<String, BudgetNode?>> {
        if (budgets == null) return flowOf(emptyMap())
        return folders.observeAll().flatMapLatest { list ->
            val ids = list.mapNotNull { it.featuredNoteId }.distinct()
            if (ids.isEmpty()) flowOf(emptyMap()) else combine(ids.map { id -> budgets.observeRoot(id).map { id to it } }) { it.toMap() }
        }
    }

    private fun featuredOf(folder: Folder, notesById: Map<String, Note>, roots: Map<String, BudgetNode?>): FeaturedInfo? {
        val id = folder.featuredNoteId ?: return null
        val note = notesById[id] ?: return null
        val root = roots[id]?.takeIf { note.type == NoteType.BUDGET }
        return FeaturedInfo(
            noteId = id,
            title = note.title,
            isBudget = root != null,
            layout = folder.featuredLayout,
            sumMinor = root?.takeIf { folder.shows(FeaturedMetric.SUM) }?.cachedSumMinor,
            goalMinor = root?.takeIf { folder.shows(FeaturedMetric.GOAL) }?.cachedGoalMinor,
            remainderMinor = root?.takeIf { folder.shows(FeaturedMetric.REMAINDER) }?.cachedRemainderMinor,
        )
    }

    val state: StateFlow<HomeUiState> = combine(
        combine(
            folderRepository.observeAll(),
            noteRepository.observeCountsByFolder(),
            noteRepository.observeAll(),
            selectedFolderId,
        ) { folders, counts, notes, selected -> Base(folders, counts, notes, selected) },
        combine(
            tagRepository?.observeAll() ?: flowOf(emptyList()),
            tagRepository?.observeAssignments() ?: flowOf(emptyMap()),
        ) { all, byNote -> TagData(all, byNote) },
        combine(query, taskFilter, taskDone) { q, f, d -> Triple(q, f, d) },
        searchOpen,
        combine(
            sorting?.folderSort ?: flowOf(SortOption.FOLDERS_DEFAULT),
            sorting?.noteSort ?: flowOf(SortOption.NOTES_DEFAULT),
            featuredRoots(folderRepository, budgets),
            sorting?.folderNoteSorts ?: flowOf(emptyMap()),
            remindedNotes,
        ) { f, n, roots, per, reminded -> Extra(f, n, roots, per, reminded) },
    ) { base, tags, asked, open, extra ->
        val q = asked.first
        val searching = open && q.active
        // A folder search narrows the folder cards; a note search lists the matching notes of ALL folders.
        val found = if (searching && q.scope == SearchScope.FOLDERS) NoteSearch.filterFolders(base.folders, q) else base.folders
        val folders = Sorting.folders(found, extra.folderSort, base.counts)
        val effective = folders.firstOrNull { it.id == base.selected }?.id ?: folders.firstOrNull()?.id
        // A folder of tasks shows the tasks of the chosen statuses (D338); the chips count all of its tasks.
        val inFolder = base.notes.filter { it.folderId == effective }
        val tasksFolder = folders.firstOrNull { it.id == effective }?.kind == com.notebudget.app.domain.model.FolderKind.TASKS
        val notes = if (searching && q.scope != SearchScope.FOLDERS) {
            NoteSearch.filterNotes(base.notes, tags.byNote, q)
        } else if (tasksFolder) {
            inFolder.filter { com.notebudget.app.domain.model.TaskFilter.matches(it, asked.second, asked.third) }
        } else {
            inFolder
        }
        // Each folder has its own sort of notes when the person set one for it; the others follow the general one (D158).
        val noteSort = effective?.let { extra.perFolder[it] } ?: extra.noteSort
        // Looked up once, not once per folder (D200).
        val notesById = if (base.folders.any { it.featuredNoteId != null }) base.notes.associateBy { it.id } else emptyMap()
        HomeUiState(
            folders = folders.map { FolderItem(it, base.counts[it.id] ?: 0, featuredOf(it, notesById, extra.roots)) },
            selectedFolderId = effective,
            notes = Sorting.notes(notes, noteSort, tags.byNote),
            tags = tags.all,
            tagsByNote = tags.byNote,
            query = q,
            searchOpen = open,
            searching = searching,
            folderSort = extra.folderSort,
            noteSort = noteSort,
            notesByFolder = base.notes.groupBy { it.folderId },
            notesWithReminder = extra.reminded,
            tasksFolder = tasksFolder,
            taskFilter = asked.second,
            taskDone = asked.third,
            taskCounts = if (tasksFolder && !searching) com.notebudget.app.domain.model.TaskFilter.counts(inFolder) else emptyMap(),
            hasTasks = base.notes.any { it.type == NoteType.TASK },
        )
    }.let { flow -> if (computeDispatcher != null) flow.flowOn(computeDispatcher) else flow }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // ---- sorting and featured note (D121) ----

    /** Sets the sort of the notes of the shown folder; with [applyToAll] of every folder (D158). */
    fun setNoteSort(option: SortOption, applyToAll: Boolean = false) {
        val folderId = state.value.selectedFolderId
        viewModelScope.launch {
            if (folderId == null) sorting?.setNoteSort(option) else sorting?.setNoteSortFor(folderId, option, applyToAll)
        }
    }

    fun setFolderSort(option: SortOption) {
        viewModelScope.launch { sorting?.setFolderSort(option) }
    }

    /** Manual order (D54): swaps the folder with its neighbour, [delta] -1 = one place earlier, +1 = later. */
    fun moveFolder(folderId: String, delta: Int) {
        viewModelScope.launch {
            val ordered = Sorting.folders(folderRepository.getAll(), SortOption.FOLDERS_DEFAULT).map { it.id }.toMutableList()
            val from = ordered.indexOf(folderId)
            val to = from + delta
            if (from < 0 || to !in ordered.indices) return@launch
            ordered.add(to, ordered.removeAt(from))
            folderUseCases.reorder(ordered)
        }
    }

    /**
     * The person dragged the folders into a new order (D141): the order is kept and the folder sort becomes "my order", so it
     * stays the way it was dropped.
     */
    fun reorderFolders(orderedIds: List<String>) {
        viewModelScope.launch {
            folderUseCases.reorder(orderedIds)
            sorting?.setFolderSort(SortOption(com.notebudget.app.domain.model.SortKey.MANUAL, descending = false))
        }
    }

    /** The same for the notes of the shown folder. */
    fun reorderNotes(orderedIds: List<String>) {
        viewModelScope.launch {
            noteUseCases.reorder(orderedIds)
            state.value.selectedFolderId.let { id ->
                val manual = SortOption(com.notebudget.app.domain.model.SortKey.MANUAL, descending = false)
                if (id == null) sorting?.setNoteSort(manual) else sorting?.setNoteSortFor(id, manual, applyToAll = false)
            }
        }
    }

    fun toggleFolderPinned(folderId: String) {
        val pinned = state.value.folders.firstOrNull { it.folder.id == folderId }?.folder?.isPinned ?: return
        viewModelScope.launch {
            (folderUseCases.setPinned(folderId, !pinned) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
        }
    }

    fun setFeatured(folderId: String, noteId: String?, metricsMask: Int, layout: FeaturedLayout) = launchDialogAction {
        when (val result = folderUseCases.setFeatured(folderId, noteId, metricsMask, layout)) {
            is DomainResult.Success -> dismissDialog()
            is DomainResult.Failure -> _dialogError.value = result.error
        }
    }

    // ---- search (D109) ----

    /** Opens or closes the search; closing forgets what was typed. */
    fun toggleSearch() {
        if (searchOpen.value) clearSearch() else searchOpen.value = true
    }

    fun clearSearch() {
        searchOpen.value = false
        query.value = SearchQuery()
    }

    fun setSearchScope(scope: SearchScope) {
        query.value = query.value.copy(scope = scope)
    }

    fun setSearchText(text: String) {
        query.value = query.value.copy(text = text)
    }

    fun setSearchIgnoreCase(on: Boolean) {
        query.value = query.value.copy(ignoreCase = on)
    }

    fun setSearchRegex(on: Boolean) {
        query.value = query.value.copy(regex = on)
    }

    fun toggleSearchTag(tagId: String) {
        val tags = query.value.tagIds
        query.value = query.value.copy(tagIds = if (tagId in tags) tags - tagId else tags + tagId)
    }

    /** A status of the search (D338): chosen statuses narrow it to the tasks that have one of them. */
    fun toggleSearchTaskState(state: com.notebudget.app.domain.model.TaskState) {
        val chosen = query.value.taskStates
        query.value = query.value.copy(taskStates = if (state in chosen) chosen - state else chosen + state)
    }

    /** The tick in the search (D358): the completed tasks, the ones still open, or `null` — it asks nothing. */
    fun setSearchTaskDone(done: Boolean?) {
        query.value = query.value.copy(taskDone = done)
    }

    /** The tick in the filter above a folder of tasks (D358). */
    fun setTaskDone(done: Boolean?) {
        taskDone.value = done
    }

    /** A status of the filter above a folder of tasks (D338); none chosen shows every task. */
    fun toggleTaskFilter(state: com.notebudget.app.domain.model.TaskState) {
        taskFilter.value = taskFilter.value.let { if (state in it) it - state else it + state }
    }

    /** The status or the priority of a task changed on its card (D337). */
    fun setTask(noteId: String, task: com.notebudget.app.domain.model.TaskDocument) {
        viewModelScope.launch {
            (noteUseCases.updateExtras(noteId, task.encode()) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
        }
    }

    // ---- tags of a note ----

    /** Gives the note exactly these tags. */
    fun setNoteTags(noteId: String, tagIds: Set<String>) {
        dismissDialog()
        viewModelScope.launch { tagUseCases?.setNoteTags(noteId, tagIds) }
    }

    /** Makes (or picks) a tag by the name typed in the tags dialog; [onDone] gets it. */
    fun createTag(name: String, onDone: (Tag) -> Unit) {
        viewModelScope.launch {
            when (val result = tagUseCases?.getOrCreate(name)) {
                is DomainResult.Success -> onDone(result.value)
                is DomainResult.Failure -> _events.send(HomeEvent.Failed(result.error))
                null -> Unit
            }
        }
    }

    private val _dialog = MutableStateFlow<HomeDialog?>(null)
    val dialog: StateFlow<HomeDialog?> = _dialog.asStateFlow()

    /** Error to show inside the open dialog (duplicate name, blank title, ...). */
    private val _dialogError = MutableStateFlow<DomainError?>(null)
    val dialogError: StateFlow<DomainError?> = _dialogError.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun selectFolder(id: String) {
        selectedFolderId.value = id
        viewModelScope.launch { selection?.setLastFolder(id) }
    }

    fun showDialog(dialog: HomeDialog) {
        _dialogError.value = null
        _dialog.value = dialog
    }

    fun dismissDialog() {
        _dialog.value = null
        _dialogError.value = null
    }

    fun clearDialogError() {
        _dialogError.value = null
    }

    // ---- folders ----

    fun createFolder(draft: FolderDraft) = launchDialogAction {
        when (val result = folderUseCases.create(draft.name, draft.colorHex, draft.styleId, draft.icon, com.notebudget.app.domain.model.FolderKind.of(draft.kind))) {
            is DomainResult.Success -> {
                selectFolder(result.value.id)
                dismissDialog()
            }
            is DomainResult.Failure -> _dialogError.value = result.error
        }
    }

    fun updateFolder(folderId: String, draft: FolderDraft) = launchDialogAction {
        when (val result = folderUseCases.update(folderId, draft.name, draft.colorHex, draft.styleId, draft.icon)) {
            is DomainResult.Success -> dismissDialog()
            is DomainResult.Failure -> _dialogError.value = result.error
        }
    }

    fun deleteFolder(folderId: String, mode: FolderDeleteMode, targetFolderId: String?) = launchDialogAction {
        when (val result = folderUseCases.delete(folderId, mode, targetFolderId)) {
            is DomainResult.Success -> {
                dismissDialog()
                _events.send(HomeEvent.FolderTrashed(folderId))
            }
            is DomainResult.Failure -> {
                dismissDialog()
                _events.send(HomeEvent.Failed(result.error))
            }
        }
    }

    fun undoFolderDelete(folderId: String) = viewModelScope.launch {
        (folderUseCases.restore(folderId) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    // ---- notes ----

    fun createNote(title: String, type: NoteType) = launchDialogAction {
        val folderId = state.value.selectedFolderId ?: return@launchDialogAction
        when (val result = noteUseCases.create(folderId, title, type)) {
            is DomainResult.Success -> {
                dismissDialog()
                _events.send(HomeEvent.NoteCreated(result.value.id))
            }
            is DomainResult.Failure -> _dialogError.value = result.error
        }
    }

    /** The comment of a note or a folder (D177); blank clears it. */
    fun setNoteComment(noteId: String, text: String) = launchDialogAction {
        dismissDialog()
        (noteUseCases.setComment(noteId, text) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    fun setFolderComment(folderId: String, text: String) = launchDialogAction {
        dismissDialog()
        (folderUseCases.setComment(folderId, text) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    /** Clones a note into the same folder (D179); [suffixFormat] is the postfix with `%1$d`, e.g. `_clone%1$d`. */
    fun cloneNote(noteId: String, suffixFormat: String) = viewModelScope.launch {
        val service = cloner ?: return@launch
        when (val result = service.clone(noteId, suffixFormat)) {
            is DomainResult.Success -> _events.send(HomeEvent.NoteCloned(result.value.title))
            is DomainResult.Failure -> _events.send(HomeEvent.Failed(result.error))
        }
    }

    fun togglePinned(note: Note) = saveNote(note.copy(isPinned = !note.isPinned))

    fun toggleBoldTitle(note: Note) = saveNote(note.copy(isTitleBold = !note.isTitleBold))

    /** Sets the colour of a note (D56: `null` = no colour, the note takes its folder colour). */
    fun setNoteColor(note: Note, argb: Int?) {
        dismissDialog()
        saveNote(note.copy(colorHex = argb?.let { com.notebudget.app.core.common.ArgbColor.toHex(it) }))
    }

    fun moveNote(noteId: String, targetFolderId: String) = launchDialogAction {
        when (val result = noteUseCases.move(noteId, targetFolderId)) {
            is DomainResult.Success -> dismissDialog()
            is DomainResult.Failure -> {
                dismissDialog()
                _events.send(HomeEvent.Failed(result.error))
            }
        }
    }

    fun deleteNote(noteId: String) = launchDialogAction {
        dismissDialog()
        when (val result = noteUseCases.delete(noteId)) {
            is DomainResult.Success -> _events.send(HomeEvent.NoteTrashed(noteId))
            is DomainResult.Failure -> _events.send(HomeEvent.Failed(result.error))
        }
    }

    /** The "Select" mode of a folder (D167): the picked notes go to the trash, all of them or none is reported at the end. */
    fun deleteNotes(noteIds: Collection<String>) = launchDialogAction {
        val done = mutableListOf<String>()
        for (id in noteIds) {
            when (val result = noteUseCases.delete(id)) {
                is DomainResult.Success -> done += id
                is DomainResult.Failure -> _events.send(HomeEvent.Failed(result.error))
            }
        }
        if (done.isNotEmpty()) _events.send(HomeEvent.NotesTrashed(done))
    }

    /**
     * The eyes of the "Select" mode (D364): [noteIds] - the picked notes the press changes (`HideMany.targets`) - are set to
     * [hidden]. Only the changed ones are reported, so Undo turns back just those.
     */
    fun setNotesHidden(noteIds: Collection<String>, hidden: Boolean) = launchDialogAction {
        val done = mutableListOf<String>()
        for (id in noteIds) {
            when (val result = noteUseCases.setHidden(id, hidden)) {
                is DomainResult.Success -> done += id
                is DomainResult.Failure -> _events.send(HomeEvent.Failed(result.error))
            }
        }
        if (done.isNotEmpty()) _events.send(HomeEvent.NotesHidden(done, hidden))
    }

    fun undoNotesHidden(noteIds: List<String>, hidden: Boolean) = viewModelScope.launch {
        for (id in noteIds) (noteUseCases.setHidden(id, !hidden) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    fun undoNotesDelete(noteIds: List<String>) = viewModelScope.launch {
        for (id in noteIds) (noteUseCases.restore(id) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    fun undoNoteDelete(noteId: String) = viewModelScope.launch {
        (noteUseCases.restore(noteId) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    private fun saveNote(note: Note) = viewModelScope.launch {
        (noteUseCases.save(note) as? DomainResult.Failure)?.let { _events.send(HomeEvent.Failed(it.error)) }
    }

    private fun launchDialogAction(block: suspend () -> Unit) = viewModelScope.launch { block() }
}
