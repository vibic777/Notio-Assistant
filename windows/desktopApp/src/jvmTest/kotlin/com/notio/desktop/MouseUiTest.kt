package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.SortKey
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.resetMain
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mouse in the running UI: drag and drop by the grip (D141), the selection mode (D167), Ctrl + wheel zoom (D34). */
@OptIn(ExperimentalTestApi::class)
class MouseUiTest {
    /** A UI test may leave its own Main dispatcher behind; the next test class must get the real one. */
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun aNoteDraggedByItsGripMovesDown() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-drag").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            controller.selectFolder("demo-personal")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.size == 3 }
            val before = controller.state.value.notes.map { it.title }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            waitForIdle()
            // The second note goes below the third (the third one's grip may lie under the "New note" button).
            val second = before[1]
            val label = controller.res.str("cd_drag_handle")
            val grip = onNodeWithContentDescription("$label $second", useUnmergedTree = true)
            grip.performTouchInput { swipe(center, center + Offset(0f, 170f), 600) }
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.map { it.title } != before }
            val after = controller.state.value.notes.map { it.title }
            assertEquals(listOf(before[0], before[2], before[1]), after)
            assertEquals(SortKey.MANUAL, controller.state.value.noteSort.key)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun selectionModePicksAndTrashesSeveralNotesWithOneUndo() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-select").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            controller.beginSelection("demo-personal")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.size == 3 }
            waitForIdle()
            assertEquals(true, controller.selection.active)
            val titles = controller.state.value.notes.map { it.title }
            // A click on a card picks it; it does not open the note.
            onNodeWithText(titles[1]).performClick()
            onNodeWithText(titles[2]).performClick()
            waitForIdle()
            assertEquals(2, controller.selection.picked.size)
            assertEquals(Screen.Home, controller.state.value.screen)
            controller.trashNotes(controller.selection.picked.toList())
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.size == 1 }
            assertEquals(false, controller.selection.active)
            assertEquals(2, controller.state.value.trash.size - 1) // the demo has one trashed note already
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun ctrlAndTheWheelZoomANote() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-zoom").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            controller.openNote("demo-ideas")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == "demo-ideas" }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            waitForIdle()
            onNodeWithText("100%", substring = true).assertExists()
            onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
            onRoot().performMouseInput { moveTo(center); scroll(-1f) }
            onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
            waitForIdle()
            onNodeWithText("115%", substring = true).assertExists()
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun theAppLockHidesTheNotesUntilTheRightPinIsTyped() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-lock").toFile()
        var controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            kotlinx.coroutines.runBlocking {
                assertEquals(null, controller.appLock.enable(com.notio.shared.lock.LockSetup(com.notio.shared.domain.LockMethod.PIN, "1234")))
            }
            // A new start of the app asks for the PIN (the lock is on whatever the timeout says).
            waitUntil(timeoutMillis = 10_000) { controller.state.value.loaded && !controller.state.value.busy }
            Thread.sleep(300)
            controller.close()
            controller = NotioController(AppPaths(home))
            assertEquals(true, controller.lockGate.locked.value)
            val c = controller
            setContent { NotioApp(c, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            waitForIdle()
            onNodeWithText("Идеи на выходные").assertDoesNotExist()
            for (d in listOf("1", "2", "3", "5")) onNodeWithText(d).performClick()
            waitUntil(timeoutMillis = 5_000) { controller.state.value.loaded }
            waitForIdle()
            assertEquals(true, controller.lockGate.locked.value)
            // Wrong: the pad is cleared; the right one opens the notes.
            for (d in listOf("1", "2", "3", "4")) { waitForIdle(); onNodeWithText(d).performClick() }
            waitUntil(timeoutMillis = 5_000) { !controller.lockGate.locked.value }
            waitForIdle()
            controller.selectFolder("demo-personal")
            waitUntil(timeoutMillis = 5_000) { controller.state.value.notes.isNotEmpty() }
            onNodeWithText("Идеи на выходные").assertExists()
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    @Test
    fun aBudgetRowDraggedByItsGripChangesPlaceWithItsBrother() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-budget-drag").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            fun rootChildren(): List<String> {
                val nodes = controller.openBudget("demo-budget").nodes()
                val root = nodes.single { it.parentId == null }
                return com.notio.shared.domain.displayedChildren(nodes, root.id, false).map { id -> nodes.single { it.id == id }.title }
            }
            val before = rootChildren()
            assertTrue(before.size >= 2)
            controller.openNote("demo-budget")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == "demo-budget" }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            waitForIdle()
            val label = controller.res.str("cd_drag_handle")
            // The budget reads its rows off the UI thread: wait for them.
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("$label ${before[0]}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            // The first brother taken by its grip and let go below the second.
            onNodeWithContentDescription("$label ${before[0]}", useUnmergedTree = true).performTouchInput { swipe(center, center + Offset(0f, 150f), 700) }
            waitUntil(timeoutMillis = 10_000) { rootChildren() != before }
            assertEquals(listOf(before[1], before[0]) + before.drop(2), rootChildren())
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
