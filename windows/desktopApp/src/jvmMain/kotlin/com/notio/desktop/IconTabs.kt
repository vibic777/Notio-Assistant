package com.notio.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

/**
 * One tab of View / Edit as an icon (Android D276: the words did not fit beside the zoom): its [name] is what a screen reader says,
 * with "tab" and whether it is chosen, and what the mouse shows when it rests on it.
 */
@Composable
fun IconTab(icon: ImageVector, name: String, selected: Boolean, onClick: () -> Unit) {
    HoverTip(name) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
            modifier = Modifier.semantics {
                contentDescription = name
                role = Role.Tab
            },
        )
    }
}

/** An open book: "read the note" (View). Drawn as an outline, like the other small icons of the note screens. */
internal val ViewIcon: ImageVector by lazy {
    ImageVector.Builder("ViewBook", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString(
                // The two pages, curved down to the spine, and the spine itself.
                "M12,7C10.3,5.6 7.6,5 4.5,5.3L3,5.5V19L4.5,18.8C7.6,18.5 10.3,19.1 12,20.5" +
                    "C13.7,19.1 16.4,18.5 19.5,18.8L21,19V5.5L19.5,5.3C16.4,5 13.7,5.6 12,7Z" +
                    "M12,7V20.5",
            ).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        // Two lines of text on the left page and one on the right, so it reads as a book with writing in it.
        addPath(
            PathParser().parsePathString("M5.5,9.3C7.2,9.1 8.7,9.3 9.8,9.9M5.5,12.5C7.2,12.3 8.7,12.5 9.8,13.1M14.2,9.9C15.3,9.3 16.8,9.1 18.5,9.3").toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
        )
    }.build()
}

/** A pencil writing on a line: "change the note" (Edit). */
internal val EditIcon: ImageVector by lazy {
    ImageVector.Builder("EditPencil", 24.dp, 24.dp, 24f, 24f).apply {
        // The pencil, its tip at the bottom left.
        addPath(
            PathParser().parsePathString(
                "M4,16.9V20H7.1L16.6,10.5L13.5,7.4Z" +
                    "M18.9,8.2C19.3,7.8 19.3,7.2 18.9,6.8L17.2,5.1C16.8,4.7 16.2,4.7 15.8,5.1L14.6,6.3L17.7,9.4Z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
        // The line it writes, to the right of the tip.
        addPath(
            PathParser().parsePathString("M11,20H20").toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        )
    }.build()
}

/**
 * A button that is only an icon (Android D278): a screen reader says its [label], the mouse resting on it shows the [label], so an
 * icon never leaves anyone guessing. Dialog buttons, rare and dangerous actions and buttons that show a value keep their words.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
    topic: String? = null,
) {
    HoverTip(label, topic = topic) {
        androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}

/**
 * One of a few things to choose from, shown as its icon (Android D298): the chosen one is filled. The mouse resting on it shows
 * the [label], a screen reader says it; the dialog also writes the chosen [label] in words beside the icons.
 */
@Composable
fun IconChoice(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    HoverTip(label) {
        // One of several (a radio button for a screen reader), not a switch: the chosen one is filled with the main colour.
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick, enabled = enabled,
            modifier = modifier.semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.RadioButton },
            colors = if (selected) androidx.compose.material3.IconButtonDefaults.filledIconButtonColors() else androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(),
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}
