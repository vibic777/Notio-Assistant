package com.notio.desktop

import com.notio.desktop.media.AacM4aWriter
import com.notio.desktop.media.PcmReader
import com.notio.desktop.media.H264Mp4Writer
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The phone's recording format made and read by Windows Media Foundation (AGENT.md D35). */
class MediaFoundationTest {
    @Test
    fun `two seconds of a tone become an m4a that decodes back to about two seconds of sound`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = Files.createTempDirectory("mf").toFile()
        val file = java.io.File(dir, "clip-test.m4a")
        val rate = 44_100
        AacM4aWriter(file).use { w ->
            val chunk = ByteArray(4410 * 2)
            var t = 0
            repeat(20) {
                for (i in 0 until 4410) {
                    val v = (sin(2 * PI * 440 * (t + i) / rate) * 12_000).toInt()
                    chunk[2 * i] = v.toByte(); chunk[2 * i + 1] = (v shr 8).toByte()
                }
                t += 4410
                w.write(chunk)
            }
            assertEquals(2000, w.durationMs)
        }
        val bytes = file.readBytes()
        // An ISO base media file: "ftyp" at offset 4, then an audio brand.
        assertEquals("ftyp", String(bytes, 4, 4, Charsets.ISO_8859_1))
        assertTrue(bytes.size in 10_000..80_000)
        PcmReader(file).use { r ->
            assertEquals(rate, r.sampleRate)
            assertEquals(1, r.channels)
            assertTrue("duration ${r.durationMs}", abs(r.durationMs - 2000) < 100)
            var total = 0L
            var peak = 0
            while (true) {
                val block = r.read() ?: break
                total += block.size
                for (i in 0 until block.size / 2) peak = maxOf(peak, abs((block[2 * i].toInt() and 0xFF) or (block[2 * i + 1].toInt() shl 8)))
            }
            val ms = total / 2 * 1000 / rate
            assertTrue("decoded $ms ms", abs(ms - 2000) < 150)
            assertTrue("peak $peak", peak in 8_000..16_000)
            r.seek(1000)
            assertTrue(r.read() != null)
        }
        dir.deleteRecursively()
    }
}

/** The real player on this computer's output, with a silent recording (nothing is heard). */
class DesktopPlayerTest {
    @Test
    fun `a silent m4a plays to its end, pauses, seeks and reports its place`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(runCatching { javax.sound.sampled.AudioSystem.getSourceDataLine(javax.sound.sampled.AudioFormat(44_100f, 16, 1, true, false)) }.isSuccess)
        val dir = Files.createTempDirectory("player").toFile()
        val file = java.io.File(dir, "silence.m4a")
        AacM4aWriter(file).use { w -> repeat(10) { w.write(ByteArray(4410 * 2)) } } // one second of silence
        val player = DesktopPlayer()
        var done = false
        assertTrue(player.load(file) { done = true })
        assertTrue("duration ${player.durationMs}", abs(player.durationMs - 1000) < 100)
        player.start()
        Thread.sleep(400)
        player.pause()
        val at = player.positionMs
        assertTrue("paused at $at", at in 100..900)
        player.seekTo(800)
        assertEquals(800, player.positionMs)
        player.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (!done && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("completed", done)
        player.release()
        dir.deleteRecursively()
    }
}

/** Videos as the phone makes them (H.264 + AAC in MP4), written and read back by Media Foundation (AGENT.md D36). */
class VideoMediaFoundationTest {
    @Test
    fun `sixty coloured pictures and a second of sound become an mp4 that reads back`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = Files.createTempDirectory("mfv").toFile()
        val file = java.io.File(dir, "media-test.mp4")
        H264Mp4Writer(file, 320, 240, fps = 30, withAudio = true).use { w ->
            val px = IntArray(320 * 240)
            for (f in 0 until 60) {
                // Left half red, right half blue.
                for (y in 0 until 240) for (x in 0 until 320) px[y * 320 + x] = if (x < 160) 0xFFE00000.toInt() else 0xFF0000E0.toInt()
                w.addFrame(px, 320, f * 1000L / 30)
                if (f % 3 == 0) w.addAudio(ByteArray(4410 * 2 / 3 * 2))
            }
        }
        val bytes = file.readBytes()
        assertEquals("ftyp", String(bytes, 4, 4, Charsets.ISO_8859_1))
        com.notio.desktop.media.VideoReader.open(file).use { r ->
            assertEquals(320, r.width)
            assertEquals(240, r.height)
            assertTrue("duration ${r.durationMs}", abs(r.durationMs - 2000) < 150)
            val first = r.read()!!
            fun at(x: Int, y: Int) = first.argb[y * 320 + x]
            val left = at(40, 120)
            val right = at(280, 120)
            assertTrue("left ${Integer.toHexString(left)}", (left shr 16 and 0xFF) > 150 && (left and 0xFF) < 80)
            assertTrue("right ${Integer.toHexString(right)}", (right and 0xFF) > 150 && (right shr 16 and 0xFF) < 80)
            // The top row is the top of the picture (not flipped): both halves keep their sides at the top too.
            assertTrue((at(40, 5) shr 16 and 0xFF) > 150)
            var n = 1
            while (r.read() != null) n++
            assertTrue("frames $n", n in 55..62)
        }
        com.notio.desktop.media.PcmReader(file).use { assertTrue(it.durationMs > 500) }
        dir.deleteRecursively()
    }
}

/** The screen recorder on this computer's screen (no microphone; the file is deleted at once). */
class ScreenRecorderTest {
    @Test
    fun `a second and a half of the screen becomes an mp4 of that length`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && !java.awt.GraphicsEnvironment.isHeadless())
        val dir = Files.createTempDirectory("screen").toFile()
        val file = java.io.File(dir, "screen.mp4")
        val recorder = ScreenRecorder()
        recorder.start(file, withMic = false, fps = 10)
        Thread.sleep(1_500)
        val captured = recorder.stop()!!
        assertTrue("captured ${captured.durationMs}", captured.durationMs in 1_100..1_700)
        com.notio.desktop.media.VideoReader.open(file).use { r ->
            assertEquals(captured.width, r.width)
            assertTrue("duration ${r.durationMs}", abs(r.durationMs - 1_500) < 400)
            var frames = 0
            while (r.read() != null) frames++
            assertTrue("frames $frames", frames in 10..20)
        }
        dir.deleteRecursively()
    }
}
