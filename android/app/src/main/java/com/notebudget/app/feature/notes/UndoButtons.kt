package com.notebudget.app.feature.notes

import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R

/**
 * An editor whose changes can be undone and redone (D197). [canUndo] and [canRedo] must be Compose state (read in
 * composition), so the buttons of the note's top bar follow them by themselves.
 */
interface UndoTarget {
    val canUndo: Boolean
    val canRedo: Boolean
    fun undo()
    fun redo()

    /** The next change is an undo step of its own, however soon it comes (a line moved or deleted in the View, D235). */
    fun endStep() {}
}

/**
 * Where the editor of the open note puts its [UndoTarget]: the note screen owns the slot and shows the buttons in its top
 * bar; the editor (text, list, schedule through the note's own model; budget and drawings through theirs) fills it.
 * `null` outside a note screen.
 */
val LocalUndoSlot = staticCompositionLocalOf<MutableState<UndoTarget?>?> { null }

/** Makes [target] the one the Undo / Redo buttons of the note act on while this composable is shown. */
@Composable
fun ProvideUndoTarget(target: UndoTarget) {
    val slot = LocalUndoSlot.current ?: return
    DisposableEffect(slot, target) {
        slot.value = target
        onDispose { if (slot.value === target) slot.value = null }
    }
}

/** The two arrows of the top bar; each is greyed out when there is nothing to undo or redo. */
@Composable
fun UndoRedoButtons(target: UndoTarget) {
    IconButton(onClick = target::undo, enabled = target.canUndo) {
        Icon(UndoIcon, contentDescription = stringResource(R.string.drawing_undo))
    }
    IconButton(onClick = target::redo, enabled = target.canRedo) {
        Icon(RedoIcon, contentDescription = stringResource(R.string.editor_redo))
    }
}

/** The Material "undo" / "redo" arrows, drawn here so the big extended icon library is not needed for two icons. */
private fun arrowIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = true)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

private val UndoIcon = arrowIcon(
    "Undo",
    "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
)

private val RedoIcon = arrowIcon(
    "Redo",
    "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
)
