package com.notebudget.app.feature.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.OverlayPosition
import com.notebudget.app.domain.model.OverlaySettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** What an overlay shows besides the settings: the moment, the place and the picture already loaded. */
class OverlayInputs(val whenMillis: Long, val place: GeoPoint?, val logo: Bitmap?, val zone: ZoneId = ZoneId.systemDefault())

/**
 * Draws the layers of [OverlaySettings] (D148) on a frame: the time stamp, the place, a text and a picture, each at its
 * own place, size and colour, with an optional plate behind it. Used for photos (drawn on the picture) and for videos
 * (a transparent picture of the frame's size, laid over every frame by the video processor).
 */
object OverlayRenderer {
    /** A transparent bitmap of [width] x [height] with every enabled layer drawn on it; used as a video overlay. */
    fun bitmap(width: Int, height: Int, settings: OverlaySettings, inputs: OverlayInputs): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), width, height, settings, inputs)
        return bitmap
    }

    fun draw(canvas: Canvas, w: Int, h: Int, settings: OverlaySettings, inputs: OverlayInputs) {
        val side = min(w, h).toFloat()
        val margin = side * 0.02f
        if (settings.image.enabled && inputs.logo != null) drawImage(canvas, w, h, settings, inputs.logo, margin)
        if (settings.text.enabled && settings.text.text.isNotBlank()) {
            with(settings.text) { drawLabel(canvas, w, h, text, colorArgb, sizePercent, position, backgroundArgb, margin) }
        }
        if (settings.stamp.enabled) {
            val stamp = settings.stamp.text(inputs.whenMillis, inputs.zone)
            if (stamp.isNotEmpty()) with(settings.stamp) { drawLabel(canvas, w, h, stamp, colorArgb, sizePercent, position, backgroundArgb, margin) }
        }
        if (settings.geo.enabled && inputs.place != null) {
            with(settings.geo) { drawLabel(canvas, w, h, inputs.place.text(), colorArgb, sizePercent, position, backgroundArgb, margin) }
        }
    }

    private fun drawLabel(
        canvas: Canvas, w: Int, h: Int, text: String, color: Int, sizePercent: Int,
        position: OverlayPosition, background: Int?, margin: Float,
    ) {
        val side = min(w, h).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = side * sizePercent.coerceIn(1, 20) / 100f
            this.color = color
            // A thin dark edge keeps light text readable on a light picture when there is no plate.
            if (background == null) setShadowLayer(textSize * 0.08f, 0f, 0f, 0xCC000000.toInt())
        }
        val pad = paint.textSize * 0.3f
        val lines = text.split('\n')
        val lineHeight = paint.fontSpacing
        val boxW = (lines.maxOfOrNull { paint.measureText(it) } ?: 0f) + 2 * pad
        val boxH = lineHeight * lines.size + 2 * pad
        val (x, y) = position.place(w.toFloat(), h.toFloat(), boxW, boxH, margin)
        if (background != null) {
            val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = background }
            canvas.drawRoundRect(RectF(x, y, x + boxW, y + boxH), pad, pad, plate)
        }
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, x + pad, y + pad - paint.fontMetrics.ascent + lineHeight * i, paint)
        }
    }

    private fun drawImage(canvas: Canvas, w: Int, h: Int, settings: OverlaySettings, logo: Bitmap, margin: Float) {
        val layer = settings.image
        val targetW = max(1f, w * layer.widthPercent.coerceIn(2, 100) / 100f)
        val targetH = targetW * logo.height / max(1, logo.width)
        val (x, y) = layer.position.place(w.toFloat(), h.toFloat(), targetW, targetH, margin)
        val rect = RectF(x, y, x + targetW, y + targetH)
        layer.backgroundArgb?.let { canvas.drawRect(rect, Paint().apply { color = it }) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (layer.opacityPercent.coerceIn(0, 100) * 255 / 100) }
        canvas.drawBitmap(logo, null, rect, paint)
    }

    /** The picture of the image layer, decoded no bigger than needed for a frame of [frameWidth] pixels. */
    fun loadLogo(file: File?, settings: OverlaySettings, frameWidth: Int): Bitmap? {
        if (file == null || !settings.image.enabled || !file.isFile) return null
        val wanted = max(64, frameWidth * settings.image.widthPercent.coerceIn(2, 100) / 100)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= wanted) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Turns the file the camera wrote into the picture that is kept: turned upright, with the overlays drawn on it. */
object PhotoComposer {
    class Result(val jpeg: ByteArray, val thumbnail: ByteArray, val width: Int, val height: Int)

    fun compose(source: File, settings: OverlaySettings, inputs: OverlayInputs): Result? {
        // Decoded mutable, so it is drawn on without a copy; a picture above MAX_PIXELS is halved (a 50 Mpx one would take 200 MB).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample },
        ) ?: return null
        val rotation = runCatching {
            when (ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val upright = if (rotation == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        // Only one full-size picture at a time: each one no longer needed is freed at once.
        if (upright !== decoded) decoded.recycle()
        val canvasBitmap = if (upright.isMutable && upright.config == Bitmap.Config.ARGB_8888) upright else upright.copy(Bitmap.Config.ARGB_8888, true).also { upright.recycle() }
        try {
            if (settings.any) {
                OverlayRenderer.draw(Canvas(canvasBitmap), canvasBitmap.width, canvasBitmap.height, settings, inputs)
            }
            val jpeg = ByteArrayOutputStream().also { canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
            return Result(jpeg, thumbnail(canvasBitmap), canvasBitmap.width, canvasBitmap.height)
        } finally {
            canvasBitmap.recycle()
        }
    }

    /** About 24 Mpx: the largest picture kept at full size (96 MB while it is drawn on). */
    private const val MAX_PIXELS = 24_000_000L

    /** A small JPEG (longest side 360 px) for lists. */
    fun thumbnail(bitmap: Bitmap): ByteArray {
        val scale = 360f / max(bitmap.width, bitmap.height)
        val small = if (scale >= 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            .also { if (small !== bitmap) small.recycle() }
    }
}
