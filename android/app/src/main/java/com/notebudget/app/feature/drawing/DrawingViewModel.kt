package com.notebudget.app.feature.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebudget.app.data.drawing.DrawingStore
import com.notebudget.app.data.drawing.VectorStore
import com.notebudget.app.domain.usecase.NoteUseCases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DrawingMode { RASTER, VECTOR }

/**
 * The two drawing kinds (D98). A [DRAWING] is one raster sheet you paint on (a vector layer made in an older version is
 * still shown, read-only); a [BLUEPRINT] is vector shapes on layers, with no raster sheet at all.
 */
enum class DrawingKind { DRAWING, BLUEPRINT }

/**
 * State of one drawing session. A drawing has two layers:
 *  - the RASTER layer, a bitmap sheet you paint on (pencil, brush, eraser) or flood-fill;
 *  - the VECTOR layer on top of it, editable shapes (see [VectorEditor]).
 * [mode] says which layer the tools act on. Each layer has its own undo. Both are saved (PNG and
 * JSON) after a short pause, when the screen stops, and when the ViewModel goes away, so leaving
 * the screen never loses work.
 */
class DrawingViewModel(
    private val noteId: String,
    private val store: DrawingStore,
    private val vectorStore: VectorStore,
    private val noteUseCases: NoteUseCases,
    /** Outlives this ViewModel: the last save runs after the screen is already gone. */
    private val appScope: CoroutineScope,
    val kind: DrawingKind = DrawingKind.DRAWING,
    /** How many steps back can be undone (Settings, D197); each layer keeps that many. */
    undoSteps: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(com.notebudget.app.core.common.UndoHistory.DEFAULT_STEPS),
) : ViewModel() {

    // ---- raster layer ----

    /** Stored picture plus operations too old to undo. */
    private var base: Bitmap = DrawingRenderer.newSheet()

    /** [base] plus the undoable operations: everything except the stroke being drawn. */
    private var committed: Bitmap = DrawingRenderer.newSheet()

    /** What is shown of the raster layer: [committed] plus the stroke being drawn. */
    var working: Bitmap = DrawingRenderer.newSheet()
        private set

    private val history = RasterHistory()
    private var current: MutableList<Stroke.Point>? = null

    // ---- vector layer ----

    val vector = VectorEditor()

    // ---- UI state ----

    /**
     * Bumped on EVERY visible change, finger moves included: only the canvas reads it. The toolbar
     * reads [uiRevision] instead, which changes only on discrete events (touch down / up, tool,
     * undo, selection); recomposing the whole toolbar on every move made dragging laggy.
     */
    val canvasRevision = mutableIntStateOf(0)
    val uiRevision = mutableIntStateOf(0)
    val loaded = mutableStateOf(false)
    val mode = mutableStateOf(if (kind == DrawingKind.BLUEPRINT) DrawingMode.VECTOR else DrawingMode.RASTER)
    val showRaster = mutableStateOf(kind == DrawingKind.DRAWING)
    val showVector = mutableStateOf(true)

    val tool = mutableStateOf(DrawingTool.PENCIL)
    val colorArgb = mutableIntStateOf(0xFF000000.toInt())

    /** Raster line width in sheet pixels for the pencil; the brush and eraser scale from it. */
    val widthPx = mutableStateOf(6f)

    // ---- D212: brush settings, shapes, text, eyedropper ----

    /** The brush: see-through 10..255, soft edge 0..1, and a flat nib at an angle in degrees (`null` = round). */
    val brushOpacity = mutableIntStateOf(204)
    val brushSoftness = mutableStateOf(0f)
    val brushNib = mutableStateOf<Float?>(null)

    /** Which primitive the SHAPE tool draws (one of [RASTER_SHAPES]) and whether it is filled with the same colour. */
    val rasterShape = mutableStateOf(VectorTool.RECT)

    /** The primitive the "Shapes" tool of a blueprint draws (D213). */
    val vectorShape = mutableStateOf(VectorTool.RECT)
    val shapeFilled = mutableStateOf(false)

    /** Where a text is to go (the screen shows the text dialog while this is set), in sheet pixels. */
    val textAt = mutableStateOf<Point?>(null)

    /** The shape being dragged out with the SHAPE tool, drawn over the sheet until the finger lifts. */
    private var shapeDraft: Shape? = null

    /** The tool before the eyedropper, taken back after one pick. */
    private var toolBeforePick = DrawingTool.PENCIL
    private var vectorToolBeforePick = VectorTool.SELECT

    /** Chooses a raster tool; the eyedropper remembers what to go back to. */
    fun chooseTool(next: DrawingTool) {
        if (next == DrawingTool.EYEDROPPER && tool.value != DrawingTool.EYEDROPPER) toolBeforePick = tool.value
        tool.value = next
        uiRevision.intValue++
    }

    val canUndo = mutableStateOf(false)
    val canRedo = mutableStateOf(false)

    /** What the Undo / Redo buttons of the note's top bar act on (D197): the layer the tools are on. */
    val undoTarget = object : com.notebudget.app.feature.notes.UndoTarget {
        override val canUndo: Boolean get() = this@DrawingViewModel.canUndo.value
        override val canRedo: Boolean get() = this@DrawingViewModel.canRedo.value
        override fun undo() = this@DrawingViewModel.undo()
        override fun redo() = this@DrawingViewModel.redo()
    }

    // What the vector toolbar and the layers panel show, as COMPOSE STATE of their own (D98 fix): they read these
    // directly, so a change recomposes exactly the chips that show it. Before, they read plain fields of the editor and
    // depended on the parent passing a changing number down, which on a phone left the chips one step behind.
    val vectorTool = mutableStateOf(VectorTool.SELECT)
    val selectedShape = mutableStateOf<Shape?>(null)
    val strokeColorState = mutableIntStateOf(0xFF000000.toInt())
    val fillColorState = mutableStateOf<Int?>(null)
    val strokeWidthState = mutableStateOf(6f)
    val layersState = mutableStateOf(vector.layers)
    val activeLayerState = mutableStateOf(vector.activeLayerId)
    val canDrawOnActiveLayerState = mutableStateOf(true)

    /** Copies what the editor holds into the states above. Called after every discrete change, not on finger moves. */
    private fun syncVector() {
        vectorTool.value = vector.tool
        selectedShape.value = vector.selected
        strokeColorState.intValue = vector.strokeColor
        fillColorState.value = vector.fillColor
        strokeWidthState.value = vector.strokeWidth
        layersState.value = vector.layers
        activeLayerState.value = vector.activeLayerId
        canDrawOnActiveLayerState.value = vector.canDrawOnActiveLayer
    }

    /** Zoom and pan of the sheet (D118); not saved, a note opens at 100 %. */
    val view = mutableStateOf(SheetView.NORMAL)

    /** The size of this note's sheet in pixels (D260) and the unit it was typed in. */
    val sheetSize = mutableStateOf(androidx.compose.ui.unit.IntSize(DrawingRenderer.WIDTH, DrawingRenderer.HEIGHT))
    val sheetUnit = mutableStateOf(SheetUnit.PX)

    /** The colour and see-through of this note's sheet (D265); it lies under both layers. */
    val sheetBackground = mutableStateOf(SheetBackground.DEFAULT)

    /** Width / height of the sheet. */
    val sheetRatio: Float get() = sheetSize.value.width.toFloat() / sheetSize.value.height

    /** The size in pixels of the box the sheet is shown in at 1x. */
    val viewport = mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1))

    /** Where the eraser cursor is drawn, in sheet pixels: the last touch; `null` before the first. */
    val cursor = mutableStateOf<Point?>(null)

    /** Diameter of the eraser in sheet pixels: what one stroke of the eraser removes. */
    val eraserDiameter: Float get() = widthPx.value * ERASER_SCALE

    private var rasterDirty = false
    private var vectorDirty = false
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            undoSteps.collect { steps ->
                vector.undoLimit = steps
                val baked = history.setLimit(steps)
                baked.forEach { DrawingRenderer.apply(base, it) }
                refresh()
            }
        }
        viewModelScope.launch {
            // A save of this note that is still being written (the note was left a moment ago) comes first (D297): reading the
            // files before it landed showed the note without its last step, and the next save then wrote that older picture.
            pendingSaves[noteId]?.join()
            // The vector document first: it holds the size of the sheet (D260).
            val document = vectorStore.read(noteId)?.let { VectorDocument.decode(it) }
            val (w, h) = document?.sheet() ?: (DrawingRenderer.WIDTH to DrawingRenderer.HEIGHT)
            sheetSize.value = androidx.compose.ui.unit.IntSize(w, h)
            sheetUnit.value = SheetUnit.of(document?.sheetUnit)
            sheetBackground.value = document?.background() ?: SheetBackground.DEFAULT
            val bytes = if (kind == DrawingKind.DRAWING) store.read(noteId) else null
            val sheet = if (bytes != null) withContext(Dispatchers.Default) { DrawingRenderer.decodeToSheet(bytes, w, h) } else null
            if (sheet != null) {
                base = sheet
                committed = sheet.copy(Bitmap.Config.ARGB_8888, true)
                working = sheet.copy(Bitmap.Config.ARGB_8888, true)
            } else if (w != DrawingRenderer.WIDTH || h != DrawingRenderer.HEIGHT) {
                base = DrawingRenderer.newSheet(w, h)
                committed = DrawingRenderer.newSheet(w, h)
                working = DrawingRenderer.newSheet(w, h)
            }
            document?.let { vector.load(it.shapes, it.layers) }
            loaded.value = true
            refresh()
        }
    }

    private fun refresh() {
        canUndo.value = if (mode.value == DrawingMode.RASTER) history.canUndo else vector.canUndo
        canRedo.value = if (mode.value == DrawingMode.RASTER) history.canRedo else vector.canRedo
        syncVector()
        canvasRevision.intValue++
        uiRevision.intValue++
    }

    fun setMode(next: DrawingMode) {
        if (mode.value == next) return
        finishGesture()
        mode.value = next
        // Working on a hidden layer would be confusing: show it.
        if (next == DrawingMode.RASTER) showRaster.value = true else showVector.value = true
        refresh()
    }

    // ---- pointer events (sheet pixels) ----

    /** [slack]: how far from a shape a touch still counts as on it, in sheet pixels (from the screen density). */
    fun pointerDown(x: Float, y: Float, slack: Float = HIT_TOLERANCE) {
        if (!loaded.value) return
        if (mode.value == DrawingMode.RASTER) {
            rasterDown(x, y)
        } else if (vector.tool == VectorTool.EYEDROPPER) {
            vector.colorAt(Point(x, y), slack)?.let { color -> vector.setStrokeColor(color) }
            vector.tool = vectorToolBeforePick
            refresh()
        } else if (vector.tool == VectorTool.TEXT) {
            if (vector.canDrawOnActiveLayer) textAt.value = Point(x, y)
            refresh()
        } else {
            val before = vector.documentVersion
            vector.pointerDown(Point(x, y), slack)
            // The fill tool paints on the touch DOWN, not on the lift: without this the fill was never marked as something to
            // save, and was lost (D162).
            if (vector.documentVersion != before) markVectorDirty()
            refresh()
        }
    }

    fun pointerMove(x: Float, y: Float) {
        if (mode.value == DrawingMode.RASTER) {
            rasterMove(x, y)
        } else {
            vector.pointerMove(Point(x, y))
            canvasRevision.intValue++
        }
    }

    fun pointerUp() {
        if (mode.value == DrawingMode.RASTER) {
            rasterUp()
        } else {
            if (vector.pointerUp()) markVectorDirty()
            refresh()
        }
    }

    /** A gesture that was interrupted (e.g. a second finger): a shape being drawn is dropped, what was moved is kept. */
    fun pointerCancel() {
        if (mode.value == DrawingMode.RASTER) {
            rasterUp()
        } else {
            val before = vector.documentVersion
            vector.pointerCancel()
            if (vector.documentVersion != before) markVectorDirty()
            refresh()
        }
    }

    /**
     * A second finger came down (a pinch or a two-finger move): the stroke or shape of the first finger was not meant, so
     * it is dropped, not kept (D118).
     */
    fun abortGesture() {
        if (mode.value == DrawingMode.RASTER) {
            if (current != null || shapeDraft != null) {
                current = null
                shapeDraft = null
                showCommitted()
                canvasRevision.intValue++
            }
        } else {
            vector.pointerCancelDroppingDraft()
            refresh()
        }
    }

    private fun finishGesture() {
        rasterUp()
        if (vector.pointerUp()) markVectorDirty()
    }

    // ---- raster ----

    private fun effectiveWidth(): Float = when (tool.value) {
        DrawingTool.BRUSH -> widthPx.value * 3f
        DrawingTool.ERASER -> eraserDiameter
        else -> widthPx.value
    }

    private fun rasterDown(x: Float, y: Float) {
        when (tool.value) {
            DrawingTool.FILL -> {
                addRasterOp(Fill(x.toInt(), y.toInt(), colorArgb.intValue))
                return
            }
            DrawingTool.EYEDROPPER -> {
                colorArgb.intValue = pickColor(x, y)
                tool.value = toolBeforePick
                refresh()
                return
            }
            DrawingTool.TEXT -> {
                textAt.value = Point(x, y)
                return
            }
            DrawingTool.SHAPE -> {
                val kind = rasterShape.value.shape ?: ShapeType.RECT
                shapeDraft = Shape(
                    id = vector.newShapeId(), type = kind.name, points = listOf(Point(x, y), Point(x, y)),
                    strokeColor = colorArgb.intValue, strokeWidth = widthPx.value,
                    fillColor = if (shapeFilled.value && kind.closed) colorArgb.intValue else null,
                )
                return
            }
            else -> Unit
        }
        current = mutableListOf(Stroke.Point(x, y))
        if (tool.value == DrawingTool.ERASER) cursor.value = Point(x, y)
        redrawWithCurrent()
    }

    private fun rasterMove(x: Float, y: Float) {
        shapeDraft?.let { draft ->
            val corner = if (rasterShape.value.square) squareCorner(draft.points[0], Point(x, y)) else Point(x, y)
            shapeDraft = draft.withPoint(1, corner)
            working.eraseColor(0)
            val canvas = Canvas(working)
            canvas.drawBitmap(committed, 0f, 0f, null)
            shapeDraft?.let { DrawingRenderer.drawShape(canvas, it) }
            canvasRevision.intValue++
            return
        }
        current?.add(Stroke.Point(x, y)) ?: return
        if (tool.value == DrawingTool.ERASER) cursor.value = Point(x, y)
        redrawWithCurrent()
    }

    private fun rasterUp() {
        shapeDraft?.let { draft ->
            shapeDraft = null
            val b = draft.bounds()
            // A tap is not a shape.
            if (b.width + b.height >= 6f) addRasterOp(ShapeStamp(draft)) else { showCommitted(); canvasRevision.intValue++ }
            return
        }
        val points = current ?: return
        current = null
        addRasterOp(newStroke(points.toList()))
    }

    private fun newStroke(points: List<Stroke.Point>) = if (tool.value == DrawingTool.BRUSH) {
        Stroke(tool.value, colorArgb.intValue, effectiveWidth(), points, brushOpacity.intValue, brushSoftness.value, brushNib.value)
    } else {
        Stroke(tool.value, colorArgb.intValue, effectiveWidth(), points)
    }

    /**
     * The colour of the sheet at ([x], [y]) for the eyedropper, as it is seen: the pixel on the sheet's background (D265), and
     * what is still see-through on white.
     */
    private fun pickColor(x: Float, y: Float): Int {
        val px = x.toInt().coerceIn(0, committed.width - 1)
        val py = y.toInt().coerceIn(0, committed.height - 1)
        val onSheet = SheetBackground.over(committed.getPixel(px, py), sheetBackground.value.argb)
        return SheetBackground.over(onSheet, SheetBackground.WHITE)
    }

    /**
     * Puts a text where [textAt] says (D212): painted into the sheet of a drawing, or a text shape of a blueprint that can be
     * moved and changed later. [style] is a mask of the `Shape.TEXT_*` flags.
     */
    fun placeText(text: String, size: Float, style: Int) {
        val at = textAt.value ?: return
        textAt.value = null
        if (text.isBlank()) return
        val (w, h) = DrawingRenderer.measureText(text, size, style)
        val color = if (mode.value == DrawingMode.RASTER) colorArgb.intValue else vector.strokeColor
        val shape = Shape(
            id = vector.newShapeId(), type = ShapeType.TEXT.name, points = listOf(at, Point(at.x + w, at.y + h)),
            strokeColor = color, text = text, textSize = size, textStyle = style,
        )
        if (mode.value == DrawingMode.RASTER) {
            addRasterOp(ShapeStamp(shape))
        } else {
            vector.add(shape)
            markVectorDirty()
            refresh()
        }
    }

    fun cancelText() {
        textAt.value = null
    }

    /** Changes the words, size or style of the selected text of a blueprint (one undo step). */
    fun editSelectedText(text: String, size: Float, style: Int) {
        vectorAction {
            changeSelected { s ->
                if (s.kind != ShapeType.TEXT || text.isBlank()) s else {
                    val (w, h) = DrawingRenderer.measureText(text, size, style)
                    val p0 = s.points.first()
                    s.copy(text = text, textSize = size, textStyle = style, points = listOf(p0, Point(p0.x + w, p0.y + h)))
                }
            }
        }
    }

    /** Chooses a vector tool; the eyedropper remembers what to go back to. */
    fun chooseVectorTool(next: VectorTool) {
        if (next == VectorTool.EYEDROPPER && vector.tool != VectorTool.EYEDROPPER) vectorToolBeforePick = vector.tool
        setVectorTool(next)
    }

    /** Applies [op] to the committed picture and remembers it for undo. */
    private fun addRasterOp(op: RasterOp) {
        history.add(op)?.let { old -> DrawingRenderer.apply(base, old) }
        DrawingRenderer.apply(committed, op)
        showCommitted()
        rasterDirty = true
        markDirty()
        refresh()
    }

    fun undo() {
        if (mode.value == DrawingMode.RASTER) {
            if (history.undo()) {
                rebuildCommitted()
                rasterDirty = true
                markDirty()
            }
        } else if (vector.canUndo) {
            vector.undo()
            markVectorDirty()
        }
        refresh()
    }

    /** Brings back what the last undo of the current layer took away (D197). */
    fun redo() {
        if (mode.value == DrawingMode.RASTER) {
            val step = history.redo()
            if (step != null) {
                step.baked?.let { DrawingRenderer.apply(base, it) }
                DrawingRenderer.apply(committed, step.op)
                showCommitted()
                rasterDirty = true
                markDirty()
            }
        } else if (vector.canRedo) {
            vector.redo()
            markVectorDirty()
        }
        refresh()
    }

    /**
     * Gives the sheet a new size (D260), the picture kept at the top left: a bigger sheet adds empty space, a smaller one cuts
     * the raster layer (vector shapes keep their places and show again if the sheet grows back). The undo steps of the
     * raster layer are merged into the picture: they cannot be taken back across a new size. Returns false for a size out of
     * the limits.
     */
    fun resizeSheet(width: Int, height: Int, unit: SheetUnit): Boolean {
        if (SheetSize.problem(width, height) != null) return false
        abortGesture()
        val old = sheetSize.value
        sheetUnit.value = unit
        if (old.width != width || old.height != height) {
            base = DrawingRenderer.resized(committed, width, height)
            history.clear()
            committed = base.copy(Bitmap.Config.ARGB_8888, true)
            working = base.copy(Bitmap.Config.ARGB_8888, true)
            sheetSize.value = androidx.compose.ui.unit.IntSize(width, height)
            view.value = SheetView.NORMAL
            rasterDirty = kind == DrawingKind.DRAWING
        }
        markVectorDirty()
        uiRevision.intValue++
        refresh()
        return true
    }

    /** A new colour or see-through of the sheet (D265); saved with the vector document, not an undo step. */
    fun setSheetBackground(background: SheetBackground) {
        if (background == sheetBackground.value) return
        sheetBackground.value = background
        markVectorDirty()
        uiRevision.intValue++
    }

    /** Rebuilds [committed] from [base] and the undoable operations; only needed after an undo. */
    private fun rebuildCommitted() {
        committed.eraseColor(0)
        Canvas(committed).drawBitmap(base, 0f, 0f, null)
        for (op in history.ops) DrawingRenderer.apply(committed, op)
        showCommitted()
    }

    private fun showCommitted() {
        working.eraseColor(0)
        Canvas(working).drawBitmap(committed, 0f, 0f, null)
    }

    /** While drawing: the cached [committed] picture with only the current stroke on top. */
    private fun redrawWithCurrent() {
        val points = current ?: return
        working.eraseColor(0)
        val canvas = Canvas(working)
        canvas.drawBitmap(committed, 0f, 0f, null)
        DrawingRenderer.drawStroke(canvas, newStroke(points.toList()))
        canvasRevision.intValue++
    }

    // ---- vector actions (thin wrappers that also keep the UI and saving in step) ----

    fun setVectorTool(tool: VectorTool) {
        finishGesture()
        vector.tool = tool
        refresh()
    }

    fun vectorAction(action: VectorEditor.() -> Unit) {
        val before = vector.documentVersion
        vector.action()
        if (vector.documentVersion != before) markVectorDirty()
        refresh()
    }

    // ---- saving ----

    /** A change of the blueprint layer is small to write (a text file): saved at once, so even a single step is on the disk (D297). */
    private fun markVectorDirty() {
        vectorDirty = true
        saveJob?.cancel()
        saveNow()
    }

    /** A change of the picture: saved a moment after the last stroke (encoding a PNG after every stroke of a quick hand costs). */
    private fun markDirty() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_MILLIS)
            saveNow()
        }
    }

    /** Saves what changed. Safe to call any time (screen stop, back). */
    fun saveNow() {
        persist()
    }

    /** One write at a time, in the order the pictures were taken: an older one never lands after a newer one. */
    private val writes = kotlinx.coroutines.sync.Mutex()

    /**
     * Takes the picture as it is now and writes it on [appScope] (D294). The write used to run on viewModelScope: Back stops the
     * screen (a save starts, the dirty marks are cleared), then the ViewModel is cleared and viewModelScope cancelled while the PNG
     * was still being encoded, so nothing was written and the last save on disk was the autosave of the step before.
     */
    private fun persist() {
        if (!rasterDirty && !vectorDirty) return
        val png = if (rasterDirty) working.copy(Bitmap.Config.ARGB_8888, false) else null
        val document = if (vectorDirty) {
            VectorDocument(
                shapes = vector.shapes, layers = vector.layers,
                sheetWidth = sheetSize.value.width, sheetHeight = sheetSize.value.height, sheetUnit = sheetUnit.value.key,
                sheetColor = sheetBackground.value.color, sheetTransparent = sheetBackground.value.transparent,
                sheetTransparency = sheetBackground.value.transparency,
            )
        } else {
            null
        }
        rasterDirty = false
        vectorDirty = false
        val job = appScope.launch {
            writes.withLock {
                if (png != null) store.write(noteId, withContext(Dispatchers.Default) { DrawingRenderer.toPng(png) })
                if (document != null) vectorStore.write(noteId, document.encode())
                noteUseCases.touch(noteId)
            }
        }
        // The next opening of this note waits for it (see the loading in init).
        pendingSaves[noteId] = job
        job.invokeOnCompletion { pendingSaves.remove(noteId, job) }
    }

    override fun onCleared() {
        saveJob?.cancel()
        // Whatever is not saved yet goes now; a save already started finishes on the app scope.
        persist()
    }

    companion object {
        const val AUTOSAVE_MILLIS = 500L

        /** The last save of each note that is still being written, across the screens that come and go (D297). */
        private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, Job>()

        /** The eraser is this many times wider than the pencil width chosen on the slider. */
        const val ERASER_SCALE = 4f

        /** Finger slack when picking a shape, in sheet pixels (about 2 mm on a phone). */
        const val HIT_TOLERANCE = 24f

        /** The primitives the SHAPE tool of a drawing offers (D212), in the order of its list. */
        val RASTER_SHAPES = listOf(
            VectorTool.RECT, VectorTool.SQUARE, VectorTool.ELLIPSE, VectorTool.CIRCLE, VectorTool.TRIANGLE, VectorTool.RHOMBUS,
            VectorTool.PENTAGON, VectorTool.HEXAGON, VectorTool.STAR, VectorTool.LINE, VectorTool.ARROW, VectorTool.DOUBLE_ARROW,
        )
    }
}
