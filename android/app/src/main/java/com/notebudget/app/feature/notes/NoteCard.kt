package com.notebudget.app.feature.notes

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.notebudget.app.core.ui.tip.DropdownMenu
import com.notebudget.app.core.ui.tip.DropdownMenuItem
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.folders.parseColorOrDefault

@Composable
fun noteTypeLabel(type: NoteType): String =
    stringResource(rememberAppContainer().noteTypes.definitionFor(type).labelRes)

/** Actions of the note overflow menu. */
/** The kinds whose card has Copy (D362). */
private val COPY_ON_CARD = setOf(
    com.notebudget.app.domain.model.NoteType.TEXT, com.notebudget.app.domain.model.NoteType.WORD, com.notebudget.app.domain.model.NoteType.LIST,
    com.notebudget.app.domain.model.NoteType.BUDGET, com.notebudget.app.domain.model.NoteType.DRAWING, com.notebudget.app.domain.model.NoteType.BLUEPRINT,
)

data class NoteActions(
    val onTogglePinned: () -> Unit,
    val onToggleBoldTitle: () -> Unit,
    val onColor: () -> Unit,
    val onTags: () -> Unit,
    val onReminder: () -> Unit,
    val onMove: () -> Unit,
    val onDelete: () -> Unit,
    val onComment: () -> Unit = {},
    val onClone: () -> Unit = {},
    /** The phone's share sheet (D216). */
    val onShare: () -> Unit = {},
    /** The note to the clipboard (D362): the icon of two sheets on the card. */
    val onCopy: () -> Unit = {},
)

/**
 * Note card (§12.1): title (bold if `isTitleBold`), type, preview, pin and favourite markers.
 * A transparent note colour inherits the folder colour (D56).
 */
@Composable
fun NoteCard(
    note: Note,
    folderColorHex: String,
    actions: NoteActions,
    tags: List<com.notebudget.app.domain.model.Tag> = emptyList(),
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** The grip for dragging the card to another place (D141); `null` while the order cannot be changed. */
    dragHandle: (@Composable () -> Unit)? = null,
    /** The "Select" mode of a folder (D167): a check box instead of the menu, and a tap ticks the card instead of opening it. */
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
    /** A reminder is waiting for this note (D169): a small bell next to the name; when and how is in the note's menu. */
    hasReminder: Boolean = false,
    /** The compact list (D300): the name alone on one line — no kind, tags, preview, stamps or comment. */
    compact: Boolean = false,
) {
    val tint = parseColorOrDefault(note.colorHex ?: folderColorHex)
    // As strong as Settings → Appearance → Card colour says (D273); the text follows so it stays readable on a strong colour.
    val container = tint.copy(alpha = com.notebudget.app.core.ui.LocalCardTint.current).compositeOver(MaterialTheme.colorScheme.surface)
    var menuOpen by remember { mutableStateOf(false) }
    // A budget that hides its amounts (D272) has an eye on its card (D306): the amounts of THIS card are shown until the eye is
    // pressed again or the list is left; nothing is stored.
    val hidesAmounts = !compact && note.type == com.notebudget.app.domain.model.NoteType.BUDGET &&
        remember(note.extras) { com.notebudget.app.feature.budget.BudgetPrivacy.of(com.notebudget.app.feature.budget.BudgetLayout.parse(note.extras)).hidesAnything }
    var amountsShown by remember(note.id) { mutableStateOf(false) }
    // Copy on the card (D362): the kinds with a text or a picture of their own; a task has it in its line. Hidden amounts are not copied.
    // A note that hides its content (D363): the card shows a line instead of the preview, and an eye that shows it for THIS card
    // until it is pressed again or the list is left; nothing is stored.
    val hidesContent = !compact && note.hidden && note.type in com.notebudget.app.core.ui.HIDEABLE_KINDS
    var contentShown by remember(note.id) { mutableStateOf(false) }
    val copyable = !compact && note.type in COPY_ON_CARD && (!hidesAmounts || amountsShown) && (!hidesContent || contentShown)
    val pinnedLabel = stringResource(R.string.cd_pinned)

    com.notebudget.app.core.ui.ReadableOn(container) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = if (selecting) onToggle else onClick),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Row(
            modifier = Modifier.padding(start = if (selecting) 4.dp else 16.dp, top = if (compact) 0.dp else 8.dp, bottom = if (compact) 0.dp else 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (note.isPinned) {
                        Text("📌", modifier = Modifier.semantics { contentDescription = pinnedLabel })
                    }
                    if (hasReminder) {
                        Icon(
                            Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.cd_has_reminder),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (note.isTitleBold) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!compact) Text(
                    text = noteTypeLabel(note.type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!compact && tags.isNotEmpty()) {
                    Text(
                        text = tags.joinToString("  ") { "#" + it.name },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A piece of the note's View, never raw syntax (D108).
                if (hidesContent && !contentShown) com.notebudget.app.core.ui.HiddenLine()
                else if (!compact) NotePreview(note, budgetRevealed = amountsShown)
                if (!compact) com.notebudget.app.core.ui.StampLines(note.createdAt, note.updatedAt)
                // The last line (D177): the first 16 characters of the comment, when the switch in Settings is on.
                if (!compact && com.notebudget.app.core.ui.LocalCommentDisplay.current.notes) {
                    com.notebudget.app.domain.model.Comment.short(note.comment)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (!selecting && (dragHandle != null || hidesAmounts || copyable || hidesContent)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    dragHandle?.invoke()
                    if (copyable) com.notebudget.app.core.ui.CopyIconButton(small = true, onClick = actions.onCopy)
                    if (hidesContent) {
                        com.notebudget.app.core.ui.IconAction(
                            if (contentShown) com.notebudget.app.core.ui.AppIcons.VisibilityOff else com.notebudget.app.core.ui.AppIcons.Visibility,
                            stringResource(if (contentShown) R.string.note_hide_again else R.string.note_hide_show),
                            onClick = { contentShown = !contentShown },
                            modifier = Modifier.testTag(com.notebudget.app.core.ui.HIDE_EYE),
                        )
                    }
                    if (hidesAmounts) {
                        com.notebudget.app.core.ui.IconAction(
                            if (amountsShown) com.notebudget.app.core.ui.AppIcons.VisibilityOff else com.notebudget.app.core.ui.AppIcons.Visibility,
                            stringResource(if (amountsShown) R.string.budget_hide_amounts else R.string.budget_show_amounts),
                            onClick = { amountsShown = !amountsShown },
                        )
                    }
                }
            }
            if (!selecting) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    NoteMenuItem(stringResource(if (note.isPinned) R.string.action_unpin else R.string.action_pin)) {
                        menuOpen = false
                        actions.onTogglePinned()
                    }
                    NoteMenuItem(
                        stringResource(if (note.isTitleBold) R.string.action_normal_title else R.string.action_bold_title),
                    ) {
                        menuOpen = false
                        actions.onToggleBoldTitle()
                    }
                    NoteMenuItem(stringResource(R.string.action_tags)) {
                        menuOpen = false
                        actions.onTags()
                    }
                    NoteMenuItem(stringResource(R.string.action_comment)) {
                        menuOpen = false
                        actions.onComment()
                    }
                    NoteMenuItem(stringResource(R.string.action_reminders)) {
                        menuOpen = false
                        actions.onReminder()
                    }
                    NoteMenuItem(stringResource(R.string.action_note_color)) {
                        menuOpen = false
                        actions.onColor()
                    }
                    NoteMenuItem(stringResource(R.string.action_share)) {
                        menuOpen = false
                        actions.onShare()
                    }
                    NoteMenuItem(stringResource(R.string.action_clone)) {
                        menuOpen = false
                        actions.onClone()
                    }
                    NoteMenuItem(stringResource(R.string.action_move)) {
                        menuOpen = false
                        actions.onMove()
                    }
                    NoteMenuItem(stringResource(R.string.action_delete)) {
                        menuOpen = false
                        actions.onDelete()
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun NoteMenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}
