package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.FolderKind
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.TaskDocument
import com.notio.shared.domain.TaskState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.resetMain

/** Folders of tasks and the task kind (Android D239) in the running window: made, shown on the card and in the note, in the calendar. */
@OptIn(ExperimentalTestApi::class)
class TaskUiTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun aTaskIsMadeInAFolderOfTasksAndShown() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-task").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            val res = controller.res
            fun waitForText(t: String) = waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            var made = false
            controller.createFolder("Работа", "#3366FF", "SOFT", "✅", { error(it.toString()) }, FolderKind.TASKS) { made = true }
            waitUntil(timeoutMillis = 10_000) { made && controller.state.value.folders.any { it.folder.kind == FolderKind.TASKS } }
            val folder = controller.state.value.folders.first { it.folder.kind == FolderKind.TASKS }.folder
            var noted = false
            controller.createNote(folder.id, "Сдать отчёт", NoteType.TASK, { error(it.toString()) }) { noted = true }
            waitUntil(timeoutMillis = 10_000) { noted && controller.state.value.notes.any { it.type == NoteType.TASK } }
            // The card: a new task is Created (D117).
            // The status is its icon alone (D122): its word is the hint, said to a screen reader, and stands nowhere as text.
            fun waitForStatus(key: String) = waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasTestTag(TASK_STATE_BUTTON) and hasContentDescription(res.str(key)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForStatus("task_state_created")
            assertEquals(0, onAllNodesWithText(res.str("task_state_created")).fetchSemanticsNodes().size)
            val task = controller.state.value.notes.first { it.type == NoteType.TASK }
            fun stateNow() = TaskDocument.decode(controller.state.value.notes.firstOrNull { it.id == task.id }?.extras).state
            assertEquals(TaskState.CREATED, stateNow())
            // Its status is chosen from the list on the card; the note is not opened by that.
            onNodeWithTag(TASK_STATE_BUTTON, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(TASK_STATE_ITEM + TaskState.PAUSED.name).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(TaskState.entries.size, TaskState.entries.count { onAllNodesWithTag(TASK_STATE_ITEM + it.name).fetchSemanticsNodes().isNotEmpty() })
            onNodeWithTag(TASK_STATE_ITEM + TaskState.PAUSED.name).performClick()
            waitUntil(timeoutMillis = 10_000) { stateNow() == TaskState.PAUSED }
            assertEquals(null, controller.state.value.detail)
            waitForStatus("task_state_paused")
            // The tick "completed" on the card (D138): Done, and back to Paused; the note is not opened by that.
            onNodeWithTag(TASK_DONE_BOX, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { stateNow() == TaskState.DONE }
            waitForStatus("task_state_done")
            // The chips of the filter by it: the completed task is not among the open ones.
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(TASK_DONE_CHIP + false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            // The chip is a box with the number of its tasks; "Not completed" is its hint, not words on it (D139).
            assertEquals(0, onAllNodesWithText(res.str("task_filter_open")).fetchSemanticsNodes().size)
            onNodeWithTag(TASK_DONE_CHIP + false, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.taskDone == false && controller.state.value.notes.isEmpty() }
            onNodeWithTag(TASK_DONE_CHIP + false, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.taskDone == null && controller.state.value.notes.size == 1 }
            onNodeWithTag(TASK_DONE_BOX, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { stateNow() == TaskState.PAUSED }
            assertEquals(null, controller.state.value.detail)
            waitForStatus("task_state_paused")
            // The chip of the filter is the icon of the status with the number of its tasks; the word is its hint.
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(TASK_STATE_CHIP + TaskState.PAUSED.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, onAllNodesWithText(res.str("task_state_paused")).fetchSemanticsNodes().size)
            // The filter above the folder (D118): the chips count its tasks; a status no task has hides them all.
            assertEquals(mapOf(TaskState.PAUSED to 1), controller.state.value.taskCounts)
            controller.setTask(task.id, TaskDocument.decode(controller.state.value.notes.first().extras).copy(priority = 7))
            // The priority is a bare field with its number; "Priority" is its hint, not a caption.
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(TASK_PRIORITY_FIELD) and hasText("7"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, onAllNodesWithText(res.str("task_priority"), substring = true).fetchSemanticsNodes().size)
            controller.toggleTaskFilter(TaskState.DONE)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.isEmpty() }
            controller.toggleTaskFilter(TaskState.PAUSED)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.size == 1 }
            controller.toggleTaskFilter(TaskState.DONE); controller.toggleTaskFilter(TaskState.PAUSED)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.taskFilter.isEmpty() && controller.state.value.notes.size == 1 }
            controller.openNote(task.id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == task.id }
            // A start and an end, saved as the phone keeps them (the task's JSON in the note's extras).
            val start = System.currentTimeMillis()
            controller.saveNoteContent(task.id, "Собрать цифры", TaskDocument.decode(controller.state.value.detail?.note?.extras).copy(start = start, end = start + 86_400_000).encode())
            waitUntil(timeoutMillis = 10_000) { TaskDocument.decode(controller.state.value.detail?.note?.extras).end != null }
            waitForText(res.str("task_description"))
            // The calendar has its bar today.
            waitUntil(timeoutMillis = 10_000) {
                controller.calendar.days.value[java.time.LocalDate.now()].orEmpty().any { it.kind == com.notio.shared.domain.CalendarEventKind.TASK }
            }
            assertEquals(FolderKind.TASKS, controller.state.value.folders.first { it.folder.id == folder.id }.folder.kind)
            // The tab of editing (D136): the fields of the task are drawn — it crashed here, a scroll stood inside a scroll.
            onNode((hasContentDescription("Правка") or hasContentDescription("Edit")) and androidx.compose.ui.test.isSelectable()).performClick()
            waitForText(res.str("task_comment"))
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun theBinOfATaskAsksAndMovesItToTheTrashFromTheCardAndFromView() = runComposeUiTest {
        // D142 (Android D361): the line of a task ends with the bin - on its card and in View, not in Edit.
        val home = Files.createTempDirectory("notio-task-bin").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            val s = controller.strings
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            var made = false
            controller.createFolder("Работа", "#3366FF", "SOFT", "✅", { error(it.toString()) }, FolderKind.TASKS) { made = true }
            waitUntil(timeoutMillis = 10_000) { made && controller.state.value.folders.any { it.folder.kind == FolderKind.TASKS } }
            val folder = controller.state.value.folders.first { it.folder.kind == FolderKind.TASKS }.folder
            fun task(title: String) {
                var noted = false
                controller.createNote(folder.id, title, NoteType.TASK, { error(it.toString()) }) { noted = true }
                waitUntil(timeoutMillis = 10_000) { noted && controller.state.value.notes.any { it.title == title } }
            }
            fun binShown() = onAllNodesWithTag(TASK_DELETE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            fun confirm() {
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(s.deleteNoteTitle).fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText(s.delete) and androidx.compose.ui.test.hasClickAction()).performClick()
            }
            // From the card: asked first, then in the trash.
            task("С карточки")
            waitUntil(timeoutMillis = 10_000) { binShown() }
            onNodeWithTag(TASK_DELETE, useUnmergedTree = true).performClick()
            assertEquals(1, controller.state.value.notes.size)
            confirm()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.isEmpty() && controller.state.value.trash.any { it.title == "С карточки" } }
            // From View: the same, and the window goes back to the folder.
            task("Из просмотра")
            val id = controller.state.value.notes.single().id
            controller.openNote(id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == id && binShown() }
            onNodeWithTag(TASK_DELETE, useUnmergedTree = true).performClick()
            confirm()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == Screen.Home && controller.state.value.trash.any { it.title == "Из просмотра" } }
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
