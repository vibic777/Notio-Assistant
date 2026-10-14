@file:Suppress("unused", "PackageDirectoryMismatch", "MemberVisibilityCanBePrivate")

package android.graphics

import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter as SkMaskFilter
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Typeface as SkTypeface

/*
 * The part of android.graphics the phone's drawing code uses (AGENT.md D26), on Skia — the engine Android itself draws with.
 * With it the Android drawing, blueprint and overlay code is ported almost line by line and paints the same pixels.
 */

class Bitmap private constructor(internal val sk: org.jetbrains.skia.Bitmap) {
    enum class Config { ARGB_8888 }
    enum class CompressFormat { PNG, JPEG }

    val width: Int get() = sk.width
    val height: Int get() = sk.height

    fun copy(config: Config, mutable: Boolean): Bitmap {
        val out = createBitmap(width, height, config)
        Canvas(out).drawBitmap(this, 0f, 0f, null)
        return out
    }

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        val bytes: ByteArray = sk.readPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), w * 4, x, y) ?: return
        for (row in 0 until h) for (col in 0 until w) {
            val i = (row * w + col) * 4
            val b = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val r = bytes[i + 2].toInt() and 0xFF
            val a = bytes[i + 3].toInt() and 0xFF
            pixels[offset + row * stride + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        val bytes = ByteArray(w * h * 4)
        for (row in 0 until h) for (col in 0 until w) {
            val p = pixels[offset + row * stride + col]
            val i = (row * w + col) * 4
            bytes[i] = (p and 0xFF).toByte()
            bytes[i + 1] = (p shr 8 and 0xFF).toByte()
            bytes[i + 2] = (p shr 16 and 0xFF).toByte()
            bytes[i + 3] = (p ushr 24).toByte()
        }
        val src = org.jetbrains.skia.Bitmap()
        src.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
        src.installPixels(bytes)
        val canvas = org.jetbrains.skia.Canvas(sk)
        canvas.drawImageRect(Image.makeFromBitmap(src), Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()),
            org.jetbrains.skia.Paint().apply { blendMode = BlendMode.SRC })
        canvas.close()
    }

    fun compress(format: CompressFormat, quality: Int, out: java.io.OutputStream): Boolean {
        val image = Image.makeFromBitmap(sk)
        val data = image.encodeToData(if (format == CompressFormat.PNG) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, quality) ?: return false
        out.write(data.bytes)
        return true
    }

    /** The colour of one pixel, not premultiplied (ARGB). */
    fun getPixel(x: Int, y: Int): Int {
        val one = IntArray(1)
        getPixels(one, 0, 1, x, y, 1, 1)
        return one[0]
    }

    /** Fills the whole bitmap with [color] (0 = transparent). */
    fun eraseColor(color: Int) {
        sk.erase(color)
    }

    fun recycle() = sk.close()

    /** The picture for Compose (`asImageBitmap` on the phone). */
    fun toSkiaImage(): Image = Image.makeFromBitmap(sk)

    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            val b = org.jetbrains.skia.Bitmap()
            b.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))
            b.erase(0)
            return Bitmap(b)
        }

        internal fun wrap(sk: org.jetbrains.skia.Bitmap) = Bitmap(sk)
    }
}

object BitmapFactory {
    fun decodeByteArray(bytes: ByteArray, offset: Int, length: Int): Bitmap? = runCatching {
        val image = Image.makeFromEncoded(if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length))
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val canvas = org.jetbrains.skia.Canvas(out.sk)
        canvas.drawImage(image, 0f, 0f)
        canvas.close()
        out
    }.getOrNull()
}

class RectF(@JvmField var left: Float, @JvmField var top: Float, @JvmField var right: Float, @JvmField var bottom: Float) {
    internal fun sk() = Rect.makeLTRB(left, top, right, bottom)
}

class Rect(@JvmField var left: Int, @JvmField var top: Int, @JvmField var right: Int, @JvmField var bottom: Int)

object Color {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0
}

class PorterDuff {
    enum class Mode { CLEAR, SRC_OVER }
}

class PorterDuffXfermode(val mode: PorterDuff.Mode)

class BlurMaskFilter(val radius: Float, val blur: Blur) {
    enum class Blur { NORMAL }
}

class Typeface private constructor(internal val sk: SkTypeface?) {
    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        private val cache = HashMap<Int, Typeface>()

        /** Roboto of the app (the resources the UI uses), else the system's default face of that style. */
        fun defaultFromStyle(style: Int): Typeface = synchronized(cache) {
            cache.getOrPut(style) {
                val file = when (style) {
                    BOLD -> "font/roboto_bold.ttf"
                    ITALIC -> "font/roboto_italic.ttf"
                    BOLD_ITALIC -> "font/roboto_bold_italic.ttf"
                    else -> "font/roboto_regular.ttf"
                }
                val bytes = Typeface::class.java.classLoader.getResourceAsStream(file)?.use { it.readBytes() }
                val face = bytes?.let { FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(it)) }
                    ?: FontMgr.default.matchFamilyStyle(null, if (style and BOLD != 0) FontStyle.BOLD else FontStyle.NORMAL)
                Typeface(face)
            }
        }
    }
}

open class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }

    var isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
    var style = Style.FILL
    var strokeCap = Cap.BUTT
    var strokeJoin = Join.MITER
    var strokeWidth = 0f
    var color: Int = Color.BLACK
    var maskFilter: BlurMaskFilter? = null
    var xfermode: PorterDuffXfermode? = null
    var pathEffect: DashPathEffect? = null

    /** 0..255, the alpha of [color]. */
    var alpha: Int
        get() = color ushr 24
        set(value) { color = (value.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF) }

    constructor(other: Paint) : this() {
        isAntiAlias = other.isAntiAlias; style = other.style; strokeCap = other.strokeCap; strokeJoin = other.strokeJoin
        strokeWidth = other.strokeWidth; color = other.color; maskFilter = other.maskFilter; xfermode = other.xfermode; pathEffect = other.pathEffect
    }

    internal fun sk(fillOverride: PaintMode? = null): org.jetbrains.skia.Paint = org.jetbrains.skia.Paint().also { p ->
        p.isAntiAlias = isAntiAlias
        p.mode = fillOverride ?: when (style) {
            Style.FILL -> PaintMode.FILL
            Style.STROKE -> PaintMode.STROKE
            Style.FILL_AND_STROKE -> PaintMode.STROKE_AND_FILL
        }
        p.strokeCap = when (strokeCap) { Cap.BUTT -> PaintStrokeCap.BUTT; Cap.ROUND -> PaintStrokeCap.ROUND; Cap.SQUARE -> PaintStrokeCap.SQUARE }
        p.strokeJoin = when (strokeJoin) { Join.MITER -> PaintStrokeJoin.MITER; Join.ROUND -> PaintStrokeJoin.ROUND; Join.BEVEL -> PaintStrokeJoin.BEVEL }
        p.strokeWidth = strokeWidth
        p.color = color
        maskFilter?.let { p.maskFilter = SkMaskFilter.makeBlur(FilterBlurMode.NORMAL, it.radius / 2f) }
        if (xfermode?.mode == PorterDuff.Mode.CLEAR) p.blendMode = BlendMode.CLEAR
        pathEffect?.let { p.pathEffect = org.jetbrains.skia.PathEffect.makeDash(it.intervals, it.phase) }
    }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }
}

class Path() {
    enum class FillType { WINDING, EVEN_ODD }

    /** The path as the calls that made it, so it can be copied and built again (Skia builds paths with a PathBuilder). */
    private val ops = ArrayList<(org.jetbrains.skia.PathBuilder) -> Unit>()

    var fillType: FillType = FillType.WINDING

    constructor(other: Path) : this() {
        ops.addAll(other.ops)
        fillType = other.fillType
    }

    fun moveTo(x: Float, y: Float) { ops += { it.moveTo(x, y) } }
    fun lineTo(x: Float, y: Float) { ops += { it.lineTo(x, y) } }
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { ops += { it.quadTo(x1, y1, x2, y2) } }
    fun close() { ops += { it.closePath() } }

    internal val sk: org.jetbrains.skia.Path
        get() {
            val b = org.jetbrains.skia.PathBuilder(if (fillType == FillType.WINDING) PathFillMode.WINDING else PathFillMode.EVEN_ODD)
            ops.forEach { it(b) }
            return b.detach()
        }
}

class DashPathEffect(val intervals: FloatArray, val phase: Float)

/** A canvas on a [Bitmap] (as on the phone) or on the canvas Compose draws with ([wrap]). */
class Canvas private constructor(internal val sk: org.jetbrains.skia.Canvas, private val owned: Boolean) {
    constructor(bitmap: Bitmap) : this(org.jetbrains.skia.Canvas(bitmap.sk), true)

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        sk.drawImage(Image.makeFromBitmap(bitmap.sk), left, top, paint?.sk())
    }

    fun drawBitmap(bitmap: Bitmap, src: android.graphics.Rect?, dst: RectF, paint: Paint?) {
        val image = Image.makeFromBitmap(bitmap.sk)
        val s = src?.let { Rect.makeLTRB(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            ?: Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat())
        sk.drawImageRect(image, s, dst.sk(), SamplingMode.LINEAR, paint?.sk(), true)
    }

    fun drawColor(color: Int) { sk.clear(color) }
    fun drawPath(path: Path, paint: Paint) { sk.drawPath(path.sk, paint.sk()) }
    fun drawPoint(x: Float, y: Float, paint: Paint) { sk.drawPoint(x, y, paint.sk()) }
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { sk.drawLine(x0, y0, x1, y1, paint.sk()) }
    fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) { sk.drawRect(Rect.makeLTRB(l, t, r, b), paint.sk()) }
    fun drawOval(oval: RectF, paint: Paint) { sk.drawOval(oval.sk(), paint.sk()) }
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) { sk.drawCircle(cx, cy, radius, paint.sk()) }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val tp = paint as? android.text.TextPaint ?: return
        val font = tp.font()
        sk.drawString(text, x, y, font, paint.sk(PaintMode.FILL))
        val width = tp.measureText(text)
        val line = org.jetbrains.skia.Paint().apply { color = paint.color; strokeWidth = (tp.textSize / 14f).coerceAtLeast(1f); isAntiAlias = true }
        if (tp.isUnderlineText) sk.drawLine(x, y + tp.textSize * 0.12f, x + width, y + tp.textSize * 0.12f, line)
        if (tp.isStrikeThruText) sk.drawLine(x, y - tp.textSize * 0.3f, x + width, y - tp.textSize * 0.3f, line)
    }

    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        sk.drawRRect(org.jetbrains.skia.RRect.makeLTRB(rect.left, rect.top, rect.right, rect.bottom, rx, ry), paint.sk())
    }

    fun rotate(degrees: Float) { sk.rotate(degrees) }
    fun rotate(degrees: Float, px: Float, py: Float) { sk.translate(px, py); sk.rotate(degrees); sk.translate(-px, -py) }

    fun save(): Int = sk.save()
    fun restore() = sk.restore()
    fun translate(dx: Float, dy: Float) { sk.translate(dx, dy) }
    fun scale(sx: Float, sy: Float) { sk.scale(sx, sy) }

    fun close() { if (owned) sk.close() }

    companion object {
        /** The canvas Compose draws with (`drawIntoCanvas { it.nativeCanvas }`), for the phone's drawing code. */
        fun wrap(canvas: org.jetbrains.skia.Canvas) = Canvas(canvas, false)
    }
}

/** The phone's `Bitmap.asImageBitmap()` for Compose. */
fun Bitmap.asImageBitmap(): androidx.compose.ui.graphics.ImageBitmap = toSkiaImage().toComposeImageBitmap()
