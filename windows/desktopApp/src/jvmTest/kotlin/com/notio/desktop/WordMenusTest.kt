package com.notio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D93 (Android D314): a line of a menu keeps its words and gets the icon of their meaning beside them. */
@OptIn(ExperimentalTestApi::class)
class WordMenusTest {
    @Test
    fun aMenuOfValuesStaysWithoutIcons() {
        assertFalse(MenuIcons.shows(emptyList()))
        assertTrue(MenuIcons.shows(listOf("Edit")))
        assertTrue(MenuIcons.shows(listOf("Edit", "Delete")))
        // Every line the same icon: the ways to sort, the themes — a list of values, not of actions.
        assertFalse(MenuIcons.shows(listOf("Sort", "Sort", "Sort")))
        assertTrue(MenuIcons.shows(listOf("Sort", "Sort", "Delete")))
    }

    @Test
    fun theLinesOfAMenuOfActionsHaveTheirIconsAndTheOthersItsPlace() = runComposeUiTest {
        val res = AndroidRes.forLanguage("en")
        var deleted = 0
        setContent {
            CompositionLocalProvider(LocalRes provides res) {
                MaterialTheme {
                    DropdownMenu(expanded = true, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text(res.str("action_edit")) }, onClick = {})
                        DropdownMenuItem(text = { Text(res.str("action_delete")) }, onClick = { deleted++ })
                        // Words no icon says: the line keeps the place of an icon.
                        DropdownMenuItem(text = { Text("Notio by vibic") }, onClick = {})
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText(res.str("action_edit")).assertHasClickAction()
        onNodeWithText(res.str("action_delete")).assertHasClickAction().performClick()
        assertEquals(1, deleted)
        // Two lines with the icon of their words, the third with the empty place of one.
        onAllNodesWithTag(MENU_ICON, useUnmergedTree = true).assertCountEquals(2)
        onAllNodesWithTag(MENU_GAP, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun aMenuOfPlainWordsIsLeftAsItWas() = runComposeUiTest {
        val res = AndroidRes.forLanguage("en")
        setContent {
            CompositionLocalProvider(LocalRes provides res) {
                MaterialTheme {
                    DropdownMenu(expanded = true, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text("First name") }, onClick = {})
                        DropdownMenuItem(text = { Text("Second name") }, onClick = {})
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(MENU_ICON, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag(MENU_GAP, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aMenuWhoseLinesAllSayTheSameStaysWithoutIcons() = runComposeUiTest {
        val res = AndroidRes.forLanguage("en")
        setContent {
            CompositionLocalProvider(LocalRes provides res) {
                MaterialTheme {
                    DropdownMenu(expanded = true, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text(res.str("action_delete")) }, onClick = {})
                        DropdownMenuItem(text = { Text(res.str("action_delete")) }, onClick = {})
                    }
                }
            }
        }
        waitForIdle()
        onAllNodesWithTag(MENU_ICON, useUnmergedTree = true).assertCountEquals(0)
    }
}
