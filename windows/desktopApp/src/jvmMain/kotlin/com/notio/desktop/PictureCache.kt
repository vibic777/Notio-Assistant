package com.notio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures of cards and notes (the whole-code pass, §6.1 row 19; user, 2026-09-28: "the window is a little slow"): a photo used to
 * be read and decoded at full size on the UI thread each time its card scrolled in (a 12 MP photo = 48 MB, never let go while shown).
 * Now a picture is decoded off the UI thread, made no bigger than asked, and kept in one cache bounded by its memory ([MAX_BYTES],
 * the least recently shown goes first).
 */
object PictureCache {
    private const val MAX_BYTES = 64L * 1024 * 1024

    private class Entry(val bitmap: ImageBitmap, val stamp: Long) {
        val bytes: Long get() = bitmap.width.toLong() * bitmap.height * 4
    }

    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var total = 0L

    /** What is kept for [key], whatever its stamp: shown at once while a newer one is made. */
    fun peek(key: String): ImageBitmap? = synchronized(this) { entries[key]?.bitmap }

    fun get(key: String, stamp: Long): ImageBitmap? = synchronized(this) { entries[key]?.takeIf { it.stamp == stamp }?.bitmap }

    fun put(key: String, stamp: Long, bitmap: ImageBitmap) = synchronized(this) {
        entries.put(key, Entry(bitmap, stamp))?.let { total -= it.bytes }
        total += bitmap.width.toLong() * bitmap.height * 4
        val it = entries.values.iterator()
        while (total > MAX_BYTES && entries.size > 1 && it.hasNext()) {
            val old = it.next()
            if (old.bitmap === bitmap) continue
            total -= old.bytes
            it.remove()
        }
    }

    /** Everything let go (the window went into the tray, D70): the pictures are read again when shown. */
    fun clear() = synchronized(this) { entries.clear(); total = 0L }

    /** A picture file at most [maxPx] on its long side; blocking, for a background thread. */
    fun load(file: File, maxPx: Int): ImageBitmap? {
        val key = file.path + "@" + maxPx
        val stamp = file.lastModified().takeIf { it != 0L } ?: return null
        get(key, stamp)?.let { return it }
        val made = runCatching { scaled(file.readBytes(), maxPx) }.getOrNull() ?: return null
        put(key, stamp, made)
        return made
    }

    /** Encoded picture bytes decoded and drawn at most [maxPx] on the long side (never larger than it is). */
    fun scaled(bytes: ByteArray, maxPx: Int): ImageBitmap = scaled(org.jetbrains.skia.Image.makeFromEncoded(bytes), maxPx)

    /** [image] made at most [maxPx] on the long side; the full-size image is freed at once (Android D244). */
    fun scaled(image: org.jetbrains.skia.Image, maxPx: Int): ImageBitmap =
        image.use { image ->
            val scale = minOf(1f, maxPx.toFloat() / maxOf(image.width, image.height))
            if (scale >= 1f) return@use image.toComposeImageBitmap()
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            org.jetbrains.skia.Surface.makeRasterN32Premul(w, h).use { surface ->
                val paint = org.jetbrains.skia.Paint()
                surface.canvas.drawImageRect(
                    image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
                    org.jetbrains.skia.SamplingMode.LINEAR, paint, true,
                )
                paint.close()
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
}

/** A picture file for the screen: what the cache has at once, the file itself (changed or not yet read) off the UI thread. */
@Composable
fun rememberPicture(file: File, maxPx: Int): ImageBitmap? {
    val key = file.path + "@" + maxPx
    val first = remember(key) { PictureCache.peek(key) }
    return produceState(first, key) {
        withContext(Dispatchers.IO) { PictureCache.load(file, maxPx) }?.let { value = it }
    }.value
}

/** A card shows its picture 96 dp high: enough for a 2x screen and a wide photo. */
const val CARD_PICTURE_PX = 400

/** A picture in the note is at most 560 dp high and as wide as the window. */
const val NOTE_PICTURE_PX = 1600
