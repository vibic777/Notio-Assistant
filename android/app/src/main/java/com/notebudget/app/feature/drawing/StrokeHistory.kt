package com.notebudget.app.feature.drawing

/**
 * Raster tools. FILL floods a region instead of drawing a line. EYEDROPPER takes the colour under the finger, TEXT asks for words
 * and puts them there, SHAPE draws a primitive from where the finger goes down to where it lifts (D212).
 */
enum class DrawingTool { PENCIL, BRUSH, ERASER, FILL, EYEDROPPER, TEXT, SHAPE }

/** One undoable operation on the raster layer. Replaying the operations in order rebuilds the layer. */
sealed interface RasterOp

/**
 * One finger stroke. [points] are in sheet pixels, [width] is the line width in pixels. For the brush (D212): [opacity] 0..255,
 * [softness] 0..1 blurs the edge, and [nibAngle] in degrees makes a flat nib like a calligraphy pen (`null` = a round tip).
 */
class Stroke(
    val tool: DrawingTool,
    val colorArgb: Int,
    val width: Float,
    val points: List<Point>,
    val opacity: Int = 255,
    val softness: Float = 0f,
    val nibAngle: Float? = null,
) : RasterOp {
    data class Point(val x: Float, val y: Float)
}

/**
 * A bucket fill at ([x], [y]) in sheet pixels. It looks at the raster layer only, so it depends
 * on the operations before it; that is why it is replayed, not stored as pixels.
 */
class Fill(val x: Int, val y: Int, val colorArgb: Int, val tolerance: Int = FloodFill.DEFAULT_TOLERANCE) : RasterOp

/** A shape or a text painted onto the sheet (D212): drawn once like a vector shape, then it is pixels like everything else. */
class ShapeStamp(val shape: Shape) : RasterOp

/**
 * Raster operations that can still be undone, and the ones undone that can be redone (D197). Older operations are handed back
 * by [add] so the caller can bake them into the base image; this keeps undo cheap (only recent operations are replayed) and
 * memory bounded however long the user draws.
 */
class RasterHistory(maxUndo: Int = com.notebudget.app.core.common.UndoHistory.DEFAULT_STEPS) {
    private val items = ArrayList<RasterOp>()
    private val undone = ArrayList<RasterOp>()

    var maxUndo: Int = maxUndo.coerceAtLeast(1)
        private set

    val ops: List<RasterOp> get() = items
    val canUndo: Boolean get() = items.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    /** Returns the operation that can no longer be undone, if any. A new operation drops what could have been redone. */
    fun add(op: RasterOp): RasterOp? {
        undone.clear()
        return push(op)
    }

    private fun push(op: RasterOp): RasterOp? {
        items += op
        return if (items.size > maxUndo) items.removeAt(0) else null
    }

    fun undo(): Boolean = items.isNotEmpty().also { if (it) undone += items.removeAt(items.lastIndex) }

    /**
     * Takes back the operation undone last: it must be applied to the picture again. [Redo.baked] is an operation that
     * became too old to undo on the way (bake it into the base image, as with [add]).
     */
    fun redo(): Redo? {
        val op = undone.removeLastOrNull() ?: return null
        return Redo(op, push(op))
    }

    class Redo(val op: RasterOp, val baked: RasterOp?)

    /** A new limit (Settings): the operations beyond it are handed back, oldest first, to be baked in. */
    fun setLimit(limit: Int): List<RasterOp> {
        maxUndo = limit.coerceAtLeast(1)
        while (undone.size > maxUndo) undone.removeAt(0)
        val baked = ArrayList<RasterOp>()
        while (items.size > maxUndo) baked += items.removeAt(0)
        return baked
    }

    fun clear() {
        items.clear()
        undone.clear()
    }
}
