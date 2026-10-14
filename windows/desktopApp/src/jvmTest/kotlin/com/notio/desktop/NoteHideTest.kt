package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.resetMain

/** A note that hides its content until the eye is pressed (D144, Android D363): the option in Edit, the cover in View and on the card. */
@OptIn(ExperimentalTestApi::class)
class NoteHideTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun theOptionIsSetInEditAndTheContentIsCoveredInViewAndOnTheCardUntilTheEye() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-hide").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            val res = controller.res
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            var made = false
            controller.createFolder("Home", "#3366FF", "SOFT", null, { error(it.toString()) }) { made = true }
            waitUntil(timeoutMillis = 10_000) { made && controller.state.value.folders.isNotEmpty() }
            val folder = controller.state.value.folders.first().folder
            var noted = false
            controller.createNote(folder.id, "Letter", NoteType.TEXT, { error(it.toString()) }) { noted = true }
            waitUntil(timeoutMillis = 10_000) { noted && controller.state.value.notes.isNotEmpty() }
            val id = controller.state.value.notes.single().id
            controller.openNote(id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == id }
            controller.saveNoteText(id, "Letter", "- the secret word")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.contentText == "- the secret word" }
            controller.back()
            fun shown(text: String) = onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            fun tagged(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            val cover = res.str("note_hidden_text")
            // Not hidden: the card shows the text, has Copy and no eye.
            waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == Screen.Home && shown("the secret word") }
            assertEquals(0, tagged(HIDE_EYE))
            assertEquals(1, tagged(COPY_TAG))

            // View of a note that does not hide: no eye; Edit has the option.
            controller.openNote(id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == id && shown("the secret word") }
            assertEquals(0, tagged(HIDE_EYE))
            assertEquals(0, tagged(HIDE_OPTION))
            onNode((hasContentDescription("Правка") or hasContentDescription("Edit")) and isSelectable()).performClick()
            waitUntil(timeoutMillis = 10_000) { tagged(HIDE_OPTION) == 1 }
            onNodeWithTag(HIDE_OPTION, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.hidden == true }

            // Back in View: the cover instead of the text, no Copy; the eye shows it, and hides it again.
            onNode((hasContentDescription("Просмотр") or hasContentDescription("View") or hasContentDescription(res.str("md_mode_preview"))) and isSelectable()).performClick()
            waitUntil(timeoutMillis = 10_000) { shown(cover) && !shown("the secret word") }
            assertEquals(0, tagged(COPY_TAG))
            assertEquals(1, tagged(HIDE_EYE))
            onNodeWithTag(HIDE_EYE, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { shown("the secret word") && !shown(cover) }
            onNodeWithTag(HIDE_EYE, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { shown(cover) && !shown("the secret word") }

            // The card: the line instead of the preview, the eye, no Copy; the eye shows the preview of this card.
            // (The app's own step back: a second Back of the person within a moment is taken for a double click and ignored.)
            controller.back()
            runCatching { waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == Screen.Home && shown(cover) } }
                .onFailure { error("card: screen " + controller.state.value.screen + ", notes " + controller.state.value.notes.map { it.title + " hidden " + it.hidden } + ", cover " + shown(cover) + ", text " + shown("the secret word") + ", compact " + controller.state.value.display) }
            assertEquals(false, shown("the secret word"))
            assertEquals(0, tagged(COPY_TAG))
            onNodeWithTag(HIDE_EYE, useUnmergedTree = true).performClick()
            waitUntil(timeoutMillis = 10_000) { shown("the secret word") && tagged(COPY_TAG) == 1 }

            // Opened again, the note is covered again: the eye of the last time is not remembered.
            controller.openNote(id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == id && shown(cover) && !shown("the secret word") }
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
