package com.notio.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The name of the app in the system follows the language of the system (D131). */
class SystemNameTest {
    @Test
    fun `Russian has a name of its own, every other language the English one`() {
        assertEquals("Нотио от Вибик", SystemName.forLanguage("ru"))
        assertEquals("Нотио от Вибик", SystemName.forLanguage("RU"))
        for (language in listOf("en", "de", "be", "uk", "zh", "", null)) assertEquals("Notio by vibic", SystemName.forLanguage(language), language)
    }

    @Test
    fun `the desktop entries of Linux carry both names`() {
        val entry = AutoStart.linuxEntryText("Notio", File("/opt/notio/bin/Notio"))
        assertTrue("Name=Notio\n" in entry, entry)
        assertTrue("Name[ru]=Нотио от Вибик\n" in entry, entry)
        assertTrue("Name[ru]=Нотио от Вибик (portable)\n" in AutoStart.linuxEntryText("Notio (portable)", File("/home/me/Notio.bin")))
    }
}
