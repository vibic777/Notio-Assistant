package com.notebudget.app.domain.repository

import com.notebudget.app.domain.model.Note

/**
 * Removes what a note keeps outside the database (drawing and audio files) when the note is
 * deleted for good. The domain does not know which kinds keep files; the app wires this to the
 * note-kind registry.
 */
fun interface NoteDataCleaner {
    suspend fun deleteData(note: Note)

    companion object {
        val None = NoteDataCleaner { }
    }
}
