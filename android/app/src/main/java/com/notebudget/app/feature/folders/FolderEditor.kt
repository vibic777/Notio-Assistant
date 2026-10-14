package com.notebudget.app.feature.folders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import com.notebudget.app.core.ui.tip.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.messageRes
import com.notebudget.app.domain.model.DomainError
import com.notebudget.app.domain.model.FolderStyleId

@Composable
fun folderStyleLabel(styleId: String): String = stringResource(
    when (styleId) {
        "MINIMAL" -> R.string.style_minimal
        "GRADIENT" -> R.string.style_gradient
        "PATTERN" -> R.string.style_pattern
        "OUTLINE" -> R.string.style_outline
        "COMPACT" -> R.string.style_compact
        else -> R.string.style_soft
    },
)

/**
 * Name, icon/emoji, colour, style and a live preview (D47). Shared by onboarding and the
 * create/edit folder dialogs. [error] is shown under the name field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderEditor(
    draft: FolderDraft,
    onDraftChange: (FolderDraft) -> Unit,
    error: DomainError?,
    modifier: Modifier = Modifier,
    /** Offer the kind of the folder, notes or tasks (D239): only when a folder is made. */
    showKind: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showKind) {
            // The two kinds as icons (D298): a long press names one, and the chosen one is named beside them.
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tasks = draft.kind == "TASKS"
                com.notebudget.app.core.ui.IconChoice(com.notebudget.app.core.ui.AppIcons.Folder, stringResource(R.string.folder_kind_notes), !tasks, { onDraftChange(draft.copy(kind = "NOTES")) })
                com.notebudget.app.core.ui.IconChoice(com.notebudget.app.core.ui.AppIcons.TaskAlt, stringResource(R.string.folder_kind_tasks), tasks, {
                    // A folder of tasks gets a tick as its icon unless one was chosen.
                    onDraftChange(draft.copy(kind = "TASKS", icon = draft.icon.ifBlank { "\u2705" }))
                })
                Text(
                    stringResource(if (tasks) R.string.folder_kind_tasks else R.string.folder_kind_notes),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text(stringResource(R.string.folder_name_label)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(stringResource(it.messageRes())) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.icon,
            onValueChange = { onDraftChange(draft.copy(icon = FolderIcon.limit(it))) },
            label = { Text(stringResource(R.string.folder_icon_label)) },
            // How to type an emoji: many people do not know the smiley key of their keyboard.
            supportingText = { Text(stringResource(R.string.folder_icon_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // The same colour choice as for a note: ready-made colours, the ones picked before, and any colour by RGB (D143).
        com.notebudget.app.feature.colors.ColorRow(
            label = R.string.folder_color_label,
            selected = com.notebudget.app.core.common.ArgbColor.parseHex(draft.colorHex),
            allowNone = false,
            onPick = { argb -> argb?.let { onDraftChange(draft.copy(colorHex = com.notebudget.app.core.common.ArgbColor.toHex(it))) } },
        )

        Text(stringResource(R.string.folder_style_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FolderStyleId.entries.forEach { style ->
                FilterChip(
                    selected = draft.styleId == style.name,
                    onClick = { onDraftChange(draft.copy(styleId = style.name)) },
                    label = { Text(folderStyleLabel(style.name)) },
                )
            }
        }

        Text(stringResource(R.string.folder_preview_label), style = MaterialTheme.typography.labelLarge)
        FolderCard(
            name = draft.name.ifBlank { stringResource(R.string.default_folder_name) },
            colorHex = draft.colorHex,
            styleId = draft.styleId,
            icon = draft.icon,
            noteCount = 0,
        )
    }
}
