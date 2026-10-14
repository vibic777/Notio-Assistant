package com.notio.desktop

import androidx.compose.ui.graphics.ImageBitmap
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pictures of cards and notes (the whole-code pass): made small, kept within the memory bound, made again when the file changes. */
class PictureCacheTest {
    private fun png(w: Int, h: Int): ByteArray = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { s ->
        s.canvas.clear(0xFF3366FF.toInt())
        s.makeImageSnapshot().use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes }
    }

    @Test
    fun aBigPictureIsMadeSmallAndASmallOneStaysAsItIs() {
        val big = PictureCache.scaled(png(4000, 2000), CARD_PICTURE_PX)
        assertEquals(CARD_PICTURE_PX, big.width)
        assertEquals(CARD_PICTURE_PX / 2, big.height)
        val small = PictureCache.scaled(png(120, 80), CARD_PICTURE_PX)
        assertEquals(120, small.width)
    }

    @Test
    fun aChangedFileIsReadAgainAndTheSameOneIsNot() {
        val dir = Files.createTempDirectory("notio-picture").toFile()
        try {
            val file = dir.resolve("photo.png").apply { writeBytes(png(800, 600)) }
            val first = assertNotNull(PictureCache.load(file, 200))
            assertSame(first, PictureCache.load(file, 200))
            file.writeBytes(png(600, 800))
            file.setLastModified(file.lastModified() + 2_000)
            val second = assertNotNull(PictureCache.load(file, 200))
            assertNotSame(first, second)
            assertEquals(150, second.width)
            assertNull(PictureCache.load(dir.resolve("gone.png"), 200))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theCacheKeepsWithinItsMemory() {
        // 40 pictures of 1600 x 1600 (10 MB each) never keep more than the bound (64 MB), and the newest is always there.
        val bytes = png(1600, 1600)
        var last: ImageBitmap? = null
        repeat(40) { i ->
            last = PictureCache.scaled(bytes, NOTE_PICTURE_PX).also { PictureCache.put("test:$i", 1, it) }
        }
        assertSame(last, PictureCache.peek("test:39"))
        assertNull(PictureCache.peek("test:0"))
        val kept = (0 until 40).count { PictureCache.peek("test:$it") != null }
        assertTrue(kept <= 6, "kept $kept")
    }
}
