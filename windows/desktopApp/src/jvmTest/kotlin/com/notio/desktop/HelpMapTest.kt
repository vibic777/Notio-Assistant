package com.notio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.notio.common.HelpMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D113 (Android D332): the help has a tab for every category and a tab for every section; every name it is described by is a
 * text of every language and an icon of the app; a hint knows the section it leads to.
 */
@OptIn(ExperimentalTestApi::class)
class HelpMapTest {
    @Test
    fun everyNameOfTheHelpIsATextOfEveryLanguageAndAnIconOfTheApp() {
        for (lang in AndroidRes.LANGUAGES) {
            val res = AndroidRes.forLanguage(lang)
            for (category in HelpMap.CATEGORIES) {
                assertTrue(res.has(category.titleKey), "$lang: ${category.titleKey}")
                for (section in category.sections) {
                    assertTrue(res.has(section.titleKey), "$lang: ${section.titleKey}")
                    assertTrue(res.has(section.bodyKey), "$lang: ${section.bodyKey}")
                    for (item in section.items) assertTrue(res.has(item.label), "$lang: ${item.label}")
                }
            }
            HelpMap.GLOSSARY_KEYS.forEach { assertTrue(res.has(it), "$lang: $it") }
            assertTrue(res.has("help_icons_title"), lang)
        }
        for (item in HelpMap.CATEGORIES.flatMap { it.sections }.flatMap { it.items }) assertNotNull(AppIcons.byName(item.icon), item.icon)
    }

    @Test
    fun everySectionIsThereOnceAndIsFoundByItsName() {
        val ids = HelpMap.CATEGORIES.flatMap { it.sections }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // The sections the help had as one long page are all still there.
        val old = listOf(
            "start", "folders", "emoji", "notes", "budget", "text", "lists", "price", "search", "patterns", "export", "zoom", "sort",
            "backgrounds", "reminders", "lock", "drawing", "blueprint", "audio", "media", "schedule", "order", "undo", "format", "share",
            "sync", "settings", "trash", "backup", "privacy",
        )
        assertTrue(ids.containsAll(old), (old - ids.toSet()).toString())
        for (id in ids) {
            val (c, s) = HelpMap.find(id)
            assertEquals(id, HelpMap.CATEGORIES[c].sections[s].id)
        }
        assertEquals(0 to 0, HelpMap.find(null))
        assertEquals(0 to 0, HelpMap.find("no such section"))
        assertEquals(HelpMap.CATEGORIES.lastIndex to 0, HelpMap.find(HelpMap.GLOSSARY))
    }

    @Test
    fun aButtonLeadsToTheSectionItsWordsLiveIn() {
        assertEquals("sync", HelpMap.topicForKey("sync_test"))
        assertEquals("budget", HelpMap.topicForKey("budget_item_merge"))
        assertEquals("reminders", HelpMap.topicForKey("reminders_add"))
        assertEquals("reminders", HelpMap.topicForKey("reminder_stop_sound"))
        assertEquals("search", HelpMap.topicForKey("note_search_open"))
        assertEquals("notes", HelpMap.topicForKey("note_view_cards"))
        assertEquals("media", HelpMap.topicForKey("rec_start"))
        // Words that do not say where they live: the screen answers.
        assertNull(HelpMap.topicForKey("action_save"))
        assertNull(HelpMap.topicForKey(null))
        // Every answer is a section of the help.
        val ids = HelpMap.CATEGORIES.flatMap { it.sections }.map { it.id }.toSet()
        for (key in listOf("sync_x", "budget_x", "calendar_x", "trash_x", "lock_x", "drawing_x", "audio_x", "media_x", "schedule_x", "list_x", "md_x", "export_x", "share_x", "import_x", "folder_x", "home_x", "tags_x", "sort_x", "zoom_x", "settings_x")) {
            assertTrue(HelpMap.topicForKey(key) in ids, key)
        }
        for (type in listOf("TEXT", "WORD", "LIST", "BUDGET", "SCHEDULE", "DRAWING", "BLUEPRINT", "AUDIO", "PHOTO", "VIDEO", "SCREEN", "TASK", "?")) {
            assertTrue(HelpMap.topicForNoteType(type) in ids, type)
        }
    }

    @Test
    fun theHelpOpensAtTheSectionOfAHintAndItsTabsLeadOn() = runComposeUiTest {
        val res = AndroidRes.forLanguage("en")
        setContent { CompositionLocalProvider(LocalRes provides res) { MaterialTheme { HelpScreen("sync") } } }
        // The tab of the category and the tab of the section are the chosen ones; the text and the icons of the section are shown.
        onNodeWithText(res.str("help_cat_data")).assertIsSelected()
        // The name of the section is its tab and the heading of its text.
        onAllNodesWithText(res.str("help_sync_title")).onFirst().assertIsSelected()
        onNodeWithText(res.str("help_icons_title")).assertExists()
        onNodeWithText(res.str("sync_test")).assertExists()
        // Another category: its first section.
        onNodeWithText(res.str("note_type_budget")).performClick()
        onNodeWithText(res.str("budget_log_button")).assertExists()
        // The glossary has no sections.
        onNodeWithText(res.str("glossary_title")).performClick()
        onNodeWithText(res.str("glossary_goal")).assertExists()
    }

    /** D116 (Android D335): the search of the help finds a word in the titles, the texts and the names of the icons. */
    @Test
    fun theSearchFindsWordsInTheTitlesTextsAndIcons() {
        val ru = AndroidRes.forLanguage("ru")
        fun find(query: String) = HelpMap.search(query) { if (ru.has(it)) ru.str(it) else "" }
        // In a text, any case; the found words are marked in the piece shown.
        val merge = find("СЛИТЬ С ДРУГОЙ")
        assertTrue(merge.any { it.titleKey == "help_budget_title" })
        merge.first { it.titleKey == "help_budget_title" }.let { assertEquals("слить с другой", it.snippet.substring(it.from, it.to).lowercase()) }
        // In a title: such a place comes first.
        assertEquals("help_sync_title", find("синхрониз").first().titleKey)
        // In the name of an icon of a section, and in the glossary.
        assertTrue(find(ru.str("budget_log_button")).any { it.titleKey == "help_budget_title" })
        assertTrue(find(ru.str("glossary_goal").take(12)).any { it.titleKey == "glossary_title" })
        // Every place found can be opened.
        for (hit in find("заметк")) assertTrue(hit.category in HelpMap.CATEGORIES.indices && hit.section >= 0)
        // One letter, or nothing of the kind: nothing.
        assertTrue(find("а").isEmpty())
        assertTrue(find("qwertyuiop").isEmpty())
    }

    @Test
    fun theSearchOfTheHelpOpensTheSectionFound() = runComposeUiTest {
        val res = AndroidRes.forLanguage("en")
        setContent { CompositionLocalProvider(LocalRes provides res) { MaterialTheme { HelpScreen() } } }
        onNodeWithText(res.str("help_search_hint")).performTextInput("authenticator")
        // The place found names its tab and its section; a press opens it and ends the search.
        onNodeWithText(res.str("home_settings") + " › " + res.str("help_lock_title")).performClick()
        onAllNodesWithText(res.str("help_lock_title")).onFirst().assertIsSelected()
        onNodeWithText(res.str("help_search_hint")).assertExists()
    }

    @Test
    fun aPressedHintOpensTheHelpAtItsSection() {
        HelpLink.around = "trash"
        HelpLink.open("budget")
        assertTrue(HelpLink.open)
        assertEquals("budget", HelpLink.topic)
        HelpLink.close()
        // A button whose words do not say where it lives: the section of the screen on show.
        HelpLink.open(null)
        assertEquals("trash", HelpLink.topic)
        HelpLink.close()
        assertTrue(!HelpLink.open)
    }
}
