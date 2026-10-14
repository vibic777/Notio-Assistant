package com.notio.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drag and drop for the items of a lazy list or row (D141): a finger takes an item by its handle and drags it to the place it
 * should have. While it moves, neighbours change places through [onSwap] (the owner keeps the order in its own state and
 * saves it in [onDrop]); the list scrolls by itself when the finger is near an edge.
 *
 * [canSwap] says whether the dragged key may pass the other one (a pinned item may not leave the pinned group).
 */
class DragReorderState(
    private val listState: LazyListState,
    private val horizontal: Boolean,
    private val scope: CoroutineScope,
    private val onSwap: (dragged: Any, over: Any) -> Unit,
    private val canSwap: (dragged: Any, over: Any) -> Boolean,
    private val onDrop: () -> Unit,
    /** Told when a finger takes an item, before anything moves (the budget closes the brothers of the item then, D176). */
    private val onStart: (Any) -> Unit = {},
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** After a swap the list needs one layout pass before its numbers are true again; until then no second swap. */
    private var expectIndex: Int? = null
    private var scroller: Job? = null

    fun start(key: Any) {
        onStart(key)
        draggedKey = key
        dragOffset = 0f
        expectIndex = null
        scroller?.cancel()
        scroller = scope.launch {
            while (isActive && draggedKey != null) {
                delay(16)
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.key == draggedKey } ?: continue
                val edge = 56f
                val center = item.offset + dragOffset + item.size / 2f
                val speed = when {
                    center < info.viewportStartOffset + edge -> -18f
                    center > info.viewportEndOffset - edge -> 18f
                    else -> 0f
                }
                if (speed != 0f) {
                    val consumed = listState.scrollBy(speed)
                    dragOffset += consumed
                    swapIfNeeded()
                }
            }
        }
    }

    fun drag(delta: Float) {
        if (draggedKey == null) return
        dragOffset += delta
        swapIfNeeded()
    }

    fun end() {
        scroller?.cancel()
        scroller = null
        if (draggedKey != null) {
            draggedKey = null
            dragOffset = 0f
            onDrop()
        }
    }

    private fun swapIfNeeded() {
        val key = draggedKey ?: return
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        expectIndex?.let { if (item.index == it) expectIndex = null else return }
        val center = item.offset + dragOffset + item.size / 2f
        val target = info.visibleItemsInfo.firstOrNull { other ->
            other.key != key && center >= other.offset && center <= other.offset + other.size && canSwap(key, other.key)
        } ?: return
        val forward = target.index > item.index
        val newOffset = if (forward) target.offset + target.size - item.size else target.offset
        expectIndex = target.index
        onSwap(key, target.key)
        dragOffset += item.offset - newOffset
    }

    /** The dragged item follows the finger and lies above the others. */
    fun itemModifier(key: Any): Modifier {
        val dragged = draggedKey == key
        return if (dragged) {
            Modifier.zIndex(1f).graphicsLayer {
                if (horizontal) translationX = dragOffset else translationY = dragOffset
                shadowElevation = 12f
            }
        } else {
            Modifier
        }
    }

    /** The grip: dragging it moves the item [key]. */
    fun handleModifier(key: Any): Modifier = Modifier.pointerInput(key) {
        detectDragGestures(
            onDragStart = { start(key) },
            onDrag = { change, amount ->
                change.consume()
                drag(if (horizontal) amount.x else amount.y)
            },
            onDragEnd = { end() },
            onDragCancel = { end() },
        )
    }
}

@Composable
fun rememberDragReorder(
    listState: LazyListState,
    horizontal: Boolean,
    onSwap: (dragged: Any, over: Any) -> Unit,
    canSwap: (dragged: Any, over: Any) -> Boolean = { _, _ -> true },
    onDrop: () -> Unit,
    onStart: (Any) -> Unit = {},
): DragReorderState {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val swap = androidx.compose.runtime.rememberUpdatedState(onSwap)
    val can = androidx.compose.runtime.rememberUpdatedState(canSwap)
    val drop = androidx.compose.runtime.rememberUpdatedState(onDrop)
    val started = androidx.compose.runtime.rememberUpdatedState(onStart)
    return remember(listState, horizontal) {
        DragReorderState(
            listState, horizontal, scope,
            onSwap = { a, b -> swap.value(a, b) },
            canSwap = { a, b -> can.value(a, b) },
            onDrop = { drop.value() },
            onStart = { started.value(it) },
        )
    }
}

/** Four arrows pointing apart: "take this and move it". Drawn by hand, the icon pack of the project has no such glyph. */
@Composable
fun MoveHandleIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    androidx.compose.foundation.layout.Box(modifier.size(24.dp).drawBehind { drawMoveArrows(tint) })
}

private fun DrawScope.drawMoveArrows(color: Color) {
    val w = size.width
    val h = size.height
    val c = Offset(w / 2f, h / 2f)
    val stroke = w * 0.09f
    val head = w * 0.16f
    // Cross of two lines.
    drawLine(color, Offset(w * 0.12f, c.y), Offset(w * 0.88f, c.y), strokeWidth = stroke)
    drawLine(color, Offset(c.x, h * 0.12f), Offset(c.x, h * 0.88f), strokeWidth = stroke)
    fun arrow(tip: Offset, dx: Float, dy: Float) {
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - dx * head + dy * head, tip.y - dy * head + dx * head)
            lineTo(tip.x - dx * head - dy * head, tip.y - dy * head - dx * head)
            close()
        }
        drawPath(path, color)
    }
    arrow(Offset(w * 0.06f, c.y), -1f, 0f)
    arrow(Offset(w * 0.94f, c.y), 1f, 0f)
    arrow(Offset(c.x, h * 0.06f), 0f, -1f)
    arrow(Offset(c.x, h * 0.94f), 0f, 1f)
}

/** The rows to show while a drag is going on, and the state that drives it (D155). */
class ArrangedDrag<T>(val drag: DragReorderState, val shown: List<T>)

/**
 * Drag and drop for a vertical list of rows that have ids (a list note, a schedule): while a finger drags, the order is kept
 * here; when it lets go, [onOrder] gets the ids in their new order and saves them. Works the same in View and in Edit.
 */
@Composable
fun <T> rememberArrangedDrag(
    listState: LazyListState,
    entries: List<T>,
    id: (T) -> String,
    onOrder: (List<String>) -> Unit,
): ArrangedDrag<T> {
    var order by remember { mutableStateOf<List<String>?>(null) }
    val shown = remember(entries, order) {
        val ids = order
        if (ids == null) entries else {
            val byId = entries.associateBy(id)
            ids.mapNotNull { byId[it] } + entries.filter { id(it) !in ids }
        }
    }
    androidx.compose.runtime.LaunchedEffect(entries) { if (order != null && entries.map(id) == order) order = null }
    val current = androidx.compose.runtime.rememberUpdatedState(entries)
    val drag = rememberDragReorder(
        listState, horizontal = false,
        onSwap = { a, b ->
            val ids = (order ?: current.value.map(id)).toMutableList()
            val from = ids.indexOf(a as String)
            val to = ids.indexOf(b as String)
            if (from >= 0 && to >= 0) { ids.add(to, ids.removeAt(from)); order = ids }
        },
        canSwap = { a, b -> current.value.any { id(it) == a } && current.value.any { id(it) == b } },
        onDrop = { order?.let(onOrder) },
    )
    return ArrangedDrag(drag, shown)
}

/** The grip of one row: take it and drag the row up or down. */
@Composable
fun DragHandle(state: DragReorderState, key: Any, modifier: Modifier = Modifier) {
    val label = stringResource("cd_drag_handle")
    androidx.compose.foundation.layout.Box(
        modifier
            .size(36.dp)
            .then(state.handleModifier(key))
            .androidSemantics(label),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { MoveHandleIcon() }
}

private fun Modifier.androidSemantics(description: String): Modifier =
    this.then(androidx.compose.ui.Modifier.semantics { contentDescription = description })
