package com.notio.desktop

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One Notio per data folder (D45), and the texts of the desktop's own functions in every language of the app. */
class SingleInstanceTest {
    @Test
    fun aSecondStartOnTheSameDataFolderShowsTheFirstAndEnds() {
        val root = Files.createTempDirectory("notio-single").toFile()
        try {
            val shown = CountDownLatch(1)
            assertTrue(SingleInstance.claim(root))
            SingleInstance.onShow = { shown.countDown() }
            assertFalse(SingleInstance.claim(root), "the data folder is taken")
            assertTrue(shown.await(5, TimeUnit.SECONDS), "the first Notio was asked to show its window")
            SingleInstance.release()
            assertTrue(SingleInstance.claim(root), "free again after the end")
        } finally {
            SingleInstance.release()
            SingleInstance.onShow = {}
            root.deleteRecursively()
        }
    }

    @Test
    fun aRequestWithoutTheSecretIsIgnored() {
        val root = Files.createTempDirectory("notio-single").toFile()
        try {
            val quit = CountDownLatch(1)
            assertTrue(SingleInstance.claim(root))
            SingleInstance.onQuit = { quit.countDown() }
            val port = java.io.File(root, "instance.port").readLines().first().trim().toInt()
            fun send(text: String): String? = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3_000
                s.getOutputStream().write(text.toByteArray())
                runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull()
            }
            assertEquals(null, send("quit\nwrong\n"))
            assertFalse(quit.await(500, TimeUnit.MILLISECONDS), "a stranger cannot end Notio")
            val token = java.io.File(root, "instance.port").readLines()[1].trim()
            assertEquals("ok", send("quit\n$token\n"))
            assertTrue(quit.await(5, TimeUnit.SECONDS), "the installer with the secret can")
        } finally {
            SingleInstance.release()
            SingleInstance.onQuit = {}
            root.deleteRecursively()
        }
    }

    @Test
    fun theSettingIsOnUnlessTurnedOff() {
        val root = Files.createTempDirectory("notio-single").toFile()
        try {
            assertTrue(SingleInstance.enabled(root))
            java.io.File(root, "ui.properties").writeText("singleInstance=false\n")
            assertFalse(SingleInstance.enabled(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theDesktopTextsExistInAllTenLanguages() {
        val keys = listOf(
            "desktop_window", "desktop_close_to_tray", "desktop_close_to_tray_hint", "desktop_close_to_tray_notice",
            "desktop_single_instance", "desktop_single_instance_hint",
        )
        val english = AndroidRes.forLanguage("en")
        for (lang in listOf("en", "ru", "be", "de", "es", "fr", "it", "ja", "ko", "zh")) {
            val res = AndroidRes.forLanguage(lang)
            for (k in keys) {
                val text = res.str(k)
                assertTrue(text != k, "$lang: $k missing")
                if (lang != "en") assertTrue(text != english.str(k), "$lang: $k is the English text")
            }
        }
        assertEquals("Окно", AndroidRes.forLanguage("ru").str("desktop_window"))
    }
}
