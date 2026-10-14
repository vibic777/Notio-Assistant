package com.notebudget.app.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

/**
 * The two tabs of every note screen (D108): a read-only View first, and Edit beside it. Since D276 they are icons — an open
 * book for View, a pencil writing on a line for Edit — because the words did not fit beside the zoom on a phone; TalkBack still
 * reads their names and whether they are chosen, and a long press shows the name as a tooltip.
 */
@Composable
fun ViewEditTabs(
    editing: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    zoom: ZoomControl? = LocalZoomControl.current,
    /** A small button of the note before the zoom (the eye of a budget with hidden amounts, D272). */
    extra: (@Composable () -> Unit)? = null,
) {
    // The row is not scaled by the note's zoom, and the zoom buttons stand at its end (D142).
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides (zoom?.density ?: androidx.compose.ui.platform.LocalDensity.current),
    ) {
        Row(
            modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The tabs give way on a narrow screen, never the buttons after them (D270: the + of the zoom was cut off).
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTab(ViewIcon, stringResource(R.string.md_mode_preview), selected = !editing) { onChange(false) }
                IconTab(EditIcon, stringResource(R.string.md_mode_edit), selected = editing) { onChange(true) }
            }
            // The eye of a note that can hide its content (D363).
            com.notebudget.app.core.ui.HideEye(editing)
            extra?.invoke()
            if (zoom != null) ZoomControls(zoom)
        }
    }
}

/** One tab as an icon chip: its [name] is what TalkBack reads and what the tooltip of a long press shows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconTab(icon: ImageVector, name: String, selected: Boolean, onClick: () -> Unit) {
    // The app's own hint (D334): five seconds and a press on it opens the help, as on every other icon.
    com.notebudget.app.core.ui.tip.HintBox(name) {
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
