package com.notio.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Android texts read on the desktop (D17): ten languages, escapes, formats and plurals as Android does them. */
class AndroidResTest {
    @Test
    fun everyLanguageLoadsItsOwnWords() {
        for (lang in AndroidRes.LANGUAGES) {
            val res = AndroidRes.forLanguage(lang)
            assertTrue(res.has("sync_wait"), lang)
            assertTrue(res.str("home_new_note").isNotBlank(), lang)
        }
        assertEquals("Ждать устройство", AndroidRes.forLanguage("ru").str("sync_wait"))
        assertEquals("New note", AndroidRes.forLanguage("en").str("home_new_note"))
        assertEquals("en", AndroidRes.forLanguage("pt-BR").language)
    }

    @Test
    fun formatsAndPluralsFollowAndroid() {
        val ru = AndroidRes.forLanguage("ru")
        assertEquals("Сумма 1 / цель 2", ru.str("budget_sum_goal", "1", "2"))
        assertEquals("1 заметка", ru.plural("notes_count", 1))
        assertEquals("3 заметки", ru.plural("notes_count", 3))
        assertEquals("11 заметок", ru.plural("notes_count", 11))
        assertEquals("2 notes", AndroidRes.forLanguage("en").plural("notes_count", 2))
    }

    @Test
    fun escapesAreRead() {
        assertEquals("it's \"x\"\nnext", AndroidRes.unescape("it\\'s \\\"x\\\"\\nnext"))
        assertEquals("a b", AndroidRes.unescape("  a\n      b "))
    }
}
