package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.notio.shared.sync.Discovery
import com.notio.shared.sync.JmdnsDiscovery
import com.notio.shared.sync.SecretBox
import java.io.File
import java.net.ServerSocket

/**
 * A check of the packaged app's own runtime (`-Dnotio.selftest=true`): what reflection-heavy libraries need at run time is not
 * seen by the module analysis of the build. Writes `selftest.txt` into the data folder and ends the process.
 */
object SelfTest {
    fun runIfAsked(): Boolean {
        if (System.getProperty("notio.selftest") != "true") return false
        val lines = ArrayList<String>()
        fun check(name: String, block: () -> String) {
            lines += name + ": " + runCatching(block).fold({ "OK $it" }, { "FAIL " + it.javaClass.simpleName + " " + it.message })
        }
        check("dpapi") {
            val box = SecretBox.forThisSystem()
            val sealed = box.seal("secret-42")
            check(box.open(sealed) == "secret-42")
            sealed.substringBefore(':')
        }
        check("socket") { ServerSocket(0).use { "port ${it.localPort}" } }
        check("mdns") {
            val d = JmdnsDiscovery()
            val stop = d.advertise(47998, "selftest", "Selftest", false)
            stop(); d.close()
            Discovery.localAddresses().joinToString()
        }
        check("pdf") {
            check(com.notio.shared.transfer.PdfBoxWriter.classpathRoboto() != null) { "no Roboto" }
            val bytes = com.notio.shared.transfer.PdfBoxWriter().write(listOf(com.notio.shared.transfer.PdfBlock("Проверка PDF", com.notio.shared.transfer.PdfStyle.TITLE)))
            "${bytes.size} bytes"
        }
        check("png") {
            DrawingRenderer.useFonts()
            val png = DrawingRenderer.flatten(null, emptyList())
            "${DrawingRenderer.pngToJpeg(png).size} bytes jpeg"
        }
        check("aac") {
            val f = java.io.File.createTempFile("selftest", ".m4a")
            com.notio.desktop.media.AacM4aWriter(f).use { w -> repeat(5) { w.write(ByteArray(8820)) } }
            val ms = com.notio.desktop.media.PcmReader(f).use { it.durationMs }
            f.delete()
            "$ms ms"
        }
        check("h264") {
            val f = java.io.File.createTempFile("selftest", ".mp4")
            com.notio.desktop.media.H264Mp4Writer(f, 160, 120, fps = 10).use { w -> repeat(10) { i -> w.addFrame(IntArray(160 * 120) { 0xFF336699.toInt() }, 160, i * 100L) } }
            val size = com.notio.desktop.media.VideoReader.open(f).use { r -> r.read()?.let { "${it.width}x${it.height}" } }
            f.delete()
            size ?: error("no picture")
        }
        check("log") {
            val dir = java.nio.file.Files.createTempDirectory("notio-log").toFile()
            com.notio.shared.AppLog.install(dir)
            com.notio.shared.AppLog.fatal("selftest", "a line")
            check(com.notio.shared.AppLog.read().contains("a line"))
            dir.deleteRecursively()
            "ok"
        }
        check("sqlite") {
            com.notio.shared.data.NotioDatabase.inMemory().use { "schema ${com.notio.shared.data.NotioDatabase.SCHEMA_VERSION}" }
        }
        // Database encryption (D46): SQLCipher 4 of the packaged SQLite, a file encrypted and opened with its key.
        check("cipher") {
            val f = java.io.File.createTempFile("selftest", ".db")
            try {
                com.notio.shared.data.NotioDatabase.open(f).close()
                val key = com.notio.shared.data.DatabaseCipher.newPassphrase()
                com.notio.shared.data.DatabaseCipher.convert(f, null, key)?.let { error(it) }
                check(com.notio.shared.data.DatabaseCipher.isEncrypted(f) && com.notio.shared.data.DatabaseCipher.hasNotes(f, key))
                "sqlcipher 4"
            } finally {
                f.delete()
            }
        }
        val out = File(AppPaths.default().root, "selftest.txt").apply { parentFile.mkdirs() }
        out.writeText(lines.joinToString("\n"))
        return true
    }
}
