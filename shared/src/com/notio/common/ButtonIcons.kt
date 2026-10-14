package com.notio.common

/**
 * Which icon stands for a button's words (Android D307: "every caption becomes an icon, the caption is its hint — everywhere").
 * A button knows the KEY of its text (`action_cancel`, `settings_units_open` …); the key says what the button does, in every
 * language. [nameFor] gives the name of the icon for a key, `null` when no icon says it — such a button keeps its words.
 * Shared by the phone and the desktop, which both have the icons by these names (`AppIcons.byName`).
 *
 * The rules are tried in order; the first whose word is found in the key wins, so the narrower meaning stands before the wider
 * one ("unlock" before "lock", "hide" before "show").
 */
object ButtonIcons {
    private val EXACT = mapOf(
        "action_cancel" to "Close", "action_close" to "Close", "action_no" to "Close",
        "action_ok" to "Check", "action_done" to "Check", "action_yes" to "Check", "action_apply" to "Check", "action_continue" to "ArrowForward",
        "action_save" to "Save", "action_create" to "Add", "action_add" to "Add",
        "action_delete" to "Delete", "action_copy" to "ContentCopy", "action_edit" to "Edit", "action_back" to "ArrowBack",
        "action_share" to "Share", "action_move" to "ArrowForward", "action_more" to "MoreVert",
        "trash_empty_action" to "DeleteForever", "reminder_action_complete" to "Check",
        // The numbers of a list line are changed by + / − / =, as a budget's are (D311): not the pencil of "change".
        "list_change_quantity" to "PlusMinus", "list_change_price" to "PlusMinus",
        // Not the two arrows of "sync": the network is read again (D348).
        "sync_recheck" to "Refresh",
        // A setting of this device, not a change of a text (D357).
        "sync_port_edit" to "Tune",
        // A right taken back (D354): the cross of "no", not a deletion.
        "perm_revoke" to "Close", "perm_revoke_all" to "Close",
    )

    private val RULES: List<Pair<Regex, String>> = listOf(
        "cancel" to "Close",
        "copied|saved|complete|completed" to "Check",
        "delete_forever|purge|erase|wipe" to "DeleteForever",
        "delete|remove|clear|empty_trash|forget" to "Delete",
        "select_all" to "SelectAll",
        "deselect|select_none" to "Deselect",
        "undo" to "Undo",
        "redo" to "Redo",
        "unlock|decrypt" to "LockOpen",
        "biometric|lock|encrypt|password|secret|pattern" to "Lock",
        "hide" to "VisibilityOff",
        "export|download|save_as|backup_now|to_file" to "FileDownload",
        "import|upload|choose_file|from_file" to "FileUpload",
        "add|create|new" to "Add",
        "restore|recover|put_back" to "RestoreFromTrash",
        "copy|duplicate|clone" to "ContentCopy",
        "share" to "Share",
        "send" to "Send",
        "search|find" to "Search",
        "sync" to "Sync",
        "refresh|reload|retry|again|restart|reset|default" to "Refresh",
        "snooze|later|postpone" to "MoreTime",
        "wait|waiting" to "Schedule",
        "stop|silence|mute" to "Stop",
        "pause" to "PauseCircle",
        "play|start|test|run|preview|try|now|anyway" to "PlayArrow",
        "record|mic|voice|audio" to "Mic",
        "photo|camera|picture|image" to "PhotoCamera",
        "video" to "Videocam",
        "remind|reminder|notif|notification|alarm|sound|melody|ring" to "Notifications",
        "today|calendar|date" to "Event",
        "time|stamp|clock" to "Schedule",
        "sort|order" to "Sort",
        "tag" to "Label",
        "colou?r|theme|background|palette" to "Brush",
        "folder" to "Folder",
        "mail|smtp|email" to "Mail",
        "key|code|totp|recovery" to "Key",
        "log|history" to "History",
        "help|hint|forgot|about|licen" to "Help",
        "info|details" to "Info",
        "settings|options|configure|setup|tune" to "Tune",
        "rename|edit|change" to "Edit",
        "pick|choose|select" to "Checklist",
        "save|apply|confirm|done|accept|allow" to "Check",
        "close|dismiss|deny|skip" to "Close",
        "back|previous|earlier" to "ArrowBack",
        "next|continue|forward|move" to "ArrowForward",
        "open|show|view|reveal|go" to "OpenInNew",
    ).map { (words, icon) -> Regex("(^|_)($words)($|_|s_|s$)") to icon }

    private val cache = HashMap<String, String?>()

    fun nameFor(key: String): String? = synchronized(cache) {
        cache.getOrPut(key) {
            // The first word of a key is mostly where the button lives ("lock_forgot", "settings_units_open"), not what it does:
            // the rest is asked first, the whole key only when the rest says nothing.
            val rest = key.substringAfter('_', "")
            EXACT[key] ?: rest.takeIf { it.isNotEmpty() }?.let(::byRules) ?: byRules(key)
        }
    }

    private fun byRules(words: String): String? = RULES.firstOrNull { it.first.containsMatchIn(words) }?.second

    /** A button that destroys something is drawn in the colour of an error. */
    fun isDanger(key: String): Boolean = nameFor(key).let { it == "Delete" || it == "DeleteForever" }
}
