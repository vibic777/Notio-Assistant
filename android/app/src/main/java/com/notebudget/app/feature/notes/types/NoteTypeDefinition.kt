package com.notebudget.app.feature.notes.types

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.notebudget.app.data.transfer.NoteHandlerLookup
import com.notebudget.app.data.transfer.NoteTransferHandler
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType

/**
 * Everything the app needs to know about one kind of note. To add a new kind (a mind map, a
 * table, a photo note, ...):
 *  1. implement this interface — a label, an editor and a [NoteTransferHandler];
 *  2. pick a stable [type] id (it is stored in the database and in backups, never change it);
 *  3. add the definition to the list in `AppContainer.noteTypes`.
 * Nothing else changes: the "new note" dialog, note cards, the editor screen, note export
 * and import, and full backups all go through the registry.
 */
interface NoteTypeDefinition {
    val type: NoteType

    @get:StringRes
    val labelRes: Int

    /** Data side: export formats, import recognition, files kept outside the database. */
    val transfer: NoteTransferHandler

    /** Whether the "new note" dialog offers this kind. */
    val creatable: Boolean get() = true

    /**
     * The editor body under the title bar. [content] / [onContentChange] are the note's
     * `contentText`, autosaved by the screen; kinds with their own storage (budget, drawing)
     * ignore them and save themselves. A kind whose structure lives in `note.extras` next to a text
     * copy (LIST) reads [extras] and reports both at once through [onEdit], so the two never disagree
     * in between; the default just forwards the text.
     */
    @Composable
    fun Editor(
        note: Note,
        content: String,
        onContentChange: (String) -> Unit,
        modifier: Modifier,
        extras: String? = null,
        onEdit: (content: String, extras: String?) -> Unit = { text, _ -> onContentChange(text) },
    )
}

/** Definitions by kind. Unknown kinds (from a newer app version) get a safe placeholder. */
class NoteTypeRegistry(definitions: List<NoteTypeDefinition>) : NoteHandlerLookup {
    private val byType: Map<NoteType, NoteTypeDefinition> = definitions.associateBy { it.type }

    init {
        require(byType.size == definitions.size) { "Two note type definitions share an id" }
    }

    /** Kinds offered when creating a note, in registration order. */
    val creatable: List<NoteTypeDefinition> = definitions.filter { it.creatable }

    fun definitionFor(type: NoteType): NoteTypeDefinition = byType[type] ?: UnsupportedNoteType(type)

    override fun handlerFor(type: NoteType): NoteTransferHandler? = byType[type]?.transfer

    override val allHandlers: Collection<NoteTransferHandler> = definitions.map { it.transfer }
}
