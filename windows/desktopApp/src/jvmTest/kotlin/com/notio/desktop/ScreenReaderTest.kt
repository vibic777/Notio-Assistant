package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.runComposeUiTest
import com.notio.shared.data.AppPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.resetMain

/**
 * What a screen reader (Narrator, NVDA) meets on the main screens (Android D274 steps 1 - 3, 7 as far as a test can go): every
 * thing that can be clicked has words — its own or of what it holds —, the cards offer their menu as actions, the screens have
 * headings. A pass with a real screen reader stays to be done by a person.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenReaderTest {
    @org.junit.After
    fun resetMain() {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun SemanticsNode.words(): String {
        val c = config
        return listOfNotNull(
            c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text },
            c.getOrNull(SemanticsProperties.EditableText)?.text,
            c.getOrNull(SemanticsProperties.StateDescription),
        ).joinToString(" ").trim()
    }

    @Test
    fun everyClickableThingHasWordsAndCardsOfferTheirMenu() = runComposeUiTest {
        val home = Files.createTempDirectory("notio-reader").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitUntil(timeoutMillis = 15_000) { loaded }
            setContent { NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState()) }
            waitUntil(timeoutMillis = 10_000) { controller.state.value.notes.isNotEmpty() }
            waitForIdle()

            fun checkScreen(name: String) {
                waitForIdle()
                val silent = onAllNodes(hasClickAction(), useUnmergedTree = false).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f && it.words().isEmpty() }
                assertTrue(silent.isEmpty(), "$name: clickable things without words: " + silent.map { n -> n.boundsInRoot.toString() + " children=" + n.children.map { c -> c.config.toString().take(200) } })
            }

            checkScreen("home")
            // The note cards offer the items of their ⋮ menu as the reader's actions (step 2).
            val withActions = onAllNodes(hasClickAction()).fetchSemanticsNodes().count { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == controller.strings.delete } == true
            }
            assertTrue(withActions >= controller.state.value.notes.size, "cards with the menu as actions: $withActions")

            val budget = controller.state.value.reminderNotes.first { it.type == com.notio.shared.domain.NoteType.BUDGET }
            controller.openNote(budget.id)
            waitUntil(timeoutMillis = 10_000) { controller.state.value.detail?.note?.id == budget.id }
            checkScreen("budget")
            // The title of a screen is a heading (step 3).
            val headings = onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
            assertTrue(headings.isNotEmpty(), "the note screen has no heading")

            controller.back()
            controller.openReminders(null)
            checkScreen("reminders")
            controller.openSettingsSection("appearance")
            checkScreen("settings")
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
