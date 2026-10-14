package com.notebudget.app.feature.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.util.UnstableApi
import com.notebudget.app.data.media.MediaStore
import com.notebudget.app.domain.model.MediaKind
import java.io.File

/** A picture decoded no bigger than needed for [longest] pixels, so a 12-megapixel photo does not fill the memory. */
fun decodeSampledPicture(file: File, longest: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * The small picture of one item of a media note (D160): the one kept next to its file, or, when there is none yet, made from the
 * file (a frame for a video) and kept. Used by the note screen and by the cards on the main screen. Call off the main thread.
 */
@OptIn(UnstableApi::class)
fun loadMediaThumbnail(store: MediaStore, kind: MediaKind, key: String): ImageBitmap? {
    val thumb = store.thumbFile(key)
    if (thumb != null && thumb.isFile) {
        // Scrolling a card back into view asks again: the decoded picture is kept in memory (D200), keyed by the file's time.
        val cacheKey = "m:" + key + ":" + thumb.lastModified()
        ThumbnailCache.get(cacheKey)?.let { return it }
        BitmapFactory.decodeFile(thumb.absolutePath)?.let { return it.asImageBitmap().also { bitmap -> ThumbnailCache.put(cacheKey, bitmap) } }
    }
    val file = store.existing(key, kind) ?: return null
    val bytes = if (kind.isVideo) VideoProcessor.thumbnail(file) else runCatching { decodeSampledPicture(file, 360)?.let { PhotoComposer.thumbnail(it) } }.getOrNull()
    if (bytes == null) return null
    thumb?.let { runCatching { it.writeBytes(bytes) } }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

/**
 * The small pictures of the cards (media items, drawings), decoded once and kept in memory (D200): a card that scrolls out of
 * view and back used to read and decode its file again every time. Holds at most an eighth of the app's memory, the pictures
 * used least recently are dropped first. Keys carry the file's or the note's time, so a changed picture is never stale.
 */
object ThumbnailCache {
    private val cache = object : android.util.LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}
