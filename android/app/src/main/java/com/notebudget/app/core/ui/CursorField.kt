package com.notebudget.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Where the line of the cursor goes when the cursor comes onto another line (D321): to the second line from the top — one
 * line of what stands before it stays in sight — as far as the text lets the page go.
 */
object CursorFollow {
    /** The scroll position that puts the line at [lineTop] with the height [lineHeight] second from the top; never negative. */
    fun target(lineTop: Float, lineHeight: Float): Int = (lineTop - lineHeight).toInt().coerceAtLeast(0)
}

/**
 * The field in which a text or Markdown note is edited (D321; owner, 2026-10-06: "when the cursor is put lower, lift the page
 * so that the cursor of the input is on the second line from the top — the input is then nearly always in sight"). It looks
 * like the outlined field it replaces; the difference is that the page under it is its own, so it can be moved: every time the
 * cursor comes onto another line — a tap, Enter, a jump of the search or of the quick scroll — the page goes so that this line
 * is the second from the top. Nothing moves while the cursor stays on its line, while a selection is dragged, or while the
 * page is scrolled by hand to read elsewhere.
 */
@Composable
fun CursorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val current by rememberUpdatedState(value)
    // The last layout of the text and the line the cursor was on: kept beside the composition, a layout must not compose again.
    val seen = remember { object { var layout: TextLayoutResult? = null; var line = -1 } }

    fun follow() {
        val layout = seen.layout ?: return
        val selection = current.selection
        if (!selection.collapsed) return
        val line = layout.getLineForOffset(selection.start.coerceIn(0, layout.layoutInput.text.length))
        if (line == seen.line) return
        val first = seen.line < 0
        seen.line = line
        // The note has just been opened with the cursor at its beginning: nothing to move.
        if (first && line == 0) return
        val top = layout.getLineTop(line)
        val target = CursorFollow.target(top, layout.getLineBottom(line) - top)
        scope.launch {
            // After the field has asked for its cursor to be in sight (it asks for the nearest place): this is the last word.
            withFrameNanos { }
            scroll.animateScrollTo(target.coerceAtMost(scroll.maxValue))
        }
    }
    // The cursor moved without the text changing (a tap, the search, the quick scroll): the layout is the same one.
    LaunchedEffect(value.selection) { follow() }

    val color = textStyle.color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 16.dp),
    ) {
        val viewport = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // As tall as the page at least, so a tap anywhere under a short text lands in the field.
                modifier = modifier.fillMaxWidth().heightIn(min = viewport).padding(vertical = 16.dp),
                textStyle = textStyle.copy(color = color),
                visualTransformation = visualTransformation,
                onTextLayout = { seen.layout = it; follow() },
                interactionSource = interaction,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (value.text.isEmpty() && placeholder != null) {
                        ProvideTextStyle(textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) { placeholder() }
                    }
                    inner()
                },
            )
        }
    }
}
