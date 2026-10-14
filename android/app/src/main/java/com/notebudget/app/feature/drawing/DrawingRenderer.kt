package com.notebudget.app.feature.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.io.ByteArrayOutputStream

/** Android side of drawing: painting strokes on a bitmap and PNG / JPEG encoding. */
object DrawingRenderer {
    /** Fixed logical sheet (3:4), so drawings look the same on every screen. */
    const val WIDTH = 1080
    const val HEIGHT = 1440

    /** An empty sheet of [width] x [height] pixels (D260: every note has its own size). */
    fun newSheet(width: Int = WIDTH, height: Int = HEIGHT): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Loads a stored PNG / JPEG onto a sheet of [width] x [height]: as it is when the sizes agree, else scaled to fit and centred. */
    fun decodeToSheet(bytes: ByteArray, width: Int = WIDTH, height: Int = HEIGHT): Bitmap? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val sheet = newSheet(width, height)
        if (source.width == width && source.height == height) {
            Canvas(sheet).drawBitmap(source, 0f, 0f, null)
        } else {
            val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
            val w = source.width * scale
            val h = source.height * scale
            val dst = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            Canvas(sheet).drawBitmap(source, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        source.recycle()
        return sheet
    }

    /** [source] on a new sheet of [width] x [height], kept at the top left: a bigger sheet adds empty space, a smaller one cuts (D260). */
    fun resized(source: Bitmap, width: Int, height: Int): Bitmap =
        newSheet(width, height).also { Canvas(it).drawBitmap(source, 0f, 0f, null) }

    /**
     * The typefaces for texts on a sheet (D209, D212): the app's own Roboto, so that bold and italic show on every phone. Set
     * once from the screen; without it (tests) the system font is used.
     */
    private var faces: Array<android.graphics.Typeface>? = null

    fun useFonts(context: android.content.Context) {
        if (faces != null) return
        faces = runCatching {
            arrayOf(
                androidx.core.content.res.ResourcesCompat.getFont(context, com.notebudget.app.R.font.roboto_regular)!!,
                androidx.core.content.res.ResourcesCompat.getFont(context, com.notebudget.app.R.font.roboto_bold)!!,
                androidx.core.content.res.ResourcesCompat.getFont(context, com.notebudget.app.R.font.roboto_italic)!!,
                androidx.core.content.res.ResourcesCompat.getFont(context, com.notebudget.app.R.font.roboto_bold_italic)!!,
            )
        }.getOrNull()
    }

    private fun typeface(style: Int): android.graphics.Typeface {
        val index = (if (style and Shape.TEXT_BOLD != 0) 1 else 0) + (if (style and Shape.TEXT_ITALIC != 0) 2 else 0)
        return faces?.get(index) ?: android.graphics.Typeface.defaultFromStyle(index)
    }

    /** The paint of a text of [size] px in [style] (bold / italic / underline / strikethrough). */
    fun textPaint(size: Float, color: Int, style: Int) = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = typeface(style)
        isUnderlineText = style and Shape.TEXT_UNDERLINE != 0
        isStrikeThruText = style and Shape.TEXT_STRIKE != 0
    }

    /** Width and height in sheet pixels of [text] (several lines allowed): the box a text shape gets when it is made. */
    fun measureText(text: String, size: Float, style: Int): Pair<Float, Float> {
        val paint = textPaint(size, 0, style)
        val lines = text.split('\n')
        val width = lines.maxOf { paint.measureText(it) }.coerceAtLeast(size / 2)
        return width to lines.size * paint.fontSpacing
    }

    private fun drawText(canvas: Canvas, shape: Shape) {
        val text = shape.text ?: return
        val p0 = shape.points.firstOrNull() ?: return
        val paint = textPaint(shape.textSize, shape.strokeColor, shape.textStyle)
        var baseline = p0.y - paint.fontMetrics.ascent
        text.split('\n').forEach { line ->
            canvas.drawText(line, p0.x, baseline, paint)
            baseline += paint.fontSpacing
        }
    }

    fun drawStroke(canvas: Canvas, stroke: Stroke) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.width
            color = stroke.colorArgb
            when (stroke.tool) {
                DrawingTool.BRUSH -> {
                    alpha = stroke.opacity.coerceIn(10, 255)
                    // A soft brush: the edge is blurred by up to half the width (D212).
                    if (stroke.softness > 0f) maskFilter = android.graphics.BlurMaskFilter(
                        (stroke.width * stroke.softness * 0.5f).coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                DrawingTool.ERASER -> xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                else -> Unit
            }
        }
        val pts = stroke.points
        if (pts.isEmpty()) return
        val nib = stroke.nibAngle
        if (stroke.tool == DrawingTool.BRUSH && nib != null) {
            drawNib(canvas, pts, stroke.width, nib, paint)
            return
        }
        if (pts.size == 1) {
            // A tap: a dot. Zero-length paths are not drawn reliably, so use a point.
            canvas.drawPoint(pts[0].x, pts[0].y, paint)
            return
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                // Smooth the polyline through the midpoints of consecutive samples.
                quadTo(p0.x, p0.y, (p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
            }
            lineTo(pts.last().x, pts.last().y)
        }
        canvas.drawPath(path, paint)
    }

    /**
     * A flat nib held at [angleDeg] (D212): thin across its edge and [width] wide along it, like a calligraphy pen, so strokes in
     * one direction are broad and in the other thin. Every step between two samples is the quadrilateral the nib sweeps; they
     * are one path, so an overlap is not painted twice (no dark knots with a see-through brush).
     */
    private fun drawNib(canvas: Canvas, pts: List<Stroke.Point>, width: Float, angleDeg: Float, paint: Paint) {
        val a = Math.toRadians(angleDeg.toDouble())
        val hx = (cos(a) * width / 2).toFloat()
        val hy = (sin(a) * width / 2).toFloat()
        val tx = (-sin(a) * width * 0.08).toFloat()
        val ty = (cos(a) * width * 0.08).toFloat()
        val path = Path().apply { fillType = Path.FillType.WINDING }
        fun quad(p0: Stroke.Point, p1: Stroke.Point) {
            path.moveTo(p0.x - hx - tx, p0.y - hy - ty)
            path.lineTo(p0.x + hx - tx, p0.y + hy - ty)
            path.lineTo(p1.x + hx + tx, p1.y + hy + ty)
            path.lineTo(p1.x - hx + tx, p1.y - hy + ty)
            path.close()
        }
        if (pts.size == 1) quad(pts[0], pts[0]) else for (i in 1 until pts.size) quad(pts[i - 1], pts[i])
        canvas.drawPath(path, Paint(paint).apply { style = Paint.Style.FILL })
    }

    /** Applies one raster operation (stroke, bucket fill, shape or text) to [bitmap]. */
    fun apply(bitmap: Bitmap, op: RasterOp) {
        when (op) {
            is ShapeStamp -> drawShape(Canvas(bitmap), op.shape)
            is Stroke -> drawStroke(Canvas(bitmap), op)
            is Fill -> {
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                if (FloodFill.fill(pixels, w, h, op.x, op.y, op.colorArgb, op.tolerance) > 0) {
                    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                }
            }
        }
    }

    /** Draws one vector shape in sheet coordinates. Shapes of an unknown kind are skipped. */
    fun drawShape(canvas: Canvas, shape: Shape) {
        val kind = shape.kind ?: return
        val pts = shape.points
        if (pts.isEmpty()) return
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = shape.strokeWidth
            color = shape.strokeColor
        }
        val fill = shape.fillColor?.takeIf { shape.canFill }?.let {
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = it }
        }
        val b = shape.bounds()
        when (kind) {
            ShapeType.LINE -> if (pts.size >= 2) canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, outline)
            ShapeType.ARROW -> if (pts.size >= 2) drawArrow(canvas, pts[0], pts[1], shape.strokeWidth, outline)
            ShapeType.DOUBLE_ARROW -> if (pts.size >= 2) {
                val mid = Point((pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2)
                drawArrow(canvas, mid, pts[1], shape.strokeWidth, outline)
                drawArrow(canvas, mid, pts[0], shape.strokeWidth, outline)
            }
            ShapeType.PENTAGON, ShapeType.HEXAGON, ShapeType.RHOMBUS, ShapeType.STAR -> {
                val poly = shape.polygon()
                val path = Path().apply {
                    poly.forEachIndexed { i, pt -> if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y) }
                    close()
                }
                fill?.let { canvas.drawPath(path, it) }
                canvas.drawPath(path, outline)
            }
            ShapeType.TEXT -> drawText(canvas, shape)
            ShapeType.RECT -> {
                fill?.let { canvas.drawRect(b.left, b.top, b.right, b.bottom, it) }
                canvas.drawRect(b.left, b.top, b.right, b.bottom, outline)
            }
            ShapeType.ELLIPSE -> {
                val oval = RectF(b.left, b.top, b.right, b.bottom)
                fill?.let { canvas.drawOval(oval, it) }
                canvas.drawOval(oval, outline)
            }
            ShapeType.TRIANGLE -> {
                val t = shape.triangle()
                val path = Path().apply { moveTo(t[0].x, t[0].y); lineTo(t[1].x, t[1].y); lineTo(t[2].x, t[2].y); close() }
                fill?.let { canvas.drawPath(path, it) }
                canvas.drawPath(path, outline)
            }
            ShapeType.PATH -> {
                if (pts.size == 1) {
                    canvas.drawPoint(pts[0].x, pts[0].y, outline)
                } else {
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) {
                            val p0 = pts[i - 1]
                            val p1 = pts[i]
                            quadTo(p0.x, p0.y, (p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
                        }
                        lineTo(pts.last().x, pts.last().y)
                    }
                    // A filled path is filled as if closed, but its outline stays open.
                    fill?.let { canvas.drawPath(Path(path).apply { close() }, it) }
                    canvas.drawPath(path, outline)
                }
            }
        }
    }

    private fun drawArrow(canvas: Canvas, from: Point, to: Point, width: Float, outline: Paint) {
        val length = max(16f, width * 4f)
        val angle = atan2(to.y - from.y, to.x - from.x)
        // The line stops at the base of the head so that a thick line does not poke through its tip.
        val baseX = to.x - length * 0.8f * cos(angle)
        val baseY = to.y - length * 0.8f * sin(angle)
        canvas.drawLine(from.x, from.y, baseX, baseY, outline)
        val spread = Math.toRadians(26.0).toFloat()
        val head = Path().apply {
            moveTo(to.x, to.y)
            lineTo(to.x - length * cos(angle - spread), to.y - length * sin(angle - spread))
            lineTo(to.x - length * cos(angle + spread), to.y - length * sin(angle + spread))
            close()
        }
        val headPaint = Paint(outline).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f }
        canvas.drawPath(head, headPaint)
    }

    /**
     * The picture as it is seen: the sheet's [background] (D265), the raster layer on it and the vector layer on top. Where
     * nothing is drawn it is as see-through as the background (fully, when the sheet has none).
     */
    fun compose(png: ByteArray?, shapes: List<Shape>, width: Int = WIDTH, height: Int = HEIGHT, background: SheetBackground = SheetBackground.DEFAULT): Bitmap {
        val sheet = newSheet(width, height)
        val canvas = Canvas(sheet)
        if (background.visible) canvas.drawColor(background.argb)
        png?.let { decodeToSheet(it, width, height) }?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            it.recycle()
        }
        shapes.forEach { drawShape(canvas, it) }
        return sheet
    }

    /** [compose] as one PNG: what export and Share hand out (D42, D216, D265). */
    fun flatten(png: ByteArray?, shapes: List<Shape>, width: Int = WIDTH, height: Int = HEIGHT, background: SheetBackground = SheetBackground.DEFAULT): ByteArray {
        val sheet = compose(png, shapes, width, height, background)
        return toPng(sheet).also { sheet.recycle() }
    }

    fun toPng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** PNG bytes re-encoded as JPEG on a white background (JPEG has no transparency). */
    fun pngToJpeg(png: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IllegalArgumentException("Not an image")
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        return ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
    }
}
