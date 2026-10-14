package com.notio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Quick scroll (Android D250): the buttons come while a long list moves, each only where there is somewhere to go; Home / End keys. */
@OptIn(ExperimentalTestApi::class)
class QuickScrollTest {
    @Test
    fun theButtonsGoToTheEndAndTheKeysBackToTheBeginning() = runComposeUiTest {
        val state = LazyListState()
        val en = AndroidRes.forLanguage("en")
        setContent {
            CompositionLocalProvider(LocalRes provides en) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize(), state = state) {
                            items(200) { Text("row $it", Modifier.height(40.dp)) }
                        }
                        QuickScroll(state)
                    }
                }
            }
        }
        val topLabel = en.str("quick_scroll_top")
        val bottomLabel = en.str("quick_scroll_bottom")
        waitForIdle()
        // Not moving: no buttons.
        assertEquals(0, onAllNodesWithContentDescription(bottomLabel).fetchSemanticsNodes().size)
        onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = top + 10f, durationMillis = 800) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription(bottomLabel).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithContentDescription(topLabel).fetchSemanticsNodes().isNotEmpty(), "somewhere to go up, too")
        onNodeWithContentDescription(bottomLabel).performClick()
        waitUntil(timeoutMillis = 5_000) { !state.canScrollForward }
        // Home (no text field took it) goes back to the beginning.
        runOnIdle { assertTrue(ScrollKeys.go(top = true)) }
        waitUntil(timeoutMillis = 5_000) { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }
    }
}
