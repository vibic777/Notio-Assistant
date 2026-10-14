package com.notebudget.app.domain.usecase

import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.SearchOptions
import com.notebudget.app.domain.model.Tag
import com.notebudget.app.domain.model.TaskFilter
import com.notebudget.app.domain.model.TaskState
import com.notebudget.app.domain.model.TextPattern

/** What the search looks for (D109, D116): notes (title, text, tags), folders (name), or tags only (tag names). */
enum class SearchScope { NOTES, FOLDERS, TAGS }

/**
 * A search. [text] is split into words; **every word** must be found (in the title, the text or a tag name of a note;
 * in the name of a folder). [tagIds] narrow notes down to those that have **all** the chosen tags. A search with no
 * words and no tags is not active and shows everything as usual.
 *
 * D246: [ignoreCase] (on by default) finds letters in any case; a word may hold the wildcards `*` and `?`. With [regex] the whole
 * [text] is one regular expression instead of words.
 *
 * D338: [taskStates] narrow notes down to the tasks of one of these statuses; chosen statuses make a search by themselves, as tags do.
 * D358: [taskDone] narrows them to the completed tasks or to the ones still open, and makes a search by itself too.
 */
data class SearchQuery(
    val scope: SearchScope = SearchScope.NOTES,
    val text: String = "",
    val tagIds: Set<String> = emptySet(),
    val ignoreCase: Boolean = true,
    val regex: Boolean = false,
    val taskStates: Set<TaskState> = emptySet(),
    val taskDone: Boolean? = null,
) {
    val words: List<String> get() = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** What must all be found: one pattern per word, or the one regular expression. Made once per search, not per note. */
    fun patterns(): List<TextPattern> {
        val options = SearchOptions(ignoreCase, regex)
        return if (regex) listOfNotNull(TextPattern.of(text, options)) else words.mapNotNull { TextPattern.of(it, options) }
    }

    /** Why the regular expression cannot be used, or `null`. */
    val error: com.notebudget.app.domain.model.PatternError? get() = if (regex) TextPattern.of(text, SearchOptions(ignoreCase, true))?.error else null

    /** Tags narrow notes (also in a tag search); a folder search ignores them. */
    val active: Boolean get() = text.isNotBlank() || (scope != SearchScope.FOLDERS && tagIds.isNotEmpty()) || (scope == SearchScope.NOTES && (taskStates.isNotEmpty() || taskDone != null))
}

object NoteSearch {

    /** Does [note] (whose tags are [tags]) match [query]? [patterns] are the query's, made once for all notes. */
    fun matches(note: Note, tags: List<Tag>, query: SearchQuery, patterns: List<TextPattern> = query.patterns()): Boolean {
        if (query.tagIds.isNotEmpty() && !tags.map { it.id }.toSet().containsAll(query.tagIds)) return false
        if (query.scope == SearchScope.NOTES && !TaskFilter.matches(note, query.taskStates, query.taskDone)) return false
        if (query.text.isBlank()) return true
        if (patterns.isEmpty()) return false
        // A tag search looks at tag names only: every word must be part of some tag of the note.
        if (query.scope == SearchScope.TAGS) {
            return patterns.all { p -> tags.any { p.foundIn(it.name) } }
        }
        val haystack = buildString {
            append(note.title).append('\n')
            append(note.contentText.orEmpty()).append('\n')
            tags.forEach { append(it.name).append('\n') }
        }
        return patterns.all { it.foundIn(haystack) }
    }

    fun filterNotes(notes: List<Note>, tagsByNote: Map<String, List<Tag>>, query: SearchQuery): List<Note> {
        val patterns = query.patterns()
        return notes.filter { matches(it, tagsByNote[it.id].orEmpty(), query, patterns) }
    }

    /** Folders whose name has every word. */
    fun filterFolders(folders: List<Folder>, query: SearchQuery): List<Folder> {
        if (query.text.isBlank()) return folders
        val patterns = query.patterns()
        if (patterns.isEmpty()) return emptyList()
        return folders.filter { folder -> patterns.all { it.foundIn(folder.name) } }
    }

    /** Does the tag named [name] fit the words (the chips of a tag search)? */
    fun tagFits(name: String, query: SearchQuery, patterns: List<TextPattern> = query.patterns()): Boolean =
        query.text.isBlank() || (patterns.isNotEmpty() && patterns.all { it.foundIn(name) })
}
