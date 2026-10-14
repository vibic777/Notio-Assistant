package com.notebudget.app.feature.drawing

import com.notebudget.app.data.transfer.TransferFormat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
data class Point(val x: Float, val y: Float)

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Vector primitives. [twoPoint] shapes are defined by two points (a line's ends, or two opposite
 * corners of the bounding box of a rectangle / ellipse / triangle); PATH is a free-hand curve.
 * [closed] shapes can be filled.
 */
enum class ShapeType(val twoPoint: Boolean, val closed: Boolean) {
    LINE(true, false),
    ARROW(true, false),
    RECT(true, true),
    ELLIPSE(true, true),
    TRIANGLE(true, true),
    PATH(false, true),

    // D212: more primitives, drawn inside the box of their two points, and text.
    PENTAGON(true, true),
    HEXAGON(true, true),
    RHOMBUS(true, true),
    STAR(true, true),
    DOUBLE_ARROW(true, false),

    /** A text: [Shape.points] = its top-left and bottom-right corners (measured when it is made), [Shape.text] the words. */
    TEXT(false, false);

    companion object {
        fun of(id: String): ShapeType? = entries.firstOrNull { it.name == id }
    }
}

/**
 * One vector object in sheet pixels. [type] is a string (a [ShapeType] name) so that a shape kind
 * added by a later version is kept and re-saved untouched instead of being dropped; such a shape
 * is simply not drawn. [fillColor] `null` means no fill.
 */
@Serializable
data class Shape(
    val id: String,
    val type: String,
    val points: List<Point> = emptyList(),
    val strokeColor: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 6f,
    val fillColor: Int? = null,
    /** The layer the shape is on (D98); `null` = the first layer (drawings made before layers existed). */
    val layerId: String? = null,
    /** TEXT only (D212): the words, their size in sheet pixels and a mask of [TEXT_BOLD] / [TEXT_ITALIC] / ... */
    val text: String? = null,
    val textSize: Float = DEFAULT_TEXT_SIZE,
    val textStyle: Int = 0,
) {
    val kind: ShapeType? get() = ShapeType.of(type)
    val canFill: Boolean get() = kind?.closed == true

    fun bounds(): Bounds {
        if (points.isEmpty()) return Bounds(0f, 0f, 0f, 0f)
        return Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    fun translated(dx: Float, dy: Float) = copy(points = points.map { Point(it.x + dx, it.y + dy) })

    fun withPoint(index: Int, p: Point): Shape =
        copy(points = points.toMutableList().also { if (index in it.indices) it[index] = p })

    /** The three corners of a TRIANGLE inscribed in the bounding box: apex on top. */
    fun triangle(): List<Point> {
        val b = bounds()
        return listOf(Point((b.left + b.right) / 2, b.top), Point(b.right, b.bottom), Point(b.left, b.bottom))
    }

    /**
     * The corners of a regular polygon kind (D212) inscribed in the bounding box, first corner on top: a pentagon, a hexagon,
     * a rhombus (the middles of the four sides) or a five-pointed star; the triangle too. Empty for other kinds.
     */
    fun polygon(): List<Point> {
        val b = bounds()
        val cx = (b.left + b.right) / 2
        val cy = (b.top + b.bottom) / 2
        val rx = b.width / 2
        val ry = b.height / 2
        fun ring(n: Int, inner: Float = 1f, every: Int = 1): List<Point> = (0 until n).map { k ->
            val a = -Math.PI / 2 + 2 * Math.PI * k / n
            val r = if (every > 1 && k % every == 1) inner else 1f
            Point(cx + (rx * r * kotlin.math.cos(a)).toFloat(), cy + (ry * r * kotlin.math.sin(a)).toFloat())
        }
        return when (kind) {
            ShapeType.TRIANGLE -> triangle()
            ShapeType.PENTAGON -> ring(5)
            ShapeType.HEXAGON -> ring(6)
            ShapeType.RHOMBUS -> listOf(Point(cx, b.top), Point(b.right, cy), Point(cx, b.bottom), Point(b.left, cy))
            ShapeType.STAR -> ring(10, inner = 0.42f, every = 2)
            else -> emptyList()
        }
    }

    /** Does a touch at ([x], [y]) hit this shape? [tolerance] is the finger slack in sheet pixels. */
    fun hit(x: Float, y: Float, tolerance: Float): Boolean {
        val kind = kind ?: return false
        if (points.isEmpty()) return false
        val t = tolerance + strokeWidth / 2
        val b = bounds()
        return when (kind) {
            ShapeType.LINE, ShapeType.ARROW, ShapeType.DOUBLE_ARROW ->
                points.size >= 2 && distanceToSegment(x, y, points[0], points[1]) <= t

            ShapeType.PENTAGON, ShapeType.HEXAGON, ShapeType.RHOMBUS, ShapeType.STAR -> {
                val poly = polygon()
                nearEdges(x, y, poly, closed = true, t) || (fillColor != null && insidePolygon(x, y, poly))
            }

            // A text is picked anywhere in its box.
            ShapeType.TEXT -> x in (b.left - t)..(b.right + t) && y in (b.top - t)..(b.bottom + t)

            ShapeType.RECT -> {
                val inOuter = x in (b.left - t)..(b.right + t) && y in (b.top - t)..(b.bottom + t)
                if (fillColor != null) inOuter
                else inOuter && !(x in (b.left + t)..(b.right - t) && y in (b.top + t)..(b.bottom - t))
            }

            ShapeType.ELLIPSE -> {
                val a = max(b.width / 2, 0.5f)
                val c = max(b.height / 2, 0.5f)
                val nx = (x - (b.left + b.right) / 2) / a
                val ny = (y - (b.top + b.bottom) / 2) / c
                val r = sqrt(nx * nx + ny * ny)
                val scale = min(a, c)
                if (fillColor != null) r <= 1f + t / max(scale, 1f) else abs(r - 1f) * scale <= t
            }

            ShapeType.TRIANGLE -> {
                val tri = triangle()
                nearEdges(x, y, tri, closed = true, t) || (fillColor != null && insidePolygon(x, y, tri))
            }

            ShapeType.PATH ->
                nearEdges(x, y, points, closed = false, t) ||
                    (fillColor != null && points.size >= 3 && insidePolygon(x, y, points))
        }
    }

    /**
     * Is ([x], [y]) inside this CLOSED shape, edge included? Unlike [hit] this is true anywhere in the
     * interior even when the shape has no fill, so a finger can pick or paint an outline-only shape by
     * touching inside it. Lines and arrows are never "inside" anything.
     */
    fun contains(x: Float, y: Float, slack: Float): Boolean {
        val kind = kind ?: return false
        if (!kind.closed || points.isEmpty()) return false
        val t = slack + strokeWidth / 2
        val b = bounds()
        return when (kind) {
            ShapeType.RECT -> x in (b.left - t)..(b.right + t) && y in (b.top - t)..(b.bottom + t)

            ShapeType.ELLIPSE -> {
                val a = max(b.width / 2, 0.5f)
                val c = max(b.height / 2, 0.5f)
                val nx = (x - (b.left + b.right) / 2) / a
                val ny = (y - (b.top + b.bottom) / 2) / c
                sqrt(nx * nx + ny * ny) <= 1f + t / max(min(a, c), 1f)
            }

            ShapeType.TRIANGLE, ShapeType.PENTAGON, ShapeType.HEXAGON, ShapeType.RHOMBUS, ShapeType.STAR -> {
                val poly = polygon()
                insidePolygon(x, y, poly) || nearEdges(x, y, poly, closed = true, t)
            }

            // A free-hand curve is filled as if closed; fewer than three points enclose nothing.
            ShapeType.PATH -> points.size >= 3 && (insidePolygon(x, y, points) || nearEdges(x, y, points, closed = true, t))

            else -> false
        }
    }

    /** Area of the bounding box: picks the smallest shape when several contain a touch. */
    fun area(): Float = bounds().let { it.width * it.height }

    private fun nearEdges(x: Float, y: Float, pts: List<Point>, closed: Boolean, t: Float): Boolean {
        val n = pts.size
        if (n == 1) return hypot(x - pts[0].x, y - pts[0].y) <= t
        val last = if (closed) n else n - 1
        for (i in 0 until last) {
            if (distanceToSegment(x, y, pts[i], pts[(i + 1) % n]) <= t) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_TEXT_SIZE = 56f
        const val TEXT_BOLD = 1
        const val TEXT_ITALIC = 2
        const val TEXT_UNDERLINE = 4
        const val TEXT_STRIKE = 8

        fun distanceToSegment(x: Float, y: Float, a: Point, b: Point): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) return hypot(x - a.x, y - a.y)
            val t = (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
        }

        /** Even-odd rule. */
        fun insidePolygon(x: Float, y: Float, poly: List<Point>): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val pi = poly[i]
                val pj = poly[j]
                if ((pi.y > y) != (pj.y > y) && x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x) inside = !inside
                j = i
            }
            return inside
        }
    }
}

/**
 * The vector layer as stored (`vector.json`). Follows the compatibility rules of the other files
 * (D84): defaults everywhere, unknown fields ignored, unknown shape kinds kept.
 */
@Serializable
data class VectorDocument(
    val formatVersion: Int = 1,
    val shapes: List<Shape> = emptyList(),
    /** Bottom layer first (D98). Empty in documents made before layers: they have the one default layer. */
    val layers: List<VectorLayer> = emptyList(),
    /** The size of the sheet in pixels (D260); 1080 x 1440 (3:4) in documents made before. */
    val sheetWidth: Int = SheetSize.DEFAULT_WIDTH,
    val sheetHeight: Int = SheetSize.DEFAULT_HEIGHT,
    /** The unit the size was typed in (px, mm, cm): the dialog shows it again. */
    val sheetUnit: String = "px",
    /** The colour of the sheet, RGB (D265); white in documents made before. */
    val sheetColor: Int = SheetBackground.WHITE,
    /** Whether the sheet is see-through, and by how much in % (100 = no background at all) (D265). */
    val sheetTransparent: Boolean = false,
    val sheetTransparency: Int = 100,
) {
    /** The sheet size to use: the stored one, or the default when it is out of the limits. */
    fun sheet(): Pair<Int, Int> = SheetSize.safe(sheetWidth, sheetHeight)

    /** The background of the sheet (D265). */
    fun background(): SheetBackground = SheetBackground(sheetColor, sheetTransparent, sheetTransparency.coerceIn(0, 100))

    /** The layers of the document; there is always at least one. */
    fun allLayers(): List<VectorLayer> = layers.ifEmpty { listOf(VectorLayer(VectorLayer.DEFAULT_ID)) }

    /** What is drawn, in drawing order: layer by layer from the bottom, hidden layers left out. */
    fun drawableShapes(): List<Shape> = VectorLayers.drawOrder(shapes, allLayers(), visibleOnly = true)

    fun encode(): ByteArray =
        TransferFormat.json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        /** `null` if [bytes] is not a vector layer. */
        fun decode(bytes: ByteArray): VectorDocument? = try {
            TransferFormat.json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}

/** Ramer-Douglas-Peucker: drops points of a free-hand path that are within [epsilon] of a straight run. */
fun simplify(points: List<Point>, epsilon: Float): List<Point> {
    if (points.size < 3) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<Pair<Int, Int>>()
    stack.addLast(0 to points.lastIndex)
    while (stack.isNotEmpty()) {
        val (first, last) = stack.removeLast()
        var far = -1
        var farDistance = epsilon
        for (i in first + 1 until last) {
            val d = Shape.distanceToSegment(points[i].x, points[i].y, points[first], points[last])
            if (d > farDistance) { farDistance = d; far = i }
        }
        if (far >= 0) {
            keep[far] = true
            stack.addLast(first to far)
            stack.addLast(far to last)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/**
 * A layer of a blueprint (D98): a name, and whether it is shown and whether it can be edited. [name] may be empty; the
 * screen then shows "Layer N". Hidden layers are not drawn, not exported as picture and not selectable; locked layers are
 * drawn but cannot be picked or changed.
 */
@Serializable
data class VectorLayer(
    val id: String,
    val name: String = "",
    val visible: Boolean = true,
    val locked: Boolean = false,
) {
    companion object {
        /** The id of the layer that shapes without a layer (older drawings) belong to. */
        const val DEFAULT_ID = "base"
    }
}

/** The order in which layered shapes are drawn. */
object VectorLayers {
    /** The layer a shape is on; an unknown or missing layer id means the first layer. */
    fun layerOf(shape: Shape, layers: List<VectorLayer>): String =
        shape.layerId?.takeIf { id -> layers.any { it.id == id } } ?: layers.first().id

    /** Shapes layer by layer, bottom layer first; inside a layer in their own order. */
    fun drawOrder(shapes: List<Shape>, layers: List<VectorLayer>, visibleOnly: Boolean): List<Shape> {
        val byLayer = shapes.groupBy { layerOf(it, layers) }
        return layers.filter { !visibleOnly || it.visible }.flatMap { byLayer[it.id].orEmpty() }
    }
}
