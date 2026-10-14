package com.notio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import kotlin.test.Test

/**
 * The checklist lines of the Markdown View (Android D235, D242, D245) with real gestures — the phone's
 * `MarkdownViewChecklistTest` on the desktop: a line dragged down past its neighbours, dropped exactly on another, in a long
 * scrolled list, a second drag, a delete that asks first. The texts are the English ones whatever the computer's language.
 */
@OptIn(ExperimentalTestApi::class)
class MarkdownViewChecklistTest {

    private fun show(ui: ComposeUiTest, initial: String): () -> String {
        var text by mutableStateOf(initial)
        ui.setContent {
            CompositionLocalProvider(LocalRes provides AndroidRes.forLanguage("en")) {
                MaterialTheme { MarkdownPreview(text, { text = it }, null) }
            }
        }
        return { text }
    }

    @Test
    fun draggingTheFirstLineDownMovesItWithItsSubLines() = runComposeUiTest {
        val text = show(this, "- [ ] a\n  - [ ] a1\n- [ ] b\n- [ ] c")
        // Grips of the top level (a, b, c); a1 has no neighbour of its level, so it has none.
        val grips = onAllNodesWithContentDescription("Drag to move")
        grips.assertCountEquals(3)
        grips[0].performTouchInput { swipeDown(startY = centerY, endY = centerY + 2000f, durationMillis = 600) }
        waitForIdle()
        assertEquals("- [ ] b\n- [ ] c\n- [ ] a\n  - [ ] a1", text())
    }

    @Test
    fun aLineDroppedOnTheCentreOfAnotherTakesItsPlaceExactly() = runComposeUiTest {
        val lines = (1..30).map { "- [ ] item $it" }
        val text = show(this, lines.joinToString("\n"))
        val grips = onAllNodesWithContentDescription("Drag to move")
        val from = grips[2].centre()
        val to = grips[7].centre()
        grips[2].performTouchInput {
            down(center)
            // Small steps, as a finger moves; the whole way is the distance between the two grips.
            val steps = 40
            repeat(steps) { moveBy(androidx.compose.ui.geometry.Offset(0f, (to.y - from.y) / steps)) }
            up()
        }
        waitForIdle()
        val expected = lines.toMutableList().also { it.add(7, it.removeAt(2)) }
        assertEquals(expected.joinToString("\n"), text())
    }

    @Test
    fun inALongScrolledListALineLandsWhereItIsDropped() = runComposeUiTest {
        // The user's case: a list of hundreds of lines, a line dragged to just before line 255.
        val lines = (1..300).map { "- [ ] item $it" }
        val text = show(this, lines.joinToString("\n"))
        val grips = onAllNodesWithContentDescription("Drag to move")
        grips[250].performScrollTo()
        waitForIdle()
        val from = grips[250].centre()
        val to = grips[254].centre()
        grips[250].performTouchInput {
            down(center)
            repeat(40) { moveBy(androidx.compose.ui.geometry.Offset(0f, (to.y - from.y) / 40)) }
            up()
        }
        waitForIdle()
        val expected = lines.toMutableList().also { it.add(254, it.removeAt(250)) }
        assertEquals(expected.joinToString("\n"), text())
        // A swap of two neighbours is not undone on screen by the drag before it (the "went back on the first try" bug).
        val again = onAllNodesWithContentDescription("Drag to move")
        onNodeWithText("item 251").assertExists()
        again[253].performScrollTo()
        waitForIdle()
        val a = again[253].centre()
        val b = again[254].centre()
        again[253].performTouchInput {
            down(center)
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, (b.y - a.y) / 20)) }
            up()
        }
        waitForIdle()
        expected.add(254, expected.removeAt(253))
        assertEquals(expected.joinToString("\n"), text())
    }

    @Test
    fun aSecondDragWorksAsWellAsTheFirst() = runComposeUiTest {
        val lines = (1..10).map { "- [ ] item $it" }
        val text = show(this, lines.joinToString("\n"))
        fun drag(from: Int, to: Int) {
            val grips = onAllNodesWithContentDescription("Drag to move")
            val a = grips[from].centre()
            val b = grips[to].centre()
            grips[from].performTouchInput {
                down(center)
                repeat(30) { moveBy(androidx.compose.ui.geometry.Offset(0f, (b.y - a.y) / 30)) }
                up()
            }
            waitForIdle()
        }
        val expected = lines.toMutableList()
        drag(0, 3); expected.add(3, expected.removeAt(0))
        assertEquals(expected.joinToString("\n"), text())
        drag(5, 1); expected.add(1, expected.removeAt(5))
        assertEquals(expected.joinToString("\n"), text())
    }

    @Test
    fun deletingALineAsksFirstAndRemovesItsSubLines() = runComposeUiTest {
        val text = show(this, "- [ ] a\n  - [ ] a1\n- [ ] b")
        onAllNodesWithContentDescription("Delete the line")[0].performClick()
        onNodeWithText("Delete this line?").assertExists()
        assertEquals("- [ ] a\n  - [ ] a1\n- [ ] b", text()) // nothing yet
        onNodeWithText("Delete").performClick()
        waitForIdle()
        assertEquals("- [ ] b", text())
    }

    /** A picture of the View with checklist lines (grips, boxes, Delete) for looking through: `build/screenshots/4b-markdown-checklist.png`. */
    @Test
    fun theViewWithChecklistLinesIsDrawn() {
        val text = "# Shopping\n- [ ] milk\n- [x] bread\n  - [ ] rye\n  - [ ] white\n- [ ] apples\n\nPlain text after the list."
        val scene = androidx.compose.ui.ImageComposeScene(700, 420, androidx.compose.ui.unit.Density(1f)) {
            CompositionLocalProvider(LocalRes provides AndroidRes.forLanguage("en")) {
                MaterialTheme { androidx.compose.material3.Surface { MarkdownPreview(text, {}, null) } }
            }
        }
        scene.render(0)
        val image = scene.render(500_000_000)
        val out = java.io.File("build/screenshots/4b-markdown-checklist.png").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        kotlin.test.assertTrue(out.length() > 0)
    }
}

/** The middle of a node in root coordinates, also when it is outside the visible part (bounds are clipped there). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.centre(): androidx.compose.ui.geometry.Offset {
    val node = fetchSemanticsNode()
    return node.positionInRoot + androidx.compose.ui.geometry.Offset(node.size.width / 2f, node.size.height / 2f)
}
