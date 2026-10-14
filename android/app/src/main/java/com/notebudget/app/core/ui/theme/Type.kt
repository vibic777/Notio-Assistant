package com.notebudget.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.notebudget.app.R

/**
 * The app's own font (D209): Roboto (SIL Open Font License 1.1, `assets/licenses/Roboto-OFL.txt`) with real regular, medium,
 * bold, italic and bold italic faces. The user's phone drew **bold** and *italic* of notes exactly like plain text: many phone
 * makers replace the system font with one that gives apps no bold or italic face, so the styles of Markdown vanished there.
 * Letters this font does not have (Chinese, Japanese, Korean, emoji, ...) come from the phone's fonts as before — Android falls
 * back to them letter by letter, so all ten languages keep working.
 */
val AppFontFamily = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_bold, FontWeight.Bold),
    Font(R.font.roboto_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.roboto_bold_italic, FontWeight.Bold, FontStyle.Italic),
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
