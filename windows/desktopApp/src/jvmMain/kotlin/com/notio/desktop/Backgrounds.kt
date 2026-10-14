package com.notio.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.notio.shared.domain.BackgroundImageMath
import com.notio.shared.domain.BackgroundSlot
import com.notio.shared.domain.BackgroundSpec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Rect as SkRect
import org.jetbrains.skia.Surface as SkSurface

/**
 * The pictures used as backgrounds (Android `BackgroundImages`, D110; AGENT.md D31): one JPEG per slot in the data folder's
 * `backgrounds\`, shrunk to a long side of 1600 px when taken over, made from a copy of the chosen file. Pictures are not part of a
 * backup (the phone's rule): a restored `IMAGE` slot whose file is missing shows the default.
 */
class BackgroundImages(private val dir: File) {
    fun file(slot: BackgroundSlot): File = File(dir, slot.name.lowercase() + ".jpg")

    fun exists(slot: BackgroundSlot): Boolean = file(slot).isFile

    /** Takes over the picture in [source]; `false` when it is not a picture Skia can read (nothing changes then). */
    fun import(slot: BackgroundSlot, source: File): Boolean = runCatching {
        if (source.length() <= 0 || source.length() > MAX_INPUT_BYTES) return false
        val image = SkImage.makeFromEncoded(source.readBytes())
        if (image.width <= 0 || image.height <= 0) return false
        val sample = BackgroundImageMath.sampleSize(image.width, image.height)
        val w = image.width / sample
        val h = image.height / sample
        val surface = SkSurface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFFFFFFFF.toInt())
        surface.canvas.drawImageRect(image, SkRect.makeWH(w.toFloat(), h.toFloat()))
        val jpeg = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 85)?.bytes ?: return false
        dir.mkdirs()
        val temp = File(dir, slot.name.lowercase() + ".tmp")
        temp.writeBytes(jpeg)
        com.notio.shared.data.SafeFile.replace(temp, file(slot))
        cache.keys.removeIf { it.startsWith(slot.name + ":") }
        true
    }.getOrDefault(false)

    fun delete(slot: BackgroundSlot) {
        file(slot).delete()
        cache.keys.removeIf { it.startsWith(slot.name + ":") }
    }

    /** Decoded once and kept (every screen of the slot draws it; Android D221). */
    fun bitmap(slot: BackgroundSlot, version: Long): ImageBitmap? {
        val key = slot.name + ":" + version
        cache[key]?.let { return it }
        val made = runCatching { SkImage.makeFromEncoded(file(slot).readBytes()).toComposeImageBitmap() }.getOrNull() ?: return null
        // One picture per slot: the one of a replaced version goes (it was kept for good before, the whole-code pass).
        cache.keys.removeIf { it.startsWith(slot.name + ":") }
        cache[key] = made
        return made
    }

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    private companion object {
        const val MAX_INPUT_BYTES = 30L * 1024 * 1024
    }
}

/** What each slot shows right now; a picture whose file is gone counts as none. */
val LocalBackgrounds = compositionLocalOf<Map<BackgroundSlot, BackgroundSpec>> { emptyMap() }

/** The real page colour of the theme, under a picture at 80 % so text stays readable. */
val LocalPageColor = compositionLocalOf { Color.White }

val LocalBackgroundImages = compositionLocalOf<BackgroundImages?> { null }

fun Map<BackgroundSlot, BackgroundSpec>.has(slot: BackgroundSlot): Boolean = (this[slot] ?: BackgroundSpec.None) != BackgroundSpec.None

/** The background of [slot], drawn behind the content (the phone's `BackgroundLayer`). */
@Composable
fun BackgroundLayer(slot: BackgroundSlot, modifier: Modifier = Modifier) {
    when (val spec = LocalBackgrounds.current[slot] ?: BackgroundSpec.None) {
        BackgroundSpec.None -> Unit
        is BackgroundSpec.Color -> Box(modifier.background(Color(spec.argb)))
        is BackgroundSpec.Image -> {
            val bitmap = LocalBackgroundImages.current?.let { images -> remember(slot, spec.version) { images.bitmap(slot, spec.version) } }
            Box(modifier) {
                bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(LocalPageColor.current.copy(alpha = if (bitmap == null) 1f else 0.8f)))
            }
        }
    }
}

private fun slotName(slot: BackgroundSlot): String = "bg_slot_" + slot.name.lowercase()

/** Settings -> Appearance -> Backgrounds (Android feature/backgrounds, D110, D181): per slot a colour, a picture or the default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundsPage(controller: NotioController) {
    val current = LocalBackgrounds.current
    var pickingColorFor by remember { mutableStateOf<BackgroundSlot?>(null) }
    Page { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource("bg_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BackgroundSlot.entries.forEach { slot ->
                val spec = current[slot] ?: BackgroundSpec.None
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(8.dp)),
                        ) {
                            CompositionLocalProvider(LocalBackgrounds provides mapOf(slot to spec)) { BackgroundLayer(slot, Modifier.fillMaxSize()) }
                        }
                        Column {
                            Text(stringResource(slotName(slot)), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    when (spec) {
                                        BackgroundSpec.None -> "bg_state_default"
                                        is BackgroundSpec.Color -> "bg_state_color"
                                        is BackgroundSpec.Image -> "bg_state_image"
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickingColorFor = slot }) { Text(stringResource("bg_colour")) }
                        OutlinedButton(onClick = {
                            chooseFile(controller.res.str("bg_picture"), save = false)?.let { controller.setBackgroundImage(slot, it) }
                        }) { Text(stringResource("bg_picture")) }
                        OutlinedButton(enabled = spec != BackgroundSpec.None, onClick = { controller.setBackground(slot, BackgroundSpec.None) }) {
                            Text(stringResource("bg_reset"))
                        }
                    }
                }
            }
        }
    }
    pickingColorFor?.let { slot ->
        ColorPickerDialog(
            initial = (current[slot] as? BackgroundSpec.Color)?.argb ?: 0xFFFFFFFF.toInt(),
            onPick = { argb -> pickingColorFor = null; controller.setBackground(slot, BackgroundSpec.Color(argb or (0xFF shl 24))) },
            onDismiss = { pickingColorFor = null },
        )
    }
}
