package com.notebudget.app.feature.backgrounds

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import com.notebudget.app.core.ui.tip.Icon
import com.notebudget.app.core.ui.tip.IconButton
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.BackgroundLayer
import com.notebudget.app.core.ui.LocalBackgrounds
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.BackgroundSlot
import com.notebudget.app.domain.model.BackgroundSpec
import com.notebudget.app.feature.colors.ColorPickerDialog
import kotlinx.coroutines.launch

private fun slotName(slot: BackgroundSlot): Int = when (slot) {
    BackgroundSlot.APP -> R.string.bg_slot_app
    BackgroundSlot.FOLDERS -> R.string.bg_slot_folders
    BackgroundSlot.NOTE_TEXT -> R.string.bg_slot_note_text
    BackgroundSlot.NOTE_LIST -> R.string.bg_slot_note_list
    BackgroundSlot.NOTE_BUDGET -> R.string.bg_slot_note_budget
    BackgroundSlot.NOTE_DRAWING -> R.string.bg_slot_note_drawing
    BackgroundSlot.NOTE_BLUEPRINT -> R.string.bg_slot_note_blueprint
    BackgroundSlot.NOTE_AUDIO -> R.string.bg_slot_note_audio
    BackgroundSlot.NOTE_SCHEDULE -> R.string.bg_slot_note_schedule
    BackgroundSlot.NOTE_PHOTO -> R.string.bg_slot_note_photo
    BackgroundSlot.NOTE_VIDEO -> R.string.bg_slot_note_video
    BackgroundSlot.NOTE_SCREEN -> R.string.bg_slot_note_screen
    BackgroundSlot.NOTE_WORD -> R.string.bg_slot_note_word
    BackgroundSlot.NOTE_TASK -> R.string.bg_slot_note_task
}

/** Settings → Backgrounds (D110): for the whole app, the folder cards and every kind of note, a colour or a picture. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackgroundsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val container = rememberAppContainer()
    val settings = container.settings
    val images = container.backgroundImages
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickingColorFor by remember { mutableStateOf<BackgroundSlot?>(null) }
    var pickingImageFor by remember { mutableStateOf<BackgroundSlot?>(null) }
    val current = LocalBackgrounds.current

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pickingImageFor
        pickingImageFor = null
        if (uri == null || slot == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = images.import(slot) { context.contentResolver.openInputStream(uri) }
            if (ok) settings.setBackground(slot, BackgroundSpec.Image(System.currentTimeMillis()))
            else Toast.makeText(context, context.getString(R.string.bg_error), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bg_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.bg_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BackgroundSlot.entries.forEach { slot ->
                val spec = current[slot] ?: BackgroundSpec.None
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(width = 56.dp, height = 40.dp)
                                .clipToShape()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp)),
                        ) {
                            // The same layer the app uses, shown small.
                            androidx.compose.runtime.CompositionLocalProvider(LocalBackgrounds provides mapOf(slot to spec)) {
                                BackgroundLayer(slot, Modifier.fillMaxSize())
                            }
                        }
                        Column {
                            Text(stringResource(slotName(slot)), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    when (spec) {
                                        BackgroundSpec.None -> R.string.bg_state_default
                                        is BackgroundSpec.Color -> R.string.bg_state_color
                                        is BackgroundSpec.Image -> R.string.bg_state_image
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickingColorFor = slot }) { Text(stringResource(R.string.bg_colour)) }
                        OutlinedButton(onClick = {
                            pickingImageFor = slot
                            imageLauncher.launch(arrayOf("image/png", "image/jpeg"))
                        }) { Text(stringResource(R.string.bg_picture)) }
                        OutlinedButton(
                            enabled = spec != BackgroundSpec.None,
                            onClick = {
                                scope.launch {
                                    settings.setBackground(slot, BackgroundSpec.None)
                                    images.delete(slot)
                                }
                            },
                        ) { Text(stringResource(R.string.bg_reset)) }
                    }
                }
            }
        }
    }

    pickingColorFor?.let { slot ->
        ColorPickerDialog(
            initial = (current[slot] as? BackgroundSpec.Color)?.argb ?: Color.White.value.toInt(),
            onPick = { argb ->
                pickingColorFor = null
                scope.launch {
                    images.delete(slot)
                    settings.setBackground(slot, BackgroundSpec.Color(argb or (0xFF shl 24)))
                }
            },
            onDismiss = { pickingColorFor = null },
        )
    }
}

private fun Modifier.clipToShape(): Modifier = this.then(Modifier.clip(RoundedCornerShape(8.dp)))
