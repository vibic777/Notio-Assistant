package com.notebudget.app.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.common.ArgbColor
import com.notebudget.app.domain.model.ColorFamily

@StringRes
fun ColorFamily.labelRes(): Int = when (this) {
    ColorFamily.RED -> R.string.color_family_red
    ColorFamily.ORANGE -> R.string.color_family_orange
    ColorFamily.YELLOW -> R.string.color_family_yellow
    ColorFamily.GREEN -> R.string.color_family_green
    ColorFamily.CYAN -> R.string.color_family_cyan
    ColorFamily.BLUE -> R.string.color_family_blue
    ColorFamily.PURPLE -> R.string.color_family_purple
    ColorFamily.PINK -> R.string.color_family_pink
    ColorFamily.NEUTRAL -> R.string.color_family_neutral
    ColorFamily.NONE -> R.string.color_family_none
}

/**
 * The header of one colour group when notes or folders are sorted by colour (D196): a dot in the colour of the first item of
 * the group ([sampleHex]) and the name of the family.
 */
@Composable
fun ColorGroupHeader(family: ColorFamily, sampleHex: String?, modifier: Modifier = Modifier) {
    val dot = sampleHex?.let { ArgbColor.parseHex(it) }?.let { Color(it) }
    Row(
        modifier.semantics { heading() }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = CircleShape
        val base = Modifier.size(12.dp)
        if (dot != null && family != ColorFamily.NONE) {
            androidx.compose.foundation.layout.Box(base.background(dot, shape).border(1.dp, MaterialTheme.colorScheme.outline, shape))
        } else {
            androidx.compose.foundation.layout.Box(base.border(1.dp, MaterialTheme.colorScheme.outline, shape))
        }
        Text(
            stringResource(family.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
