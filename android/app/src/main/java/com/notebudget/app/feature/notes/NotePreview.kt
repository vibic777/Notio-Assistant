package com.notebudget.app.feature.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import com.notebudget.app.feature.media.loadMediaThumbnail
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.common.Money
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.drawing.DrawingRenderer
import com.notebudget.app.feature.drawing.VectorDocument
import com.notebudget.app.feature.notes.markdown.MarkdownRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A piece of the note's View for its card on the main screen (D108): rendered lines for text and lists, the total
 * for a budget, a small picture for a drawing. Never raw Markdown or list syntax.
 */
@Composable
fun NotePreview(note: Note, /** The eye of the card was pressed (D306): the hidden amounts of a budget are shown. */ budgetRevealed: Boolean = false) {
    val lines = com.notebudget.app.core.ui.LocalPreviewLines.current
    when (note.type) {
        NoteType.BUDGET -> if (lines > 0) BudgetPreview(note, lines, budgetRevealed)
        NoteType.DRAWING, NoteType.BLUEPRINT -> if (com.notebudget.app.core.ui.LocalDrawingPreview.current) DrawingPreview(note)
        NoteType.TASK -> TaskPreview(note, lines)
        else -> {
            if (lines > 0) TextPreview(note, lines)
            MediaPicturesPreview(note)
        }
    }
}

/**
 * The pictures of a photo, video or screen note on its card (D160): the first photo or frame, or a "deck of cards" of up to
 * three (the first on top, behind it the last and a random one, fanned out a little), as Settings -> Appearance says.
 */
@Composable
private fun MediaPicturesPreview(note: Note) {
    val kind = com.notebudget.app.domain.model.MediaKind.of(note.type) ?: return
    val mode = com.notebudget.app.core.ui.LocalMediaPreview.current
    if (mode == com.notebudget.app.domain.model.MediaPreviewMode.NONE) return
    val container = rememberAppContainer()
    val pictures by produceState<List<ImageBitmap>>(emptyList(), note.id, note.extras, mode) {
        val items = com.notebudget.app.domain.model.MediaDocument.parse(note.extras).items
        // Back to front: the random one, then the last, and the first on top; the random one is the same every time for a note.
        val chosen = when {
            items.isEmpty() -> emptyList()
            mode == com.notebudget.app.domain.model.MediaPreviewMode.FIRST || items.size == 1 -> listOf(items.first())
            else -> {
                val middle = items.drop(1).dropLast(1)
                val random = middle.takeIf { it.isNotEmpty() }?.let { it[kotlin.random.Random(note.id.hashCode() + items.size).nextInt(it.size)] }
                listOfNotNull(random, items.last(), items.first())
            }
        }
        value = withContext(Dispatchers.IO) {
            chosen.mapNotNull { loadMediaThumbnail(container.mediaStore, kind, com.notebudget.app.domain.model.MediaDocument.fileKey(note.id, it.id)) }
        }
    }
    if (pictures.isEmpty()) return
    if (pictures.size == 1) {
        Image(bitmap = pictures[0], contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(96.dp))
    } else {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            pictures.forEachIndexed { i, bitmap ->
                val fan = i - (pictures.size - 1) / 2f
                Image(
                    bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(90.dp)
                        .graphicsLayer { rotationZ = fan * 7f; translationX = fan * 14.dp.toPx() }
                        .shadow(3.dp),
                )
            }
        }
    }
}

/**
 * The card shows the first [count] lines of the note (D159; 2 by default, 0 to 2 in Settings), one under the other, each on
 * one line: text, list and schedule alike, each rendered like in the View (no raw Markdown or list syntax). For an audio,
 * photo, video or screen note the lines are the NAMES of its first items.
 */
@Composable
private fun TextPreview(note: Note, count: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lines: List<androidx.compose.ui.text.AnnotatedString> = remember(note.contentText, note.extras, note.type, count) {
        val names: List<String>? = when (note.type) {
            NoteType.AUDIO -> com.notebudget.app.domain.model.AudioDocument.parse(note.extras)?.clips
                ?.mapIndexed { i, c -> c.name.ifBlank { context.getString(R.string.audio_default_name, i + 1) } }
            com.notebudget.app.domain.model.MediaKind.PHOTO.type, com.notebudget.app.domain.model.MediaKind.VIDEO.type, com.notebudget.app.domain.model.MediaKind.SCREEN.type ->
                com.notebudget.app.domain.model.MediaDocument.parse(note.extras).items
                    .mapIndexed { i, item -> item.name.ifBlank { context.getString(R.string.media_default_name, i + 1) } }
            else -> null
        }
        if (names != null) {
            names.take(count).map { androidx.compose.ui.text.AnnotatedString(it) }
        } else {
            val content = note.contentText?.takeIf { it.isNotBlank() }.orEmpty()
            // A formatted note (D211) is plain text in contentText: its lines are shown as they are, not read as Markdown.
            val rich = note.type == NoteType.WORD || (note.type == NoteType.TEXT && com.notebudget.app.domain.model.RichDocument.isRich(note.extras))
            content.lineSequence().filter { it.isNotBlank() }.take(count)
                .map { if (rich) androidx.compose.ui.text.AnnotatedString(it) else MarkdownRender.preview(it) }.filter { it.text.isNotBlank() }.toList()
        }
    }
    if (lines.isNotEmpty()) {
        Column {
            lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BudgetPreview(note: Note, count: Int, revealed: Boolean = false) {
    val container = rememberAppContainer()
    val root by remember(note.id) { container.budgetRepository.observeRoot(note.id) }.collectAsState(initial = null)
    val node = root ?: return
    val currency = com.notebudget.app.domain.model.BudgetCurrency.labelOf(note.extras)
    // Hidden sums / goals are stars on the card (D272); only the eye inside the note shows them.
    val privacy = remember(note.extras, revealed) { com.notebudget.app.feature.budget.BudgetPrivacy.of(com.notebudget.app.feature.budget.BudgetLayout.parse(note.extras), revealed) }
    Column {
        if (count >= 2) Text(stringResource(R.string.budget_total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        // The sum, the goal and the remainder each on a line of its own (D302): in one line they broke in the middle on a phone.
        Text(stringResource(R.string.budget_sum) + " " + privacy.sum(node.cachedSumMinor, currency), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        // An income and an expense are a sum alone (D326).
        if (privacy.goals) Text(stringResource(R.string.budget_goal) + " " + privacy.goal(node.cachedGoalMinor, currency), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (privacy.goals) Text(
            stringResource(R.string.budget_remainder, privacy.remainder(node.cachedRemainderMinor, currency)),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            // A hidden remainder does not tell by its colour that it is negative.
            color = if (node.cachedRemainderMinor < 0 && !privacy.remainderMasked) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
        )
    }
}

@Composable
private fun DrawingPreview(note: Note) {
    val container = rememberAppContainer()
    val cacheKey = "d:" + note.id + ":" + note.updatedAt
    val picture by produceState(com.notebudget.app.feature.media.ThumbnailCache.get(cacheKey), cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val png = container.drawingStore.read(note.id)
                val document = container.vectorStore.read(note.id)?.let { VectorDocument.decode(it) }
                val shapes = document?.drawableShapes().orEmpty()
                val (w, h) = document?.sheet() ?: (DrawingRenderer.WIDTH to DrawingRenderer.HEIGHT)
                val background = document?.background() ?: com.notebudget.app.feature.drawing.SheetBackground.DEFAULT
                if (png == null && shapes.isEmpty() && !background.visible) return@runCatching null
                // The layers on the sheet's own background (D265), then made small for the card.
                val full = DrawingRenderer.compose(png, shapes, w, h, background)
                android.graphics.Bitmap.createScaledBitmap(full, (w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), true)
                    .also { if (it !== full) full.recycle() }
                    .asImageBitmap()
            }.getOrNull()?.also { com.notebudget.app.feature.media.ThumbnailCache.put(cacheKey, it) }
        }
    }
    // The picture has a transparent sheet: on the card it must lie on white paper, as in the editor, or the white fills and the
    // paper vanish into the tinted card and only the lines are left (D161).
    picture?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.drawing_sheet),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(96.dp)
                // The sheet's own proportions (D260).
                .aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1))
                .background(androidx.compose.ui.graphics.Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        )
    }
}

/** A task on its card (D239): its state and its start and end, then the first lines of its description. */
@Composable
private fun TaskPreview(note: Note, lines: Int) {
    val task = remember(note.extras) { com.notebudget.app.domain.model.TaskDocument.decode(note.extras) }
    // The status is chosen and the priority set on the card (D337); not in the trash.
    val change = com.notebudget.app.feature.notes.task.LocalTaskChange.current?.takeIf { note.deletedAt == null }
    // The bin of the task (D361), where the card can change it.
    val delete = com.notebudget.app.feature.notes.task.LocalTaskDelete.current?.takeIf { change != null }
    com.notebudget.app.feature.notes.task.TaskCardLine(
        task, change?.let { save -> { t: com.notebudget.app.domain.model.TaskDocument -> save(note.id, t) } },
        onDelete = delete?.let { ask -> { ask(note.id) } },
        copy = com.notebudget.app.feature.notes.task.taskCopyText(note.title, task, note.contentText.orEmpty()),
    )
    com.notebudget.app.feature.notes.task.taskSpan(task)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    if (lines > 0) TextPreview(note, lines)
}
