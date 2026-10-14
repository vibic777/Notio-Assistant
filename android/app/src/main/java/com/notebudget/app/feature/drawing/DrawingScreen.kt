package com.notebudget.app.feature.drawing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import android.graphics.Paint as NativePaint
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notebudget.app.R
import com.notebudget.app.core.ui.appViewModel
import com.notebudget.app.feature.colors.ColorRow
import com.notebudget.app.feature.notes.ViewEditTabs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The drawing editors (§6.9, D98). A DRAWING is a raster sheet (pencil, brush, eraser, bucket fill); a BLUEPRINT is vector
 * shapes on layers (line, arrow, rectangle, ellipse, triangle, pen; select / move / reshape, fill, colours, order, undo,
 * layers). Both open in the read-only View first, both zoom (buttons and two fingers). The sheet is a fixed-size
 * raster; touches are mapped from screen to sheet coordinates through the zoom.
 */
@Composable
fun DrawingScreen(noteId: String, kind: DrawingKind = DrawingKind.DRAWING, modifier: Modifier = Modifier) {
    val vm: DrawingViewModel = appViewModel {
        DrawingViewModel(noteId, it.drawingStore, it.vectorStore, it.noteUseCases, it.appScope, kind, it.settings.undoSteps)
    }
    // Undo / Redo are the arrows of the note's top bar, for the layer the tools are on (D197).
    com.notebudget.app.feature.notes.ProvideUndoTarget(vm.undoTarget)
    // Texts on the sheet use the app's own font, so bold and italic show on every phone (D209, D212).
    val appContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { DrawingRenderer.useFonts(appContext) }
    val textAt by vm.textAt
    if (textAt != null) {
        TextStampDialog(initial = null, onConfirm = { t, size, style -> vm.placeText(t, size, style) }, onDismiss = vm::cancelText)
    }

    // Save when the screen goes to the background, not only on a pause between strokes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.saveNow() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var editing by rememberSaveable { mutableStateOf(false) }
    var showLayers by rememberSaveable { mutableStateOf(false) }
    // The tool panel lies over the sheet at the bottom or on the right (an icon on it switches, remembered) and folds to one
    // round icon (D241, D261).
    var toolsFolded by rememberSaveable { mutableStateOf(false) }
    var sizing by rememberSaveable { mutableStateOf(false) }
    if (sizing) {
        SheetSizeDialog(
            width = vm.sheetSize.value.width,
            height = vm.sheetSize.value.height,
            unit = vm.sheetUnit.value,
            onConfirm = { w, h, unit -> if (vm.resizeSheet(w, h, unit)) sizing = false },
            onDismiss = { sizing = false },
        )
    }
    var backgrounding by rememberSaveable { mutableStateOf(false) }
    if (backgrounding) {
        SheetBackgroundDialog(
            initial = vm.sheetBackground.value,
            onConfirm = { vm.setSheetBackground(it); backgrounding = false },
            onDismiss = { backgrounding = false },
        )
    }
    val container = com.notebudget.app.core.ui.rememberAppContainer()
    val toolsSide by container.settings.drawingToolsSide.collectAsState(initial = ToolPanelSide.BOTTOM)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // What the panel covers of the area, in pixels; 0 when folded or in View.
    var coveredBottom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var coveredRight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var coveredTop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var coveredLeft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var area by androidx.compose.runtime.remember { mutableStateOf(IntSize(0, 0)) }
    val ratio = vm.sheetRatio
    val frame = androidx.compose.runtime.remember(area, ratio, coveredBottom, coveredRight, coveredTop, coveredLeft) {
        if (area.width <= 0 || area.height <= 0) null
        else SheetFrame.fit(area.width.toFloat(), area.height.toFloat(), ratio).copy(
            coveredBottom = coveredBottom.toFloat(), coveredRight = coveredRight.toFloat(),
            coveredTop = coveredTop.toFloat(), coveredLeft = coveredLeft.toFloat(),
        )
    }
    val currentFrame = androidx.compose.runtime.rememberUpdatedState(frame)

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Always the read-only View first (D108); the tools appear in Edit.
        val view by vm.view
        // What is shown: the view kept inside the limits of the frame as it is now (the panel may have just opened).
        val shown = frame?.let { view.within(it) } ?: view
        val sheetZoom = com.notebudget.app.feature.notes.ZoomControl(
            percent = shown.percent,
            canZoomOut = shown.scale > SheetView.MIN,
            canZoomIn = shown.scale < SheetView.MAX,
            onMinus = { frame?.let { vm.view.value = shown.step(false, it) } },
            // The percentage = "Fit": the whole sheet as large as the free part of the area allows.
            onNormal = { frame?.let { vm.view.value = SheetView.fit(it) } },
            onPlus = { frame?.let { vm.view.value = shown.step(true, it) } },
            density = null,
        )
        // Copy of the picture (D362), in View: the PNG of the note goes to the clipboard.
        val copyScope = androidx.compose.runtime.rememberCoroutineScope()
        ViewEditTabs(
            editing, { editing = it; if (!it) vm.saveNow() }, Modifier.padding(0.dp), zoom = sheetZoom,
            extra = if (editing || com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true) null else ({
                com.notebudget.app.core.ui.CopyIconButton {
                    copyScope.launch {
                        if (com.notebudget.app.feature.notes.NoteShare.copyById(appContext, container, noteId)) com.notebudget.app.core.ui.sayCopied(appContext)
                    }
                }
            }),
        )
        // The toolbar changes on discrete events only (touch down / up, tool, undo), not on every finger move.
        // The revision is passed as a PARAMETER on purpose: with strong skipping (Kotlin 2 Compose) a composable whose
        // parameters are unchanged is skipped, and the vector toolbar keeps its state in plain fields, so it would
        // never redraw: the chips looked stuck and nothing seemed selectable.
        val revision = vm.uiRevision.intValue
        val tools: @Composable () -> Unit = {
            if (kind == DrawingKind.DRAWING) {
                RasterToolbar(vm, revision)
            } else {
                if (showLayers) LayersPanel(vm, revision)
                VectorToolbar(vm, revision, showLayers) { showLayers = !showLayers }
            }
        }
        // The sheet lies in the whole area of the note and is clipped to it (D241, D261): the tabs and the zoom buttons are
        // above the area and the tool panel is drawn after the sheet, on its own background, so however the sheet is zoomed
        // or moved it never covers any of them.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { area = it },
        ) {
            val panelOpen = editing && !toolsFolded
            val panelWidth = minOf(340.dp, maxWidth * 0.6f)
            val panelHeight = maxHeight * 0.5f
            val side = toolsSide
            // Only the side the panel is on is covered; the others are free (D281).
            fun cover(bottom: Int = 0, right: Int = 0, top: Int = 0, left: Int = 0) {
                coveredBottom = bottom; coveredRight = right; coveredTop = top; coveredLeft = left
            }
            Box(
                Modifier
                    .fillMaxSize()
                    // Two or more fingers: zoom and pan the sheet. Looked at BEFORE the drawing handler (Initial pass) and
                    // consumed, so the first finger's stroke is dropped and never a stray dot.
                    .pointerInput(vm) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var zooming = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.size >= 2) {
                                    if (!zooming) { zooming = true; vm.abortGesture() }
                                    val factor = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val f = currentFrame.value
                                    if (f != null && (factor != 1f || pan != Offset.Zero)) {
                                        vm.view.value = vm.view.value.within(f).zoomAround(factor, centroid.x, centroid.y, f, pan.x, pan.y)
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                // The drawing hides its content (D363): the cover instead of the sheet, until the eye is pressed.
                if (!editing && com.notebudget.app.core.ui.LocalNoteHide.current?.masked == true) com.notebudget.app.core.ui.HiddenContent(Modifier.fillMaxSize())
                else if (frame != null) Sheet(vm, interactive = editing, frame = frame, shown = shown)
            }
            if (editing && toolsFolded) {
                androidx.compose.runtime.SideEffect { cover() }
                com.notebudget.app.core.ui.tip.FilledTonalIconButton(
                    onClick = { toolsFolded = false },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    com.notebudget.app.core.ui.tip.Icon(
                        androidx.compose.material.icons.Icons.Filled.Create,
                        contentDescription = stringResource(R.string.drawing_tools_show),
                    )
                }
            } else if (panelOpen) {
                val header: @Composable () -> Unit = {
                    ToolPanelHeader(
                        side = side,
                        onSheetSize = { sizing = true },
                        onSheetBackground = { backgrounding = true },
                        onFold = { toolsFolded = true },
                        onSide = { scope.launch { container.settings.setDrawingToolsSide(it) } },
                    )
                }
                if (side.vertical) {
                    androidx.compose.material3.Surface(
                        Modifier
                            .align(if (side == ToolPanelSide.RIGHT) Alignment.TopEnd else Alignment.TopStart)
                            .width(panelWidth)
                            .fillMaxHeight()
                            .onSizeChanged { if (side == ToolPanelSide.RIGHT) cover(right = it.width) else cover(left = it.width) },
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp,
                    ) {
                        Column(Modifier.padding(6.dp)) {
                            header()
                            Column(Modifier.verticalScroll(rememberScrollState())) { tools() }
                        }
                    }
                } else {
                    androidx.compose.material3.Surface(
                        Modifier
                            .align(if (side == ToolPanelSide.TOP) Alignment.TopCenter else Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { if (side == ToolPanelSide.TOP) cover(top = it.height) else cover(bottom = it.height) },
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp,
                    ) {
                        Column(Modifier.padding(6.dp)) {
                            header()
                            Column(Modifier.heightIn(max = panelHeight).verticalScroll(rememberScrollState())) { tools() }
                        }
                    }
                }
            } else {
                androidx.compose.runtime.SideEffect { cover() }
            }
        }
        // "Created" / "changed" under the sheet in View, when Settings shows them for this kind (D198).
        if (!editing) com.notebudget.app.core.ui.NoteFooter()
    }
}

/** The row on top of the tool panel: the sheet's background and size, the side of the panel (a menu of four, D281), fold it. */
@Composable
private fun ToolPanelHeader(side: ToolPanelSide, onSheetSize: () -> Unit, onSheetBackground: () -> Unit, onFold: () -> Unit, onSide: (ToolPanelSide) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        // The colour and see-through of the sheet (D265).
        com.notebudget.app.core.ui.tip.IconButton(onClick = onSheetBackground, modifier = Modifier.size(36.dp)) {
            com.notebudget.app.core.ui.tip.Icon(SheetBackgroundIcon, contentDescription = stringResource(R.string.drawing_bg))
        }
        // The size of the sheet (D260).
        com.notebudget.app.core.ui.tip.IconButton(onClick = onSheetSize, modifier = Modifier.size(36.dp)) {
            com.notebudget.app.core.ui.tip.Icon(SheetSizeIcon, contentDescription = stringResource(R.string.drawing_sheet_size))
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            com.notebudget.app.core.ui.tip.IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                com.notebudget.app.core.ui.tip.Icon(dockIcon(side), contentDescription = stringResource(R.string.drawing_tools_side))
            }
            com.notebudget.app.core.ui.tip.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ToolPanelSide.entries.forEach { s ->
                    com.notebudget.app.core.ui.tip.DropdownMenuItem(
                        text = { Text(stringResource(sideLabel(s))) },
                        leadingIcon = { com.notebudget.app.core.ui.tip.Icon(dockIcon(s), contentDescription = null) },
                        trailingIcon = if (s == side) ({ com.notebudget.app.core.ui.tip.Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null) }) else null,
                        onClick = { menu = false; if (s != side) onSide(s) },
                    )
                }
            }
        }
        com.notebudget.app.core.ui.tip.IconButton(onClick = onFold, modifier = Modifier.size(36.dp)) {
            com.notebudget.app.core.ui.tip.Icon(
                when (side) {
                    ToolPanelSide.RIGHT -> androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight
                    ToolPanelSide.LEFT -> androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    ToolPanelSide.TOP -> androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp
                    ToolPanelSide.BOTTOM -> androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown
                },
                contentDescription = stringResource(R.string.drawing_tools_hide),
            )
        }
    }
}

private fun sideLabel(side: ToolPanelSide): Int = when (side) {
    ToolPanelSide.BOTTOM -> R.string.drawing_tools_bottom
    ToolPanelSide.RIGHT -> R.string.drawing_tools_right
    ToolPanelSide.TOP -> R.string.drawing_tools_top
    ToolPanelSide.LEFT -> R.string.drawing_tools_left
}

/** A frame with the band of one side filled: "the panel there". */
private fun dockIcon(side: ToolPanelSide): androidx.compose.ui.graphics.vector.ImageVector =
    androidx.compose.ui.graphics.vector.ImageVector.Builder("Dock" + side.name, 24.dp, 24.dp, 24f, 24f).apply {
        // The outline of the screen.
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString("M4,4h16v16h-16z M6,6v12h12v-12z").toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
        )
        // The panel.
        addPath(
            androidx.compose.ui.graphics.vector.PathParser()
                .parsePathString(
                    when (side) {
                        ToolPanelSide.RIGHT -> "M13,6h5v12h-5z"
                        ToolPanelSide.LEFT -> "M6,6h5v12h-5z"
                        ToolPanelSide.TOP -> "M6,6h12v5h-12z"
                        ToolPanelSide.BOTTOM -> "M6,13h12v5h-12z"
                    },
                ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
        )
    }.build()

/** The sheet at its place in the area: its box is the sheet at 1x, zoomed and moved as a whole by [shown] (D261). */
@Composable
private fun Sheet(vm: DrawingViewModel, interactive: Boolean, frame: SheetFrame, shown: SheetView) {
    val sheetDescription = stringResource(R.string.drawing_sheet)
    val handlePaint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
    val currentView by rememberUpdatedState(shown)
    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    val sheetBackground by vm.sheetBackground
    Box(
        Modifier
            .size(with(localDensity) { frame.sheetWidth.toDp() }, with(localDensity) { frame.sheetHeight.toDp() })
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = shown.scale
                scaleY = shown.scale
                translationX = shown.offsetX
                translationY = shown.offsetY
            }
            .clip(MaterialTheme.shapes.small)
            // The sheet's own background (D265), a checkerboard where it is see-through.
            .drawBehind { drawSheetBackground(sheetBackground) }
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .onSizeChanged { vm.viewport.value = it },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = sheetDescription }
                .then(if (!interactive) Modifier else Modifier.pointerInput(vm) {
                    // One handler for tap and drag: a tap is a gesture that never moves. The sheet has a
                    // fixed logical size, so screen pixels are scaled to it. This block sits INSIDE the zoom, so
                    // the positions it gets are already those of the unzoomed sheet.
                    fun toSheet(p: Offset) = Offset(
                        p.x * vm.sheetSize.value.width / size.width,
                        p.y * vm.sheetSize.value.height / size.height,
                    )
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // How far from a shape a touch still counts as on it: a fingertip, not a pixel (and less when zoomed in).
                        val slack = HIT_SLACK_DP.dp.toPx() * vm.sheetSize.value.width / size.width / currentView.scale
                        toSheet(down.position).let { vm.pointerDown(it.x, it.y, slack) }
                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            when {
                                change == null -> { vm.pointerCancel(); finished = true }
                                event.changes.size >= 2 -> { finished = true } // the zoom handler above took over
                                change.changedToUp() -> { vm.pointerUp(); finished = true }
                                change.positionChanged() -> {
                                    change.consume()
                                    toSheet(change.position).let { vm.pointerMove(it.x, it.y) }
                                }
                            }
                        }
                    }
                }),
        ) {
            // Reading the revision makes this block redraw whenever a layer changed (every move included).
            @Suppress("UNUSED_VARIABLE") val revision = vm.canvasRevision.intValue
            val scaleX = size.width / vm.sheetSize.value.width
            val scaleY = size.height / vm.sheetSize.value.height
            if (vm.showRaster.value) {
                drawImage(vm.working.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            if (vm.showVector.value) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.scale(scaleX, scaleY)
                    vm.vector.drawOrder.forEach { DrawingRenderer.drawShape(native, it) }
                    vm.vector.draft?.let { DrawingRenderer.drawShape(native, it) }
                    // The selection frame belongs to the select tool only; with another tool it looks stuck.
                    if (interactive && vm.mode.value == DrawingMode.VECTOR && vm.vector.tool == VectorTool.SELECT) {
                        vm.vector.selected?.let { drawSelection(native, it, handlePaint) }
                    }
                    native.restore()
                }
            }
            // The eraser shows what it will remove: a ring of its real size at the last touch (the sheet
            // centre before the first one), with a small eraser next to it because a finger hides the ring.
            if (interactive && vm.mode.value == DrawingMode.RASTER && vm.tool.value == DrawingTool.ERASER && vm.showRaster.value) {
                val at = vm.cursor.value
                val cx = (at?.x ?: (vm.sheetSize.value.width / 2f)) * scaleX
                val cy = (at?.y ?: (vm.sheetSize.value.height / 2f)) * scaleY
                val radius = vm.eraserDiameter / 2f * scaleX
                drawIntoCanvas { canvas -> drawEraserCursor(canvas.nativeCanvas, cx, cy, radius, density) }
            }
        }
    }
}

/** Finger slack when picking a vector shape, in dp of the screen. */
private const val HIT_SLACK_DP = 14

/** Ring of the eraser's size plus a small eraser glyph, in screen pixels. */
private fun drawEraserCursor(canvas: android.graphics.Canvas, cx: Float, cy: Float, radius: Float, density: Float) {
    val paint = NativePaint(NativePaint.ANTI_ALIAS_FLAG)
    // Translucent body so the erased area is visible, then a dark and a light ring: readable on any picture.
    paint.style = NativePaint.Style.FILL
    paint.color = 0x33FFFFFF
    canvas.drawCircle(cx, cy, radius, paint)
    paint.style = NativePaint.Style.STROKE
    paint.strokeWidth = 3f * density
    paint.color = 0xFF000000.toInt()
    canvas.drawCircle(cx, cy, radius, paint)
    paint.strokeWidth = 1.5f * density
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, cy, radius, paint)

    // The eraser itself, tilted, up and to the right of the ring.
    val w = 30f * density
    val h = 16f * density
    val offset = (radius + h * 0.6f) * 0.71f
    canvas.save()
    canvas.translate(cx + offset, cy - offset)
    canvas.rotate(-35f)
    val body = android.graphics.RectF(-w / 2, -h / 2, w / 2, h / 2)
    paint.style = NativePaint.Style.FILL
    paint.color = 0xFFF48FB1.toInt() // pink rubber
    canvas.drawRoundRect(body, 4f * density, 4f * density, paint)
    paint.color = 0xFF5C6BC0.toInt() // blue sleeve
    canvas.drawRoundRect(android.graphics.RectF(-w / 2, -h / 2, -w / 2 + w * 0.38f, h / 2), 4f * density, 4f * density, paint)
    paint.style = NativePaint.Style.STROKE
    paint.strokeWidth = 1.5f * density
    paint.color = 0xFF000000.toInt()
    canvas.drawRoundRect(body, 4f * density, 4f * density, paint)
    canvas.restore()
}

/** Dashed frame and end handles of the selected shape, in sheet coordinates. */
private fun drawSelection(canvas: android.graphics.Canvas, shape: Shape, paint: NativePaint) {
    val b = shape.bounds()
    val pad = shape.strokeWidth / 2 + 8f
    paint.style = NativePaint.Style.STROKE
    paint.strokeWidth = 3f
    paint.color = 0xFF1976D2.toInt()
    paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    canvas.drawRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, paint)
    paint.pathEffect = null
    if (shape.kind?.twoPoint == true) {
        for (p in shape.points) {
            paint.style = NativePaint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(p.x, p.y, 14f, paint)
            paint.style = NativePaint.Style.STROKE
            paint.color = 0xFF1976D2.toInt()
            canvas.drawCircle(p.x, p.y, 14f, paint)
        }
    }
}

@Composable
private fun RasterToolbar(vm: DrawingViewModel, @Suppress("UNUSED_PARAMETER") revision: Int) {
    val tool by vm.tool
    val width by vm.widthPx
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolChip(DrawingTool.PENCIL, R.string.drawing_tool_pencil, tool, vm::chooseTool)
            ToolChip(DrawingTool.BRUSH, R.string.drawing_tool_brush, tool, vm::chooseTool)
            ToolChip(DrawingTool.ERASER, R.string.drawing_tool_eraser, tool, vm::chooseTool)
            ToolChip(DrawingTool.FILL, R.string.drawing_tool_fill, tool, vm::chooseTool)
            ToolChip(DrawingTool.SHAPE, R.string.drawing_tool_shape, tool, vm::chooseTool)
            ToolChip(DrawingTool.TEXT, R.string.drawing_tool_text, tool, vm::chooseTool)
            ToolChip(DrawingTool.EYEDROPPER, R.string.drawing_tool_eyedropper, tool, vm::chooseTool)
        }
        when (tool) {
            DrawingTool.BRUSH -> BrushSettings(vm)
            DrawingTool.SHAPE -> ShapePicker(vm.rasterShape.value, DrawingViewModel.RASTER_SHAPES, { vm.rasterShape.value = it; vm.uiRevision.intValue++ }) {
                FilterChip(vm.shapeFilled.value, { vm.shapeFilled.value = !vm.shapeFilled.value }, { Text(stringResource(R.string.drawing_shape_filled)) })
            }
            DrawingTool.EYEDROPPER -> Text(stringResource(R.string.drawing_eyedropper_hint), style = MaterialTheme.typography.bodySmall)
            DrawingTool.TEXT -> Text(stringResource(R.string.drawing_text_hint), style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
        if (tool != DrawingTool.ERASER) {
            ColorRow(R.string.drawing_color, vm.colorArgb.intValue, allowNone = false) { c ->
                vm.colorArgb.intValue = c!!
            }
        }
        if (tool != DrawingTool.FILL && tool != DrawingTool.EYEDROPPER && tool != DrawingTool.TEXT) WidthRow(width) { vm.widthPx.value = it }
    }
}

/** The brush (D212): how see-through it is, how soft its edge, and whether it is a round tip or a flat nib at an angle. */
@Composable
private fun BrushSettings(vm: DrawingViewModel) {
    val opacity by vm.brushOpacity
    val softness by vm.brushSoftness
    val nib by vm.brushNib
    Column {
        LabeledSlider(R.string.drawing_brush_opacity, "${opacity * 100 / 255}%", opacity.toFloat(), 10f..255f) { vm.brushOpacity.intValue = it.toInt() }
        LabeledSlider(R.string.drawing_brush_softness, "${(softness * 100).toInt()}%", softness, 0f..1f) { vm.brushSoftness.value = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(nib == null, { vm.brushNib.value = null }, { Text(stringResource(R.string.drawing_brush_round)) })
            FilterChip(nib != null, { if (nib == null) vm.brushNib.value = 45f }, { Text(stringResource(R.string.drawing_brush_nib)) })
        }
        nib?.let { angle -> LabeledSlider(R.string.drawing_brush_angle, "${angle.toInt()}\u00B0", angle, 0f..180f) { vm.brushNib.value = it } }
    }
}

@Composable
private fun LabeledSlider(@StringRes label: Int, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label) + " " + value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(150.dp))
        androidx.compose.material3.Slider(value = current, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

/** The primitives to draw, each a small picture of itself (D212), and room for more options after them. */
@Composable
private fun ShapePicker(current: VectorTool, choices: List<VectorTool>, onPick: (VectorTool) -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        choices.forEach { t ->
            val label = stringResource(shapeLabel(t))
            FilterChip(
                selected = t == current,
                onClick = { onPick(t) },
                label = { Text(shapeGlyph(t), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        extra()
    }
}

private fun shapeGlyph(t: VectorTool): String = when (t) {
    VectorTool.RECT -> "\u25AD"
    VectorTool.SQUARE -> "\u25A1"
    VectorTool.ELLIPSE -> "\u2B2D"
    VectorTool.CIRCLE -> "\u25CB"
    VectorTool.TRIANGLE -> "\u25B3"
    VectorTool.RHOMBUS -> "\u25C7"
    VectorTool.PENTAGON -> "\u2B20"
    VectorTool.HEXAGON -> "\u2B21"
    VectorTool.STAR -> "\u2606"
    VectorTool.LINE -> "\u2215"
    VectorTool.ARROW -> "\u2192"
    VectorTool.DOUBLE_ARROW -> "\u2194"
    else -> "?"
}

@StringRes
private fun shapeLabel(t: VectorTool): Int = when (t) {
    VectorTool.RECT -> R.string.drawing_vtool_rect
    VectorTool.SQUARE -> R.string.drawing_shape_square
    VectorTool.ELLIPSE -> R.string.drawing_vtool_ellipse
    VectorTool.CIRCLE -> R.string.drawing_shape_circle
    VectorTool.TRIANGLE -> R.string.drawing_vtool_triangle
    VectorTool.RHOMBUS -> R.string.drawing_shape_rhombus
    VectorTool.PENTAGON -> R.string.drawing_shape_pentagon
    VectorTool.HEXAGON -> R.string.drawing_shape_hexagon
    VectorTool.STAR -> R.string.drawing_shape_star
    VectorTool.LINE -> R.string.drawing_vtool_line
    VectorTool.ARROW -> R.string.drawing_vtool_arrow
    VectorTool.DOUBLE_ARROW -> R.string.drawing_shape_double_arrow
    else -> R.string.drawing_tool_shape
}

/**
 * The words of a text on a sheet (D212): what to write, its size and bold / italic / underline / strikethrough — the second
 * text panel of the app, without Markdown. [initial] is a text being changed (blueprint), `null` for a new one.
 */
@Composable
private fun TextStampDialog(initial: Shape?, onConfirm: (String, Float, Int) -> Unit, onDismiss: () -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var size by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial?.textSize ?: Shape.DEFAULT_TEXT_SIZE) }
    var style by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial?.textStyle ?: 0) }
    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawing_tool_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 2, maxLines = 6,
                    label = { Text(stringResource(R.string.drawing_text_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(28f, 40f, 56f, 80f, 120f).forEach { px ->
                        FilterChip(size == px, { size = px }, { Text(px.toInt().toString()) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fun flag(bit: Int) = style and bit != 0
                    FilterChip(flag(Shape.TEXT_BOLD), { style = style xor Shape.TEXT_BOLD }, { Text("B", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) })
                    FilterChip(flag(Shape.TEXT_ITALIC), { style = style xor Shape.TEXT_ITALIC }, { Text("I", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) })
                    FilterChip(flag(Shape.TEXT_UNDERLINE), { style = style xor Shape.TEXT_UNDERLINE }, { Text("U", textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) })
                    FilterChip(flag(Shape.TEXT_STRIKE), { style = style xor Shape.TEXT_STRIKE }, { Text("S", textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, size, style) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun VectorToolbar(vm: DrawingViewModel, @Suppress("UNUSED_PARAMETER") revision: Int, layersOpen: Boolean, onToggleLayers: () -> Unit) {
    val tool by vm.vectorTool
    val selected by vm.selectedShape
    val strokeColor by vm.strokeColorState
    val fillColor by vm.fillColorState
    val strokeWidth by vm.strokeWidthState
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VectorChip(VectorTool.SELECT, R.string.drawing_vtool_select, tool, vm)
            // One "Shapes" tool with the list of primitives, as in a drawing (D213); the free-hand pen is gone from here.
            val shaping = tool in DrawingViewModel.RASTER_SHAPES
            FilterChip(selected = shaping, onClick = { vm.chooseVectorTool(vm.vectorShape.value) }, label = { Text(stringResource(R.string.drawing_tool_shape)) })
            VectorChip(VectorTool.FILL, R.string.drawing_tool_fill, tool, vm)
            VectorChip(VectorTool.TEXT, R.string.drawing_tool_text, tool, vm)
            VectorChip(VectorTool.EYEDROPPER, R.string.drawing_tool_eyedropper, tool, vm)
            FilterChip(layersOpen, onToggleLayers, { Text(stringResource(R.string.layers_title)) })
        }
        if (tool in DrawingViewModel.RASTER_SHAPES) {
            ShapePicker(tool, DrawingViewModel.RASTER_SHAPES, { vm.vectorShape.value = it; vm.chooseVectorTool(it) }) {
                // Filled or outline: the fill colour below is used, or none (D213).
                FilterChip(fillColor != null, { vm.vectorAction { setFillColor(if (fillColor != null) null else strokeColor) } }, { Text(stringResource(R.string.drawing_shape_filled)) })
            }
        }
        val shape = selected
        var editingText by remember { mutableStateOf(false) }
        if (editingText && shape?.kind == ShapeType.TEXT) {
            TextStampDialog(shape, onConfirm = { t, size, style -> vm.editSelectedText(t, size, style); editingText = false }, onDismiss = { editingText = false })
        }
        if (shape != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // D278: the actions of the chosen shape as icons, a long press says which; all of them can be undone.
                if (shape.kind == ShapeType.TEXT) com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.EditNote, stringResource(R.string.drawing_edit_text), { editingText = true })
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.Delete, stringResource(R.string.drawing_delete_shape), { vm.vectorAction { deleteSelected() } })
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.ContentCopy, stringResource(R.string.drawing_duplicate), { vm.vectorAction { duplicateSelected() } })
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.FlipToFront, stringResource(R.string.drawing_forward), { vm.vectorAction { bringForward() } })
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.FlipToBack, stringResource(R.string.drawing_backward), { vm.vectorAction { sendBackward() } })
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.VerticalAlignTop, stringResource(R.string.drawing_to_front), { vm.vectorAction { bringToFront() } })
                com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.VerticalAlignBottom, stringResource(R.string.drawing_to_back), { vm.vectorAction { sendToBack() } })
            }
        }
        ColorRow(R.string.drawing_outline, shape?.strokeColor ?: strokeColor, allowNone = false) { c ->
            vm.vectorAction { setStrokeColor(c!!) }
        }
        // A selected shape's own fill is shown only while the select tool is active; otherwise the fill to apply.
        val shownFill = if (shape != null && tool == VectorTool.SELECT) shape.fillColor else fillColor
        ColorRow(R.string.drawing_fill_label, shownFill, allowNone = true) { c ->
            vm.vectorAction { setFillColor(c) }
        }
        WidthRow(shape?.strokeWidth ?: strokeWidth) { w -> vm.vectorAction { setStrokeWidth(w) } }
    }
}

@Composable
private fun VectorChip(tool: VectorTool, @StringRes label: Int, current: VectorTool, vm: DrawingViewModel) {
    FilterChip(selected = tool == current, onClick = { vm.chooseVectorTool(tool) }, label = { Text(stringResource(label)) })
}

@Composable
private fun ToolChip(tool: DrawingTool, @StringRes labelRes: Int, selected: DrawingTool, onSelect: (DrawingTool) -> Unit) {
    FilterChip(selected = tool == selected, onClick = { onSelect(tool) }, label = { Text(stringResource(labelRes)) })
}

@Composable
private fun WidthRow(width: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.drawing_width), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = width.coerceIn(1f, 30f),
            onValueChange = onChange,
            valueRange = 1f..30f,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

/** The layers of a blueprint (D98): choose the one to draw on, show / hide, lock, order, rename, delete, add. */
@Composable
private fun LayersPanel(vm: DrawingViewModel, @Suppress("UNUSED_PARAMETER") revision: Int) {
    val editor = vm.vector
    val layers by vm.layersState
    val activeLayerId by vm.activeLayerState
    val canDraw by vm.canDrawOnActiveLayerState
    val selectedShape by vm.selectedShape
    var renaming by remember { mutableStateOf<VectorLayer?>(null) }
    var deleting by remember { mutableStateOf<VectorLayer?>(null) }
    val selected = selectedShape
    Column(
        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Top layer first, as in every drawing program.
        layers.withIndex().reversed().forEach { (index, layer) ->
            val title = layer.name.ifBlank { stringResource(R.string.layer_default_name, index + 1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = layer.id == activeLayerId,
                    onClick = { vm.vectorAction { setActiveLayer(layer.id) } },
                )
                Text(
                    title,
                    Modifier.weight(1f).clickable { vm.vectorAction { setActiveLayer(layer.id) } },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                // D278: the icon shows the state (an open / closed eye, an open / closed lock), the words say what a tap does.
                com.notebudget.app.core.ui.IconAction(
                    if (layer.visible) com.notebudget.app.core.ui.AppIcons.Visibility else com.notebudget.app.core.ui.AppIcons.VisibilityOff,
                    stringResource(if (layer.visible) R.string.layer_hide else R.string.layer_show),
                    { vm.vectorAction { setLayerVisible(layer.id, !layer.visible) } },
                )
                com.notebudget.app.core.ui.IconAction(
                    if (layer.locked) com.notebudget.app.core.ui.AppIcons.Lock else com.notebudget.app.core.ui.AppIcons.LockOpen,
                    stringResource(if (layer.locked) R.string.layer_unlock else R.string.layer_lock),
                    { vm.vectorAction { setLayerLocked(layer.id, !layer.locked) } },
                )
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("\u22EE") }
                    com.notebudget.app.core.ui.tip.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        com.notebudget.app.core.ui.tip.DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_rename)) },
                            onClick = { menu = false; renaming = layer },
                        )
                        com.notebudget.app.core.ui.tip.DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_up)) },
                            enabled = index < layers.lastIndex,
                            onClick = { menu = false; vm.vectorAction { moveLayer(layer.id, 1) } },
                        )
                        com.notebudget.app.core.ui.tip.DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_down)) },
                            enabled = index > 0,
                            onClick = { menu = false; vm.vectorAction { moveLayer(layer.id, -1) } },
                        )
                        com.notebudget.app.core.ui.tip.DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_top)) },
                            enabled = index < layers.lastIndex,
                            onClick = { menu = false; vm.vectorAction { moveLayerToEdge(layer.id, top = true) } },
                        )
                        com.notebudget.app.core.ui.tip.DropdownMenuItem(
                            text = { Text(stringResource(R.string.layer_bottom)) },
                            enabled = index > 0,
                            onClick = { menu = false; vm.vectorAction { moveLayerToEdge(layer.id, top = false) } },
                        )
                        if (selected != null && editor.layerOf(selected) != layer.id) {
                            com.notebudget.app.core.ui.tip.DropdownMenuItem(
                                text = { Text(stringResource(R.string.layer_move_shape_here)) },
                                onClick = { menu = false; vm.vectorAction { moveSelectedToLayer(layer.id) } },
                            )
                        }
                        com.notebudget.app.core.ui.tip.DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            enabled = layers.size > 1,
                            onClick = { menu = false; deleting = layer },
                        )
                    }
                }
            }
        }
        val next = layers.size + 1
        val nextName = stringResource(R.string.layer_default_name, next)
        com.notebudget.app.core.ui.IconAction(com.notebudget.app.core.ui.AppIcons.AddToPhotos, stringResource(R.string.layer_add), { vm.vectorAction { addLayer(nextName) } })
        if (!canDraw) {
            Text(stringResource(R.string.layer_cannot_draw), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    renaming?.let { layer ->
        var name by remember(layer.id) { mutableStateOf(layer.name) }
        com.notebudget.app.core.ui.EditorDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.layer_rename)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.layer_name_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.vectorAction { renameLayer(layer.id, name) }; renaming = null }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    deleting?.let { layer ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.layer_delete_title)) },
            text = { Text(stringResource(R.string.layer_delete_message)) },
            confirmButton = {
                TextButton(onClick = { vm.vectorAction { deleteLayer(layer.id) }; deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
