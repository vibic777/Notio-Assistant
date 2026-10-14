package com.notebudget.app.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The app ships in ten languages (D15, D128, D132): English (default), Russian, Belarusian, French, German, Spanish, Italian, Chinese, Japanese, Korean. These checks read the
 * resource files (cwd of unit tests is `app/`), so a string added in English only, or a help text left in
 * English, fails the build instead of reaching a phone.
 */
class LocalizationTest {
    private val languages = listOf("values-ru", "values-be", "values-fr", "values-de", "values-es", "values-it", "values-zh", "values-ja", "values-ko")

    private class Res(val strings: Map<String, String>, val plurals: Map<String, Set<String>>)

    private fun read(folder: String): Res {
        val file = File("src/main/res/$folder/strings.xml")
        check(file.isFile) { "missing ${file.absolutePath}" }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Set<String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = node.textContent
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[node.getAttribute("name")] = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
                }
            }
        }
        return Res(strings, plurals)
    }

    private val english = read("values")

    @Test
    fun `every language has every string and every plural of the default, except the app name`() {
        for (folder in languages) {
            val other = read(folder)
            val missing = (english.strings.keys - other.strings.keys) - setOf("app_name", "about_edition")
            val missingPlurals = english.plurals.keys - other.plurals.keys
            val extra = (other.strings.keys - english.strings.keys) + (other.plurals.keys - english.plurals.keys)
            assertTrue("$folder lacks strings: $missing", missing.isEmpty())
            assertTrue("$folder lacks plurals: $missingPlurals", missingPlurals.isEmpty())
            assertTrue("$folder has keys the default lacks: $extra", extra.isEmpty())
        }
    }

    @Test
    fun `the help and glossary are translated, not copied from English`() {
        // The texts of the sections; a title can be the very same word in another language ("Notes" in French).
        val helpKeys = english.strings.keys.filter { it.startsWith("help_") && it.endsWith("_body") }
        assertTrue("expected help sections", helpKeys.size >= 10)
        for (folder in languages) {
            val other = read(folder)
            val untranslated = helpKeys.filter { other.strings[it] == english.strings[it] }
            assertTrue("$folder help still in English: $untranslated", untranslated.isEmpty())
        }
    }

    @Test
    fun `plurals carry the categories each language needs`() {
        // CLDR: ru and be use one/few/many/other; es and it use one/many/other; zh, ja, ko only other; the rest one/other.
        val needed = mapOf(
            "values" to listOf("one", "other"), "values-fr" to listOf("one", "other"), "values-de" to listOf("one", "other"),
            "values-ru" to listOf("one", "few", "many", "other"), "values-be" to listOf("one", "few", "many", "other"),
            "values-es" to listOf("one", "many", "other"), "values-it" to listOf("one", "many", "other"),
            "values-zh" to listOf("other"), "values-ja" to listOf("other"), "values-ko" to listOf("other"),
        )
        for ((folder, quantities) in needed) {
            val plurals = read(folder).plurals
            assertTrue("$folder has plurals", plurals.isNotEmpty())
            for ((name, have) in plurals) {
                assertTrue("$folder $name needs $quantities, has $have", have.containsAll(quantities))
            }
        }
    }

    @Test
    fun `format placeholders match between the languages`() {
        val placeholder = Regex("%\\d\\$[sd]")
        for (folder in languages) {
            val other = read(folder)
            for ((key, value) in english.strings) {
                val translated = other.strings[key] ?: continue
                assertEquals(
                    "$folder $key placeholders",
                    placeholder.findAll(value).map { it.value }.toSet(),
                    placeholder.findAll(translated).map { it.value }.toSet(),
                )
            }
        }
    }

    @Test
    fun `the developer credit is the developer's name only`() {
        val credit = english.strings.getValue("about_developer")
        assertEquals("Developer: vibic", credit)
        for (folder in languages) assertTrue(read(folder).strings.getValue("about_developer").endsWith("vibic"))
    }
}
