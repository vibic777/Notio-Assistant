package com.notio.desktop

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.Comment
import com.notio.shared.domain.Folder
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.Tag
import java.io.File

private data class FolderLook(
    val color: Color,
    val brush: Brush? = null,
    val content: Color,
    val border: BorderStroke? = null,
    val stripes: Boolean = false,
    val accentBar: Boolean = false,
    val compact: Boolean = false,
)

fun parseColorOrDefault(hex: String?, default: Color = Color(0xFF3366FF)): Color = Format.color(hex) ?: default

/**
 * Folder card, the Android one (feature/folders/FolderCard.kt, §12.2): the six styles MINIMAL / SOFT / GRADIENT / PATTERN /
 * OUTLINE / COMPACT from the folder colour and the theme; the open folder has a check mark and a frame of the accent colour.
 */
@Composable
fun FolderCard(
    s: Strings, folder: Folder, noteCount: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    menu: (@Composable () -> Unit)? = null,
    featured: FeaturedInfo? = null,
    /** The grip to drag the card to another place (D141); none while searching. */
    dragHandle: (@Composable () -> Unit)? = null,
    /** The items of the ⋮ menu, offered by a screen reader as the card's own actions (Android D274 step 2); the button stays. */
    readerActions: List<Pair<String, (() -> Unit)?>> = emptyList(),
) {
    val base = parseColorOrDefault(folder.colorHex)
    val surface = MaterialTheme.colorScheme.surface
    val themeOnSurface = MaterialTheme.colorScheme.onSurface
    // Android D273: as strong as the setting, a little stronger than a note card; the text stays readable on it.
    val soft = base.copy(alpha = com.notio.shared.domain.CardTint.folder(LocalCardTint.current)).compositeOver(surface)
    val onSurface = readableOn(soft, themeOnSurface)
    val look = when (folder.styleId) {
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
    val cardActions = readerCustomActions(readerActions)
    Surface(onClick = onClick, modifier = modifier.semantics { if (cardActions.isNotEmpty()) customActions = cardActions }, shape = RoundedCornerShape(16.dp), color = look.color, contentColor = look.content, border = border) {
        Row(
            Modifier.height(IntrinsicSize.Min)
                .then(if (look.brush != null) Modifier.background(look.brush) else Modifier)
                .then(if (look.stripes) Modifier.drawBehind { drawStripes(base) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (look.accentBar) Box(Modifier.width(6.dp).fillMaxHeight().background(base))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                // The select circle of the phone's card (filled with a check mark on the open folder) and the menu at the other end.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (selected) look.content else look.content.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = look.color, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                dragHandle?.invoke()
                menu?.invoke()
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Box(
                        Modifier.size(if (look.compact) 28.dp else 34.dp).clip(CircleShape).background(look.content.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(folder.icon?.takeIf { it.isNotBlank() } ?: folder.name.trim().take(1).uppercase(), color = look.content, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (folder.isPinned) Glyphs.pin + " " + folder.name else folder.name,
                        color = look.content, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(s.notesCount(noteCount), color = look.content.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                featured?.let { FeaturedLines(it, look.content) }
                StampLines(folder.createdAt, folder.updatedAt, color = look.content.copy(alpha = 0.75f), folder = true)
                if (LocalCommentDisplay.current.folders) Comment.short(folder.comment)?.let {
                    Text(it, color = look.content.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

/** The featured note on a folder card (D121, §6.4): its title, and for a budget the chosen values, compact or one per line. */
@Composable
private fun FeaturedLines(info: FeaturedInfo, color: Color) {
    val style = MaterialTheme.typography.bodySmall
    Text("\u2605 " + info.title, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val values = listOfNotNull(
        info.sumMinor?.let { stringResource("featured_metric_sum") + " " + com.notio.shared.domain.Money.format(it) },
        info.goalMinor?.let { stringResource("featured_metric_goal") + " " + com.notio.shared.domain.Money.format(it) },
        info.remainderMinor?.let { stringResource("featured_metric_remainder") + " " + com.notio.shared.domain.Money.format(it) },
    )
    when (info.layout) {
        com.notio.shared.domain.FeaturedLayout.TITLE_ONLY -> Unit
        com.notio.shared.domain.FeaturedLayout.COMPACT ->
            if (values.isNotEmpty()) Text(values.joinToString(" \u00B7 "), color = color.copy(alpha = 0.9f), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        com.notio.shared.domain.FeaturedLayout.FULL -> values.forEach { Text(it, color = color.copy(alpha = 0.9f), style = style) }
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

/**
 * Note card, the Android one (feature/notes/NoteCard.kt, §12.1): tinted with the note's colour or its folder's, pin and bell,
 * the title (bold if chosen), the kind, the tags, a piece of the View (never raw syntax), the comment.
 */
@Composable
fun NoteCard(
    s: Strings,
    note: Note,
    folderColorHex: String?,
    tags: List<Tag>,
    hasReminder: Boolean,
    picture: File?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: (@Composable () -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
    /** The selection mode (D167): a check box in front, a click picks the note instead of opening it. */
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
    /** The key of the title for the search that marks it (the trash, D253); `null`: no marks. */
    titleSearchKey: Any? = null,
    /** The items of the ⋮ menu, offered by a screen reader as the card's own actions (Android D274 step 2); the button stays. */
    readerActions: List<Pair<String, (() -> Unit)?>> = emptyList(),
    /** The compact list (Android D300): the name alone on one line — no kind, tags, preview, stamps or comment. */
    compact: Boolean = false,
    /** The note to the clipboard (D143, Android D362): the icon of two sheets on the card; `null` - none (the trash). */
    onCopy: (() -> Unit)? = null,
) {
    val tint = parseColorOrDefault(note.colorHex ?: folderColorHex)
    // As strong as Settings → Appearance → Card colour says (Android D273); the text follows so it stays readable on a strong colour.
    val container = tint.copy(alpha = LocalCardTint.current).compositeOver(MaterialTheme.colorScheme.surface)
    // A budget that hides its amounts has an eye on its card (Android D306): the amounts of THIS card are shown until the eye is
    // pressed again or the list is left; nothing is stored.
    val hidesAmounts = !compact && note.type == NoteType.BUDGET &&
        remember(note.extras) { com.notio.shared.domain.BudgetPrivacy.of(com.notio.shared.domain.BudgetLayout.parse(note.extras)).hidesAnything }
    var amountsShown by remember(note.id) { androidx.compose.runtime.mutableStateOf(false) }
    // Copy on the card (D143): the kinds with a text or a picture of their own; a task has it in its line. Hidden amounts are not copied.
    // A note that hides its content (D144, Android D363): the card shows a line instead of the preview, and an eye that shows it
    // for THIS card until it is pressed again or the list is left; nothing is stored.
    val hidesContent = !compact && note.hidden && note.type in HIDEABLE_KINDS
    var contentShown by remember(note.id) { androidx.compose.runtime.mutableStateOf(false) }
    val copyable = onCopy != null && !compact && note.type in COPY_ON_CARD && (!hidesAmounts || amountsShown) && (!hidesContent || contentShown)
    ReadableOn(container) {
    // The card is read as one message (its texts are merged by the click); its menu comes as the reader's actions (Android D274 step 2).
    val cardActions = readerCustomActions(readerActions)
    Card(onClick = if (selecting) onToggle else onClick, modifier = modifier.fillMaxWidth().semantics { if (!selecting && cardActions.isNotEmpty()) customActions = cardActions }, colors = CardDefaults.cardColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface)) {
        Row(Modifier.padding(start = if (selecting) 4.dp else 16.dp, top = if (compact) 0.dp else 8.dp, bottom = if (compact) 0.dp else 8.dp, end = if (menu != null) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (note.isPinned) {
                        val pinned = stringResource("cd_pinned")
                        Text(Glyphs.pin, Modifier.semantics { contentDescription = pinned })
                    }
                    if (hasReminder) Icon(Icons.Filled.Notifications, contentDescription = s.reminder, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(
                        if (titleSearchKey != null) highlighted(note.title, titleSearchKey) else androidx.compose.ui.text.AnnotatedString(note.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (note.isTitleBold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact) Text(s.kind(note.type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!compact && tags.isNotEmpty()) {
                    Text(tags.joinToString("  ") { "#" + it.name }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hidesContent && !contentShown) HiddenLine()
                else if (!compact) NotePreview(note, picture, amountsShown)
                if (!compact) StampLines(note.createdAt, note.updatedAt)
                if (!compact && LocalCommentDisplay.current.notes) Comment.short(note.comment)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (!selecting && (dragHandle != null || hidesAmounts || copyable || hidesContent)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    dragHandle?.invoke()
                    if (copyable && onCopy != null) CopyIconButton(small = true, onClick = onCopy)
                    if (hidesContent) {
                        IconAction(
                            if (contentShown) AppIcons.VisibilityOff else AppIcons.Visibility,
                            stringResource(if (contentShown) "note_hide_again" else "note_hide_show"),
                            onClick = { contentShown = !contentShown },
                            modifier = Modifier.testTag(HIDE_EYE),
                        )
                    }
                    if (hidesAmounts) {
                        IconAction(
                            if (amountsShown) AppIcons.VisibilityOff else AppIcons.Visibility,
                            stringResource(if (amountsShown) "budget_hide_amounts" else "budget_show_amounts"),
                            onClick = { amountsShown = !amountsShown },
                        )
                    }
                }
            }
            if (!selecting) menu?.invoke()
        }
    }
    }
}

/** The first two lines of the View (D159) or, for a drawing or photo, its picture (D160). */
@Composable
private fun NotePreview(note: Note, picture: File?, budgetRevealed: Boolean = false) {
    // Settings -> Appearance: the picture of a drawing on or off, of a photo / video / screen note none or the first (D160).
    val drawing = note.type == NoteType.DRAWING || note.type == NoteType.BLUEPRINT
    val showPicture = if (drawing) LocalDrawingPreview.current else LocalMediaPreview.current != com.notio.shared.domain.MediaPreviewMode.NONE
    if (drawing) {
        if (showPicture) DrawingPreview(note)
        return
    }
    if (picture != null && showPicture) {
        val bitmap = rememberPicture(picture, CARD_PICTURE_PX)
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(96.dp).padding(top = 4.dp))
            return
        }
    }
    if (note.type == NoteType.BUDGET) {
        // The total of the budget (Android D159); hidden sums / goals are stars on the card, only the eye inside the note shows them (D272).
        val node = LocalBudgetRoots.current[note.id] ?: return
        val count = LocalPreviewLines.current
        if (count == 0) return
        val layout = remember(note.extras) { com.notio.shared.domain.BudgetLayout.parse(note.extras) }
        val privacy = remember(layout, budgetRevealed) { com.notio.shared.domain.BudgetPrivacy.of(layout, budgetRevealed) }
        if (count >= 2) Text(stringResource("budget_total"), style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        // The sum, the goal and the remainder each on a line of its own (Android D302).
        Text(stringResource("budget_sum") + " " + privacy.sum(node.cachedSumMinor, layout.currencyLabel), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        // An income and an expense are a sum alone (Android D326).
        if (privacy.goals) Text(stringResource("budget_goal") + " " + privacy.goal(node.cachedGoalMinor, layout.currencyLabel), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (privacy.goals) Text(
            stringResource("budget_remainder", privacy.remainder(node.cachedRemainderMinor, layout.currencyLabel)),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            color = if (node.cachedRemainderMinor < 0 && !privacy.remainderMasked) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
        )
        return
    }
    // A task on its card (D239): its state and its start and end, then the first lines of its description.
    if (note.type == NoteType.TASK) {
        val task = remember(note.extras) { com.notio.shared.domain.TaskDocument.decode(note.extras) }
        // The status is chosen and the priority set on the card (D117); not in the trash.
        val change = LocalTaskChange.current?.takeIf { note.deletedAt == null }
        // The bin of the task (D142), where the card can change it.
        val delete = LocalTaskDelete.current?.takeIf { change != null }
        TaskCardLine(
            task, change?.let { save -> { t: com.notio.shared.domain.TaskDocument -> save(note.id, t) } }, onDelete = delete?.let { ask -> { ask(note.id) } },
            copy = taskCopyText(note.title, task, note.contentText.orEmpty()),
        )
        taskSpan(task)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    val count = LocalPreviewLines.current
    val lines = remember(note.contentText, count) {
        note.contentText.orEmpty().lineSequence().map { Markdown.preview(it) }.filter { it.text.isNotBlank() }.take(count).toList()
    }
    lines.forEach { line ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (line.checked != null) {
                // A small check box, drawn (a text glyph would come out as a coloured emoji on Windows).
                Box(
                    Modifier.padding(end = 6.dp).size(14.dp).clip(RoundedCornerShape(3.dp))
                        .then(
                            if (line.checked) Modifier.background(MaterialTheme.colorScheme.primary)
                            else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(3.dp)),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (line.checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
            Text(line.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The picture of a drawing or blueprint on its card (the phone's `DrawingPreview`): made off the UI thread and kept until the note
 * changes; a blueprint's shapes are drawn first. The sheet is transparent, so it lies on white paper with a thin edge, in the sheet's
 * own proportions — on the tinted card the white fills and the paper would vanish and only the lines be left (Android D161).
 */
@Composable
private fun DrawingPreview(note: Note) {
    val controller = LocalController.current
    // What the cache has is shown at once (a card scrolled back in does not blink); a changed drawing is made again off the UI thread.
    val first = remember(note.id) { controller.drawingPictureNow(note.id) }
    val picture = androidx.compose.runtime.produceState(first, note.id, note.updatedAt) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { controller.drawingPicture(note) }
    }
    picture.value?.let {
        Image(
            it, contentDescription = stringResource("drawing_sheet"), contentScale = ContentScale.Fit,
            modifier = Modifier.padding(top = 4.dp).height(96.dp)
                .aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1))
                .background(androidx.compose.ui.graphics.Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        )
    }
}

/** The menu items a screen reader offers as a card's actions; separators and items that do not apply now are left out. */
internal fun readerCustomActions(items: List<Pair<String, (() -> Unit)?>>): List<CustomAccessibilityAction> =
    items.mapNotNull { (label, action) -> action?.takeIf { label != "-" }?.let { a -> CustomAccessibilityAction(label) { a(); true } } }

/** The kinds whose card has Copy (D143, Android D362). */
private val COPY_ON_CARD = setOf(NoteType.TEXT, NoteType.WORD, NoteType.LIST, NoteType.BUDGET, NoteType.DRAWING, NoteType.BLUEPRINT)
