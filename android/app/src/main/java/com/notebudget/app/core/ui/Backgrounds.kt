package com.notebudget.app.core.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.notebudget.app.data.BackgroundImages
import com.notebudget.app.domain.model.BackgroundSlot
import com.notebudget.app.domain.model.BackgroundSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What each slot shows right now (D110); a picture whose file is gone already counts as [BackgroundSpec.None]. */
val LocalBackgrounds = compositionLocalOf<Map<BackgroundSlot, BackgroundSpec>> { emptyMap() }

/** The real page colour of the theme; the theme's own `background` becomes transparent when the app has a background. */
val LocalPageColor = compositionLocalOf { Color.White }

/** A picture that is not there any more is the default background. */
fun effectiveBackgrounds(
    specs: Map<BackgroundSlot, BackgroundSpec>,
    images: BackgroundImages,
): Map<BackgroundSlot, BackgroundSpec> = specs.mapValues { (slot, spec) ->
    if (spec is BackgroundSpec.Image && !images.exists(slot)) BackgroundSpec.None else spec
}

fun Map<BackgroundSlot, BackgroundSpec>.has(slot: BackgroundSlot): Boolean = (this[slot] ?: BackgroundSpec.None) != BackgroundSpec.None

/**
 * The background of [slot], to be drawn behind the content. A colour is drawn as it is; a picture is drawn to fill the
 * space and is covered by the page colour at 80 %, so the text on top stays readable whatever the picture is.
 */
@Composable
fun BackgroundLayer(slot: BackgroundSlot, modifier: Modifier = Modifier) {
    when (val spec = LocalBackgrounds.current[slot] ?: BackgroundSpec.None) {
        BackgroundSpec.None -> Unit
        is BackgroundSpec.Color -> Box(modifier.background(Color(spec.argb)))
        is BackgroundSpec.Image -> {
            val images = rememberAppContainer().backgroundImages
            // Decoded once and kept in memory: every screen and note of the slot draws it, the file is not read again (D221).
            val cacheKey = "bg:" + slot.name + ":" + spec.version
            val bitmap by produceState(com.notebudget.app.feature.media.ThumbnailCache.get(cacheKey), cacheKey) {
                if (value != null) return@produceState
                value = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(images.file(slot).absolutePath)?.asImageBitmap() }.getOrNull()
                }?.also { com.notebudget.app.feature.media.ThumbnailCache.put(cacheKey, it) }
            }
            Box(modifier) {
                bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(LocalPageColor.current.copy(alpha = if (bitmap == null) 1f else 0.8f)))
            }
        }
    }
}
