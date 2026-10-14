package com.notebudget.app.feature.drawing

import java.util.UUID

enum class VectorTool(val shape: ShapeType?, val square: Boolean = false) {
    SELECT(null),
    LINE(ShapeType.LINE),
    ARROW(ShapeType.ARROW),
    RECT(ShapeType.RECT),
    ELLIPSE(ShapeType.ELLIPSE),
    TRIANGLE(ShapeType.TRIANGLE),
    PATH(ShapeType.PATH),

    /** Sets the fill of the shape under the finger; the vector counterpart of the raster bucket. */
    FILL(null),

    // D212: a square and a circle are a rectangle and an ellipse with equal sides; more primitives; text; the eyedropper.
    SQUARE(ShapeType.RECT, square = true),
    CIRCLE(ShapeType.ELLIPSE, square = true),
    PENTAGON(ShapeType.PENTAGON),
    HEXAGON(ShapeType.HEXAGON),
    RHOMBUS(ShapeType.RHOMBUS),
    STAR(ShapeType.STAR),
    DOUBLE_ARROW(ShapeType.DOUBLE_ARROW),

    /** A tap asks for a text there (the screen shows the dialog and adds it with [VectorEditor.add]). */
    TEXT(null),

    /** A tap takes the colour of the shape under the finger as the colour of the lines (D212). */
    EYEDROPPER(null),
}

/** The point that makes the box from [from] to [to] a square (for the square and the circle tools). */
fun squareCorner(from: Point, to: Point): Point {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val side = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
    return Point(from.x + side * (if (dx < 0) -1 else 1), from.y + side * (if (dy < 0) -1 else 1))
}

/**
 * The logic of the vector layer, without any Android type so that it is unit-tested: drawing new
 * shapes, selecting, moving and reshaping them, fill, style, z-order and undo. The screen feeds it
 * pointer events in sheet pixels.
 */
class VectorEditor(
    initial: List<Shape> = emptyList(),
    initialLayers: List<VectorLayer> = emptyList(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    maxUndo: Int = com.notebudget.app.core.common.UndoHistory.DEFAULT_STEPS,
) {
    var shapes: List<Shape> = initial
        private set
    var selectedId: String? = null
        private set

    /** The layers, bottom first; never empty (D98). */
    var layers: List<VectorLayer> = initialLayers.ifEmpty { listOf(VectorLayer(VectorLayer.DEFAULT_ID)) }
        private set

    /** The layer new shapes go on: the top layer at first. */
    var activeLayerId: String = layers.last().id
        private set

    /** The layer of [shape]. */
    fun layerOf(shape: Shape): String = VectorLayers.layerOf(shape, layers)

    private fun layer(id: String): VectorLayer? = layers.firstOrNull { it.id == id }

    /** What is drawn: layer by layer, bottom first, hidden layers left out. */
    val drawOrder: List<Shape> get() = VectorLayers.drawOrder(shapes, layers, visibleOnly = true)

    /** Shapes a touch can pick or fill: visible AND unlocked layers, topmost last. */
    private val pickable: List<Shape>
        get() {
            val open = layers.filter { it.visible && !it.locked }.map { it.id }.toSet()
            return VectorLayers.drawOrder(shapes, layers, visibleOnly = true).filter { layerOf(it) in open }
        }

    /** New shapes can only go on a layer that is shown and not locked. */
    val canDrawOnActiveLayer: Boolean get() = layer(activeLayerId)?.let { it.visible && !it.locked } == true

    /** The shape being drawn right now; not part of [shapes] until the finger lifts. */
    var draft: Shape? = null
        private set

    /**
     * The active tool. Choosing the fill tool while the fill colour is "none" picks a colour, so that
     * the first tap paints something instead of silently doing nothing.
     */
    var tool: VectorTool = VectorTool.SELECT
        set(value) {
            field = value
            if (value == VectorTool.FILL && fillColor == null) fillColor = DEFAULT_FILL
            // The picked colour must not recolour a shape that happens to be selected (D212).
            if (value == VectorTool.EYEDROPPER || value == VectorTool.TEXT) selectedId = null
        }
    var strokeColor: Int = 0xFF000000.toInt()
        private set
    var strokeWidth: Float = 6f
        private set

    /** `null` = no fill. */
    var fillColor: Int? = null
        private set

    /** Bumped when [shapes] change, i.e. when there is something to save. */
    var documentVersion = 0
        private set

    /** Bumped on every visible change, drafts and selection included. */
    var revision = 0
        private set

    private val history = com.notebudget.app.core.common.UndoHistory<List<Shape>>(maxUndo)
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /** How many steps back can be undone (Settings, D197). */
    var undoLimit: Int
        get() = history.limit
        set(value) { history.limit = value }

    val selected: Shape? get() = shapes.firstOrNull { it.id == selectedId }

    private sealed interface Drag {
        /** [slop]: a touch that moves less than this is a tap, not a drag (finger jitter). */
        class Move(val start: Point, var last: Point, val before: List<Shape>, val slop: Float, var moved: Boolean = false) : Drag
        class Handle(val index: Int, val start: Point, val before: List<Shape>, val slop: Float, var moved: Boolean = false) : Drag
        object Create : Drag
    }

    private var drag: Drag? = null

    // ---- pointer events ----

    fun pointerDown(p: Point, tolerance: Float) {
        drag = null
        when (tool) {
            VectorTool.SELECT -> {
                val sel = selected
                val handle = sel?.takeIf { it.kind?.twoPoint == true }
                    ?.points?.indexOfFirst { distance(it, p) <= tolerance * 1.5f }?.takeIf { it >= 0 }
                if (sel != null && handle != null) {
                    drag = Drag.Handle(handle, p, shapes, tolerance / 2)
                } else {
                    val hit = pickShape(p, tolerance)
                    selectedId = hit?.id
                    if (hit != null) drag = Drag.Move(p, p, shapes, tolerance / 2)
                }
            }

            VectorTool.FILL -> {
                // The topmost closed shape the touch is inside of, filled or not.
                val target = pickable.lastOrNull { it.canFill && it.contains(p.x, p.y, tolerance) }
                if (target != null && target.fillColor != fillColor) {
                    commit(shapes.map { if (it.id == target.id) it.copy(fillColor = fillColor) else it })
                }
            }

            else -> {
                val type = tool.shape ?: return
                if (!canDrawOnActiveLayer) return // a hidden or locked layer takes no new shapes
                draft = Shape(
                    id = newId(),
                    type = type.name,
                    points = if (type.twoPoint) listOf(p, p) else listOf(p),
                    strokeColor = strokeColor,
                    strokeWidth = strokeWidth,
                    fillColor = if (type.closed) fillColor else null,
                    layerId = activeLayerId,
                )
                drag = Drag.Create
            }
        }
        revision++
    }

    fun pointerMove(p: Point) {
        when (val d = drag) {
            is Drag.Move -> {
                if (!d.moved && distance(d.start, p) < d.slop) return // still a tap
                val sel = selected ?: return
                replace(sel.translated(p.x - d.last.x, p.y - d.last.y))
                d.last = p
                d.moved = true
            }

            is Drag.Handle -> {
                if (!d.moved && distance(d.start, p) < d.slop) return
                val sel = selected ?: return
                replace(sel.withPoint(d.index, p))
                d.moved = true
            }

            Drag.Create -> draft = draft?.let { s ->
                if (s.kind == ShapeType.PATH) {
                    val last = s.points.last()
                    if (distance(last, p) >= MIN_PATH_STEP) s.copy(points = s.points + p) else s
                } else {
                    s.withPoint(1, if (tool.square) squareCorner(s.points[0], p) else p)
                }
            }

            null -> return
        }
        revision++
    }

    /** Ends the gesture. Returns `true` if the document changed. */
    fun pointerUp(): Boolean {
        val d = drag
        drag = null
        var changed = false
        when (d) {
            is Drag.Move -> if (d.moved) { push(d.before); changed = true }
            is Drag.Handle -> if (d.moved) { push(d.before); changed = true }
            Drag.Create -> {
                val s = draft
                draft = null
                if (s != null && meaningful(s)) {
                    val finished = if (s.kind == ShapeType.PATH) s.copy(points = simplify(s.points, PATH_EPSILON)) else s
                    push(shapes)
                    shapes = shapes + finished
                    // Not selected: a selection frame that follows every new shape looks stuck.
                    selectedId = null
                    changed = true
                }
            }
            null -> Unit
        }
        if (changed) documentVersion++
        revision++
        return changed
    }

    /** Like [pointerCancel], but a move that is under way is undone as well (a pinch started, D118). */
    fun pointerCancelDroppingDraft() {
        val d = drag
        if (d is Drag.Move && d.moved) shapes = d.before
        if (d is Drag.Handle && d.moved) shapes = d.before
        drag = null
        draft = null
        revision++
    }

    /** The gesture was interrupted (e.g. a second finger): drop the draft, keep what was moved. */
    fun pointerCancel() {
        if (drag is Drag.Create) draft = null
        pointerUp()
    }

    // ---- selection actions ----

    fun deleteSelected() {
        val id = selectedId ?: return
        commit(shapes.filterNot { it.id == id })
        selectedId = null
    }

    fun duplicateSelected() {
        val s = selected ?: return
        val copy = s.translated(DUPLICATE_OFFSET, DUPLICATE_OFFSET).copy(id = newId())
        commit(shapes + copy)
        selectedId = copy.id
    }

    fun bringToFront() = reorder { list, i -> list.toMutableList().also { it.add(it.removeAt(i)) } }

    fun sendToBack() = reorder { list, i -> list.toMutableList().also { it.add(0, it.removeAt(i)) } }

    fun bringForward() = reorder { list, i ->
        if (i >= list.lastIndex) list else list.toMutableList().also { it.add(i + 1, it.removeAt(i)) }
    }

    fun sendBackward() = reorder { list, i ->
        if (i <= 0) list else list.toMutableList().also { it.add(i - 1, it.removeAt(i)) }
    }

    private fun reorder(change: (List<Shape>, Int) -> List<Shape>) {
        val i = shapes.indexOfFirst { it.id == selectedId }
        if (i < 0) return
        val next = change(shapes, i)
        if (next != shapes) commit(next)
    }

    fun selectNone() {
        selectedId = null
        revision++
    }

    // ---- style: sets the default for new shapes and, if a shape is selected, restyles it ----

    fun setStrokeColor(color: Int) {
        strokeColor = color
        restyle { it.copy(strokeColor = color) }
    }

    fun setFillColor(color: Int?) {
        fillColor = color
        restyle { if (it.canFill) it.copy(fillColor = color) else it }
    }

    fun setStrokeWidth(width: Float) {
        strokeWidth = width
        restyle { it.copy(strokeWidth = width) }
    }

    private fun restyle(change: (Shape) -> Shape) {
        // Only while a shape is selected for editing; a drawing tool just changes the default.
        val sel = selected
        if (sel != null && tool == VectorTool.SELECT) {
            val next = change(sel)
            if (next != sel) commit(shapes.map { if (it.id == sel.id) next else it })
        }
        revision++
    }

    // ---- undo ----

    fun undo() {
        show(history.undo(shapes) ?: return)
    }

    /** Brings back what the last [undo] took away (D197). */
    fun redo() {
        show(history.redo(shapes) ?: return)
    }

    private fun show(state: List<Shape>) {
        shapes = state
        if (shapes.none { it.id == selectedId }) selectedId = null
        documentVersion++
        revision++
    }

    // ---- layers (D98): changes of the layers themselves are not part of undo ----

    fun setActiveLayer(id: String) {
        if (layer(id) == null) return
        activeLayerId = id
        revision++
    }

    /** Adds a layer on top and makes it the active one; returns its id. */
    fun addLayer(name: String): String {
        val id = newId().take(12)
        layers = layers + VectorLayer(id, name.trim())
        activeLayerId = id
        changedLayers()
        return id
    }

    fun renameLayer(id: String, name: String) = changeLayer(id) { it.copy(name = name.trim()) }

    fun setLayerVisible(id: String, visible: Boolean) = changeLayer(id) { it.copy(visible = visible) }

    fun setLayerLocked(id: String, locked: Boolean) = changeLayer(id) { it.copy(locked = locked) }

    /** Moves a layer one place up ([delta] +1, drawn later, above) or down (-1). */
    fun moveLayer(id: String, delta: Int) {
        val from = layers.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in layers.indices) return
        layers = layers.toMutableList().also { it.add(to, it.removeAt(from)) }
        changedLayers()
    }

    /** Moves a layer to the very top ([top], drawn last, above all) or to the very bottom (D234). */
    fun moveLayerToEdge(id: String, top: Boolean) {
        val from = layers.indexOfFirst { it.id == id }
        val to = if (top) layers.lastIndex else 0
        if (from < 0 || from == to) return
        layers = layers.toMutableList().also { it.add(to, it.removeAt(from)) }
        changedLayers()
    }

    /** Deletes a layer with the shapes on it; the last layer cannot be deleted. Clears undo (the shapes are gone for good). */
    fun deleteLayer(id: String) {
        if (layers.size <= 1 || layer(id) == null) return
        shapes = shapes.filterNot { layerOf(it) == id }
        layers = layers.filterNot { it.id == id }
        if (activeLayerId == id) activeLayerId = layers.last().id
        history.clear()
        changedLayers()
    }

    /** Adds a finished shape (a text made in its dialog, D212) on the active layer, as one undo step. */
    fun add(shape: Shape) {
        if (!canDrawOnActiveLayer) return
        commit(shapes + shape.copy(layerId = activeLayerId))
    }

    /** Changes the selected shape as one undo step (the words of a text, D212). */
    fun changeSelected(change: (Shape) -> Shape) {
        val sel = selected ?: return
        val next = change(sel)
        if (next != sel) commit(shapes.map { if (it.id == sel.id) next else it })
    }

    /** The colour under [p] for the eyedropper (D212): the fill of a filled shape the touch is inside of, else its lines. */
    fun colorAt(p: Point, tolerance: Float): Int? {
        val hit = pickable.lastOrNull { it.hit(p.x, p.y, tolerance) || (it.fillColor != null && it.contains(p.x, p.y, tolerance)) } ?: return null
        return if (hit.fillColor != null && hit.contains(p.x, p.y, 0f) && hit.kind != ShapeType.TEXT) hit.fillColor else hit.strokeColor
    }

    fun newShapeId(): String = newId()

    /** Moves the selected shape to another layer (undoable). */
    fun moveSelectedToLayer(id: String) {
        val sel = selected ?: return
        if (layer(id) == null || layerOf(sel) == id) return
        commit(shapes.map { if (it.id == sel.id) it.copy(layerId = id) else it })
        selectedId = null
    }

    private fun changeLayer(id: String, change: (VectorLayer) -> VectorLayer) {
        if (layer(id) == null) return
        layers = layers.map { if (it.id == id) change(it) else it }
        changedLayers()
    }

    /** After a layer changed: a selected shape on a layer that can no longer be edited is deselected. */
    private fun changedLayers() {
        selected?.let { sel -> layer(layerOf(sel))?.let { if (!it.visible || it.locked) selectedId = null } }
        documentVersion++
        revision++
    }

    /** Replaces the whole document (loading). Clears undo. */
    fun load(list: List<Shape>, layerList: List<VectorLayer> = emptyList()) {
        shapes = list
        layers = layerList.ifEmpty { listOf(VectorLayer(VectorLayer.DEFAULT_ID)) }
        activeLayerId = layers.last().id
        selectedId = null
        draft = null
        history.clear()
        revision++
    }

    // ---- internals ----

    private fun commit(next: List<Shape>) {
        push(shapes)
        shapes = next
        documentVersion++
        revision++
    }

    private fun push(before: List<Shape>) = history.record(before)

    private fun replace(shape: Shape) {
        shapes = shapes.map { if (it.id == shape.id) shape else it }
    }

    /**
     * The shape a touch means: first the topmost one hit on its outline or (when filled) inside; else
     * the smallest closed shape the touch is inside of, so an outline-only shape can be picked by
     * touching its middle without hiding the small shapes on top of it.
     */
    private fun pickShape(p: Point, tolerance: Float): Shape? =
        pickable.lastOrNull { it.hit(p.x, p.y, tolerance) }
            ?: pickable.filter { it.contains(p.x, p.y, tolerance) }.minByOrNull { it.area() }

    private fun meaningful(s: Shape): Boolean = when {
        s.points.size < 2 -> false
        s.kind?.twoPoint == true -> distance(s.points[0], s.points[1]) >= MIN_SIZE
        else -> s.bounds().let { kotlin.math.hypot(it.width, it.height) >= MIN_SIZE }
    }

    private fun distance(a: Point, b: Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    companion object {
        private const val MIN_SIZE = 4f
        private const val MIN_PATH_STEP = 2f
        private const val PATH_EPSILON = 1.5f
        private const val DUPLICATE_OFFSET = 30f

        /** Fill colour offered when the fill tool is chosen with "no fill" selected (amber). */
        const val DEFAULT_FILL = 0xFFFFC107.toInt()
    }
}
