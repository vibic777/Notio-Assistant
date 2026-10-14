package com.notio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.DateTimeFormats

/** The global date and time format (D138); everything that shows a stamp reads it from here. */
val LocalDateTimeFormats = compositionLocalOf { DateTimeFormats() }

/** How many lines of a note its card previews (D159). */
val LocalPreviewLines = compositionLocalOf { 2 }

/** The top node of each budget shown in the list, for the total on its card (Android D159). */
val LocalBudgetRoots = compositionLocalOf<Map<String, com.notio.shared.domain.BudgetNode>> { emptyMap() }

/** How strongly note and folder cards show their colour (Android D273), 0..1. */
val LocalCardTint = compositionLocalOf { com.notio.shared.domain.CardTint.DEFAULT / 100f }

/** The picture on the card of a photo, video or screen note (D160), and of a drawing or blueprint. */
val LocalMediaPreview = compositionLocalOf { com.notio.shared.domain.MediaPreviewMode.FIRST }
val LocalDrawingPreview = compositionLocalOf { true }

/** Whether cards show the comment of a note and of a folder (D177). */
val LocalCommentDisplay = compositionLocalOf { com.notio.shared.domain.CommentDisplay() }

/** Which time stamps are shown (D157). */
val LocalStampDisplay = compositionLocalOf { com.notio.shared.domain.StampDisplay() }

/** A moment written in the chosen format. */
@Composable
fun stampText(millis: Long): String = LocalDateTimeFormats.current.format(millis)

/** "Created: ..." and "Changed: ..." in two short lines; a moment that is not known (0) is left out. */
@Composable
fun StampLines(
    createdAt: Long,
    updatedAt: Long,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** `true` on a folder card: the folder switches of Settings decide, not the ones of the notes. */
    folder: Boolean = false,
) {
    val display = LocalStampDisplay.current
    val showCreated = if (folder) display.foldersCreated else display.notesCreated
    val showModified = if (folder) display.foldersModified else display.notesModified
    val style = MaterialTheme.typography.labelSmall
    androidx.compose.foundation.layout.Column(modifier) {
        if (showCreated && createdAt > 0) Text(stringResource("stamp_created", stampText(createdAt)), style = style, color = color)
        if (showModified && updatedAt > 0) Text(stringResource("stamp_modified", stampText(updatedAt)), style = style, color = color)
    }
}

/**
 * What an open note draws at its very bottom, after its content (D198): the note screen fills it with the stamps chosen for
 * this kind of note; every editor calls [NoteFooter] at the end of what it scrolls. Nothing outside a note screen.
 */
val LocalNoteFooter = compositionLocalOf<@Composable () -> Unit> { {} }

/** The stamps at the bottom of the open note, when Settings shows them for its kind (D198). */
@Composable
fun NoteFooter() = LocalNoteFooter.current()

@Composable
fun NoteFooterStampLines(note: com.notio.shared.domain.Note, stamps: com.notio.shared.domain.NoteFooterStamps) {
    val showCreated = stamps.showsCreated(note.type) && note.createdAt > 0
    val showModified = stamps.showsModified(note.type) && note.updatedAt > 0
    if (!showCreated && !showModified) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        androidx.compose.material3.HorizontalDivider(androidx.compose.ui.Modifier.padding(bottom = 6.dp))
        if (showCreated) Text(stringResource("stamp_created", stampText(note.createdAt)), style = style, color = color)
        if (showModified) Text(stringResource("stamp_modified", stampText(note.updatedAt)), style = style, color = color)
    }
}
