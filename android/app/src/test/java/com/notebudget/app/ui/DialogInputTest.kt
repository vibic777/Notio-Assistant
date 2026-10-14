package com.notebudget.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.notebudget.app.core.common.AdjustMode
import com.notebudget.app.core.common.Money
import com.notebudget.app.core.ui.AdjustDialog
import com.notebudget.app.core.ui.LocalInlineDialogs
import com.notebudget.app.domain.model.BudgetNodeKind
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.budget.GroupOption
import com.notebudget.app.feature.budget.NodeDialog
import com.notebudget.app.feature.folders.FolderDraft
import com.notebudget.app.feature.home.CommentDialog
import com.notebudget.app.feature.home.FolderDialog
import com.notebudget.app.feature.home.NewNoteDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests of the dialogs where text is typed (D202): the real dialog content, drawn in place through [LocalInlineDialogs]
 * because under Robolectric a dialog WINDOW that holds a text field never becomes idle (AlertDialog, BasicAlertDialog and a
 * plain Dialog all hang; without the field they do not). What is checked: typing, what is refused and why, what is handed on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DialogInputTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        MaterialTheme { CompositionLocalProvider(LocalInlineDialogs provides true) { content() } }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun newNote_createIsOffForABlankTitle_thenHandsOnTitleAndKind() {
        var created: Pair<String, NoteType>? = null
        show { NewNoteDialog(error = null, onDraftEdited = {}, onConfirm = { t, k -> created = t to k }, onDismiss = {}) }

        field("Title").performTextReplacement("")
        rule.onNodeWithText("Create").assertIsNotEnabled()
        field("Title").performTextReplacement("Shopping")
        rule.onNodeWithText("Create").assertIsEnabled()
        rule.onNodeWithContentDescription("Budget").performClick()
        rule.onNodeWithText("Create").performClick()

        assertEquals("Shopping" to NoteType.BUDGET, created)
    }

    @Test
    fun newNote_theTitleIsReady_kindAndTheFirstFreeNumber() {
        var created: Pair<String, NoteType>? = null
        show {
            NewNoteDialog(
                error = null, onDraftEdited = {}, onConfirm = { t, k -> created = t to k }, onDismiss = {},
                existingTitles = listOf("Audio 1", "audio 2", "Audio 4", "Budget 1"),
            )
        }
        // D264: Create works at once; the ready name follows the kind until a name of one's own is typed.
        rule.onNodeWithText("Create").assertIsEnabled()
        rule.onNodeWithContentDescription("Audio").performClick()
        field("Title").assert(hasText("Audio 3"))
        rule.onNodeWithContentDescription("Budget").performClick()
        field("Title").assert(hasText("Budget 2"))
        field("Title").performTextReplacement("Trip")
        rule.onNodeWithContentDescription("Audio").performClick()
        field("Title").assert(hasText("Trip"))
        field("Title").performTextReplacement("")
        rule.onNodeWithContentDescription("Budget").performClick()
        rule.onNodeWithText("Create").performClick()

        assertEquals("Budget 2" to NoteType.BUDGET, created)
    }

    @Test
    fun newNote_showsTheErrorItIsGiven() {
        show { NewNoteDialog(error = DomainError.BLANK_TITLE, onDraftEdited = {}, onConfirm = { _, _ -> }, onDismiss = {}) }
        rule.onNodeWithText("Enter a title").assertExists()
    }

    @Test
    fun folder_theNameTypedIsWhatIsSaved() {
        var saved: FolderDraft? = null
        show {
            FolderDialog(
                title = "New folder", confirmLabel = "Create", initial = FolderDraft(name = ""), error = null,
                onDraftEdited = {}, onConfirm = { saved = it }, onDismiss = {},
            )
        }
        rule.onNodeWithText("Create").assertIsNotEnabled()
        field("Folder name").performTextReplacement("Home")
        rule.onNodeWithText("Create").performClick()
        assertEquals("Home", saved?.name)
    }

    @Test
    fun newFolder_theNameIsReady_kindAndTheFirstFreeNumber() {
        var saved: FolderDraft? = null
        show {
            FolderDialog(
                title = "New folder", confirmLabel = "Create", initial = FolderDraft(name = ""), error = null,
                onDraftEdited = {}, onConfirm = { saved = it }, onDismiss = {}, showKind = true,
                existingNames = listOf("Folder 1", "folder 2", "Folder 4", "Tasks 1", "My Notes"),
            )
        }
        // D275: as the ready note title (D264) — Create works at once; the name follows the kind until one's own is typed.
        rule.onNodeWithText("Create").assertIsEnabled()
        field("Folder name").assert(hasText("Folder 3"))
        rule.onNodeWithContentDescription("Folder of tasks").performClick()
        field("Folder name").assert(hasText("Tasks 2"))
        field("Folder name").performTextReplacement("Work")
        rule.onNodeWithContentDescription("Folder of notes").performClick()
        field("Folder name").assert(hasText("Work"))
        field("Folder name").performTextReplacement("")
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Folder of tasks").performClick()
        rule.onNodeWithText("Create").performClick()

        assertEquals("Tasks 2", saved?.name)
        assertEquals("TASKS", saved?.kind)
    }

    @Composable
    private fun adjust(current: Long, allowNegative: Boolean, onConfirm: (AdjustMode, Long, Long) -> Unit) = AdjustDialog(
        title = "Change", itemName = "Bread", currentLine = "Now: " + Money.format(current), current = current,
        parse = Money::parse, format = Money::format,
        invalidAmountRes = com.notebudget.app.R.string.budget_adjust_invalid,
        invalidValueRes = com.notebudget.app.R.string.budget_invalid_number,
        allowNegativeResult = allowNegative, onConfirm = { m, a, v, _, _ -> onConfirm(m, a, v) }, onDismiss = {},
    )

    @Test
    fun change_addsTheAmountAndShowsTheResultFirst() {
        var result: Triple<AdjustMode, Long, Long>? = null
        show { adjust(10_000, allowNegative = true) { m, a, v -> result = Triple(m, a, v) } }
        field("Amount").performTextReplacement("25.50")
        rule.onNodeWithText("Will be: " + Money.format(12_550)).assertExists()
        rule.onNodeWithText("Save").performClick()
        assertEquals(Triple(AdjustMode.ADD, 2_550L, 12_550L), result)
    }

    @Test
    fun change_refusesTextAndABelowZeroResultWhenNotAllowed() {
        var result: Triple<AdjustMode, Long, Long>? = null
        show { adjust(1_000, allowNegative = false) { m, a, v -> result = Triple(m, a, v) } }
        field("Amount").performTextReplacement("abc")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter an amount greater than zero, up to two decimals").assertExists()

        rule.onNodeWithContentDescription("Subtract").performClick()
        field("Amount").performTextReplacement("20")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("The result cannot be below zero").assertExists()
        assertNull(result)

        rule.onNodeWithContentDescription("Enter").performClick()
        field("New value").performTextReplacement("3")
        rule.onNodeWithText("Save").performClick()
        assertEquals(Triple(AdjustMode.SET, 300L, 300L), result)
    }

    @Test
    fun change_canActOnTheSumTheGoalOrBoth_neverOnNeither() {
        var result: List<Any>? = null
        show {
            AdjustDialog(
                title = "Change", itemName = "Bread", currentLine = "Now", current = 10_000,
                parse = Money::parse, format = Money::format,
                invalidAmountRes = com.notebudget.app.R.string.budget_adjust_invalid,
                invalidValueRes = com.notebudget.app.R.string.budget_invalid_number,
                allowNegativeResult = true,
                second = com.notebudget.app.core.ui.AdjustSecond("Sum", "Goal", 50_000),
                onConfirm = { m, a, _, sum, goal -> result = listOf(m, a, sum, goal) }, onDismiss = {},
            )
        }
        rule.onNodeWithText("Sum").performClick()            // the only one on: stays on
        rule.onNodeWithText("Goal").performClick()           // both on
        rule.onNodeWithContentDescription("Subtract").performClick()
        field("Amount").performTextReplacement("10")
        rule.onNodeWithText("Will be: Sum " + Money.format(9_000) + ", Goal " + Money.format(49_000)).assertExists()
        rule.onNodeWithText("Sum").performClick()            // now only the goal
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf<Any>(AdjustMode.SUBTRACT, 1_000L, false, true), result)
    }

    @Test
    fun budgetItem_numbersAreReadInMinorUnitsAndABlankNameIsRefused() {
        var saved: List<Any?>? = null
        show {
            NodeDialog(
                title = "Add item", initial = null, kind = BudgetNodeKind.LEAF,
                groups = listOf(GroupOption("root", "", 0, isRoot = true)), initialGroupId = "root",
                onConfirm = { t, c, s, g, q, grp -> saved = listOf(t, c, s, g, q, grp) }, onDismiss = {},
            )
        }
        rule.onNodeWithText("Create").performClick()
        rule.onNodeWithText("Enter a title").assertExists()
        assertNull(saved)

        field("Name").performTextReplacement("Bread")
        field("Sum").performTextReplacement("12.5")
        field("Goal").performTextReplacement("x")
        rule.onNodeWithText("Enter a number, up to two decimals").assertExists()
        field("Goal").performTextReplacement("20")
        field("Comment").performTextReplacement("weekly")
        rule.onNodeWithText("Create").performClick()

        assertEquals(listOf<Any?>("Bread", "weekly", 1_250L, 2_000L, 1, "root"), saved)
    }

    @Test
    fun comment_whatIsTypedIsSaved() {
        var saved: String? = null
        show { CommentDialog(title = "Comment", initial = "", onSave = { saved = it }, onDismiss = {}) }
        rule.onNode(hasSetTextAction()).performTextReplacement("call back")
        rule.onNodeWithText("Save").performClick()
        assertEquals("call back", saved)
    }

    @Test
    fun sheetSize_aReadyRatioAUnitAndATypedSizeAreHandedOn_aTooLargeOneIsRefused() {
        var saved: Triple<Int, Int, com.notebudget.app.feature.drawing.SheetUnit>? = null
        show {
            com.notebudget.app.feature.drawing.SheetSizeDialog(
                1080, 1440, com.notebudget.app.feature.drawing.SheetUnit.PX,
                onConfirm = { w, h, u -> saved = Triple(w, h, u) }, onDismiss = {},
            )
        }
        // A ready ratio (D260) fills the fields; the pixels are said.
        rule.onNodeWithText("16:9").performClick()
        rule.onNodeWithText("= 1920 × 1080 px").assertExists()
        // The same size in cm (150 pixels per inch).
        rule.onNodeWithText("cm").performClick()
        field("Width").assert(hasText("32.5"))
        // A size too large for the phone's memory cannot be saved.
        field("Width").performTextReplacement("400")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        field("Width").performTextReplacement("21")
        field("Height").performTextReplacement("29,7")
        rule.onNodeWithText("Save").performClick()
        assertEquals(Triple(1240, 1754, com.notebudget.app.feature.drawing.SheetUnit.CM), saved)
    }

    @Test
    fun newFolder_offersAFolderOfTasks_andANewTaskHasNoListOfKinds() {
        var made: FolderDraft? = null
        var created: Pair<String, NoteType>? = null
        var tasks by androidx.compose.runtime.mutableStateOf(false)
        show {
            if (!tasks) {
                FolderDialog("New folder", "Create", FolderDraft(name = ""), null, {}, { made = it; tasks = true }, {}, showKind = true)
            } else {
                NewNoteDialog(error = null, onDraftEdited = {}, onConfirm = { t, k -> created = t to k }, onDismiss = {}, tasksOnly = true)
            }
        }
        // D239: the kind of the folder; a folder of tasks gets a tick as its icon.
        rule.onNodeWithContentDescription("Folder of tasks").performClick()
        field("Folder name").performTextReplacement("Work")
        rule.onNodeWithText("Create").performClick()
        assertEquals("TASKS", made?.kind)
        assertEquals("✅", made?.icon)
        // In it "New" makes a task: no list of kinds.
        rule.onNodeWithText("New task").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Budget").fetchSemanticsNodes().size)
        field("Title").assert(hasText("Task 1"))
        field("Title").performTextReplacement("Call the bank")
        rule.onNodeWithText("Create").performClick()
        assertEquals("Call the bank" to NoteType.TASK, created)
    }

    @Test
    fun change_handsOnAnOptionalComment() {
        var comment: String? = null
        var saved = false
        show {
            AdjustDialog(
                title = "Change", itemName = "Bread", currentLine = "Now", current = 10_000,
                parse = Money::parse, format = Money::format,
                invalidAmountRes = com.notebudget.app.R.string.budget_adjust_invalid,
                invalidValueRes = com.notebudget.app.R.string.budget_invalid_number,
                allowNegativeResult = true, onConfirm = { _, _, _, _, _ -> saved = true }, onDismiss = {},
                onComment = { comment = it },
            )
        }
        // D262: the comment field, then Save hands it on before the change.
        field("Amount").performTextReplacement("5")
        field("Comment (optional, goes into the log)").performTextReplacement("  bakery ")
        rule.onNodeWithText("Save").performClick()
        assertEquals("bakery", comment)
        assertEquals(true, saved)
    }

    @Test
    fun recordingLimit_aLimitForTheNextRecordingOnly() {
        val kind = com.notebudget.app.domain.model.RecordingKind.AUDIO
        com.notebudget.app.core.ui.RecordingLimitOnce.set(kind, null)
        show { com.notebudget.app.core.ui.RecordingLimitLine(com.notebudget.app.domain.model.RecordingLimit.DEFAULT, kind = kind) }
        // D263: the line says the Settings limit; "Change for this recording" types another one.
        rule.onNodeWithText("Stops by itself after 4 min (Settings → Recordings)").assertExists()
        rule.onNodeWithText("Change for this recording").performClick()
        field("Length").performTextReplacement("30")
        rule.onNodeWithText("seconds").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Stops by itself after 30 s (this recording only)").assertExists()
        // The recording that starts takes it; the next one goes by Settings again.
        val taken = com.notebudget.app.core.ui.RecordingLimitOnce.take(kind, com.notebudget.app.domain.model.RecordingLimit.DEFAULT)
        assertEquals(30_000L, taken.millis)
        assertEquals(com.notebudget.app.domain.model.RecordingLimit.DEFAULT, com.notebudget.app.core.ui.RecordingLimitOnce.take(kind, com.notebudget.app.domain.model.RecordingLimit.DEFAULT))
        rule.onNodeWithText("Stops by itself after 4 min (Settings → Recordings)").assertExists()
    }
}
