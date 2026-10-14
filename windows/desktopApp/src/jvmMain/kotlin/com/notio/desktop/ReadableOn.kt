package com.notio.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Text that stays readable on a strongly coloured card (Android D273). Below this luminance white text reads better than black (the
 * point where both give the same contrast); above it, black.
 */
private const val DARK_BELOW = 0.18f

/** [fallback] when it reads well on [background], else near-black or near-white. */
fun readableOn(background: Color, fallback: Color): Color {
    val darkBackground = background.luminance() < DARK_BELOW
    val lightText = fallback.luminance() > 0.5f
    return when {
        darkBackground == lightText -> fallback
        darkBackground -> Color(0xFFF4EFF4)
        else -> Color(0xFF1C1B1F)
    }
}

/**
 * [content] with the theme's text colours ([onSurface][androidx.compose.material3.ColorScheme.onSurface] and its variant) turned
 * light or dark as [background] needs, so every line of a card follows without being changed one by one.
 */
@Composable
fun ReadableOn(background: Color, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onSurface = readableOn(background, scheme.onSurface)
    if (onSurface == scheme.onSurface) {
        content()
        return
    }
    val light = onSurface.luminance() > 0.5f
    MaterialTheme(
        colorScheme = scheme.copy(
            onSurface = onSurface,
            onSurfaceVariant = if (light) Color(0xFFCAC4D0) else Color(0xFF49454F),
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
