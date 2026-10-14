package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import com.notio.shared.data.AppPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.resetMain

/**
 * The search inside a note in the running window (Android D238, D246, D247; the phone's `AppFlowTest` cases): Ctrl+F opens it, the
 * counter "1 / 2", F3 and the arrows step through, the case tick, a regular expression and a wrong one told clearly, Esc closes it.
 */
@OptIn(ExperimentalTestApi::class)
class SearchUiTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /** A key press as the window hands it to [NotioController.keyHook] before its own keys. */
    private fun key(k: androidx.compose.ui.input.key.Key, ctrl: Boolean = false, shift: Boolean = false) = KeyPress(k, ctrl, shift)

    @Test
    fun theSearchInsideANoteCountsTheMatchesAndStepsThroughThem() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-search").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            val res = controller.res
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            controller.openNote("demo-meeting")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == "demo-meeting" }
            controller.saveNoteText("demo-meeting", "Recipes", "Green **tea**\n\nMilk\n\n- [ ] more tea")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.contentText?.contains("tea") == true }
            // Opened again, so the View shows the new text (while a note is open the View shows its draft).
            controller.back()
            waitForIdle()
            controller.openNote("demo-meeting")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.contentText?.contains("tea") == true }
            waitForIdle()
            onNodeWithContentDescription(res.str("note_search_open")).assertExists()

            // Ctrl+F opens the search (the phone's magnifier), the words are typed.
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F, ctrl = true)))
            waitForIdle()
            val hint = res.str("note_search_hint")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction() and hasText(hint)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction() and hasText(hint)).performTextInput("TEA")
            fun counter(a: Int, b: Int) = res.str("note_search_count", a, b)
            fun waitForText(t: String) = waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitForText(counter(1, 2))
            // A picture for looking through: the bar, the counter, the marks (build/screenshots/4c-note-search.png).
            val shot = onRoot().captureToImage()
            java.io.File("build/screenshots/4c-note-search.png").apply { parentFile.mkdirs() }.outputStream().use { out ->
                javax.imageio.ImageIO.write(shot.toAwtImage(), "png", out)
            }
            // F3 goes on, Shift+F3 back, the arrows as well.
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F3)))
            waitForText(counter(2, 2))
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F3, shift = true)))
            waitForText(counter(1, 2))
            onNodeWithContentDescription(res.str("note_search_next")).performClick()
            waitForText(counter(2, 2))

            // D246: with the case tick off TEA is not tea.
            onNodeWithText(res.str("search_ignore_case")).performClick()
            waitForText(res.str("note_search_none"))
            onNode(hasSetTextAction() and hasText("TEA")).performTextReplacement("tea")
            waitForText(counter(1, 2))
            // A regular expression, and a wrong one: a note under the field (D247), no crash.
            onNodeWithText(res.str("search_regex")).performClick()
            onNode(hasSetTextAction() and hasText("tea")).performTextReplacement("^Green")
            waitForText(counter(1, 1))
            onNode(hasSetTextAction() and hasText("^Green")).performTextReplacement("*tea*")
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("1", substring = true).fetchSemanticsNodes().isNotEmpty() && controller.state.value.detail != null }
            onNodeWithText(res.str("search_regex")).performClick()

            // Esc closes the search first; the note stays open.
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.Escape)))
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction() and hasText("*tea*")).fetchSemanticsNodes().isEmpty() }
            assertTrue(controller.state.value.screen is Screen.NoteView)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    /** The trash (Android D253) and the calendar (D252) have the same search: Ctrl+F, the counter, the found piece / the found list. */
    @Test
    fun theTrashAndTheCalendarAreSearchedToo() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-search").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            val res = controller.res
            fun waitForText(t: String) = waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }

            // The trash: a note found by a word of its text, the piece shown under its card.
            controller.trashNote("demo-ideas")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.trash.any { it.id == "demo-ideas" } }
            controller.openTrash()
            waitForIdle()
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F, ctrl = true)))
            val trashHint = res.str("trash_search")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction() and hasText(trashHint)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction() and hasText(trashHint)).performTextInput("семена")
            waitForText(res.str("note_search_count", 1, 1))
            waitForText("купить семена")
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.Escape)))
            controller.back()

            // The calendar: the demo reminder found by its note's name in every month, a click on it shows its day.
            controller.openCalendar()
            waitForIdle()
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F, ctrl = true)))
            val calendarHint = res.str("calendar_search")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction() and hasText(calendarHint)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction() and hasText(calendarHint)).performTextInput("встреча")
            waitForText(res.str("calendar_search_found"))
            val reminderDay = java.time.Instant.ofEpochMilli(controller.state.value.reminderRows.first().reminder.triggerAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            waitUntil(timeoutMillis = 10_000) { controller.calendar.selected.value == reminderDay }
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }


    /** The Edit of a drawing (Android D241, D261): the sheet over the whole area, the tool panel over it — a picture for looking through. */
    @Test
    fun theDrawingEditShowsTheToolPanelOverTheSheet() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-draw").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            controller.openNote("demo-drawing")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == "demo-drawing" }
            waitForIdle()
            onNodeWithContentDescription(controller.res.str("md_mode_edit")).performClick() // an icon tab since Android D276
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            val shot = onAllNodes(isRoot()).onFirst().captureToImage() // the window, not the name of the Edit icon shown under the mouse
            java.io.File("build/screenshots/6d-drawing-edit.png").apply { parentFile.mkdirs() }.outputStream().use { out ->
                javax.imageio.ImageIO.write(shot.toAwtImage(), "png", out)
            }
            // Android D281: the panel's own menu puts it on any side; the choice is kept.
            onNodeWithContentDescription(controller.res.str("drawing_tools_side")).performClick()
            waitForIdle()
            onNodeWithText(controller.res.str("drawing_tools_left")).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            assertEquals(ToolPanelSide.LEFT, controller.appSettings.drawingToolsSide.value)
            java.io.File("build/screenshots/6e-drawing-tools-left.png").outputStream().use { out ->
                javax.imageio.ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", out)
            }
            controller.appSettings.setDrawingToolsSide(ToolPanelSide.BOTTOM)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }


    /** The formatted "Note" is searched too (Android D256): the View counts the matches line by line, F3 goes on. */
    @Test
    fun theFormattedNoteIsSearched() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-word").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            val res = controller.res
            fun waitForText(t: String) = waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            controller.openNote("demo-word")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == "demo-word" }
            waitForIdle()
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F, ctrl = true)))
            val hint = res.str("note_search_hint")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction() and hasText(hint)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasSetTextAction() and hasText(hint)).performTextInput("месяц")
            waitForText(res.str("note_search_count", 1, 2))
            assertTrue(controller.keyHook(key(androidx.compose.ui.input.key.Key.F3)))
            waitForText(res.str("note_search_count", 2, 2))
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

}
