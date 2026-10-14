package com.notebudget.app.feature.help

import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.core.ui.AppIcons
import com.notebudget.app.core.ui.tip.HelpLink
import com.notio.common.HelpMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D332: the help is described by names shared with the desktop ([HelpMap]); on the phone every one of them is a string of the
 * app and an icon of the app, so no tab, text or icon of the help can be missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HelpMapTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /**
     * A name of the help is a string only through the table of the app (D336): the release build keeps no string that is asked
     * for by its name alone, so a look into the resources by name would pass here and fail on a phone.
     */
    private fun isString(key: String) = HELP_STRINGS[key]?.let { app.getString(it).isNotBlank() } == true

    @Test
    fun `every name of the help is a string and an icon of the app`() {
        for (category in HelpMap.CATEGORIES) {
            assertTrue(category.titleKey, isString(category.titleKey))
            for (section in category.sections) {
                assertTrue(section.titleKey, isString(section.titleKey))
                assertTrue(section.bodyKey, isString(section.bodyKey))
                for (item in section.items) {
                    assertTrue(item.label, isString(item.label))
                    assertNotNull(item.icon, AppIcons.byName(item.icon))
                }
            }
        }
        HelpMap.GLOSSARY_KEYS.forEach { assertTrue(it, isString(it)) }
        for (key in listOf("help_icons_title", "help_search_hint", "search_clear", "search_no_results")) assertTrue(key, isString(key))
        // The table says what its names say: a name never stands for another string.
        for ((name, id) in HELP_STRINGS) assertEquals(name, app.resources.getResourceEntryName(id))
    }

    @Test
    fun `the search of the help reads its words through the table`() {
        val found = HelpMap.search("sync") { key -> HELP_STRINGS[key]?.let { app.getString(it) }.orEmpty() }
        assertTrue(found.any { it.titleKey == "help_sync_title" })
    }

    @Test
    fun `a pressed hint opens the help at its section and closing it leaves it`() {
        HelpLink.open("sync")
        assertTrue(HelpLink.open)
        assertEquals("sync", HelpLink.topic)
        val (category, section) = HelpMap.find(HelpLink.topic)
        assertEquals("sync", HelpMap.CATEGORIES[category].sections[section].id)
        HelpLink.close()
        assertFalse(HelpLink.open)
    }
}
