package com.notio.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import com.notio.shared.domain.ColorTheme
import com.notio.shared.domain.FontSize
import com.notio.shared.domain.ThemeMode

/**
 * The app's own font, as on Android (D209): Roboto (SIL OFL 1.1, `licenses/Roboto-OFL.txt`) with real bold and italic faces;
 * letters it does not have come from the system fonts.
 */
val AppFontFamily = FontFamily(
    Font("font/roboto_regular.ttf", FontWeight.Normal),
    Font("font/roboto_medium.ttf", FontWeight.Medium),
    Font("font/roboto_bold.ttf", FontWeight.Bold),
    Font("font/roboto_italic.ttf", FontWeight.Normal, FontStyle.Italic),
    Font("font/roboto_bold_italic.ttf", FontWeight.Bold, FontStyle.Italic),
)

private fun TextStyle.inAppFont() = copy(fontFamily = AppFontFamily)

val AppTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.inAppFont(),
        displayMedium = t.displayMedium.inAppFont(),
        displaySmall = t.displaySmall.inAppFont(),
        headlineLarge = t.headlineLarge.inAppFont(),
        headlineMedium = t.headlineMedium.inAppFont(),
        headlineSmall = t.headlineSmall.inAppFont(),
        titleLarge = t.titleLarge.inAppFont(),
        titleMedium = t.titleMedium.inAppFont(),
        titleSmall = t.titleSmall.inAppFont(),
        bodyLarge = t.bodyLarge.inAppFont(),
        bodyMedium = t.bodyMedium.inAppFont(),
        bodySmall = t.bodySmall.inAppFont(),
        labelLarge = t.labelLarge.inAppFont(),
        labelMedium = t.labelMedium.inAppFont(),
        labelSmall = t.labelSmall.inAppFont(),
    )
}

/** What the theme is made from; read from the same settings a phone backup carries (theme, colour theme, text size). */
data class ThemeChoice(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: ColorTheme = ColorTheme.GREEN,
    val customColor: Int = DEFAULT_CUSTOM_THEME,
    val fontSize: FontSize = FontSize.NORMAL,
)

@Composable
fun ThemeChoice.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The Android `NoteBudgetTheme`: the colour scheme of the chosen theme, Roboto, and the text size on top of the system's (D119). */
@Composable
fun NotioTheme(choice: ThemeChoice = ThemeChoice(), content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * choice.fontSize.factor)) {
        MaterialTheme(
            colorScheme = appColorScheme(choice.colorTheme, choice.isDark(), choice.customColor),
            typography = AppTypography,
            content = content,
        )
    }
}
