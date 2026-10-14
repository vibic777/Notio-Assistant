package com.notio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android D321: the page of the edit field follows the cursor so that its line is the second from the top. */
@OptIn(ExperimentalTestApi::class)
class CursorFieldTest {
    @Test
    fun theLineOfTheCursorGoesSecondFromTheTop() {
        assertEquals(0, CursorFollow.target(lineTop = 0f, lineHeight = 20f))
        // The second line is where it is already.
        assertEquals(0, CursorFollow.target(lineTop = 20f, lineHeight = 20f))
        assertEquals(180, CursorFollow.target(lineTop = 200f, lineHeight = 20f))
    }

    @Test
    fun thePageFollowsTheCursorDownAndBackUp() = runComposeUiTest {
        val lines = (1..200).joinToString("\n") { "line $it" }
        var value by mutableStateOf(TextFieldValue(lines, TextRange(0)))
        setContent {
            MaterialTheme { Box(Modifier.size(400.dp, 300.dp)) { CursorField(value, { value = it }) } }
        }
        waitForIdle()
        fun scrolled(): Float = onNode(hasScrollAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.value()
        fun longest(): Float = onNode(hasScrollAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!!.maxValue()
        assertEquals(0f, scrolled())

        // The cursor is put on line 101: the page goes there, and one line before it stays in sight.
        value = value.copy(selection = TextRange(lines.indexOf("line 101")))
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val line = (longest() + 300f) / 200f
        val at = scrolled()
        assertTrue(at > 98 * line && at < 100.5f * line, "line 101 second from the top: scrolled $at, a line is about $line")

        // The same line again: nothing moves.
        value = value.copy(selection = TextRange(lines.indexOf("line 101") + 3))
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        assertEquals(at, scrolled())

        // Back to the first line: the page is at its beginning.
        value = value.copy(selection = TextRange(0))
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(0f, scrolled())
    }
}
