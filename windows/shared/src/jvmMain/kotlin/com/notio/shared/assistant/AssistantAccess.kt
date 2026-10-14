package com.notio.shared.assistant

/**
 * What the assistant may do with one note or one folder (D145; docs/ASSISTANT_PLAN_RU.md, section 8): the switch "Assistant" and,
 * under it, "May go to Telegram" and "May go to an outside model"; apart from them, "Reminders to Telegram". Kept as bits, one
 * number per note or folder; nothing kept means everything off, which is how every note starts.
 */
object AssistantAccess {
    /** The assistant sees the note at all: through the chat of Notio and MCP, with a model of the person's own. */
    const val ASSISTANT = 1

    /** The note may be shown and changed from Telegram. */
    const val TELEGRAM = 2

    /** The text of the note may be handed to a model outside the person's devices. */
    const val EXTERNAL = 4

    /** A reminder of the note is passed on to Telegram; works with [ASSISTANT] off too. */
    const val REMINDERS_TELEGRAM = 8

    const val NONE = 0

    /** Whether a note or folder with [flags] is there for a request that came through [channel]. */
    fun visible(flags: Int, channel: Channel): Boolean =
        flags and ASSISTANT != 0 &&
            (!channel.telegram || flags and TELEGRAM != 0) &&
            (!channel.externalModel || flags and EXTERNAL != 0)

    /** What a note made by the assistant itself gets: what it was made through, so that the assistant sees its own note. */
    fun of(channel: Channel): Int =
        ASSISTANT or (if (channel.telegram) TELEGRAM else 0) or (if (channel.externalModel) EXTERNAL else 0)
}

/**
 * Where a request came from and where its text goes: [source] is written into the journal ("chat", "mcp", "telegram");
 * [telegram] - the words pass through Telegram; [externalModel] - the model that reads the notes is not on the person's devices.
 */
data class Channel(val source: String, val telegram: Boolean = false, val externalModel: Boolean = false) {
    companion object {
        val CHAT = Channel("chat")
        val MCP = Channel("mcp")
        val TELEGRAM = Channel("telegram", telegram = true)
    }
}

/**
 * What the assistant is allowed to do at all (section 9 of the plan): by kinds of action, all off until the person turns them
 * on. Its own rights, the access of notes, the lock, passwords, encryption, pairing and emptying the trash are not here and are
 * given by no right.
 */
enum class AssistantRight {
    READ, CREATE, EDIT, MOVE, MARKS, TRASH, LISTS, TASKS, BUDGET, REMINDERS, FOLDERS, SCREEN, APPEARANCE, SETTINGS;

    companion object {
        /** Reads the stored names; an unknown name is dropped. */
        fun decode(text: String?): Set<AssistantRight> =
            text.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.toSet()

        fun encode(rights: Set<AssistantRight>): String = entries.filter { it in rights }.joinToString(",") { it.name }
    }
}

/** What the assistant asks of the app outside the notes: the sort of a folder, the screen. Without an app (tests) nothing happens. */
interface AssistantHost {
    /** The sort of the notes of [folderId] as stored (`NAME:asc`), `null` when the folder has none of its own. */
    fun folderSort(folderId: String): String? = null

    /** Sets it; `null` takes the folder's own sort away. */
    fun setFolderSort(folderId: String, sort: String?) {}

    fun openNote(noteId: String) {}

    fun openFolder(folderId: String) {}

    /** The reminders of a note that still wait: the id of each and when it rings (UTC millis). */
    fun reminders(noteId: String): List<Pair<String, Long>> = emptyList()

    /** Sets a reminder of a note for [at]; its id, or `null` when it could not be set (a time in the past). */
    fun addReminder(noteId: String, at: Long): String? = null

    fun removeReminder(reminderId: String) {}

    /**
     * The settings of the app a model may see and change: the name -> the value now and the values it may take, in words.
     * [appearance] - how the app looks (the right APPEARANCE), otherwise the other settings (the right SETTINGS). The trash,
     * backups, the lock, passwords, sync and the assistant itself are in neither.
     */
    fun settings(appearance: Boolean): Map<String, Pair<String, String>> = emptyMap()

    /** The value of one such setting, `null` when there is none of this name. */
    fun setting(name: String): String? = null

    /** Sets it; `null` - done, otherwise why not, in words for the model. */
    fun setSetting(name: String, value: String): String? = "There is no such setting."

    /** The app is locked: no command works. */
    fun locked(): Boolean = false

    object None : AssistantHost
}
