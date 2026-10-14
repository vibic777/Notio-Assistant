package com.notebudget.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.notebudget.app.domain.model.ColorTheme
import com.notebudget.app.domain.model.FontSize

@Composable
fun NoteBudgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorTheme: ColorTheme = ColorTheme.GREEN,
    fontSize: FontSize = FontSize.NORMAL,
    /** The colour of the CUSTOM theme (D219). */
    customColor: Int = DEFAULT_CUSTOM_THEME,
    /** The app has a background (D110): the page colour becomes transparent so the background shows through. */
    transparentPage: Boolean = false,
    content: @Composable () -> Unit,
) {
    // The chosen size multiplies the system font scale for every screen at once (D119).
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontSize.factor)) {
        val scheme = appColorScheme(colorTheme, darkTheme, customColor)
        CompositionLocalProvider(com.notebudget.app.core.ui.LocalPageColor provides scheme.background) {
            MaterialTheme(
                colorScheme = if (transparentPage) scheme.copy(background = androidx.compose.ui.graphics.Color.Transparent) else scheme,
                typography = AppTypography,
                content = content,
            )
        }
    }
}
