package com.notio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.NoteType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.resetMain

/** Copy (D143, Android D362): the icon of two sheets on every line of a list in the View, on the card and in the note's View. */
@OptIn(ExperimentalTestApi::class)
class CopyIconTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun everyLineOfAListInAMarkdownViewCopiesItsOwnWordsWithoutTheMarker() = runComposeUiTest {
        val copied = ArrayList<String>()
        setContent {
            CompositionLocalProvider(LocalRes provides AndroidRes.forLanguage("en"), LocalCopyText provides { text: String -> copied += text; Unit }) {
                MaterialTheme { MarkdownPreview("# Shop\nplain line\n- **milk**\n1. bread\n- [ ] eggs", {}, null) }
            }
        }
        // A heading and a plain line have none: three lines of lists, three icons.
        val icons = onAllNodesWithTag(COPY_TAG, useUnmergedTree = true)
        icons.assertCountEquals(3)
        icons[0].performClick()
        icons[1].performClick()
        icons[2].performClick()
        assertEquals(listOf("milk", "bread", "eggs"), copied)
    }

    @Test
    fun theViewOfANoteAndItsCardHaveCopy() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-copy").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            var made = false
            controller.createFolder("Дом", "#3366FF", "SOFT", null, { error(it.toString()) }) { made = true }
            waitUntil(timeoutMillis = 10_000) { made && controller.state.value.folders.isNotEmpty() }
            val folder = controller.state.value.folders.first().folder
            var noted = false
            controller.createNote(folder.id, "Покупки", NoteType.TEXT, { error(it.toString()) }) { noted = true }
            waitUntil(timeoutMillis = 10_000) { noted && controller.state.value.notes.isNotEmpty() }
            val id = controller.state.value.notes.single().id
            fun icons() = onAllNodesWithTag(COPY_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
            // The text is typed in the open note; the note is left, so that it is saved and shown anew.
            controller.openNote(id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == id }
            controller.saveNoteText(id, "Shop", "- milk\n- bread")
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.contentText == "- milk\n- bread" }
            controller.backByPerson()
            // The card: one icon.
            waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == Screen.Home && icons() == 1 }
            // The View: the whole text and each of the two lines.
            controller.openNote(id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == id && icons() == 3 }
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
