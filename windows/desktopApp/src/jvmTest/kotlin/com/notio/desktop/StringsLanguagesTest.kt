package com.notio.desktop

import com.notio.shared.domain.NoteType
import com.notio.shared.transfer.TransferError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The desktop's own texts in the ten languages (row 19 of the plan): Russian and English as written, the others from the phone's
 * texts or from `desktop_phrases.json` — none left in English, every phrase file complete.
 */
class StringsLanguagesTest {
    private val others = AndroidRes.LANGUAGES - setOf("en", "ru")

    @Test
    fun russianAndEnglishStayAsWritten() {
        val ru = Strings(AndroidRes.forLanguage("ru"))
        val en = Strings(AndroidRes.forLanguage("en"))
        assertEquals("Новая заметка", ru.newNote)
        assertEquals("New note", en.newNote)
        assertEquals("5 заметок", ru.notesCount(5))
        assertEquals("Sum 1 / goal 2", en.budgetSumGoal("1", "2"))
    }

    @Test
    fun theOtherLanguagesAreNotEnglish() {
        val en = Strings(AndroidRes.forLanguage("en"))
        for (lang in others) {
            val s = Strings(AndroidRes.forLanguage(lang))
            // A text of the phone, a desktop phrase, a text with values, a plural, a line of the budget log, a kind of note.
            assertNotEquals(en.newNote, s.newNote, lang)
            assertNotEquals(en.transferError(TransferError.WRONG_PASSWORD), s.transferError(TransferError.WRONG_PASSWORD), lang)
            assertNotEquals(en.keepInTray, s.keepInTray, lang)
            assertNotEquals(en.budgetSumGoal("1", "2"), s.budgetSumGoal("1", "2"), lang)
            assertTrue("1" in s.budgetSumGoal("1", "2") && "2" in s.budgetSumGoal("1", "2"), lang)
            assertEquals(AndroidRes.forLanguage(lang).plural("notes_count", 5, 5), s.notesCount(5), lang)
            assertNotEquals(en.kind(NoteType.DRAWING), s.kind(NoteType.DRAWING), lang)
            val log = com.notio.shared.transfer.BudgetLogRecord("l", "n", 0, com.notio.shared.domain.BudgetLogKind.GOAL, "Rent", 100, 200)
            assertNotEquals(en.budgetLogLine(log, { it.toString() }), s.budgetLogLine(log, { it.toString() }), lang)
        }
    }

    @Test
    fun everyPhraseFileHasEveryPhrase() {
        val keys = others.map { lang -> lang to phraseKeys(lang) }
        val first = keys.first().second
        assertTrue(first.isNotEmpty())
        keys.forEach { (lang, k) -> assertEquals(first, k, lang) }
    }

    private fun phraseKeys(lang: String): Set<String> {
        val text = javaClass.classLoader.getResourceAsStream("strings/values-$lang/desktop_phrases.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return (kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject).keys
    }
}
