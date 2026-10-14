package com.notio.desktop

import com.notio.shared.domain.CardTint
import com.notio.shared.domain.ColorTheme
import com.notio.shared.domain.FontSize
import com.notio.shared.domain.ThemeMode
import kotlinx.coroutines.runBlocking

/**
 * The settings of the app the assistant may see and change (D147; docs/ASSISTANT_PLAN_RU.md, section 9): a short list by names,
 * each with the values it may take. What is not in the list cannot be reached by any command: the trash, backups, the lock,
 * passwords, encryption, sync, and the assistant's own rights and access.
 */
class AssistantSettingsBridge(private val settings: AppSettings) {
    private class Entry(val appearance: Boolean, val allowed: String, val read: () -> String, val write: (String) -> Boolean)

    private fun <E : Enum<E>> choice(appearance: Boolean, values: List<E>, read: () -> E, write: suspend (E) -> Unit) = Entry(
        appearance, values.joinToString(", ") { it.name.lowercase() }, { read().name.lowercase() },
        { text -> values.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }?.let { runBlocking { write(it) }; true } ?: false },
    )

    private fun number(appearance: Boolean, range: IntRange, read: () -> Int, write: suspend (Int) -> Unit) = Entry(
        appearance, "a whole number from ${range.first} to ${range.last}", { read().toString() },
        { text -> text.trim().toIntOrNull()?.takeIf { it in range }?.let { runBlocking { write(it) }; true } ?: false },
    )

    private val entries: Map<String, Entry> = linkedMapOf(
        "theme" to choice(true, ThemeMode.entries, { settings.themeMode.value }, { settings.setThemeMode(it) }),
        // A colour of the person's own is picked by hand, not by a model.
        "color_theme" to choice(true, ColorTheme.entries.filter { it != ColorTheme.CUSTOM }, { settings.colorTheme.value }, { settings.setColorTheme(it) }),
        "font_size" to choice(true, FontSize.entries, { settings.fontSize.value }, { settings.setFontSize(it) }),
        "card_tint" to number(true, CardTint.MIN..CardTint.MAX, { settings.cardTint.value }, { settings.setCardTint(it) }),
        "preview_lines" to number(false, 0..2, { settings.previewLines.value }, { settings.setPreviewLines(it) }),
        "notice_seconds" to number(false, com.notio.common.NoticeTime.MIN..com.notio.common.NoticeTime.MAX, { settings.noticeSeconds.value }, { settings.setNoticeSeconds(it) }),
    )

    fun list(appearance: Boolean): Map<String, Pair<String, String>> =
        entries.filterValues { it.appearance == appearance }.mapValues { (_, entry) -> entry.read() to entry.allowed }

    fun get(name: String): String? = entries[name]?.read?.invoke()

    /** `null` - done; otherwise why not, in words for the model. */
    fun set(name: String, value: String): String? {
        val entry = entries[name] ?: return "There is no such setting."
        return if (entry.write(value)) null else "The value must be: " + entry.allowed + "."
    }
}
