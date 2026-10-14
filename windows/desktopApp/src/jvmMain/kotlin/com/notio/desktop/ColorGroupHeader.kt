package com.notio.desktop

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.ArgbColor
import com.notio.shared.domain.ColorFamily

fun ColorFamily.labelRes(): String = when (this) {
    ColorFamily.RED -> "color_family_red"
    ColorFamily.ORANGE -> "color_family_orange"
    ColorFamily.YELLOW -> "color_family_yellow"
    ColorFamily.GREEN -> "color_family_green"
    ColorFamily.CYAN -> "color_family_cyan"
    ColorFamily.BLUE -> "color_family_blue"
    ColorFamily.PURPLE -> "color_family_purple"
    ColorFamily.PINK -> "color_family_pink"
    ColorFamily.NEUTRAL -> "color_family_neutral"
    ColorFamily.NONE -> "color_family_none"
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
