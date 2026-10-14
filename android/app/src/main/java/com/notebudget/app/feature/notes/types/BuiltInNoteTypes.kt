package com.notebudget.app.feature.notes.types

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.data.transfer.AudioTransferHandler
import com.notebudget.app.data.transfer.BudgetTransferHandler
import com.notebudget.app.data.transfer.DrawingTransferHandler
import com.notebudget.app.data.transfer.ListTransferHandler
import com.notebudget.app.data.transfer.NoteTransferHandler
import com.notebudget.app.data.transfer.TextTransferHandler
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import com.notebudget.app.feature.audio.AudioScreen
import com.notebudget.app.feature.budget.BudgetScreen
import com.notebudget.app.feature.drawing.DrawingKind
import com.notebudget.app.feature.drawing.DrawingScreen
import com.notebudget.app.feature.notes.list.ListNote

class TextNoteType(override val transfer: NoteTransferHandler = TextTransferHandler()) : NoteTypeDefinition {
    override val type = NoteType.TEXT
    override val labelRes = R.string.note_type_text

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) {
        // Markdown only (D214); a text note made formatted in 0.9.32 - 0.9.33 still opens with the formatted editor.
        com.notebudget.app.feature.notes.rich.TextNoteEditor(content, extras, onEdit, modifier)
    }
}

/** WORD (D214): formatted text with a toolbar like a word processor; the styles are in `extras`, the text in `contentText`. */
class WordNoteType(override val transfer: NoteTransferHandler = com.notebudget.app.data.transfer.WordTransferHandler()) : NoteTypeDefinition {
    override val type = NoteType.WORD
    override val labelRes = R.string.note_type_word

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = com.notebudget.app.feature.notes.rich.WordNoteEditor(content, extras, onEdit, modifier)
}

/**
 * TASK (D239): made only in a folder of tasks (so not offered in the ordinary "New note" list); name = title, description =
 * `contentText` (exported and searched as text), the rest in `extras`.
 */
class TaskNoteType(override val transfer: NoteTransferHandler = com.notebudget.app.data.transfer.TextTransferHandler()) : NoteTypeDefinition {
    override val type = NoteType.TASK
    override val labelRes = R.string.note_type_task
    override val creatable: Boolean get() = false

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = com.notebudget.app.feature.notes.task.TaskEditor(note, content, extras, onEdit, modifier)
}

class ListNoteType(override val transfer: NoteTransferHandler = ListTransferHandler()) : NoteTypeDefinition {
    override val type = NoteType.LIST
    override val labelRes = R.string.note_type_list

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = ListNote(content, extras, onEdit, modifier)
}

class BudgetNoteType(override val transfer: BudgetTransferHandler) : NoteTypeDefinition {
    override val type = NoteType.BUDGET
    override val labelRes = R.string.note_type_budget

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = BudgetScreen(note.id, modifier)
}

class DrawingNoteType(override val transfer: DrawingTransferHandler) : NoteTypeDefinition {
    override val type = NoteType.DRAWING
    override val labelRes = R.string.note_type_drawing

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = DrawingScreen(note.id, DrawingKind.DRAWING, modifier)
}

/** A blueprint (D98): vector shapes on layers. Shares the files and the export code of drawings, without a raster sheet. */
class BlueprintNoteType(override val transfer: NoteTransferHandler) : NoteTypeDefinition {
    override val type = NoteType.BLUEPRINT
    override val labelRes = R.string.note_type_blueprint

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = DrawingScreen(note.id, DrawingKind.BLUEPRINT, modifier)
}

class AudioNoteType(override val transfer: AudioTransferHandler) : NoteTypeDefinition {
    override val type = NoteType.AUDIO
    override val labelRes = R.string.note_type_audio

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = AudioScreen(note.id, modifier)
}

/** A schedule (D155): times with descriptions and calculators of the time between two of them; rows can be dragged. */
class ScheduleNoteType(override val transfer: com.notebudget.app.data.transfer.ScheduleTransferHandler) : NoteTypeDefinition {
    override val type = NoteType.SCHEDULE
    override val labelRes = R.string.note_type_schedule

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = com.notebudget.app.feature.notes.schedule.ScheduleNote(content, extras, onEdit, modifier)
}

/** PHOTO, VIDEO (camera) and SCREEN (screen recording) notes: lists of items with pictures, one editor for the three (D147). */
class MediaNoteType(
    private val kind: com.notebudget.app.domain.model.MediaKind,
    override val labelRes: Int,
    override val transfer: com.notebudget.app.data.transfer.MediaTransferHandler,
) : NoteTypeDefinition {
    override val type = kind.type

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) = com.notebudget.app.feature.media.MediaNoteScreen(note.id, kind, modifier)
}

/**
 * Stand-in for a kind this version does not know, e.g. a note from a newer app's backup. The
 * note and its data are kept untouched; it just cannot be edited here.
 */
class UnsupportedNoteType(override val type: NoteType) : NoteTypeDefinition {
    override val labelRes = R.string.note_type_unknown
    override val creatable = false
    override val transfer = object : NoteTransferHandler {
        override val exportFormats = emptyList<com.notebudget.app.data.transfer.ExportFormat>()
        override suspend fun export(note: Note, format: com.notebudget.app.data.transfer.ExportFormat) = ByteArray(0)
    }

    @Composable
    override fun Editor(
        note: Note, content: String, onContentChange: (String) -> Unit, modifier: Modifier,
        extras: String?, onEdit: (String, String?) -> Unit,
    ) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.editor_unsupported_type, type.id),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
