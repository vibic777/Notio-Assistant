package com.notebudget.app.feature.folders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.notebudget.app.core.ui.tip.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

private data class FolderLook(
    val color: Color,
    val brush: Brush? = null,
    val content: Color,
    val border: BorderStroke? = null,
    val stripes: Boolean = false,
    val accentBar: Boolean = false,
    val compact: Boolean = false,
)

/**
 * Folder card (AGENT_EN.md §6.3, §12.2). The six [com.notebudget.app.domain.model.FolderStyleId]
 * styles are derived from the folder colour and the current theme, so they work in light and dark.
 * The selected state is shown by a check mark and semantics, not by colour alone (§12.4).
 */
@Composable
fun FolderCard(
    name: String,
    colorHex: String,
    styleId: String,
    icon: String?,
    noteCount: Int?,
    featured: com.notebudget.app.feature.home.FeaturedInfo? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    menu: (@Composable () -> Unit)? = null,
    pinned: Boolean = false,
    createdAt: Long = 0,
    updatedAt: Long = 0,
    dragHandle: (@Composable () -> Unit)? = null,
    /** The comment to show as the last line (D177), already cut to 16 characters; `null` = none. */
    comment: String? = null,
) {
    val base = parseColorOrDefault(colorHex)
    val surface = MaterialTheme.colorScheme.surface
    val themeOnSurface = MaterialTheme.colorScheme.onSurface
    // D273: as strong as the setting, a little stronger than a note card.
    val soft = base.copy(alpha = com.notebudget.app.core.datastore.CardTint.folder(com.notebudget.app.core.ui.LocalCardTint.current)).compositeOver(surface)
    // The text on the soft colour stays readable however strong the setting makes it (D273).
    val onSurface = com.notebudget.app.core.ui.readableOn(soft, themeOnSurface)

    val look = when (styleId) {
        "MINIMAL" -> FolderLook(MaterialTheme.colorScheme.surfaceVariant, content = themeOnSurface, accentBar = true)
        "GRADIENT" -> FolderLook(
            color = base,
            brush = Brush.horizontalGradient(listOf(base, base.copy(alpha = 0.6f).compositeOver(surface))),
            content = if (base.luminance() > 0.5f) Color.Black else Color.White,
        )
        "PATTERN" -> FolderLook(soft, content = onSurface, stripes = true)
        "OUTLINE" -> FolderLook(surface, content = themeOnSurface, border = BorderStroke(2.dp, base))
        "COMPACT" -> FolderLook(soft, content = onSurface, compact = true)
        else -> FolderLook(soft, content = onSurface)
    }
    val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else look.border

    // From the top (D162): a row of three small buttons - select, move, menu - spread apart so the grip is never grabbed by
    // accident; then the icon and the name (two lines); then the count; then, when Settings switch them on, the time stamps.
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .then(if (look.brush != null) Modifier.background(look.brush) else Modifier)
                .then(if (look.stripes) Modifier.drawBehind { drawStripes(base) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (look.accentBar) {
                Box(Modifier.width(6.dp).fillMaxHeight().background(base))
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    // Select: shows whether this folder is the open one, and opens it.
                    Box(
                        Modifier.size(36.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (selected) look.content else look.content.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_selected), tint = look.color, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    dragHandle?.invoke()
                    menu?.invoke()
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp, top = 2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(if (look.compact) 28.dp else 34.dp)
                            .clip(CircleShape)
                            .background(look.content.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = icon?.takeIf { it.isNotBlank() } ?: name.trim().take(1).uppercase(),
                            color = look.content,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (pinned) "📌 " + name else name,
                        color = look.content,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = true),
                    )
                }
                if (noteCount != null) {
                    Text(
                        text = pluralStringResource(R.plurals.notes_count, noteCount, noteCount),
                        color = look.content.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (createdAt > 0 || updatedAt > 0) {
                    com.notebudget.app.core.ui.StampLines(createdAt, updatedAt, Modifier.padding(bottom = 6.dp), color = look.content.copy(alpha = 0.75f), folder = true)
                } else {
                    Spacer(Modifier.height(6.dp))
                }
                if (comment != null) {
                    Text(
                        text = comment,
                        color = look.content.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }

    val shape = RoundedCornerShape(16.dp)
    val semanticsModifier = modifier.semantics { this.selected = selected }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = semanticsModifier,
            shape = shape,
            color = look.color,
            contentColor = look.content,
            border = border,
            content = content,
        )
    } else {
        Surface(
            modifier = semanticsModifier.fillMaxWidth(),
            shape = shape,
            color = look.color,
            contentColor = look.content,
            border = border,
            content = content,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStripes(color: Color) {
    val step = 14.dp.toPx()
    val stroke = 3.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color.copy(alpha = 0.18f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
        x += step
    }
}

/** The featured note on a folder card (D121, §6.4): its title, and for a budget the chosen values, compact or one per line. */
@Composable
private fun FeaturedLines(info: com.notebudget.app.feature.home.FeaturedInfo, color: Color) {
    val style = MaterialTheme.typography.bodySmall
    Text("\u2605 " + info.title, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val values = listOfNotNull(
        info.sumMinor?.let { stringResource(R.string.featured_metric_sum) + " " + com.notebudget.app.core.common.Money.format(it) },
        info.goalMinor?.let { stringResource(R.string.featured_metric_goal) + " " + com.notebudget.app.core.common.Money.format(it) },
        info.remainderMinor?.let { stringResource(R.string.featured_metric_remainder) + " " + com.notebudget.app.core.common.Money.format(it) },
    )
    when (info.layout) {
        com.notebudget.app.domain.model.FeaturedLayout.TITLE_ONLY -> Unit
        com.notebudget.app.domain.model.FeaturedLayout.COMPACT ->
            if (values.isNotEmpty()) Text(values.joinToString(" \u00B7 "), color = color.copy(alpha = 0.9f), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        com.notebudget.app.domain.model.FeaturedLayout.FULL ->
            values.forEach { Text(it, color = color.copy(alpha = 0.9f), style = style) }
    }
}
