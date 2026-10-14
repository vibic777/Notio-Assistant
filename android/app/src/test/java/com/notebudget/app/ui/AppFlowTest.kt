package com.notebudget.app.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.notebudget.app.MainActivity
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.notebudget.app.feature.home.NEW_NOTE_FAB_TAG
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end UI flows on the JVM (AGENT_EN.md §15.2). They start the real [MainActivity] with the
 * real Room database, so they also exercise the Room repositories. English strings are used
 * (Robolectric default locale). This is NOT a substitute for running the app on a device.
 *
 * Known limitation: a dialog that contains a text field never lets Compose become idle under
 * Robolectric (verified with a minimal probe: a plain AlertDialog is fine, one with an
 * OutlinedTextField hangs). So typing into the "new note" / "new folder" dialogs is not covered
 * here; that logic is covered by HomeViewModelTest, and the dialogs need a device/emulator.
 * Test data is therefore created through the real use cases instead of the dialogs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {

    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule<MainActivity>()

    /**
     * A focused text field blinks its cursor forever. Inside a dialog window that keeps Compose
     * from ever being "idle" under Robolectric, so automatic clock advancing is turned off and
     * the clock is moved by hand while waiting.
     */
    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis) {
            rule.mainClock.advanceTimeBy(100)
            condition()
        }
    }

    @Before
    fun disableAutoAdvance() {
        rule.mainClock.autoAdvance = false
    }

    /** Icons (the View / Edit tabs of D276, the icon buttons of D278) are found by the name TalkBack reads. */
    private fun waitForTab(name: String, timeoutMillis: Long = 10_000) {
        try {
            waitUntil(timeoutMillis) { rule.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Tab \"$name\" never appeared. Screen:\n" + rule.onRoot().printToString(), e)
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        try {
            waitUntil(timeoutMillis) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Show what was on screen instead of just "condition not satisfied".
            throw AssertionError("Text \"$text\" never appeared. Screen:\n" + rule.onRoot().printToString(), e)
        }
    }

    private fun waitForNoText(text: String, timeoutMillis: Long = 10_000) =
        waitUntil(timeoutMillis) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun textField(label: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasText(label))

    /** Settings is a menu of pages (D145): opens the main-screen Settings and then the page called [title]. */
    private fun openSettingsPage(title: String) {
        rule.onNodeWithContentDescription("Settings").performClick()
        waitForText(title)
        // The last lines of the menu are below the fold since it has the Reminders and the Rights (D349, D354); a scroll would
        // never finish while the clock is manual (see waitUntil), so the click is asked for directly.
        rule.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForNewNoteButton() {
        try {
            waitUntil(10_000) { rule.onAllNodesWithTag(NEW_NOTE_FAB_TAG).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Main screen never appeared. Screen:\n" + rule.onRoot().printToString(), e)
        }
    }

    private fun createFirstFolder() {
        waitForText("Create your first folder")
        rule.onNodeWithText("Create folder").performScrollTo().performClick()
        waitForNewNoteButton()
    }

    // ---- test data goes through the real use cases and the real Room database ----

    private val container get() = (rule.activity.application as NoteBudgetApplication).container

    private fun seedFolder(name: String): Folder = runBlocking {
        (container.folderUseCases.create(name, "#3366FF", "SOFT") as DomainResult.Success).value
    }

    private fun seedNote(folderId: String, title: String): Note = runBlocking {
        (container.noteUseCases.create(folderId, title, NoteType.TEXT) as DomainResult.Success).value
    }

    private fun onlyFolderId(): String = runBlocking { container.folderRepository.getAll().single().id }

    @Test
    fun firstLaunchShowsOnboardingWithDefaultNameThenTheMainScreen() {
        waitForText("Create your first folder")
        rule.onNode(hasSetTextAction() and hasText("My Notes")).assertExists()

        rule.onNodeWithText("Create folder").performScrollTo().performClick()

        waitForNewNoteButton()
        waitForText("0 notes")
        rule.onNodeWithText("No notes in this folder yet").assertExists()
    }

    @Test
    fun aNoteCreatedInTheDatabaseAppearsAndTheFolderCountUpdates() {
        createFirstFolder()

        seedNote(onlyFolderId(), "Buy milk")

        waitForText("Buy milk")
        waitForText("1 note")
    }

    /** A second tap on the back arrow while the note is leaving used to pop the main screen too: an empty grey window. */
    @Test
    fun aDoubleTapOnBackInANoteLeavesTheMainScreenShown() {
        createFirstFolder()
        seedNote(onlyFolderId(), "Groceries")
        waitForText("Groceries")
        rule.onNodeWithText("Groceries").performClick()
        waitForTab("Back")
        rule.onAllNodesWithContentDescription("Back")[0].performClick()
        // The clock stands still: the note is saved and popped, the main screen comes in, the note is still drawn leaving.
        val deadline = System.currentTimeMillis() + 10_000
        while (rule.onAllNodesWithTag(NEW_NOTE_FAB_TAG).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20); rule.waitForIdle()
        }
        // A second tap in the same corner hits the leaving note's arrow; its quick save ends while the note is still leaving.
        rule.onAllNodesWithContentDescription("Back")[0].performClick()
        repeat(25) { Thread.sleep(20); rule.waitForIdle() }
        // Both saves end and their backs run; the main screen must still be there afterwards.
        repeat(20) { rule.mainClock.advanceTimeBy(100); Thread.sleep(25); rule.waitForIdle() }
        assertTrue(rule.onAllNodesWithTag(NEW_NOTE_FAB_TAG).fetchSemanticsNodes().isNotEmpty())
        waitForText("Groceries")
    }

    @Test
    fun theLastFolderCannotBeDeleted() {
        createFirstFolder()

        rule.onAllNodesWithContentDescription("More options")[0].performClick()

        waitForText("Delete")
        rule.onNodeWithText("Delete").assertIsNotEnabled()
    }

    @Test
    fun deletingANoteAsksForConfirmationAndCanBeUndone() {
        createFirstFolder()
        val note = seedNote(onlyFolderId(), "Buy milk")
        waitForText("Buy milk")

        // [0] is the folder card menu, [1] the note menu.
        rule.onAllNodesWithContentDescription("More options")[1].performClick()
        waitForText("Delete")
        rule.onNodeWithText("Delete").performClick()

        waitForText("Delete note?")
        // Wait for the popup menu to be gone so that "Delete" is only the dialog button.
        waitUntil(5_000) { rule.onAllNodesWithText("Delete").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText("Delete").performClick()

        waitForText("Moved to the trash. Restore it from the bin icon at the top.")
        waitForNoText("Buy milk")
        // Soft delete (D81): the row is still in the database, marked as deleted.
        assertNotNull(runBlocking { container.noteRepository.getByIdIncludingDeleted(note.id) }?.deletedAt)

        rule.onNodeWithText("Undo").performClick()
        waitForText("Buy milk")
        assertNull(runBlocking { container.noteRepository.getByIdIncludingDeleted(note.id) }?.deletedAt)
    }

    @Test
    fun tappingABudgetNoteOpensTheTreeWithTotalsAndBackReturns() {
        createFirstFolder()
        val note = runBlocking {
            (container.noteUseCases.create(onlyFolderId(), "Trip", NoteType.BUDGET) as DomainResult.Success).value
        }
        runBlocking {
            val root = container.budgetRepository.getNodes(note.id).single()
            val leaf = (container.budgetEditor.addNode(note.id, root.id, "Hotel", BudgetNodeKind.LEAF)
                as DomainResult.Success).value
            container.budgetEditor.updateLeaf(note.id, leaf.id, sumMinor = 10_050, goalMinor = 20_000)
        }
        waitForText("Trip")

        rule.onNodeWithText("Trip").performClick()

        waitForText("Total")
        waitForText("Hotel")
        // The leaf and the root show the same figures: one leaf, so the total equals it.
        waitUntil(5_000) { rule.onAllNodesWithText("Sum 100.50").fetchSemanticsNodes().size == 2 && rule.onAllNodesWithText("Goal 200.00").fetchSemanticsNodes().size == 2 }
        waitUntil(5_000) { rule.onAllNodesWithText("Remainder 99.50").fetchSemanticsNodes().size == 2 }

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForNewNoteButton()
    }

    @Test
    fun editingATextNoteSavesOnBack() {
        createFirstFolder()
        val note = runBlocking {
            (container.noteUseCases.create(onlyFolderId(), "Ideas", NoteType.TEXT, contentText = "Hello")
                as DomainResult.Success).value
        }
        waitForText("Ideas")

        rule.onNodeWithText("Ideas").performClick()
        // A note that already has text opens in the rendered view (D99): no field to type into yet.
        waitForTab("Edit")
        // (the title field of the top bar is always editable; the BODY must not be)
        assertEquals(0, rule.onAllNodes(hasSetTextAction() and hasText("Hello")).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Edit").performClick()
        // "Hello" is also the preview on the note card, so wait for the editable field itself.
        waitUntil(10_000) {
            rule.onAllNodes(hasSetTextAction() and hasText("Hello")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and hasText("Hello")).performTextReplacement("Hello world")
        // The field must have taken the new text before "back" is pressed, as it always has for a person typing.
        waitUntil(10_000) {
            rule.onAllNodes(hasSetTextAction() and hasText("Hello world")).fetchSemanticsNodes().isNotEmpty()
        }

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForNewNoteButton()
        // The save is asynchronous (it reads the stored note first), and the main screen is already there under the
        // closing editor: wait for the database instead of reading it at once.
        waitUntil(10_000) { runBlocking { container.noteRepository.getById(note.id) }?.contentText == "Hello world" }
        assertEquals("Hello world", runBlocking { container.noteRepository.getById(note.id) }?.contentText)
    }

    @Test
    fun settingsAndHelpAreReachableFromTheMainScreen() {
        createFirstFolder()

        openSettingsPage("Language and formats")
        waitForText("Language")
        waitForText("Date and time format")

        // Back from a page goes to the menu of settings, and Back from the menu to the main screen.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Backup and restore")
        rule.onNodeWithText("Backup and restore").performClick()
        waitForText("Export all data")

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Help and about")
        rule.onNodeWithText("Help and about").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Open help and glossary")
        // Below the fold; performScrollTo would animate a scroll that never finishes while the clock is
        // manual (see waitUntil), so invoke the click action directly.
        rule.onNodeWithText("Open help and glossary").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Getting started")
        waitForText("Glossary")

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Open help and glossary")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Help and about")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForNewNoteButton()
    }

    @Test
    fun anAudioNoteOpensWithTheRecorder() {
        createFirstFolder()
        runBlocking {
            container.noteUseCases.create(onlyFolderId(), "Voice memo", NoteType.AUDIO)
        }
        waitForText("Voice memo")

        rule.onNodeWithText("Voice memo").performClick()

        waitForText("No recording yet")
        rule.onNodeWithText("Record").assertExists()
        // The search inside the note reads the names and comments of the recordings (D248).
        rule.onNodeWithContentDescription("Find in the note").assertExists()
        // D251: the limit of a recording is said under the button.
        rule.onNodeWithText("Stops by itself after 4 min (Settings → Recordings)").assertExists()
    }

    @Test
    fun aNoteOfAnUnknownKindOpensWithAnExplanationInsteadOfCrashing() {
        createFirstFolder()
        runBlocking {
            val note = (container.noteUseCases.create(onlyFolderId(), "From the future", NoteType.TEXT) as DomainResult.Success).value
            container.noteRepository.update(note.copy(type = NoteType("MINDMAP")))
        }
        waitForText("From the future")

        rule.onNodeWithText("From the future").performClick()

        waitForText("This note type (MINDMAP) is not supported by this version of the app. Your data is kept and will work after an update.")
    }

    @Test
    fun deletingAFolderMovesItsNotesToTheChosenFolder() {
        createFirstFolder()
        val myNotes = onlyFolderId()
        seedFolder("Work")
        seedNote(myNotes, "Note in My Notes")
        waitForText("Note in My Notes")

        // Folder cards are ordered by creation: [0] = My Notes (selected).
        rule.onAllNodesWithContentDescription("More options")[0].performClick()
        waitForText("Delete")
        rule.onNodeWithText("Delete").performClick()

        waitForText("Move notes to another folder")
        waitUntil(5_000) { rule.onAllNodesWithText("Delete").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText("Delete").performClick()

        waitForText("Moved to the trash. Restore it from the bin icon at the top.")
        // "Work" is selected after the fallback and now holds the moved note.
        waitForText("Note in My Notes")
        waitForNoText("My Notes")
    }

    @Test
    fun theCalendarShowsTodaysReminderAndOpensItsNote() {
        createFirstFolder()
        val note = seedNote(onlyFolderId(), "Dentist")
        runBlocking {
            // The time from java.time, as the calendar reads "today": Robolectric moves System.currentTimeMillis() on its own during a
            // full run, and a reminder put by that clock landed on another day than the one the calendar chose.
            container.reminderUseCases.create(
                note.id,
                com.notebudget.app.domain.model.ReminderDraft(
                    triggerAt = java.time.LocalDate.now().atTime(23, 58).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                ),
            )
        }
        waitForText("Dentist")

        // The calendar button stands in the row under the name of the app (D236).
        rule.onNodeWithContentDescription("Calendar").performClick()
        waitForText("Today")
        // Today is chosen: its reminder is listed under the month; a tap opens the note. (While the screens change, the card of
        // the main screen is still there for a moment.)
        // Wait until the main screen (its "Calendar" button) is gone too, or the tap can land on its leaving card, which lies over a
        // day of the month.
        waitUntil(10_000) {
            rule.onAllNodesWithContentDescription("Calendar").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("Dentist").fetchSemanticsNodes().size == 1
        }
        rule.waitForIdle()
        rule.onNodeWithText("Dentist").performClick()
        // Opening the note is a screen change with its animation: in a full run of the tests it can take longer.
        waitForTab("Edit", timeoutMillis = 30_000)
    }

    @Test
    fun theSearchOfTheCalendarGoesToTheMonthOfWhatItFinds() {
        createFirstFolder()
        val note = seedNote(onlyFolderId(), "Dentist")
        val day = java.time.LocalDate.now().plusMonths(3).withDayOfMonth(10)
        runBlocking {
            container.reminderUseCases.create(
                note.id,
                com.notebudget.app.domain.model.ReminderDraft(triggerAt = day.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
            )
        }
        waitForText("Dentist")
        rule.onNodeWithContentDescription("Calendar").performClick()
        waitForText("Today")

        // The magnifier of the calendar (D252): the reminder three months ahead is found, and its month is shown.
        rule.onNodeWithContentDescription("Find in the calendar").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("dent")
        waitForText("1 / 1")
        waitForText("Found in the calendar")
        val month = java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.ENGLISH).format(day)
        waitForText(month.replaceFirstChar { it.titlecase(java.util.Locale.ENGLISH) })
    }

    @Test
    fun theSearchInsideAFormattedNoteWorksInViewAndEdit() {
        createFirstFolder()
        runBlocking { container.noteUseCases.create(onlyFolderId(), "Formatted", NoteType.WORD, contentText = "Green tea\nMilk\nmore tea") }
        waitForText("Formatted")
        rule.onNodeWithText("Formatted").performClick()
        waitForTab("Edit")

        // The formatted "Note" has the magnifier too (D256): in View the matches of every line are counted.
        rule.onNodeWithContentDescription("Find in the note").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasSetTextAction() and hasText("Find in the note")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Find in the note")).performTextInput("TEA")
        waitForText("1 / 2")
        rule.onNodeWithContentDescription("Next match").performClick()
        waitForText("2 / 2")
        // And in Edit, in the text of the field; the current match stays the one it was.
        rule.onNodeWithContentDescription("Edit").performClick()
        waitForText("2 / 2")
        rule.onNodeWithContentDescription("Next match").performClick()
        waitForText("1 / 2")
    }

    @Test
    fun theSearchInsideANoteCountsTheMatchesAndStepsThroughThem() {
        createFirstFolder()
        runBlocking {
            container.noteUseCases.create(onlyFolderId(), "Recipes", NoteType.TEXT, contentText = "Green **tea**\n\nMilk\n\n- [ ] more tea")
        }
        waitForText("Recipes")
        rule.onNodeWithText("Recipes").performClick()
        waitForTab("Edit")

        // The magnifier in the top bar of the note opens the search (D238).
        rule.onNodeWithContentDescription("Find in the note").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasSetTextAction() and hasText("Find in the note")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("Find in the note")).performTextInput("TEA")
        waitForText("1 / 2")
        rule.onNodeWithContentDescription("Next match").performClick()
        waitForText("2 / 2")
        rule.onNodeWithContentDescription("Next match").performClick()
        waitForText("1 / 2")

        // D246: the case of the letters counts when the tick is off.
        rule.onNodeWithText("Aa any case").performClick()
        waitForText("No matches")
        rule.onNode(hasSetTextAction() and hasText("TEA")).performTextReplacement("tea")
        waitForText("1 / 2")
        // A regular expression, and a wrong one: a note under the field, no crash.
        rule.onNodeWithText(".* Regexp").performClick()
        rule.onNode(hasSetTextAction() and hasText("tea")).performTextReplacement("^Green")
        waitForText("1 / 1")
        rule.onNode(hasSetTextAction() and hasText("^Green")).performTextReplacement("*tea*")
        waitUntil(10_000) { rule.onAllNodes(hasText("Error in the expression, character 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(".* Regexp").performClick()

        rule.onNode(hasSetTextAction() and hasText("*tea*")).performTextReplacement("coffee")
        waitForText("No matches")

        rule.onNodeWithContentDescription("Close the search").performClick()
        waitForNoText("No matches")
    }

    @Test
    fun typingTheFirstWordsLetterByLetterSearchesAtOnce() {
        createFirstFolder()
        runBlocking {
            container.noteUseCases.create(onlyFolderId(), "Recipes", NoteType.TEXT, contentText = "Green tea\n\nmore tea")
            container.noteUseCases.create(onlyFolderId(), "Shopping", NoteType.TEXT, contentText = "bread")
        }
        waitForText("Recipes")
        // The search of notes on the main screen: the first letters typed must already filter (user, 2026-09-27).
        rule.onNodeWithContentDescription("Search").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val field = rule.onAllNodes(hasSetTextAction())[0]
        field.performTextInput("s")
        field.performTextInput("h")
        waitForNoText("Recipes")
        waitForText("Shopping")
        rule.onNodeWithContentDescription("Cancel search").performClick()
        waitForText("Recipes")

        // The search inside a note, typed letter by letter.
        rule.onNodeWithText("Recipes").performClick()
        waitForTab("Edit")
        rule.onNodeWithContentDescription("Find in the note").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasSetTextAction() and hasText("Find in the note")).fetchSemanticsNodes().isNotEmpty() }
        val inNote = rule.onNode(hasSetTextAction() and hasText("Find in the note"))
        inNote.performTextInput("t")
        waitUntil(10_000) { rule.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasText("t")).performTextInput("e")
        waitForText("1 / 2")
    }

    @Test
    fun quickScrollButtonsGoToTheEndAndBackToTheBeginning() {
        createFirstFolder()
        runBlocking { (1..40).forEach { container.noteUseCases.create(onlyFolderId(), "Note %02d".format(it), NoteType.TEXT) } }
        waitForText("Note 40")
        // Moving the list shows the buttons (D250).
        rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.5f) }
        waitUntil(10_000) { rule.onAllNodesWithContentDescription("To the end").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("To the end").performClick()
        // At the end there is nowhere further to go: that button goes, the other stays.
        waitUntil(10_000) { rule.onAllNodesWithContentDescription("To the end").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("To the beginning").performClick()
        waitUntil(10_000) { rule.onAllNodesWithContentDescription("To the beginning").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aScreenRecordingNoteHasTheFloatingStopSwitch() {
        createFirstFolder()
        runBlocking { container.noteUseCases.create(onlyFolderId(), "Screen", NoteType("SCREEN")) }
        waitForText("Screen")
        rule.onNodeWithText("Screen").performClick()
        // D249: on by default; off, the note says where Stop is then.
        waitForText("Floating Stop button")
        rule.onNodeWithText("Floating Stop button").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasText("Stop the recording from the notification", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(10_000) { runBlocking { !container.settings.screenFloatingStop.first() } }
    }

    @Test
    fun theRecordingsPageOfTheSettingsHasTheThreeLimits() {
        createFirstFolder()
        waitForNewNoteButton()
        openSettingsPage("Recordings")
        waitForText("Audio recording: longest")
        rule.onNodeWithText("Camera video: longest").assertExists()
        rule.onNodeWithText("Screen recording: longest").assertExists()
    }

    @Test
    fun theAppLogSettingsTickLevelsOnAndOff() {
        createFirstFolder()
        waitForNewNoteButton()
        try {
            openSettingsPage("Help and about")
            // D254: the log settings under the App log button; every level ticked by default, FATAL always.
            // The settings page, not the list of the screen before it (still there for a moment while the screens change).
            val isPage = androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Developer: vibic"))
            waitUntil(10_000) { rule.onAllNodes(isPage).fetchSemanticsNodes().size == 1 }
            val page = rule.onNode(isPage)
            page.performScrollToNode(hasText("Keep an app log"))
            page.performScrollToNode(hasText("INFO"))
            rule.onNodeWithText("INFO").performClick()
            waitUntil(10_000) { com.notebudget.app.core.common.AppLog.Level.INFO !in com.notebudget.app.core.common.AppLog.options().levels }
            page.performScrollToNode(hasText("FATAL"))
            page.performScrollToNode(hasText("once a day"))
        } finally {
            com.notebudget.app.core.common.AppLog.setOptions(com.notebudget.app.core.common.AppLog.Options())
        }
    }

    @Test
    fun theSearchOfTheTrashFindsTheTextOfADeletedNote() {
        createFirstFolder()
        val note = runBlocking {
            (container.noteUseCases.create(onlyFolderId(), "Shopping", NoteType.TEXT, contentText = "bread and green tea") as com.notebudget.app.domain.model.DomainResult.Success).value
        }
        waitForText("Shopping")
        runBlocking { container.noteUseCases.delete(note.id) }
        waitUntil(10_000) { rule.onAllNodesWithContentDescription("Trash, 1 item").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Trash, 1 item").performClick()
        waitForText("Shopping")

        // The magnifier of the trash (D253): the words are found in the note's text, the piece around them is shown.
        rule.onNodeWithContentDescription("Find in the trash").performClick()
        waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput("TEA")
        waitForText("1 / 1")
        rule.onNodeWithText("bread and green tea").assertExists()
    }

    @Test
    fun theTrashScreenListsADeletedNoteAndRestoresIt() {
        createFirstFolder()
        val note = seedNote(onlyFolderId(), "Buy milk")
        waitForText("Buy milk")
        runBlocking { container.noteUseCases.delete(note.id) }
        waitForNoText("Buy milk")

        waitUntil(10_000) { rule.onAllNodesWithContentDescription("Trash, 1 item").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Trash, 1 item").performClick()

        waitForText("Buy milk")
        waitForTab("Restore")
        rule.onNodeWithContentDescription("Restore").performClick()

        waitForText("Restored: Buy milk")
        assertNull(runBlocking { container.noteRepository.getByIdIncludingDeleted(note.id) }?.deletedAt)
        // The trash is empty again.
        waitForText("Trash is empty")
    }

    @Test
    fun deletingForeverAsksForConfirmationAndRemovesTheNoteForGood() {
        createFirstFolder()
        val note = seedNote(onlyFolderId(), "Secret plan")
        waitForText("Secret plan")
        runBlocking { container.noteUseCases.delete(note.id) }
        waitForNoText("Secret plan")

        waitUntil(10_000) { rule.onAllNodesWithContentDescription("Trash, 1 item").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Trash, 1 item").performClick()
        waitForText("Secret plan")
        rule.onNodeWithContentDescription("Delete forever").performClick()

        waitForText("Delete this note forever?")
        // Nothing has been deleted yet: it is only the question.
        assertNotNull(runBlocking { container.noteRepository.getByIdIncludingDeleted(note.id) })
        rule.onNode(hasText("Delete forever") and hasAnyAncestor(isDialog())).performClick()

        waitForText("Deleted permanently: Secret plan")
        assertNull(runBlocking { container.noteRepository.getByIdIncludingDeleted(note.id) })
        waitForText("Trash is empty")
    }

    @Test
    fun settingsShowTheVersionTheDeveloperAndTheDataFormats() {
        createFirstFolder()

        openSettingsPage("Help and about")

        waitForText("About")
        waitForText("Developer: vibic")
        // "Notio by vibic, version 0.7.0 (build 10)": the exact numbers come from the build, so only the shape is checked.
        waitUntil(5_000) { rule.onAllNodesWithText("Notio by vibic, version", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(5_000) { rule.onAllNodesWithText("(build", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Backup format 1 · database version 14")
    }

    @Test
    fun theUnitsScreenShowsTheBuiltInUnitsAndTheOnesTheUserAdded() {
        createFirstFolder()
        runBlocking {
            container.unitRepository.setCustom(listOf(com.notebudget.app.domain.model.CustomUnit("u-packs", "Packs of tea")))
        }

        openSettingsPage("Notes and export")
        waitForText("Manage units")
        rule.onNodeWithText("Manage units").performSemanticsAction(SemanticsActions.OnClick)

        waitForText("Your units")
        waitForText("Packs of tea")
        // The built-in units are listed and cannot be deleted from here (only the user's own have a delete button).
        waitForText("Built-in units (cannot be deleted)")
        waitForText("kg - Kilograms")
        waitForText("₽ - Roubles")
        waitForText("€ - Euros")
        assertEquals(1, rule.onAllNodesWithContentDescription("Delete unit").fetchSemanticsNodes().size)
    }

    @Test
    fun theTrashIsReachableFromSettingsAndTheBinShowsHowManyThingsAreInIt() {
        createFirstFolder()
        // Empty: the bin has no number.
        rule.onNodeWithContentDescription("Trash").assertExists()

        val note = seedNote(onlyFolderId(), "Old idea")
        waitForText("Old idea")
        runBlocking { container.noteUseCases.delete(note.id) }
        waitUntil(10_000) { rule.onAllNodesWithContentDescription("Trash, 1 item").fetchSemanticsNodes().isNotEmpty() }

        // A second way in: Settings has a button for it.
        openSettingsPage("Trash")
        waitForText("Open trash")
        rule.onNodeWithText("Open trash").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Old idea")
        waitForTab("Restore")
    }

    @Test
    fun theColourThemesAreOfferedAndTheChoiceIsSaved() {
        createFirstFolder()
        openSettingsPage("Appearance")
        for (name in listOf("Green", "Pink", "Orange", "Red", "Light blue", "Blue", "Lime")) waitForText(name)
        // Light, dark and system stay available next to the colours (D105).
        waitForText("Follow the system")
        waitForText("Light")
        waitForText("Dark")

        rule.onNodeWithText("Pink").performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(10_000) {
            runBlocking { container.settings.colorTheme.first() } == com.notebudget.app.domain.model.ColorTheme.PINK
        }
    }

    @Test
    fun aNoteColourCanBeChosenFromTheCardMenu() {
        createFirstFolder()
        val note = seedNote(onlyFolderId(), "Coloured")
        waitForText("Coloured")

        rule.onAllNodesWithContentDescription("More options")[1].performClick()
        waitForText("Colour")
        rule.onNodeWithText("Colour").performClick()
        waitForText("Note colour")
        rule.onNodeWithContentDescription("Colour #D32F2F").performClick()

        waitUntil(10_000) { runBlocking { container.noteRepository.getById(note.id) }?.colorHex == "#D32F2F" }
    }

    private fun seedList(title: String): Note = runBlocking {
        val note = (container.noteUseCases.create(onlyFolderId(), title, NoteType.LIST) as DomainResult.Success).value
        val document = com.notebudget.app.domain.model.ListDocument(
            entries = listOf(
                com.notebudget.app.domain.model.ListEntry("a", text = "Milk", quantityMilli = 2_000, unit = "pcs", comment = "whole"),
                com.notebudget.app.domain.model.ListEntry("b", text = "Bread"),
            ),
        )
        val encoded = document.encode({ it }, emptyMap())
        container.noteUseCases.save(note.copy(contentText = encoded.contentText, extras = encoded.extras), withExtras = true)
        note
    }

    @Test
    fun aListOpensInViewAndItsCheckBoxCanBeTickedThereButNothingElseChanges() {
        createFirstFolder()
        val note = seedList("Groceries")
        waitForText("Groceries")
        // The card already shows a piece of the view: rendered lines, no list syntax.
        waitForText("☐ Milk — 2 pcs (whole)")
        waitForText("☐ Bread")

        rule.onNodeWithText("Groceries").performClick()

        // View: text, quantity and unit, comment - and no fields to type into except the title.
        waitForText("2 pcs")
        waitForText("whole")
        assertEquals(0, rule.onAllNodes(hasSetTextAction() and hasText("Milk")).fetchSemanticsNodes().size)

        // Edit is the second tab, View the first.
        rule.onNodeWithContentDescription("Edit").performClick()
        waitForTab("Add item")
        rule.onNodeWithContentDescription("View").performClick()
        waitForText("2 pcs")

        // Only the two check boxes of the list are toggleable; tick the first one, then leave (which saves).
        rule.onAllNodes(androidx.compose.ui.test.isToggleable())[0].performClick()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForNewNoteButton()
        // The save is asynchronous and the main screen is already there under the closing editor: wait for the database.
        waitUntil(10_000) {
            runBlocking { container.noteRepository.getById(note.id) }?.let {
                com.notebudget.app.domain.model.ListDocument.fromNote(it.contentText, it.extras).entries.firstOrNull()?.done
            } == true
        }

        val saved = runBlocking { container.noteRepository.getById(note.id) }!!
        val doc = com.notebudget.app.domain.model.ListDocument.fromNote(saved.contentText, saved.extras)
        assertEquals(true, doc.entries[0].done)
        assertEquals(2_000L, doc.entries[0].quantityMilli) // ticking did not touch the rest
        assertEquals("whole", doc.entries[0].comment)
        assertEquals(false, doc.entries[1].done)
    }

    @Test
    fun aBudgetOpensInViewWithoutEditButtonsAndEditBringsThemBack() {
        createFirstFolder()
        val note = runBlocking {
            (container.noteUseCases.create(onlyFolderId(), "Trip", NoteType.BUDGET) as DomainResult.Success).value
        }
        runBlocking {
            val root = container.budgetRepository.getNodes(note.id).single()
            val leaf = (container.budgetEditor.addNode(note.id, root.id, "Hotel", BudgetNodeKind.LEAF) as DomainResult.Success).value
            container.budgetEditor.updateLeaf(note.id, leaf.id, sumMinor = 10_000, goalMinor = 20_000)
        }
        waitForText("Trip")
        // The card shows the total of the budget.
        waitUntil(10_000) { rule.onAllNodesWithText("Sum 100.00").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Trip").performClick()
        waitForText("Hotel")
        // View: nothing to change. The only "More options" left is the export menu of the top bar; the menus of the
        // budget rows (edit, add, delete) are hidden.
        waitUntil(5_000) { rule.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().size == 1 }

        rule.onNodeWithContentDescription("Edit").performClick()
        waitUntil(5_000) { rule.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().size > 1 }
    }

    @Test
    fun searchByATagKeepsOnlyTheNotesThatHaveIt() {
        createFirstFolder()
        runBlocking {
            val folder = onlyFolderId()
            val alpha = (container.noteUseCases.create(folder, "Alpha", NoteType.TEXT) as DomainResult.Success).value
            container.noteUseCases.create(folder, "Beta", NoteType.TEXT)
            val tag = (container.tagUseCases.getOrCreate("Urgent") as DomainResult.Success).value
            container.tagUseCases.setNoteTags(alpha.id, setOf(tag.id))
        }
        waitForText("Alpha")
        waitForText("Beta")

        rule.onNodeWithContentDescription("Search").performClick()
        waitForText("Search in titles and text")
        // The chip is above the notes; the tag line of Alpha's card has the same text.
        waitUntil(10_000) { rule.onAllNodesWithText("#Urgent").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithText("#Urgent")[0].performClick()

        waitUntil(10_000) { rule.onAllNodesWithText("Beta").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Alpha").assertExists()
    }

    @Test
    fun typingASearchOverManyNotesOfEveryKindDoesNotCrash() {
        // The user, 2026-09-22: with more than 100 notes the app closed as soon as a word was typed into the search.
        createFirstFolder()
        runBlocking {
            val folders = listOf(onlyFolderId()) + (1..3).map { seedFolder("F$it").id }
            val kinds = listOf(NoteType.TEXT, NoteType.LIST, NoteType.BUDGET, NoteType.SCHEDULE, NoteType.DRAWING, NoteType.BLUEPRINT, NoteType.AUDIO,
                NoteType("PHOTO"), NoteType("VIDEO"), NoteType("SCREEN"))
            for (i in 1..130) {
                val kind = kinds[i % kinds.size]
                container.noteUseCases.create(folders[i % folders.size], "Note $i ${kind.id}", kind, contentText = "**text** of note $i" + "\n- [ ] item $i")
            }
        }
        waitForText("32 notes")
        rule.onNodeWithContentDescription("Search").performClick()
        waitForText("Search in titles and text")
        for (typed in listOf("n", "no", "note", "note 1", "text", "zzz", "")) {
            rule.onNode(hasSetTextAction() and hasText("Search in titles and text")).performTextReplacement(typed)
            rule.mainClock.advanceTimeBy(500)
        }
        rule.onNode(hasSetTextAction() and hasText("Search in titles and text")).performTextReplacement("15 blueprint")
        waitForText("Note 15 BLUEPRINT")
        rule.onNode(hasSetTextAction() and hasText("Search in titles and text")).performTextReplacement("item")
        waitForText("Found: 130")
    }

    @Test
    fun sharingANoteHandsItsTextOrFilesToTheShareSheet() {
        // D216: text-like notes go as text with their title; a media note without items has nothing to share.
        createFirstFolder()
        val folder = onlyFolderId()
        val text = runBlocking {
            (container.noteUseCases.create(folder, "Shopping", NoteType.TEXT, contentText = "milk\nbread") as DomainResult.Success).value
        }
        val photo = runBlocking { (container.noteUseCases.create(folder, "Trip", NoteType("PHOTO")) as DomainResult.Success).value }
        val intent = runBlocking { com.notebudget.app.feature.notes.NoteShare.intentFor(rule.activity, container, text) }!!
        assertEquals(android.content.Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        val body = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        assertEquals(true, body.startsWith("Shopping") && body.contains("milk") && body.contains("bread"))
        assertNull(runBlocking { com.notebudget.app.feature.notes.NoteShare.intentFor(rule.activity, container, photo) })
    }

    @Test
    fun theFolderSearchSwitchAndTheTagManagerAreReachable() {
        createFirstFolder()
        rule.onNodeWithContentDescription("Search").performClick()
        waitForText("Notes")
        rule.onNodeWithText("Folders").performClick()
        waitForText("Search folders by name")

        openSettingsPage("Notes and export")
        waitForText("Manage tags")
        rule.onNodeWithText("Manage tags").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("You have no tags yet.")
    }

    @Test
    fun searchEndsWithTheBackButtonAndWithTheCancelButton() {
        createFirstFolder()
        rule.onNodeWithContentDescription("Search").performClick()
        waitForText("Search in titles and text")
        // The three kinds of search and a cancel button are on the panel; the icon now says it ends the search.
        rule.onNodeWithText("Tags").assertExists()
        rule.onNodeWithText("Cancel search").assertExists()
        rule.onNodeWithContentDescription("Cancel search").assertExists()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitUntil(5_000) { rule.onAllNodesWithText("Search in titles and text").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("Search").assertExists()

        rule.onNodeWithContentDescription("Search").performClick()
        waitForText("Search in titles and text")
        rule.onNodeWithText("Cancel search").performClick()
        waitUntil(5_000) { rule.onAllNodesWithText("Search in titles and text").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theLockScreenCoversTheAppUntilItIsUnlocked() {
        createFirstFolder()
        runBlocking {
            container.appLock.enable(com.notebudget.app.domain.usecase.LockSetup(com.notebudget.app.domain.model.LockMethod.PASSWORD, "secret1"))
        }
        // A fresh start of the app: the lock is asked for whatever the timeout says.
        rule.activityRule.scenario.recreate()
        waitForText("Locked")
        rule.onNodeWithText("Unlock").assertExists()
        assertEquals("nothing of the app is on the screen", 0, rule.onAllNodesWithText("New note").fetchSemanticsNodes().size)

        rule.runOnUiThread { container.lockGate.unlocked() }
        waitForNewNoteButton()
        runBlocking { container.appLock.disable("secret1") }
    }

    @Test
    fun theLockSettingsAreReachableFromSettings() {
        createFirstFolder()
        openSettingsPage("Security and privacy")
        waitForText("Set up the lock")
        rule.onNodeWithText("Set up the lock").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("The lock is off")
    }

    @Test
    fun theBackgroundsScreenIsReachableFromSettings() {
        createFirstFolder()
        openSettingsPage("Appearance")
        waitForText("Choose backgrounds")
        rule.onNodeWithText("Choose backgrounds").performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Whole app")
        waitForText("Folder cards")
    }

    @Test
    fun theReminderScreenOpensFromTheMainScreen() {
        createFirstFolder()
        rule.onNodeWithContentDescription("Reminders").performClick()
        waitForText("Add reminder")
        waitForText("No reminders yet. Tap “Add reminder”.")
    }

    @Test
    fun theSortDialogAndTheFolderFeaturedChooserOpen() {
        createFirstFolder()
        rule.onNodeWithContentDescription("Sort and import").performClick()
        waitForText("Sort")
        rule.onNodeWithText("Sort").performClick()
        waitForText("Pinned notes always stay first, the one pinned last on top.")
        rule.onNodeWithText("Done").performClick()
        waitUntil(5_000) { rule.onAllNodesWithText("Pinned notes always stay first, the one pinned last on top.").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theToolPanelOfADrawingFoldsAwayAndComesBack() {
        createFirstFolder()
        runBlocking { container.noteUseCases.create(onlyFolderId(), "Sketch", NoteType.DRAWING) }
        waitForText("Sketch")
        rule.onNodeWithText("Sketch").performClick()
        waitForTab("Edit")
        rule.onNodeWithContentDescription("Edit").performClick()
        waitForText("Pencil")

        // The tools lie over the sheet and fold to one icon (D241, D261), and move to the right and back.
        rule.onNodeWithContentDescription("Hide the tools").performClick()
        waitForNoText("Pencil")
        rule.onNodeWithContentDescription("Tools").performClick()
        waitForText("Pencil")
        // Four sides from the panel's menu (D281).
        rule.onNodeWithContentDescription("Where the tools are").performClick()
        waitForText("Tools on the left")
        rule.onNodeWithText("Tools on the left").performClick()
        waitUntil(10_000) { runBlocking { container.settings.drawingToolsSide.first() } == com.notebudget.app.feature.drawing.ToolPanelSide.LEFT }
        waitForText("Pencil")
        rule.onNodeWithContentDescription("Where the tools are").performClick()
        waitForText("Tools at the bottom")
        rule.onNodeWithText("Tools at the bottom").performClick()
        waitUntil(10_000) { runBlocking { container.settings.drawingToolsSide.first() } == com.notebudget.app.feature.drawing.ToolPanelSide.BOTTOM }
    }

    @Test
    fun theSheetOfABlueprintGetsANewSize() {
        createFirstFolder()
        val note = runBlocking { (container.noteUseCases.create(onlyFolderId(), "Poster", NoteType.BLUEPRINT) as com.notebudget.app.domain.model.DomainResult.Success).value }
        waitForText("Poster")
        rule.onNodeWithText("Poster").performClick()
        waitForTab("Edit")
        rule.onNodeWithContentDescription("Edit").performClick()
        waitForText("Shapes")

        // The frame icon on the tool panel opens "Sheet size" (D260; the dialog itself is tested in DialogInputTest: under
        // Robolectric a dialog window with a text field never becomes idle). Here: the new size is kept with the note.
        rule.onNodeWithContentDescription("Sheet size").assertExists()
        runBlocking {
            val doc = com.notebudget.app.feature.drawing.VectorDocument(sheetWidth = 1920, sheetHeight = 1080)
            container.vectorStore.write(note.id, doc.encode())
        }
        assertEquals(1920 to 1080, runBlocking { container.vectorStore.read(note.id) }?.let { com.notebudget.app.feature.drawing.VectorDocument.decode(it) }?.sheet())
    }

    @Test
    fun aTaskShowsItsStateAndDatesAndIsEditedOnItsEditTab() {
        createFirstFolder()
        val start = java.time.LocalDate.now().plusDays(1).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking {
            val folder = (container.folderUseCases.create("Work", "#3366FF", "SOFT", kind = com.notebudget.app.domain.model.FolderKind.TASKS)
                as com.notebudget.app.domain.model.DomainResult.Success).value
            val task = (container.noteUseCases.create(folder.id, "Call the bank", NoteType.TASK, contentText = "about the card")
                as com.notebudget.app.domain.model.DomainResult.Success).value
            container.noteUseCases.updateExtras(task.id, com.notebudget.app.domain.model.TaskDocument(comment = "morning", start = start, end = start + 3_600_000).encode())
        }
        waitForText("Work")
        rule.onNodeWithText("Work").performClick()
        waitForText("Call the bank")
        // On the name itself: the middle of the card is the line of the task, with its Copy and its bin (D361, D362).
        rule.onNodeWithText("Call the bank", useUnmergedTree = true).performClick()

        // D239: View shows the state, the dates, the comment and the description.
        waitForText("morning")
        // The status is its icon alone (D343); its word is the hint and what a screen reader says.
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("In work"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let { assertEquals(true, it) }
        waitForText("about the card")
        rule.onNodeWithContentDescription("Edit").performClick()
        waitForText("Start")
        rule.onNodeWithText("End").assertExists()
        // D337: the status is chosen from the list behind its button.
        // The card of the task behind the note has the button too: the one of the note is the last.
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(com.notebudget.app.feature.notes.task.TASK_STATE_BUTTON), useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag(com.notebudget.app.feature.notes.task.TASK_STATE_ITEM + "DONE")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(androidx.compose.ui.test.hasTestTag(com.notebudget.app.feature.notes.task.TASK_STATE_ITEM + "DONE")).performClick()
        waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Done"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Leaving the note saves it at once.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitUntil(10_000) {
            runBlocking { container.noteRepository.getAllIncludingDeleted() }.any { it.type == NoteType.TASK && com.notebudget.app.domain.model.TaskDocument.decode(it.extras).done }
        }
    }

    @Test
    fun theBlueprintToolChipsFollowEveryTap() {
        createFirstFolder()
        runBlocking { container.noteUseCases.create(onlyFolderId(), "Plan", NoteType.BLUEPRINT) }
        waitForText("Plan")
        rule.onNodeWithText("Plan").performClick()
        waitForTab("Edit")
        rule.onNodeWithContentDescription("Edit").performClick()
        waitForText("Shapes")

        fun node(name: String) = rule.onAllNodesWithText(name).fetchSemanticsNodes().firstOrNull()
            ?: rule.onNodeWithContentDescription(name).fetchSemanticsNode()
        fun selected(name: String) = node(name).config.let { it.contains(SemanticsProperties.Selected) && it[SemanticsProperties.Selected] }
        fun tap(name: String) {
            val byText = rule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
            (if (byText) rule.onNodeWithText(name) else rule.onNodeWithContentDescription(name)).performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(5_000) { selected(name) }
        }
        assertEquals("the select tool is chosen at first", true, selected("Select"))
        // One "Shapes" tool with its list of primitives (D213); the free-hand pen is gone.
        assertEquals(0, rule.onAllNodesWithText("Pen").fetchSemanticsNodes().size)
        tap("Shapes")
        assertEquals(false, selected("Select"))
        tap("Ellipse")
        tap("Star")
        assertEquals(false, selected("Ellipse"))
        assertEquals(true, selected("Shapes"))
        tap("Fill")
        assertEquals(false, selected("Shapes"))
        tap("Text")
        assertEquals(false, selected("Fill"))
    }

    @Test
    fun aTapOnAReminderNotificationOpensThatNote() {
        createFirstFolder()
        val note = runBlocking {
            (container.noteUseCases.create(onlyFolderId(), "Ideas", NoteType.TEXT, contentText = "Hello") as DomainResult.Success).value
        }
        waitForText("Ideas")
        // The intent the notification carries: the note id, taken by the activity.
        val intent = android.content.Intent(com.notebudget.app.core.notification.OpenNoteRequest.ACTION_OPEN_NOTE)
            .putExtra(com.notebudget.app.core.notification.OpenNoteRequest.EXTRA_NOTE_ID, note.id)
        rule.runOnUiThread { container.openNote.take(intent) }

        waitForTab("Edit") // the note screen, in View
        assertEquals("the request is used up, so a turn of the screen does not open it again", null, container.openNote.noteId.value)
        assertEquals("the intent is marked handled", null, intent.action)
    }

    @Test
    fun aNotificationForANoteThatIsGoneOpensNothingAndIsForgotten() {
        createFirstFolder()
        val note = runBlocking {
            (container.noteUseCases.create(onlyFolderId(), "Gone", NoteType.TEXT) as DomainResult.Success).value
        }
        runBlocking { container.noteUseCases.delete(note.id) }
        rule.runOnUiThread { container.openNote.noteId.value = note.id }
        waitUntil(10_000) { container.openNote.noteId.value == null }
        waitForNewNoteButton()
    }

    @Test
    fun anIntentThatIsNotAReminderIsIgnored() {
        val request = com.notebudget.app.core.notification.OpenNoteRequest()
        request.take(android.content.Intent(android.content.Intent.ACTION_MAIN).putExtra("note_id", "x"))
        request.take(null)
        request.take(android.content.Intent(com.notebudget.app.core.notification.OpenNoteRequest.ACTION_OPEN_NOTE))
        assertEquals(null, request.noteId.value)
    }
}
