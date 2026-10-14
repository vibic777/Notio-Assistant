package com.notio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The two tabs of every note screen (D108): a read-only View first, and Edit beside it. */
@Composable
fun ViewEditTabs(
    editing: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, zoom: ZoomControl? = LocalZoomControl.current,
    /** A small button of the note before the zoom (Copy of a drawing, D143). */
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
            // The tabs give way in a narrow window, never the zoom buttons after them (Android D270).
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icons since Android D276: a book for View, a pencil for Edit; their names on hover and for a screen reader.
                IconTab(ViewIcon, stringResource("md_mode_preview"), selected = !editing) { onChange(false) }
                IconTab(EditIcon, stringResource("md_mode_edit"), selected = editing) { onChange(true) }
            }
            // The eye of a note that can hide its content (D144, Android D363).
            HideEye(editing)
            extra?.invoke()
            if (zoom != null) ZoomControls(zoom)
        }
    }
}
