package com.notio.shared.assistant

import com.notio.shared.data.DbBudgets
import com.notio.shared.data.EditException
import com.notio.shared.data.NotioDatabase
import com.notio.shared.data.NotioEditor
import com.notio.shared.domain.BudgetEditor
import com.notio.shared.domain.BudgetLayout
import com.notio.shared.domain.BudgetNodeKind
import com.notio.shared.domain.BudgetPrivacy
import com.notio.shared.domain.DomainResult
import com.notio.shared.domain.Folder
import com.notio.shared.domain.FolderKind
import com.notio.shared.domain.ListDocument
import com.notio.shared.domain.ListEntry
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import com.notio.shared.domain.SortKey
import com.notio.shared.domain.SortOption
import com.notio.shared.domain.TaskDocument
import com.notio.shared.domain.TaskState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One command as a model is told about it: its name, what it does, its parameters (a JSON Schema object) and the right it needs.
 * [changes] - it changes something (so it counts for the limit of one unconfirmed request); [confirm] - it always needs the
 * person's yes (the sums of a budget: a wrong number is not seen by the eye).
 */
class AssistantTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val right: AssistantRight,
    val changes: Boolean = false,
    val confirm: Boolean = false,
)

/**
 * The commands of the assistant (D145, D147; docs/ASSISTANT_PLAN_RU.md, sections 2, 5, 8, 9, 11): the only door through which a
 * model reaches the notes. A model never touches the database; it asks for one of these by name, and Notio does it with the same
 * rules as a press of the person ([NotioEditor], [BudgetEditor]).
 *
 * Three checks stand before a command. The RIGHT - is this kind of action allowed at all ([rights]; nothing is, until the
 * person allows it). The ACCESS - is this note or folder open to the assistant through this [Channel] ([AssistantAccess]); a
 * closed one is not there for the model: not listed, not found, not counted. The YES of the person - a request that has changed
 * [MASS_LIMIT] notes, or one that changes the sums of a budget, stops until the person confirms it. Every change goes through
 * the [journal] first, so the person can roll the whole request back.
 *
 * What is not here is given by no right: purging and emptying the trash, the rights and the access themselves, the lock,
 * passwords, encryption, pairing, the settings of the trash and of backups, files outside the notes, links and programs.
 */
class AssistantCommands(
    private val db: NotioDatabase,
    private val editor: NotioEditor,
    private val journal: AssistantJournal,
    private val rights: () -> Set<AssistantRight>,
    private val host: AssistantHost = AssistantHost.None,
    /** A line for the log of the app: the name of a command and how it ended. Never the content of a note. */
    private val log: (String) -> Unit = {},
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val budgets = BudgetEditor(DbBudgets(db), log = DbBudgets(db))

    /** The commands a model is told about now: only those the person has allowed. */
    fun tools(): List<AssistantTool> = rights().let { allowed -> ALL.filter { it.right in allowed } }

    /**
     * Does the command [name] of the request [session] and answers with JSON for the model: the result, or `{"error": …}` in
     * plain words. Never throws: whatever a model sends, the answer is an error it can read.
     */
    fun call(session: AssistantJournal.Session, channel: Channel, name: String, args: JsonObject): JsonObject {
        val result = checked(session, channel, name, args)
        // The log names the command and its outcome; the words of an error are the app's own and hold nothing of a note.
        log("command " + name.take(40) + ": " + ((result["error"] as? JsonPrimitive)?.content?.let { "refused - $it" } ?: "done"))
        return result
    }

    private fun checked(session: AssistantJournal.Session, channel: Channel, name: String, args: JsonObject): JsonObject {
        val tool = ALL.firstOrNull { it.name == name } ?: return error("There is no command of this name.")
        if (host.locked()) return error("Notio is locked. Nothing can be done until the person unlocks it.")
        if (tool.right !in rights()) return error("The person has not allowed this kind of action to the assistant.")
        if (++session.calls > MAX_CALLS) return error("Too many commands for one request ($MAX_CALLS). Stop and tell the person what is done.")
        if (!session.confirmed && (tool.confirm || (tool.changes && session.changedNotes >= MASS_LIMIT))) {
            session.needsConfirm = true
            return error(
                if (tool.confirm) "A change of the sums of a budget needs the person's confirmation. Stop now and tell the person exactly what you are going to change; they will confirm in the app."
                else "This request has already changed $MASS_LIMIT notes. More needs the person's confirmation. Stop now and tell the person what is done and what is left; they will confirm in the app.",
            )
        }
        return try {
            Run(session, channel, args).run(name)
        } catch (e: EditException) {
            error("Notio refused: " + e.error.name.lowercase().replace('_', ' ') + ".")
        } catch (e: Refused) {
            error(e.message.orEmpty())
        } catch (e: Exception) {
            error("The command failed.")
        }
    }

    private class Refused(message: String) : Exception(message)

    private fun error(text: String) = buildJsonObject { put("error", text) }

    /** One command being done: what is open to this channel is read once. */
    private inner class Run(val session: AssistantJournal.Session, val channel: Channel, val args: JsonObject) {
        val access = db.assistantAccess()

        fun open(id: String) = AssistantAccess.visible(access[id] ?: AssistantAccess.NONE, channel)

        /** The folders the assistant may see: open themselves and not in the trash. */
        fun folders(): List<Folder> = db.folders().filter { it.deletedAt == null && open(it.id) }

        fun folder(id: String): Folder = folders().firstOrNull { it.id == id } ?: throw Refused("There is no folder with this id.")

        /** A note the assistant may see: open itself, not in the trash, its folder not in the trash. Otherwise it is "not there". */
        fun note(id: String): Note {
            val note = db.note(id)?.takeIf { it.deletedAt == null && open(it.id) } ?: throw Refused("There is no note with this id.")
            if (db.folders().none { it.id == note.folderId && it.deletedAt == null }) throw Refused("There is no note with this id.")
            return note
        }

        fun noteOf(kind: NoteType, what: String): Note = note(text("note_id")).also { if (it.type != kind) throw Refused("This note is not $what; it is of the kind ${it.type.id}.") }

        fun notesOf(folderId: String): List<Note> = db.notesOf(folderId).filter { it.deletedAt == null && open(it.id) }

        fun text(key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Refused("The parameter '$key' is needed.")
        fun textOrNull(key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun int(key: String, default: Int): Int = (args[key] as? JsonPrimitive)?.intOrNull ?: default
        fun flag(key: String): Boolean = (args[key] as? JsonPrimitive)?.booleanOrNull ?: false
        fun has(key: String): Boolean = args[key] != null && args[key] !is kotlinx.serialization.json.JsonNull

        /** A sum of money as a number with at most two decimals, in the hundredths the budget counts in. */
        fun money(key: String): Long {
            val value = (args[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() } ?: throw Refused("The parameter '$key' is needed, a number.")
            if (value.isNaN() || kotlin.math.abs(value) > 1e12) throw Refused("'$key' is not a sum.")
            return Math.round(value * 100)
        }

        /** A moment written as `2026-10-14T09:00`, in the time of this computer. */
        fun moment(key: String): Long = runCatching { LocalDateTime.parse(text(key).trim().take(19)).atZone(zone()).toInstant().toEpochMilli() }
            .getOrElse { throw Refused("'$key' must be a date and time like 2026-10-14T09:00.") }

        fun written(millis: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone()).toString().take(16)

        fun <T> done(result: DomainResult<T>): T = when (result) {
            is DomainResult.Success -> result.value
            is DomainResult.Failure -> throw Refused("Notio refused: " + result.error.name.lowercase().replace('_', ' ') + ".")
        }

        fun ok() = buildJsonObject { put("ok", true) }

        fun brief(note: Note): JsonElement = buildJsonObject {
            put("id", note.id); put("title", note.title); put("kind", note.type.id); put("folder_id", note.folderId); put("updated", written(note.updatedAt))
        }

        /** A note that hides its content gives nothing of it and is not changed inside. */
        fun shown(note: Note): Note = note.also { if (it.hidden) throw Refused("The person hides the content of this note; the assistant neither reads nor changes it.") }

        fun saveList(note: Note, doc: ListDocument) {
            val encoded = doc.encode({ doc.unitNames[it] ?: it }, doc.unitNames)
            journal.touch(session, note)
            editor.saveNote(note.copy(contentText = encoded.contentText, extras = encoded.extras), withExtras = true)
        }

        fun saveTask(note: Note, task: TaskDocument) {
            if (!task.valid) throw Refused("The end of a task cannot be before its start.")
            journal.touch(session, note)
            editor.saveNote(note.copy(extras = task.encode()), withExtras = true)
        }

        /** A budget whose amounts the person hides gives none of them and is not changed. */
        fun budget(): Note = noteOf(NoteType.BUDGET, "a budget").also {
            if (BudgetPrivacy.of(BudgetLayout.parse(it.extras)).hidesAnything) throw Refused("The person hides the amounts of this budget; the assistant neither reads nor changes them.")
        }

        fun run(name: String): JsonObject = when (name) {
            // ---- reading
            "list_folders" -> buildJsonObject {
                put("folders", buildJsonArray {
                    folders().sortedBy { it.sortOrder }.forEach { f -> add(buildJsonObject { put("id", f.id); put("name", f.name); put("kind", f.kind.name.lowercase()); put("notes", notesOf(f.id).size) }) }
                })
            }
            "list_notes" -> {
                val folder = folder(text("folder_id"))
                val all = notesOf(folder.id).sortedByDescending { it.updatedAt }
                val offset = int("offset", 0).coerceAtLeast(0)
                val page = all.drop(offset).take(int("limit", PAGE).coerceIn(1, PAGE))
                buildJsonObject {
                    put("folder", folder.name)
                    put("total", all.size)
                    put("offset", offset)
                    put("notes", buildJsonArray { page.forEach { add(brief(it)) } })
                }
            }
            "search_notes" -> {
                val words = text("query").trim()
                if (words.isEmpty()) throw Refused("The query is empty.")
                val open = folders().map { it.id }.toSet()
                val found = db.notes().asSequence()
                    .filter { it.deletedAt == null && open(it.id) && it.folderId in open }
                    // A note that hides its content (D144) is found by its title only: its text is not given to a model.
                    .filter { it.title.contains(words, ignoreCase = true) || (!it.hidden && it.contentText.orEmpty().contains(words, ignoreCase = true)) }
                    .sortedByDescending { it.updatedAt }.take(int("limit", 20).coerceIn(1, PAGE)).toList()
                buildJsonObject { put("notes", buildJsonArray { found.forEach { add(brief(it)) } }) }
            }
            "read_note" -> {
                val note = note(text("note_id"))
                buildJsonObject {
                    put("id", note.id); put("title", note.title); put("kind", note.type.id); put("folder_id", note.folderId)
                    put("pinned", note.isPinned)
                    put("tags", buildJsonArray { db.tagsOf(note.id).forEach { add(JsonPrimitive(it.name)) } })
                    when {
                        note.hidden -> put("hidden", "The person hides the content of this note. Only its title is given.")
                        note.type !in TEXT_KINDS -> put("text", JsonPrimitive(null as String?))
                        else -> {
                            val text = note.contentText.orEmpty()
                            put("text", text.take(TEXT_CHARS))
                            if (text.length > TEXT_CHARS) put("truncated", true)
                            if (note.type == NoteType.TASK) TaskDocument.decode(note.extras).let { task ->
                                put("status", task.state.name.lowercase())
                                task.start?.let { put("start", written(it)) }
                                task.end?.let { put("end", written(it)) }
                                task.priority?.let { put("priority", it) }
                                if (task.comment.isNotBlank()) put("comment", task.comment)
                            }
                        }
                    }
                }
            }
            // ---- notes
            "create_note" -> {
                val folder = folder(text("folder_id"))
                val made = editor.createNote(folder.id, text("title"), NoteType.TEXT, textOrNull("text").orEmpty())
                // The assistant sees what it made: the note is open through what it was made through (the plan, section 8).
                db.setAssistantAccess(listOf(made.id), AssistantAccess.of(channel))
                journal.created(session, made.id)
                journal.op(session, "Created the note \"${made.title}\" in \"${folder.name}\"")
                buildJsonObject { put("ok", true); put("note_id", made.id) }
            }
            "rename_note" -> {
                val note = note(text("note_id"))
                val title = text("title")
                journal.touch(session, note)
                editor.saveNote(note.copy(title = title))
                journal.op(session, "Renamed \"${note.title}\" to \"${title.trim()}\"")
                ok()
            }
            "set_note_text", "append_note_text" -> {
                val note = shown(note(text("note_id")))
                if (note.type != NoteType.TEXT) throw Refused("Only the text of a Markdown note can be changed this way; this note is of the kind ${note.type.id}.")
                val given = text("text")
                val next = if (name == "set_note_text") given else note.contentText.orEmpty().let { if (it.isEmpty() || it.endsWith("\n")) it + given else it + "\n" + given }
                journal.touch(session, note)
                editor.saveNote(note.copy(contentText = next))
                journal.op(session, (if (name == "set_note_text") "Replaced the text of" else "Added text to") + " \"${note.title}\"")
                ok()
            }
            "move_note" -> {
                val note = note(text("note_id"))
                val target = folder(text("folder_id"))
                journal.touch(session, note)
                editor.moveNote(note.id, target.id)
                journal.op(session, "Moved \"${note.title}\" to \"${target.name}\"")
                ok()
            }
            "sort_notes" -> {
                val folder = folder(text("folder_id"))
                val key = SORTS[text("by").lowercase()] ?: throw Refused("'by' is one of: " + SORTS.keys.joinToString(", ") + ".")
                val option = SortOption(key, flag("descending"))
                journal.sortTouched(session, folder.id)
                host.setFolderSort(folder.id, option.encode())
                journal.op(session, "Sorted the notes of \"${folder.name}\" by ${text("by").lowercase()}" + if (option.descending) ", descending" else "")
                ok()
            }
            "trash_note" -> {
                val note = note(text("note_id"))
                journal.touch(session, note)
                editor.trashNote(note.id)
                journal.op(session, "Moved \"${note.title}\" to the trash")
                ok()
            }
            // ---- marks
            "set_note_tags" -> {
                val note = note(text("note_id"))
                val names = (args["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.trimStart('#') }?.filter { it.isNotEmpty() }?.distinct()
                    ?: throw Refused("The parameter 'tags' is needed, a list of names.")
                journal.tagsTouched(session, note.id)
                editor.setNoteTags(note.id, names.map { editor.getOrCreateTag(it).id }.toSet())
                journal.op(session, "Set the tags of \"${note.title}\": " + names.joinToString(", ").ifEmpty { "none" })
                ok()
            }
            "set_note_color" -> {
                val note = note(text("note_id"))
                val color = text("color").trim().let { if (it.equals("none", ignoreCase = true)) null else it.uppercase() }
                journal.touch(session, note)
                editor.saveNote(note.copy(colorHex = color))
                journal.op(session, "Set the colour of \"${note.title}\"")
                ok()
            }
            "pin_note" -> {
                val note = note(text("note_id"))
                journal.touch(session, note)
                editor.saveNote(note.copy(isPinned = flag("pinned")))
                journal.op(session, (if (flag("pinned")) "Pinned" else "Unpinned") + " \"${note.title}\"")
                ok()
            }
            // ---- lists
            "read_list" -> {
                val doc = ListDocument.fromNote(shown(noteOf(NoteType.LIST, "a list")).contentText, db.note(text("note_id"))?.extras)
                buildJsonObject {
                    put("items", buildJsonArray {
                        doc.entries.filter { it.isItem }.forEach { e -> add(buildJsonObject { put("id", e.id); put("text", e.text); put("done", e.done); e.comment?.let { put("comment", it) } }) }
                    })
                }
            }
            "list_add_item" -> {
                val note = shown(noteOf(NoteType.LIST, "a list"))
                val words = text("text").trim()
                if (words.isEmpty()) throw Refused("The text of the item is empty.")
                val item = ListEntry.newItem().copy(text = words)
                saveList(note, ListDocument.fromNote(note.contentText, note.extras).add(item))
                journal.op(session, "Added \"$words\" to the list \"${note.title}\"")
                buildJsonObject { put("ok", true); put("item_id", item.id) }
            }
            "list_check_item", "list_remove_item" -> {
                val note = shown(noteOf(NoteType.LIST, "a list"))
                val doc = ListDocument.fromNote(note.contentText, note.extras)
                val item = doc.entries.firstOrNull { it.id == text("item_id") && it.isItem } ?: throw Refused("There is no item with this id; read the list first.")
                if (name == "list_check_item") {
                    saveList(note, doc.replace(item.id) { it.copy(done = flag("done")) })
                    journal.op(session, (if (flag("done")) "Ticked" else "Unticked") + " \"${item.text}\" in \"${note.title}\"")
                } else {
                    saveList(note, doc.remove(item.id))
                    journal.op(session, "Removed \"${item.text}\" from \"${note.title}\"")
                }
                ok()
            }
            // ---- tasks
            "create_task" -> {
                val folder = folder(text("folder_id"))
                if (folder.kind != FolderKind.TASKS) throw Refused("A task is made in a folder of tasks; this folder is a folder of notes.")
                val made = editor.createNote(folder.id, text("title"), NoteType.TASK, textOrNull("description").orEmpty())
                db.setAssistantAccess(listOf(made.id), AssistantAccess.of(channel))
                journal.created(session, made.id)
                journal.op(session, "Created the task \"${made.title}\" in \"${folder.name}\"")
                buildJsonObject { put("ok", true); put("note_id", made.id) }
            }
            "set_task" -> {
                val note = noteOf(NoteType.TASK, "a task")
                var task = TaskDocument.decode(note.extras)
                textOrNull("status")?.let { word ->
                    val state = TaskState.entries.firstOrNull { it.name.equals(word.trim(), ignoreCase = true) } ?: throw Refused("'status' is one of: " + TaskState.entries.joinToString(", ") { it.name.lowercase() } + ".")
                    task = task.with(state)
                }
                if (has("start")) task = task.copy(start = if (textOrNull("start").isNullOrBlank()) null else moment("start"))
                if (has("end")) task = task.copy(end = if (textOrNull("end").isNullOrBlank()) null else moment("end"))
                if (has("priority")) task = task.copy(priority = int("priority", 0).takeIf { it > 0 })
                textOrNull("comment")?.let { task = task.copy(comment = it) }
                saveTask(note, task)
                journal.op(session, "Changed the task \"${note.title}\"")
                ok()
            }
            // ---- budget
            "read_budget" -> {
                val note = budget()
                buildJsonObject {
                    put("items", buildJsonArray {
                        db.budgetNodesOf(note.id).sortedBy { it.sortOrder }.forEach { n ->
                            add(buildJsonObject {
                                put("id", n.id); n.parentId?.let { put("parent_id", it) }
                                put("title", if (n.parentId == null) "(total)" else n.title)
                                put("kind", if (n.kind == BudgetNodeKind.GROUP) "group" else "item")
                                put("sum", n.cachedSumMinor / 100.0); put("goal", n.cachedGoalMinor / 100.0)
                            })
                        }
                    })
                }
            }
            "budget_add_item" -> {
                val note = budget()
                val nodes = db.budgetNodesOf(note.id)
                val parent = textOrNull("group_id")?.let { id -> nodes.firstOrNull { it.id == id && it.kind == BudgetNodeKind.GROUP } ?: throw Refused("There is no group with this id; read the budget first.") }
                    ?: nodes.firstOrNull { it.parentId == null } ?: throw Refused("The budget has no total.")
                journal.budgetTouched(session, note.id)
                val made = done(budgets.addNode(note.id, parent.id, text("title"), BudgetNodeKind.LEAF, sumMinor = if (has("sum")) money("sum") else 0))
                journal.op(session, "Added \"${made.title}\" to the budget \"${note.title}\"")
                buildJsonObject { put("ok", true); put("item_id", made.id) }
            }
            "budget_change_sum" -> {
                val note = budget()
                val item = db.budgetNodesOf(note.id).firstOrNull { it.id == text("item_id") && it.kind == BudgetNodeKind.LEAF } ?: throw Refused("There is no item with this id; read the budget first.")
                val amount = money("amount")
                journal.budgetTouched(session, note.id)
                when (text("mode").lowercase()) {
                    "add" -> done(budgets.adjustLeafSum(note.id, item.id, amount))
                    "subtract" -> done(budgets.adjustLeafSum(note.id, item.id, -amount))
                    "set" -> done(budgets.setLeafSum(note.id, item.id, amount))
                    else -> throw Refused("'mode' is one of: add, subtract, set.")
                }
                journal.op(session, "Changed the sum of \"${item.title}\" in the budget \"${note.title}\": ${text("mode").lowercase()} ${amount / 100.0}")
                ok()
            }
            // ---- reminders
            "list_reminders" -> {
                val note = note(text("note_id"))
                buildJsonObject { put("reminders", buildJsonArray { host.reminders(note.id).forEach { (id, at) -> add(buildJsonObject { put("id", id); put("at", written(at)) }) } }) }
            }
            "add_reminder" -> {
                val note = note(text("note_id"))
                val at = moment("at")
                val id = host.addReminder(note.id, at) ?: throw Refused("The reminder could not be set: the time must be in the future.")
                journal.createdReminder(session, id)
                journal.op(session, "Set a reminder of \"${note.title}\" for ${written(at)}")
                buildJsonObject { put("ok", true); put("reminder_id", id) }
            }
            // ---- folders
            "create_folder" -> {
                val kind = if (textOrNull("kind").equals("tasks", ignoreCase = true)) FolderKind.TASKS else FolderKind.NOTES
                val made = editor.createFolder(text("name"), "#3366FF", "SOFT", null, kind)
                db.setAssistantAccess(listOf(made.id), AssistantAccess.of(channel))
                journal.createdFolder(session, made.id)
                journal.op(session, "Created the folder \"${made.name}\"")
                buildJsonObject { put("ok", true); put("folder_id", made.id) }
            }
            "rename_folder" -> {
                val folder = folder(text("folder_id"))
                journal.folderTouched(session, folder)
                editor.updateFolder(folder.id, text("name"), folder.colorHex, folder.styleId, folder.icon)
                journal.op(session, "Renamed the folder \"${folder.name}\" to \"${text("name").trim()}\"")
                ok()
            }
            // ---- the screen
            "open_note" -> { host.openNote(note(text("note_id")).id); ok() }
            "open_folder" -> { host.openFolder(folder(text("folder_id")).id); ok() }
            // ---- how the app looks, and its other settings
            "list_appearance", "list_settings" -> buildJsonObject {
                put("settings", buildJsonArray {
                    host.settings(appearance = name == "list_appearance").forEach { (key, value) -> add(buildJsonObject { put("name", key); put("value", value.first); put("allowed", value.second) }) }
                })
            }
            "set_appearance", "set_setting" -> {
                val key = text("name")
                if (key !in host.settings(appearance = name == "set_appearance")) throw Refused("There is no such setting; list them first.")
                journal.settingTouched(session, key)
                host.setSetting(key, text("value"))?.let { throw Refused(it) }
                journal.op(session, "Set \"$key\" to \"${text("value")}\"")
                ok()
            }
            else -> throw Refused("There is no command of this name.")
        }
    }

    companion object {
        /** How many commands one request may take: a model that goes round in circles is stopped. */
        const val MAX_CALLS = 60

        /** How many notes one request may change before the person is asked to confirm the rest. */
        const val MASS_LIMIT = 5
        private const val PAGE = 50
        private const val TEXT_CHARS = 20_000

        /** The kinds whose text a model may read. */
        private val TEXT_KINDS = setOf(NoteType.TEXT, NoteType.WORD, NoteType.LIST, NoteType.TASK)
        private val SORTS = linkedMapOf(
            "name" to SortKey.NAME, "created" to SortKey.CREATED, "updated" to SortKey.UPDATED, "color" to SortKey.COLOR,
            "kind" to SortKey.TYPE, "tags" to SortKey.TAGS, "manual" to SortKey.MANUAL,
        )

        private fun schema(required: List<String>, vararg fields: Pair<String, Pair<String, String>>): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                fields.forEach { (name, type) ->
                    putJsonObject(name) {
                        put("type", type.first); put("description", type.second)
                        if (type.first == "array") putJsonObject("items") { put("type", "string") }
                    }
                }
            }
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }

        private val NOTE = "note_id" to ("string" to "The id of the note, as list_notes or search_notes gave it.")
        private val FOLDER = "folder_id" to ("string" to "The id of the folder, as list_folders gave it.")
        private const val WHEN = "A date and time like 2026-10-14T09:00, in the time of the person's computer."
        private val STATES = TaskState.entries.joinToString(", ") { it.name.lowercase() }

        /** Every command there is. A model is shown only those of the rights the person turned on ([tools]). */
        val ALL: List<AssistantTool> = listOf(
            AssistantTool("list_folders", "Lists the folders with their ids, their kind (notes or tasks) and how many notes each has.", schema(emptyList()), AssistantRight.READ),
            AssistantTool(
                "list_notes", "Lists the notes of one folder: id, title, kind. At most $PAGE at a time; use offset for the next ones.",
                schema(listOf("folder_id"), FOLDER, "offset" to ("integer" to "How many notes to skip."), "limit" to ("integer" to "How many notes to give, at most $PAGE.")),
                AssistantRight.READ,
            ),
            AssistantTool(
                "search_notes", "Finds notes whose title or text has these words.",
                schema(listOf("query"), "query" to ("string" to "The words to look for."), "limit" to ("integer" to "How many notes to give.")), AssistantRight.READ,
            ),
            AssistantTool("read_note", "Gives the title, the tags and the text of one note; for a task also its status, dates and priority.", schema(listOf("note_id"), NOTE), AssistantRight.READ),
            AssistantTool(
                "create_note", "Makes a new Markdown note in a folder.",
                schema(listOf("folder_id", "title"), FOLDER, "title" to ("string" to "The title of the note."), "text" to ("string" to "The text of the note, Markdown.")),
                AssistantRight.CREATE, changes = true,
            ),
            AssistantTool("rename_note", "Changes the title of a note.", schema(listOf("note_id", "title"), NOTE, "title" to ("string" to "The new title.")), AssistantRight.EDIT, changes = true),
            AssistantTool(
                "set_note_text", "Replaces the whole text of a Markdown note.",
                schema(listOf("note_id", "text"), NOTE, "text" to ("string" to "The new text, Markdown.")), AssistantRight.EDIT, changes = true,
            ),
            AssistantTool(
                "append_note_text", "Adds text to the end of a Markdown note.",
                schema(listOf("note_id", "text"), NOTE, "text" to ("string" to "The text to add, Markdown.")), AssistantRight.EDIT, changes = true,
            ),
            AssistantTool("move_note", "Moves a note to another folder.", schema(listOf("note_id", "folder_id"), NOTE, FOLDER), AssistantRight.MOVE, changes = true),
            AssistantTool(
                "sort_notes", "Sets how the notes of a folder are ordered on the screen.",
                schema(
                    listOf("folder_id", "by"), FOLDER,
                    "by" to ("string" to "One of: " + SORTS.keys.joinToString(", ") + "."), "descending" to ("boolean" to "true: from Z to A, the newest first."),
                ),
                AssistantRight.MOVE,
            ),
            AssistantTool("trash_note", "Moves a note to the trash. The person can restore it from there.", schema(listOf("note_id"), NOTE), AssistantRight.TRASH, changes = true),
            AssistantTool(
                "set_note_tags", "Sets the tags of a note: the list given replaces the tags it has. An empty list takes all tags away.",
                schema(listOf("note_id", "tags"), NOTE, "tags" to ("array" to "The names of the tags, without #.")), AssistantRight.MARKS, changes = true,
            ),
            AssistantTool(
                "set_note_color", "Sets the colour of a note.",
                schema(listOf("note_id", "color"), NOTE, "color" to ("string" to "A colour like #FF8800, or none for the colour of the folder.")), AssistantRight.MARKS, changes = true,
            ),
            AssistantTool("pin_note", "Pins a note to the top of its folder, or unpins it.", schema(listOf("note_id", "pinned"), NOTE, "pinned" to ("boolean" to "true to pin, false to unpin.")), AssistantRight.MARKS, changes = true),
            AssistantTool("read_list", "Gives the items of a list note with their ids and whether each is ticked.", schema(listOf("note_id"), NOTE), AssistantRight.READ),
            AssistantTool("list_add_item", "Adds an item to a list note.", schema(listOf("note_id", "text"), NOTE, "text" to ("string" to "The text of the item.")), AssistantRight.LISTS, changes = true),
            AssistantTool(
                "list_check_item", "Ticks or unticks an item of a list note.",
                schema(listOf("note_id", "item_id", "done"), NOTE, "item_id" to ("string" to "The id of the item, as read_list gave it."), "done" to ("boolean" to "true: ticked.")), AssistantRight.LISTS, changes = true,
            ),
            AssistantTool("list_remove_item", "Removes an item from a list note.", schema(listOf("note_id", "item_id"), NOTE, "item_id" to ("string" to "The id of the item, as read_list gave it.")), AssistantRight.LISTS, changes = true),
            AssistantTool(
                "create_task", "Makes a new task in a folder of tasks.",
                schema(listOf("folder_id", "title"), FOLDER, "title" to ("string" to "The name of the task."), "description" to ("string" to "What is to be done.")), AssistantRight.TASKS, changes = true,
            ),
            AssistantTool(
                "set_task", "Changes a task: its status, start, end, priority or comment. Only what is given is changed.",
                schema(
                    listOf("note_id"), NOTE, "status" to ("string" to "One of: $STATES."), "start" to ("string" to "$WHEN Empty to clear."),
                    "end" to ("string" to "$WHEN Empty to clear."), "priority" to ("integer" to "A number from 1; 0 to clear."), "comment" to ("string" to "A short comment."),
                ),
                AssistantRight.TASKS, changes = true,
            ),
            AssistantTool("read_budget", "Gives the items and groups of a budget note with their ids, sums and goals.", schema(listOf("note_id"), NOTE), AssistantRight.READ),
            AssistantTool(
                "budget_add_item", "Adds an item to a budget note.",
                schema(listOf("note_id", "title"), NOTE, "title" to ("string" to "The name of the item."), "sum" to ("number" to "Its sum."), "group_id" to ("string" to "The id of the group to put it in; without it - under the total.")),
                AssistantRight.BUDGET, changes = true, confirm = true,
            ),
            AssistantTool(
                "budget_change_sum", "Changes the sum of an item of a budget note.",
                schema(listOf("note_id", "item_id", "mode", "amount"), NOTE, "item_id" to ("string" to "The id of the item, as read_budget gave it."), "mode" to ("string" to "One of: add, subtract, set."), "amount" to ("number" to "The sum.")),
                AssistantRight.BUDGET, changes = true, confirm = true,
            ),
            AssistantTool("list_reminders", "Gives the reminders of a note that still wait.", schema(listOf("note_id"), NOTE), AssistantRight.REMINDERS),
            AssistantTool("add_reminder", "Sets a reminder of a note.", schema(listOf("note_id", "at"), NOTE, "at" to ("string" to WHEN)), AssistantRight.REMINDERS, changes = true),
            AssistantTool("create_folder", "Makes a new folder.", schema(listOf("name"), "name" to ("string" to "The name of the folder."), "kind" to ("string" to "notes (the usual) or tasks.")), AssistantRight.FOLDERS, changes = true),
            AssistantTool("rename_folder", "Changes the name of a folder.", schema(listOf("folder_id", "name"), FOLDER, "name" to ("string" to "The new name.")), AssistantRight.FOLDERS, changes = true),
            AssistantTool("open_note", "Opens a note on the screen of Notio.", schema(listOf("note_id"), NOTE), AssistantRight.SCREEN),
            AssistantTool("open_folder", "Opens a folder on the screen of Notio.", schema(listOf("folder_id"), FOLDER), AssistantRight.SCREEN),
            AssistantTool("list_appearance", "Lists the settings of how Notio looks, with the values each may take.", schema(emptyList()), AssistantRight.APPEARANCE),
            AssistantTool(
                "set_appearance", "Changes one setting of how Notio looks.",
                schema(listOf("name", "value"), "name" to ("string" to "The name, as list_appearance gave it."), "value" to ("string" to "One of the allowed values.")), AssistantRight.APPEARANCE,
            ),
            AssistantTool("list_settings", "Lists the other settings of Notio the assistant may change, with the values each may take.", schema(emptyList()), AssistantRight.SETTINGS),
            AssistantTool(
                "set_setting", "Changes one of the other settings of Notio.",
                schema(listOf("name", "value"), "name" to ("string" to "The name, as list_settings gave it."), "value" to ("string" to "One of the allowed values.")), AssistantRight.SETTINGS,
            ),
        )
    }
}
