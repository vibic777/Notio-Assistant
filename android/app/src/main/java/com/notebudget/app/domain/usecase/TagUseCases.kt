package com.notebudget.app.domain.usecase

import com.notebudget.app.core.common.Clock
import com.notebudget.app.core.common.IdGenerator
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Tag
import com.notebudget.app.domain.model.asFailure
import com.notebudget.app.domain.model.asSuccess
import com.notebudget.app.domain.repository.NoteRepository
import com.notebudget.app.domain.repository.TagRepository

/**
 * Tag rules (D109): a name is 1 to 32 characters, unique ignoring case (also for Cyrillic, which SQLite does not fold);
 * a leading `#` and extra spaces are dropped; a note can have any number of tags; deleting a tag removes it from every
 * note. Deleting asks for confirmation in the UI (INV-6).
 */
class TagUseCases(
    private val tags: TagRepository,
    private val notes: NoteRepository,
    private val ids: IdGenerator = IdGenerator.Random,
    private val clock: Clock = Clock.System,
) {
    companion object {
        const val MAX_NAME_LENGTH = 32

        /** What a person typed, made into a tag name: `  #Work  trip ` becomes `Work trip`. */
        fun clean(input: String): String = input.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ")
    }

    private fun valid(name: String) = name.isNotEmpty() && name.length <= MAX_NAME_LENGTH

    suspend fun create(name: String): DomainResult<Tag> {
        val clean = clean(name)
        if (!valid(clean)) return DomainError.INVALID_TAG_NAME.asFailure()
        if (tags.getAll().any { it.name.equals(clean, ignoreCase = true) }) return DomainError.DUPLICATE_TAG_NAME.asFailure()
        val tag = Tag(ids.newId(), clean)
        tags.insert(tag, clock.now())
        return tag.asSuccess()
    }

    /** The tag with this name, made when it does not exist yet (typing a tag that exists just picks it). */
    suspend fun getOrCreate(name: String): DomainResult<Tag> {
        val clean = clean(name)
        if (!valid(clean)) return DomainError.INVALID_TAG_NAME.asFailure()
        tags.getAll().firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it.asSuccess() }
        return create(clean)
    }

    suspend fun rename(tagId: String, name: String): DomainResult<Tag> {
        val clean = clean(name)
        if (!valid(clean)) return DomainError.INVALID_TAG_NAME.asFailure()
        val all = tags.getAll()
        val tag = all.firstOrNull { it.id == tagId } ?: return DomainError.TAG_NOT_FOUND.asFailure()
        if (all.any { it.id != tagId && it.name.equals(clean, ignoreCase = true) }) return DomainError.DUPLICATE_TAG_NAME.asFailure()
        tags.rename(tagId, clean)
        return tag.copy(name = clean).asSuccess()
    }

    suspend fun delete(tagId: String): DomainResult<Unit> {
        if (tags.getAll().none { it.id == tagId }) return DomainError.TAG_NOT_FOUND.asFailure()
        tags.delete(tagId)
        return Unit.asSuccess()
    }

    /** Gives the note exactly these tags. */
    suspend fun setNoteTags(noteId: String, tagIds: Set<String>): DomainResult<Unit> {
        notes.getByIdIncludingDeleted(noteId) ?: return DomainError.NOTE_NOT_FOUND.asFailure()
        val known = tags.getAll().map { it.id }.toSet()
        if (!known.containsAll(tagIds)) return DomainError.TAG_NOT_FOUND.asFailure()
        tags.setNoteTags(noteId, tagIds)
        return Unit.asSuccess()
    }

    /** Import: the tags come as names; missing ones are made, the note gets exactly those. */
    suspend fun assignByNames(noteId: String, names: List<String>): DomainResult<Unit> {
        val ids = names.mapNotNull { (getOrCreate(it) as? DomainResult.Success)?.value?.id }.toSet()
        return setNoteTags(noteId, ids)
    }
}
