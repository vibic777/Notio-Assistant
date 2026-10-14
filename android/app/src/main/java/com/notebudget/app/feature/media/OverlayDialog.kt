package com.notebudget.app.feature.media

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.notebudget.app.core.ui.tip.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.notebudget.app.core.ui.tip.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.notebudget.app.core.ui.tip.stringResource
import androidx.compose.ui.unit.dp
import com.notebudget.app.R
import com.notebudget.app.core.ui.rememberAppContainer
import com.notebudget.app.domain.model.DateStyle
import com.notebudget.app.domain.model.GeoPoint
import com.notebudget.app.domain.model.MediaKind
import com.notebudget.app.domain.model.OverlayPosition
import com.notebudget.app.domain.model.OverlaySettings
import com.notebudget.app.domain.model.TimeStyle
import com.notebudget.app.feature.colors.ColorRow
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A moment and a place to show what the overlays look like. */
private const val PREVIEW_MOMENT = 1_789_916_829_000L
private val PREVIEW_PLACE = GeoPoint(55.75581, 37.61764)

/**
 * The overlays of one kind of media note (D148): the time stamp, the place, a text and a picture, each with its own colour,
 * size, position and background (a plate, or none = transparent), with a preview. They are laid over every new photo or
 * video; what is already made is not touched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayDialog(kind: MediaKind, initial: OverlaySettings, onSave: (OverlaySettings) -> Unit, onDismiss: () -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    val container = rememberAppContainer()
    val scope = rememberCoroutineScope()
    val imageName = "overlay-" + kind.name.lowercase() + ".png"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val png = withContext(Dispatchers.IO) { readScaledPng(container.appContext, uri) }
            if (png != null && container.overlayImages.save(imageName, png)) {
                // The name stays the same for a new picture, so a change of [version] is what makes the preview reload.
                settings = settings.copy(image = settings.image.copy(enabled = true, file = imageName, version = settings.image.version + 1))
            }
        }
    }

    com.notebudget.app.core.ui.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.overlay_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.overlay_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Preview(settings, container.overlayImages.file(settings.image.file))

                // ---- date and time ----
                LayerHeader(R.string.overlay_stamp, settings.stamp.enabled) { settings = settings.copy(stamp = settings.stamp.copy(enabled = it)) }
                if (settings.stamp.enabled) {
                    val s = settings.stamp
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(s.showDate, { settings = settings.copy(stamp = s.copy(showDate = !s.showDate)) }, { Text(stringResource(R.string.settings_date_label)) })
                        FilterChip(s.showTime, { settings = settings.copy(stamp = s.copy(showTime = !s.showTime)) }, { Text(stringResource(R.string.settings_time_label)) })
                    }
                    if (s.showDate) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DateStyle.entries.forEach { style ->
                            val sample = s.formats.copy(date = style).formatDate(PREVIEW_MOMENT)
                            FilterChip(s.formats.date == style, { settings = settings.copy(stamp = s.copy(formats = s.formats.copy(date = style))) }, { Text(sample) })
                        }
                    }
                    if (s.showTime) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimeStyle.entries.forEach { style ->
                            val sample = s.formats.copy(time = style).formatTime(PREVIEW_MOMENT)
                            FilterChip(s.formats.time == style, { settings = settings.copy(stamp = s.copy(formats = s.formats.copy(time = style))) }, { Text(sample) })
                        }
                    }
                    LabelLook(s.colorArgb, s.sizePercent, s.position, s.backgroundArgb) { c, size, pos, bg ->
                        settings = settings.copy(stamp = s.copy(colorArgb = c, sizePercent = size, position = pos, backgroundArgb = bg))
                    }
                }
                HorizontalDivider()

                // ---- place ----
                LayerHeader(R.string.overlay_geo, settings.geo.enabled) { settings = settings.copy(geo = settings.geo.copy(enabled = it)) }
                if (settings.geo.enabled) {
                    val g = settings.geo
                    Text(stringResource(R.string.overlay_geo_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LabelLook(g.colorArgb, g.sizePercent, g.position, g.backgroundArgb) { c, size, pos, bg ->
                        settings = settings.copy(geo = g.copy(colorArgb = c, sizePercent = size, position = pos, backgroundArgb = bg))
                    }
                }
                HorizontalDivider()

                // ---- text ----
                LayerHeader(R.string.overlay_text, settings.text.enabled) { settings = settings.copy(text = settings.text.copy(enabled = it)) }
                if (settings.text.enabled) {
                    val t = settings.text
                    OutlinedTextField(
                        value = t.text, onValueChange = { settings = settings.copy(text = t.copy(text = it.take(120))) },
                        label = { Text(stringResource(R.string.overlay_text_field)) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
                    )
                    LabelLook(t.colorArgb, t.sizePercent, t.position, t.backgroundArgb) { c, size, pos, bg ->
                        settings = settings.copy(text = t.copy(colorArgb = c, sizePercent = size, position = pos, backgroundArgb = bg))
                    }
                }
                HorizontalDivider()

                // ---- picture ----
                LayerHeader(R.string.overlay_image, settings.image.enabled) { settings = settings.copy(image = settings.image.copy(enabled = it)) }
                if (settings.image.enabled) {
                    val i = settings.image
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.overlay_pick_image)) }
                        if (i.file.isNotBlank()) {
                            TextButton(onClick = {
                                scope.launch { container.overlayImages.delete(i.file) }
                                settings = settings.copy(image = i.copy(file = "", enabled = false))
                            }) { Text(stringResource(R.string.overlay_remove_image)) }
                        }
                    }
                    Text(stringResource(R.string.overlay_width, i.widthPercent), style = MaterialTheme.typography.labelLarge)
                    Slider(value = i.widthPercent.toFloat(), onValueChange = { settings = settings.copy(image = i.copy(widthPercent = it.toInt())) }, valueRange = 5f..80f)
                    Text(stringResource(R.string.overlay_opacity, i.opacityPercent), style = MaterialTheme.typography.labelLarge)
                    Slider(value = i.opacityPercent.toFloat(), onValueChange = { settings = settings.copy(image = i.copy(opacityPercent = it.toInt())) }, valueRange = 10f..100f)
                    PositionPicker(i.position) { settings = settings.copy(image = i.copy(position = it)) }
                    PlateRow(i.backgroundArgb) { settings = settings.copy(image = i.copy(backgroundArgb = it)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(settings) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LayerHeader(title: Int, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

/** Colour, size, position and plate of a label. */
@Composable
private fun LabelLook(color: Int, size: Int, position: OverlayPosition, background: Int?, onChange: (Int, Int, OverlayPosition, Int?) -> Unit) {
    ColorRow(label = R.string.overlay_color, selected = color, allowNone = false) { picked -> if (picked != null) onChange(picked, size, position, background) }
    Text(stringResource(R.string.overlay_size, size), style = MaterialTheme.typography.labelLarge)
    Slider(value = size.toFloat(), onValueChange = { onChange(color, it.toInt().coerceIn(1, 15), position, background) }, valueRange = 1f..15f)
    PositionPicker(position) { onChange(color, size, it, background) }
    PlateRow(background) { onChange(color, size, position, it) }
}

/** "Plate behind": off = transparent (nothing behind the letters or the picture); on = a colour of its own. */
@Composable
private fun PlateRow(background: Int?, onChange: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.overlay_plate), Modifier.weight(1f))
        Switch(checked = background != null, onCheckedChange = { on -> onChange(if (on) 0xCC000000.toInt() else null) })
    }
    if (background != null) {
        ColorRow(label = R.string.overlay_color, selected = background or (0xFF shl 24), allowNone = false) { picked ->
            // A plate is a little see-through, so the picture below still shows.
            if (picked != null) onChange((picked and 0x00FFFFFF) or (0xCC shl 24))
        }
    }
}

/** The 3 x 3 grid of places. */
@Composable
private fun PositionPicker(current: OverlayPosition, onPick: (OverlayPosition) -> Unit) {
    val glyphs = listOf("↖", "↑", "↗", "←", "●", "→", "↙", "↓", "↘")
    val description = stringResource(R.string.overlay_position)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(description, style = MaterialTheme.typography.labelLarge)
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 3) {
                    val position = OverlayPosition.entries[row * 3 + col]
                    val selected = position == current
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(position) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(glyphs[row * 3 + col], color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The overlays as they will look on a frame, drawn by the very code that draws them on a photo or a video. */
@Composable
private fun Preview(settings: OverlaySettings, imageFile: java.io.File?) {
    val logo by produceState<android.graphics.Bitmap?>(null, settings.image.file, settings.image.version, settings.image.widthPercent) {
        value = withContext(Dispatchers.IO) { OverlayRenderer.loadLogo(imageFile, settings, 800) }
    }
    val inputs = OverlayInputs(PREVIEW_MOMENT, PREVIEW_PLACE, logo)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(Color(0xFF546E7A))
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
    ) {
        drawIntoCanvas { canvas -> OverlayRenderer.draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), settings, inputs) }
    }
}

/** The picture the person chose, at most 1024 px on its longest side, as PNG (a see-through background stays see-through). */
private fun readScaledPng(context: android.content.Context, uri: Uri): ByteArray? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
    ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}.getOrNull()
