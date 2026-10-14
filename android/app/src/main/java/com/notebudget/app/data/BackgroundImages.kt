package com.notebudget.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.notebudget.app.domain.model.BackgroundImageMath
import com.notebudget.app.domain.model.BackgroundSlot
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures used as backgrounds (D110): one JPEG per slot in app-private storage, shrunk when taken over so a huge
 * photo neither fills the phone nor makes scrolling slow. The picture is decoded from a copy of the chosen file, never
 * from the original, so the app needs no permission to read the gallery later.
 */
class BackgroundImages(private val dir: File) {

    fun file(slot: BackgroundSlot): File = File(dir, slot.name.lowercase() + ".jpg")

    fun exists(slot: BackgroundSlot): Boolean = file(slot).isFile

    /** Up to [max] bytes of [input] (`InputStream.readNBytes` needs Android 13, the app runs on Android 9 and newer). */
    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = input.read(buffer, 0, minOf(buffer.size, max - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** Takes over the picture in [open]; `false` when it is not a picture the phone can read (nothing changes then). */
    suspend fun import(slot: BackgroundSlot, open: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = open()?.use { readAtMost(it, MAX_INPUT_BYTES + 1) } ?: return@runCatching false
            if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return@runCatching false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            val options = BitmapFactory.Options().apply { inSampleSize = BackgroundImageMath.sampleSize(bounds.outWidth, bounds.outHeight) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching false
            dir.mkdirs()
            val temp = File(dir, slot.name.lowercase() + ".tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            val target = file(slot)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target))
            }
            true
        }.getOrDefault(false)
    }

    fun delete(slot: BackgroundSlot) {
        file(slot).delete()
    }

    private companion object {
        const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    }
}
