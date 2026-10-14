package com.notebudget.app.feature.folders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt

/** Editable folder properties (D05, D47). Survives rotation via [rememberFolderDraft]. */
data class FolderDraft(
    val name: String,
    val colorHex: String = FolderPalette.first(),
    val styleId: String = "SOFT",
    val icon: String = "",
    /** NOTES or TASKS (D239); chosen when the folder is made. */
    val kind: String = "NOTES",
)

/** Preset colours; all of them keep enough contrast with black or white text. */
val FolderPalette = listOf(
    "#1B7F5C", "#2F6FDE", "#8E44AD", "#D9534F", "#E67E22",
    "#C9A400", "#00897B", "#546E7A", "#E91E63", "#6D4C41",
)

private val FolderDraftSaver = listSaver<FolderDraft, String>(
    save = { listOf(it.name, it.colorHex, it.styleId, it.icon, it.kind) },
    restore = { FolderDraft(it[0], it[1], it[2], it[3], it.getOrElse(4) { "NOTES" }) },
)

@Composable
fun rememberFolderDraft(initial: FolderDraft): MutableState<FolderDraft> =
    rememberSaveable(stateSaver = FolderDraftSaver) { mutableStateOf(initial) }

fun parseColorOrDefault(hex: String, default: Color = Color(0xFF1B7F5C)): Color =
    runCatching { Color(hex.toColorInt()) }.getOrDefault(default)
