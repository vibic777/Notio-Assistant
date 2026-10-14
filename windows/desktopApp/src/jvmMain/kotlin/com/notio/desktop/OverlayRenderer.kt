package com.notio.desktop

import com.notio.shared.domain.GeoPoint
import com.notio.shared.domain.OverlayPosition
import com.notio.shared.domain.OverlaySettings
import java.io.File
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface

/** What an overlay shows besides the settings: the moment, the place and the picture already loaded. */
class OverlayInputs(val whenMillis: Long, val place: GeoPoint?, val logo: Image?, val zone: ZoneId = ZoneId.systemDefault())

/**
 * Draws the layers of [OverlaySettings] (Android D148, `OverlayRenderer`; AGENT.md D40) on a frame: the time stamp, the place, a text
 * and a picture, each at its own place, size and colour, with an optional plate behind it — the phone's sizes and positions, on Skia.
 */
object OverlayRenderer {
    private val face: Typeface? by lazy {
        runCatching { OverlayRenderer::class.java.getResourceAsStream("/font/roboto_medium.ttf")!!.use { org.jetbrains.skia.FontMgr.default.makeFromData(Data.makeFromBytes(it.readBytes())) } }.getOrNull()
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
        val font = Font(face, side * sizePercent.coerceIn(1, 20) / 100f)
        val metrics = font.metrics
        val lineHeight = metrics.descent - metrics.ascent + metrics.leading
        val pad = font.size * 0.3f
        val lines = text.split('\n').map { TextLine.make(it, font) }
        val boxW = (lines.maxOfOrNull { it.width } ?: 0f) + 2 * pad
        val boxH = lineHeight * lines.size + 2 * pad
        val (x, y) = position.place(w.toFloat(), h.toFloat(), boxW, boxH, margin)
        if (background != null) {
            canvas.drawRRect(RRect.makeXYWH(x, y, boxW, boxH, pad), Paint().apply { this.color = background; isAntiAlias = true })
        } else {
            // A thin dark edge keeps light text readable on a light picture when there is no plate (the phone's shadow layer).
            val shadow = Paint().apply { this.color = 0xCC000000.toInt(); isAntiAlias = true; maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, font.size * 0.08f) }
            lines.forEachIndexed { i, line -> canvas.drawTextLine(line, x + pad, y + pad - metrics.ascent + lineHeight * i, shadow) }
        }
        val paint = Paint().apply { this.color = color; isAntiAlias = true }
        lines.forEachIndexed { i, line -> canvas.drawTextLine(line, x + pad, y + pad - metrics.ascent + lineHeight * i, paint) }
    }

    private fun drawImage(canvas: Canvas, w: Int, h: Int, settings: OverlaySettings, logo: Image, margin: Float) {
        val layer = settings.image
        val targetW = max(1f, w * layer.widthPercent.coerceIn(2, 100) / 100f)
        val targetH = targetW * logo.height / max(1, logo.width)
        val (x, y) = layer.position.place(w.toFloat(), h.toFloat(), targetW, targetH, margin)
        val rect = Rect.makeXYWH(x, y, targetW, targetH)
        layer.backgroundArgb?.let { canvas.drawRect(rect, Paint().apply { color = it }) }
        val paint = Paint().apply { isAntiAlias = true; alpha = layer.opacityPercent.coerceIn(0, 100) * 255 / 100 }
        canvas.drawImageRect(logo, rect, paint)
    }

    /** The picture of the image layer; `null` when the layer is off or the file is gone. */
    fun loadLogo(file: File?, settings: OverlaySettings): Image? {
        if (file == null || !settings.image.enabled || !file.isFile) return null
        return runCatching { Image.makeFromEncoded(file.readBytes()) }.getOrNull()
    }

    /** [argb] (a frame, top-down) with the overlays drawn on it, in place. */
    fun drawOnPixels(argb: IntArray, w: Int, h: Int, settings: OverlaySettings, inputs: OverlayInputs) {
        if (!settings.any) return
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImage(skiaImage(w, h, argb), 0f, 0f)
        draw(surface.canvas, w, h, settings, inputs)
        val bytes = surface.makeImageSnapshot().peekPixels()?.buffer?.bytes ?: return
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb, 0, w * h)
    }
}

/**
 * The overlays laid over a video (AGENT.md D40): the picture of the layers is made once — and again every second when the time stamp
 * is on — and blended into each frame where it is not transparent, so a recording is not slowed down by drawing text 30 times a second.
 */
class VideoOverlay(private val settings: OverlaySettings, private val logo: Image?, private val startedAt: Long = System.currentTimeMillis()) {
    private var layer: IntArray? = null
    private var layerW = 0
    private var layerH = 0
    private var layerSecond = -1L

    fun apply(argb: IntArray, w: Int, h: Int, frameTimeMs: Long) {
        if (!settings.any) return
        val second = if (settings.changesWithTime) (startedAt + frameTimeMs) / 1000 else 0
        if (layer == null || layerW != w || layerH != h || second != layerSecond) {
            val surface = Surface.makeRasterN32Premul(w, h)
            OverlayRenderer.draw(surface.canvas, w, h, settings, OverlayInputs(startedAt + frameTimeMs, null, logo))
            val bytes = surface.makeImageSnapshot().peekPixels()?.buffer?.bytes ?: return
            val pixels = IntArray(w * h)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
            layer = pixels; layerW = w; layerH = h; layerSecond = second
        }
        val over = layer ?: return
        for (i in 0 until w * h) {
            val o = over[i]
            val a = o ushr 24
            if (a == 0) continue
            if (a == 255) { argb[i] = o; continue }
            // Premultiplied source over an opaque frame.
            val d = argb[i]
            val inv = 255 - a
            val r = ((o shr 16) and 0xFF) + ((d shr 16) and 0xFF) * inv / 255
            val g = ((o shr 8) and 0xFF) + ((d shr 8) and 0xFF) * inv / 255
            val b = (o and 0xFF) + (d and 0xFF) * inv / 255
            argb[i] = (0xFF shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
        }
    }
}
