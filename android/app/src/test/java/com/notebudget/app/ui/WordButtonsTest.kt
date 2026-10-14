package com.notebudget.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.tip.OutlinedButton
import com.notebudget.app.core.ui.tip.TextButton
import com.notebudget.app.core.ui.tip.stringResource
import com.notio.common.ButtonIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D307: a button whose words have an icon is drawn as that icon; the words stay for a screen reader and come up as its hint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WordButtonsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aButtonWithKnownWordsIsAnIconAndStillAnswersToItsWords() {
        var cancelled = 0
        rule.setContent {
            MaterialTheme {
                TextButton(onClick = { cancelled++ }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        rule.waitForIdle()
        // Found by its words (a screen reader, the tests), clicked as before.
        val button = rule.onNodeWithText("Cancel")
        button.assertHasClickAction().assertIsEnabled().performClick()
        assertEquals(1, cancelled)
        // Drawn as an icon: it has the hint that a long press brings up (a button with words has none).
        assertEquals("the words take no room", 0.dp, wordsHeight("Cancel"))
    }

    /** D311: Quantity and Price of a list line are changed by + / − / =, so their icon is the budget's, not the pencil. */
    @Test
    fun theNumbersOfAListLineHaveThePlusMinusIcon() {
        assertEquals("PlusMinus", ButtonIcons.nameFor("list_change_quantity"))
        assertEquals("PlusMinus", ButtonIcons.nameFor("list_change_price"))
        assertEquals(com.notebudget.app.core.ui.PlusMinusIcon, com.notebudget.app.core.ui.AppIcons.byName("PlusMinus"))
    }

    /** D314: a line of a menu keeps its words and gets the icon of their meaning beside them; a line without one keeps its place. */
    @Test
    fun theLinesOfAMenuHaveTheirIconsBesideTheWords() {
        rule.setContent {
            MaterialTheme {
                com.notebudget.app.core.ui.tip.DropdownMenu(expanded = true, onDismissRequest = {}) {
                    com.notebudget.app.core.ui.tip.DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = {})
                    com.notebudget.app.core.ui.tip.DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {})
                    com.notebudget.app.core.ui.tip.DropdownMenuItem(text = { Text("Notio by vibic") }, onClick = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Edit").assertHasClickAction()
        rule.onAllNodesWithTag(com.notebudget.app.core.ui.tip.MENU_ICON, useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithTag(com.notebudget.app.core.ui.tip.MENU_GAP, useUnmergedTree = true).assertCountEquals(1)
        // A list of values (every line the same icon) and a menu of plain words stay without icons.
        assertTrue(com.notebudget.app.core.ui.tip.MenuIcons.shows(listOf("Edit", "Delete")))
        assertTrue(!com.notebudget.app.core.ui.tip.MenuIcons.shows(listOf("Sort", "Sort")))
        assertTrue(!com.notebudget.app.core.ui.tip.MenuIcons.shows(emptyList()))
    }

    @Test
    fun wordsThatAreNotAResourceStayWords() {
        rule.setContent {
            MaterialTheme { OutlinedButton(onClick = {}) { Text("A long name of a file to save") } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("A long name of a file to save").assertHasClickAction()
        assertTrue("the words are shown", wordsHeight("A long name of a file to save") > 0.dp)
    }

    /** How tall the words themselves are drawn inside their button. */
    private fun wordsHeight(text: String): androidx.compose.ui.unit.Dp =
        rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun theIconFollowsTheMeaningOfTheKey() {
        assertEquals("Close", ButtonIcons.nameFor("action_cancel"))
        assertEquals("Delete", ButtonIcons.nameFor("action_delete"))
        assertEquals("LockOpen", ButtonIcons.nameFor("lock_unlock"))
        assertEquals("Lock", ButtonIcons.nameFor("lock_use_biometric"))
        assertEquals("FileDownload", ButtonIcons.nameFor("settings_export_backup"))
        assertEquals("OpenInNew", ButtonIcons.nameFor("settings_units_open"))
        assertEquals("Help", ButtonIcons.nameFor("lock_forgot"))
        assertEquals("Refresh", ButtonIcons.nameFor("sync_refresh_folders"))
        assertEquals("VisibilityOff", ButtonIcons.nameFor("budget_hide_amounts"))
        assertTrue(ButtonIcons.isDanger("trash_delete_forever"))
        // A key no icon says keeps its words.
        assertNull(ButtonIcons.nameFor("note_type_label"))
    }

    @Test
    fun anIconButtonOfADialogWindowIsClickedByItsWords() {
        var closed = 0
        rule.setContent {
            MaterialTheme {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    text = { Text("Body") },
                    confirmButton = { TextButton(onClick = { closed++ }) { Text(stringResource(R.string.action_done)) } },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun theSortDialogClosesByItsIcon() {
        var closed = 0
        rule.setContent {
            MaterialTheme {
                com.notebudget.app.feature.home.SortDialog(
                    folderSort = com.notebudget.app.domain.model.SortOption.FOLDERS_DEFAULT, noteSort = com.notebudget.app.domain.model.SortOption.NOTES_DEFAULT,
                    onFolderSort = {}, onNoteSort = { _, _ -> }, onDismiss = { closed++ },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithText("Done")
        node.performClick()
        rule.waitForIdle()
        assertEquals(1, closed)
    }
}
