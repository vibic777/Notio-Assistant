package com.notio.desktop

import com.notio.shared.data.AppPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The first class of the app (D91): it finds an installation that lost its files and must itself need nothing of Kotlin. */
class LauncherTest {
    @Test
    fun aWholeClassPathHasNothingMissing() {
        assertEquals(emptyList(), Launcher.missing(System.getProperty("java.class.path")))
        assertEquals(emptyList(), Launcher.missing(""))
    }

    @Test
    fun theMissingFilesOfAClassPathAreNamed() {
        val dir = File(System.getProperty("java.io.tmpdir"), "launcher-test-" + System.nanoTime()).apply { mkdirs() }
        try {
            val here = File(dir, "here.jar").apply { writeText("x") }
            val gone = File(dir, "kotlin-stdlib.jar")
            val alsoGone = File(dir, "jna.jar")
            val path = listOf(here, gone, alsoGone).joinToString(File.pathSeparator) { it.path }
            assertEquals(listOf(gone.path, alsoGone.path), Launcher.missing(path))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theTextIsThereInEveryLanguageOfNotio() {
        val english = Launcher.message("en")
        assertTrue(english.startsWith("Notio cannot start"), english)
        // A language Notio does not have gets the English text.
        assertEquals(english, Launcher.message("xx"))
        val texts = listOf("ru", "be", "de", "es", "fr", "it", "ja", "ko", "zh").map(Launcher::message)
        texts.forEach { assertTrue(it.contains("Notio") && it != english, it) }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun aLongTextIsBrokenIntoLines() {
        val lines = Launcher.wrapped(Launcher.message("ru")).lines()
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 100 }, lines.toString())
    }

    @Test
    fun theDataFolderIsTheOneOfTheApp() {
        assertEquals(AppPaths.default().root.absoluteFile, Launcher.dataRoot().absoluteFile)
    }

    /** D95: what a start writes before the app's own log is there has the format of that log. */
    @Test
    fun aLineOfTheLogHasItsFormat() {
        val line = Launcher.line(java.util.Date(0), "ERROR", "a second start")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} ERROR    app: a second start\r?\n""").matches(line), line)
        assertTrue(Launcher.line(java.util.Date(0), "FATAL", "x").contains(" FATAL    app: x"))
        assertTrue(Launcher.line(java.util.Date(0), "INFO", "x").contains(" INFO     app: x"))
    }

    @Test
    fun theTextOfANotioThatDoesNotAnswerIsThereInEveryLanguage() {
        val english = Launcher.stringOf("strings/values/desktop_strings.xml", "desktop_not_answering")
        assertTrue(english!!.startsWith("Notio is already running"), english)
        val texts = listOf("ru", "be", "de", "es", "fr", "it", "ja", "ko", "zh").map { Launcher.stringOf("strings/values-$it/desktop_strings.xml", "desktop_not_answering") }
        texts.forEach { assertTrue(it != null && it.contains("Notio") && it != english, it.toString()) }
        assertEquals(texts.size, texts.toSet().size)
        // The title of an error at the start comes from the texts of the app.
        assertEquals("Notio closed because of an error", Launcher.stringOf("strings/values/strings.xml", "crash_now_title"))
    }

    @Test
    fun onlyTheFirstLinesOfAStackAreShown() {
        assertEquals("a\nb\n...", Launcher.firstLines("a\nb\nc\n", 2))
        assertEquals("a", Launcher.firstLines("a\n", 5))
    }

    /** With a library gone not one class of Kotlin loads: the class file may name none. */
    @Test
    fun theClassNeedsNothingOfKotlin() {
        val bytes = Launcher::class.java.getResourceAsStream("Launcher.class")!!.use { it.readBytes() }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("kotlin"))
    }
}
