package com.notebudget.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.notebudget.app.feature.notes.markdown.MarkdownEditor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The checklist lines of the Markdown View (D235) with real gestures: 0.9.46 froze as soon as a line was dragged (a swap
 * made the opposite swap due, for ever); this drags one down past its neighbours and checks the text that comes out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownViewChecklistTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(initial: String): () -> String {
        var text by mutableStateOf(initial)
        rule.setContent {
            MaterialTheme {
                // The confirmation is a real dialog window: it holds no text field, so Robolectric gets idle (D202).
                MarkdownEditor(text, { text = it })
            }
        }
        return { text }
    }

    @Test
    fun draggingTheFirstLineDownMovesItWithItsSubLines() {
        val text = show("- [ ] a\n  - [ ] a1\n- [ ] b\n- [ ] c")
        // Grips of the top level (a, b, c); a1 has no neighbour of its level, so it has none.
        val grips = rule.onAllNodesWithContentDescription("Drag to move")
        grips.assertCountEquals(3)
        grips[0].performTouchInput { swipeDown(startY = centerY, endY = centerY + 2000f, durationMillis = 600) }
        rule.waitForIdle()
        assertEquals("- [ ] b\n- [ ] c\n- [ ] a\n  - [ ] a1", text())
    }

    @Test
    fun aLineDroppedOnTheCentreOfAnotherTakesItsPlaceExactly() {
        val lines = (1..30).map { "- [ ] item $it" }
        val text = show(lines.joinToString("\n"))
        val grips = rule.onAllNodesWithContentDescription("Drag to move")
        val from = grips[2].centre()
        val to = grips[7].centre()
        grips[2].performTouchInput {
            down(center)
            // Small steps, as a finger moves; the whole way is the distance between the two grips.
            val steps = 40
            repeat(steps) { moveBy(androidx.compose.ui.geometry.Offset(0f, (to.y - from.y) / steps)) }
            up()
        }
        rule.waitForIdle()
        val expected = lines.toMutableList().also { it.add(7, it.removeAt(2)) }
        assertEquals(expected.joinToString("\n"), text())
    }

    @Test
    fun inALongScrolledListALineLandsWhereItIsDropped() {
        // The user's case: a list of hundreds of lines, a line dragged to just before line 255.
        val lines = (1..300).map { "- [ ] item $it" }
        val text = show(lines.joinToString("\n"))
        val grips = rule.onAllNodesWithContentDescription("Drag to move")
        grips[250].performScrollTo()
        rule.waitForIdle()
        val from = grips[250].centre()
        val to = grips[254].centre()
        grips[250].performTouchInput {
            down(center)
            repeat(40) { moveBy(androidx.compose.ui.geometry.Offset(0f, (to.y - from.y) / 40)) }
            up()
        }
        rule.waitForIdle()
        val expected = lines.toMutableList().also { it.add(254, it.removeAt(250)) }
        assertEquals(expected.joinToString("\n"), text())
        // A swap of two neighbours is not undone on screen by the drag before it (the "went back on the first try" bug).
        val again = rule.onAllNodesWithContentDescription("Drag to move")
        rule.onNodeWithText("item 251").assertExists()
        again[253].performScrollTo()
        rule.waitForIdle()
        val a = again[253].centre()
        val b = again[254].centre()
        again[253].performTouchInput {
            down(center)
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, (b.y - a.y) / 20)) }
            up()
        }
        rule.waitForIdle()
        expected.add(254, expected.removeAt(253))
        assertEquals(expected.joinToString("\n"), text())
    }

    @Test
    fun aSecondDragWorksAsWellAsTheFirst() {
        val lines = (1..10).map { "- [ ] item $it" }
        val text = show(lines.joinToString("\n"))
        fun drag(from: Int, to: Int) {
            val grips = rule.onAllNodesWithContentDescription("Drag to move")
            val a = grips[from].centre()
            val b = grips[to].centre()
            grips[from].performTouchInput {
                down(center)
                repeat(30) { moveBy(androidx.compose.ui.geometry.Offset(0f, (b.y - a.y) / 30)) }
                up()
            }
            rule.waitForIdle()
        }
        val expected = lines.toMutableList()
        drag(0, 3); expected.add(3, expected.removeAt(0))
        assertEquals(expected.joinToString("\n"), text())
        drag(5, 1); expected.add(1, expected.removeAt(5))
        assertEquals(expected.joinToString("\n"), text())
    }

    @Test
    fun deletingALineAsksFirstAndRemovesItsSubLines() {
        val text = show("- [ ] a\n  - [ ] a1\n- [ ] b")
        rule.onAllNodesWithContentDescription("Delete the line")[0].performClick()
        rule.onNodeWithText("Delete this line?").assertExists()
        assertEquals("- [ ] a\n  - [ ] a1\n- [ ] b", text()) // nothing yet
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals("- [ ] b", text())
    }
}

/** The middle of a node in root coordinates, also when it is outside the visible part (bounds are clipped there). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.centre(): androidx.compose.ui.geometry.Offset {
    val node = fetchSemanticsNode()
    return node.positionInRoot + androidx.compose.ui.geometry.Offset(node.size.width / 2f, node.size.height / 2f)
}
