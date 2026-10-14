package com.notio.common

/**
 * The shape of the help (Android D332: "help with tabs by categories and sub-tabs … in every section its icons too, how each
 * looks and what it is … while a hint is shown, a press on it goes to the part of the help it belongs to"). One description for
 * the phone and the desktop: the categories (the tabs), the sections of each (the sub-tabs), the icons and buttons a section
 * shows, and which section a button belongs to ([topicForKey]).
 *
 * Everything here is a name: a section `budget` has the texts `help_budget_title` and `help_budget_body`; a category has the
 * key of its title; an icon is a name of `AppIcons.byName` and the key of the words that are its hint in the app. The tests of
 * both apps check that every name exists.
 */
object HelpMap {
    /** An icon as the help shows it: [icon] is its name in `AppIcons`, [label] the key of its words (its hint in the app). */
    class Item(val icon: String, val label: String)

    /** A sub-tab: the texts `help_<id>_title` / `help_<id>_body` and the icons that are met there. */
    class Section(val id: String, val items: List<Item> = emptyList()) {
        val titleKey: String get() = "help_${id}_title"
        val bodyKey: String get() = "help_${id}_body"
    }

    /** A tab. [titleKey] is the key of its name. A category without sections is the glossary. */
    class Category(val id: String, val titleKey: String, val sections: List<Section>)

    const val GLOSSARY = "glossary"
    val GLOSSARY_KEYS = listOf("folder", "note", "group", "item", "goal", "remainder", "backup").map { "glossary_$it" }

    private fun items(vararg pairs: Pair<String, String>) = pairs.map { Item(it.first, it.second) }

    val CATEGORIES: List<Category> = listOf(
        Category(
            "start", "help_cat_start",
            listOf(
                Section("start", items("Search" to "search_open", "Event" to "calendar_title", "Notifications" to "action_reminders", "Sync" to "settings_cat_sync", "Delete" to "trash_title", "MoreVert" to "action_more", "Tune" to "home_settings")),
                Section("hints", items("Help" to "help_title")),
                Section("folders", items("NewFolder" to "home_add_folder", "Add" to "home_new_note", "Edit" to "action_edit", "ArrowForward" to "action_move", "Delete" to "action_delete")),
                Section("tasks", items("Check" to "task_completed", "CheckBoxBlank" to "task_filter_open", "CheckBox" to "task_filter_done", "Delete" to "action_delete", "TaskAlt" to "task_state_done", "PlayCircle" to "task_state_in_progress", "PauseCircle" to "task_state_paused", "Sort" to "sort_title")),
                Section("emoji"),
                Section("notes", items("Add" to "home_new_note", "Label" to "action_tags", "ContentCopy" to "action_clone", "Brush" to "action_note_color", "Notifications" to "action_reminders", "ViewCards" to "note_view_cards", "ViewList" to "note_view_list", "ViewGrid" to "note_view_grid")),
                Section("search", items("Search" to "search_open", "Close" to "search_close", "Search" to "note_search_open", "ArrowBack" to "note_search_previous", "ArrowForward" to "note_search_next")),
                Section("patterns"),
                Section("sort", items("Sort" to "sort_title")),
                Section("order"),
                Section("trash", items("Delete" to "trash_title", "RestoreFromTrash" to "trash_restore", "DeleteForever" to "trash_delete_forever", "DeleteForever" to "trash_empty_action", "Search" to "trash_search")),
            ),
        ),
        Category(
            "notes", "search_scope_notes",
            listOf(
                Section("text", items("Description" to "md_mode_preview", "Edit" to "md_mode_edit", "Checklist" to "md_checklist")),
                Section("format"),
                Section("lists", items("PlusMinus" to "list_change_quantity", "PlusMinus" to "list_change_price", "AddComment" to "list_comment", "Functions" to "list_add_total")),
                Section("price"),
                Section("schedule", items("Add" to "schedule_add_item", "Calculate" to "schedule_add_calc")),
                Section("zoom"),
                Section("backgrounds"),
                Section("undo", items("Undo" to "action_undo")),
            ),
        ),
        Category(
            "budget", "note_type_budget",
            listOf(
                Section("budget", items(
                    "PlusMinus" to "budget_adjust", "History" to "budget_log_button", "Tune" to "budget_layout_button",
                    "UnfoldLess" to "budget_collapse_all", "UnfoldMore" to "budget_expand_all",
                    "Visibility" to "budget_show_amounts", "VisibilityOff" to "budget_hide_amounts",
                    "Add" to "budget_add_item", "Add" to "budget_add_group", "MoreVert" to "action_more",
                    "Delete" to "action_delete", "RestoreFromTrash" to "budget_log_restore",
                )),
                Section("income", items("Event" to "budget_merge_when", "Refresh" to "budget_merge_repeat", "PlayArrow" to "budget_merge_now")),
            ),
        ),
        Category(
            "media", "help_cat_media",
            listOf(
                Section("drawing", items("Brush" to "drawing_tool_brush", "Undo" to "drawing_undo", "FlipToFront" to "drawing_forward", "FlipToBack" to "drawing_backward")),
                Section("blueprint", items("Straighten" to "note_type_blueprint", "ContentCopy" to "drawing_duplicate")),
                Section("audio", items("Mic" to "audio_record", "Stop" to "audio_stop", "PlayCircle" to "audio_play", "PauseCircle" to "audio_pause")),
                Section("media", items("PhotoCamera" to "media_take_photo", "Videocam" to "media_record_video", "ScreenRecord" to "media_record_screen", "Fullscreen" to "media_full_screen", "SelectAll" to "media_select_all")),
            ),
        ),
        Category(
            "reminders", "reminders_title",
            listOf(
                Section("reminders", items(
                    "Add" to "reminders_add", "Edit" to "reminders_edit", "NotificationsOff" to "reminders_cancel", "Check" to "reminder_action_complete",
                    "VolumeOff" to "reminder_stop_sound", "Refresh" to "reminder_reset", "Delete" to "action_delete", "Event" to "calendar_title",
                )),
            ),
        ),
        Category(
            "data", "help_cat_data",
            listOf(
                Section("sync", items("Sync" to "sync_sync", "Schedule" to "sync_wait", "Search" to "sync_find", "Refresh" to "sync_recheck", "Tune" to "sync_port_edit", "PlayArrow" to "sync_test", "Delete" to "sync_remove", "History" to "sync_log")),
                Section("export", items("FileDownload" to "export_note_action", "FileUpload" to "home_import_note")),
                Section("share", items("Share" to "action_share", "ContentCopy" to "action_copy")),
                Section("backup"),
            ),
        ),
        Category(
            "settings", "home_settings",
            listOf(
                Section("settings", items("Tune" to "home_settings", "Help" to "help_title")),
                Section("lock", items("Lock" to "lock_turn_on", "LockOpen" to "lock_unlock", "Key" to "lock_recovery_section")),
                Section("permissions", items("Check" to "perm_allow", "Close" to "perm_revoke", "Close" to "perm_revoke_all", "Tune" to "perm_open_settings")),
                Section("privacy", items("VisibilityOff" to "note_hide_on", "Visibility" to "note_hide_show")),
                Section("assistant", items("Chat" to "assistant_chat", "Check" to "assistant_confirm", "Delete" to "assistant_chat_clear")),
            ),
        ),
        Category(GLOSSARY, "glossary_title", emptyList()),
    )

    /** Where [topic] (the id of a section, or of a category) is: the index of its tab and of its sub-tab. The start when unknown. */
    fun find(topic: String?): Pair<Int, Int> {
        if (topic == null) return 0 to 0
        CATEGORIES.forEachIndexed { c, category ->
            if (category.id == topic && category.sections.none { it.id == topic }) return c to 0
            category.sections.forEachIndexed { s, section -> if (section.id == topic) return c to s }
        }
        return 0 to 0
    }

    /**
     * A place of the help where a search found its words (D335): the tab and the sub-tab to open, the key of its title, and
     * a piece of the text around the first match — [from] until [to] are the found words in [snippet] (`-1` when the match is
     * in the title alone).
     */
    class Hit(val category: Int, val section: Int, val titleKey: String, val snippet: String, val from: Int, val to: Int)

    /**
     * The sections whose title, text or names of icons have [query] (any case), the ones found by their title first; the
     * glossary is one place. [text] gives the words of a key in the language of the app. Less than two letters find nothing.
     */
    fun search(query: String, text: (String) -> String): List<Hit> {
        val words = query.trim()
        if (words.length < 2) return emptyList()
        val byTitle = ArrayList<Hit>()
        val byText = ArrayList<Hit>()
        fun look(category: Int, section: Int, titleKey: String, bodies: List<String>) {
            val title = text(titleKey)
            val body = bodies.firstOrNull { it.contains(words, ignoreCase = true) }
            if (body != null) {
                val at = body.indexOf(words, ignoreCase = true)
                val start = (at - SNIPPET_BEFORE).coerceAtLeast(0).let { s -> if (s == 0) 0 else body.indexOf(' ', s).let { sp -> if (sp in 0 until at) sp + 1 else s } }
                val end = (at + words.length + SNIPPET_AFTER).coerceAtMost(body.length)
                val piece = (if (start > 0) "…" else "") + body.substring(start, end) + (if (end < body.length) "…" else "")
                val from = at - start + (if (start > 0) 1 else 0)
                val hit = Hit(category, section, titleKey, piece, from, from + words.length)
                if (title.contains(words, ignoreCase = true)) byTitle += hit else byText += hit
            } else if (title.contains(words, ignoreCase = true)) {
                byTitle += Hit(category, section, titleKey, bodies.firstOrNull().orEmpty().take(SNIPPET_BEFORE + SNIPPET_AFTER), -1, -1)
            }
        }
        CATEGORIES.forEachIndexed { c, category ->
            if (category.sections.isEmpty()) look(c, 0, category.titleKey, GLOSSARY_KEYS.map(text))
            category.sections.forEachIndexed { s, section ->
                look(c, s, section.titleKey, listOf(text(section.bodyKey)) + section.items.map { text(it.label) })
            }
        }
        return byTitle + byText
    }

    private const val SNIPPET_BEFORE = 50
    private const val SNIPPET_AFTER = 90

    /** The first word of a key says where its button lives; the narrower beginning stands before the wider one. */
    private val BY_KEY: List<Pair<String, String>> = listOf(
        "note_search" to "search", "search" to "search",
        "sync" to "sync",
        "budget" to "budget",
        "reminder" to "reminders", "calendar" to "reminders", "alarm" to "reminders",
        "trash" to "trash",
        "lock" to "lock",
        "perm" to "permissions",
        "drawing" to "drawing",
        "audio" to "audio",
        "media" to "media", "rec" to "media",
        "schedule" to "schedule",
        "list" to "lists",
        "md" to "text",
        "export" to "export", "share" to "share",
        "import" to "backup", "backup" to "backup",
        "folder" to "folders", "home" to "folders",
        "tags" to "notes", "task" to "notes", "note" to "notes",
        "sort" to "sort", "zoom" to "zoom",
        "settings" to "settings",
    )

    /**
     * The section of the help a button with the text of [key] belongs to (`sync_test` -> `sync`), `null` when its key does not
     * say: the screen it stands on answers then.
     */
    fun topicForKey(key: String?): String? {
        if (key == null) return null
        return BY_KEY.firstOrNull { (start, _) -> key == start || key.startsWith(start + "_") || (start == "reminder" && key.startsWith("reminders")) }?.second
    }

    /** The section of the help for a kind of note (`NoteType.id`). */
    fun topicForNoteType(type: String?): String = when (type) {
        "TEXT" -> "text"
        "WORD" -> "format"
        "LIST" -> "lists"
        "BUDGET" -> "budget"
        "SCHEDULE" -> "schedule"
        "DRAWING" -> "drawing"
        "BLUEPRINT" -> "blueprint"
        "AUDIO" -> "audio"
        "PHOTO", "VIDEO", "SCREEN" -> "media"
        else -> "notes"
    }
}
