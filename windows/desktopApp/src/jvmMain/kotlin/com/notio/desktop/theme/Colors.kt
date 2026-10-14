package com.notio.desktop.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.notio.shared.domain.ColorTheme

// Ported unchanged from the Android app (core/ui/theme/Color.kt, D105, D115, D219): the same fourteen colour themes.
/** The colours of one theme, light and dark (D105). Every pair is checked for contrast by a test. */
class ThemeColors(
    val lightPrimary: Long,
    val lightContainer: Long,
    val lightOnContainer: Long,
    val darkPrimary: Long,
    val darkContainer: Long,
    /** Text on the primary colour. A white primary needs DARK text (D115), so this is data, not a constant. */
    val lightOnPrimary: Long = 0xFFFFFFFF,
    val darkOnPrimary: Long = 0xFF101010,
    /** Background override (a true black for the black theme, a soft grey behind white cards for the white one). */
    val lightBackground: Long? = null,
    val darkBackground: Long? = null,
    /** The dot shown in Settings, when it should differ from the light primary (white). */
    val swatch: Long? = null,
)

val ThemePalettes: Map<ColorTheme, ThemeColors> = mapOf(
    ColorTheme.GREEN to ThemeColors(0xFF1B7F5C, 0xFFB8F0D6, 0xFF00201A, 0xFF7FD8B5, 0xFF005139),
    ColorTheme.PINK to ThemeColors(0xFFC2185B, 0xFFFFD9E3, 0xFF3E001D, 0xFFFFB0CB, 0xFF8E0040),
    ColorTheme.ORANGE to ThemeColors(0xFFB84200, 0xFFFFDBC8, 0xFF3B0A00, 0xFFFFB68D, 0xFF8A3A00),
    ColorTheme.RED to ThemeColors(0xFFC62828, 0xFFFFDAD6, 0xFF410002, 0xFFFFB4AB, 0xFF93000A),
    ColorTheme.LIGHT_BLUE to ThemeColors(0xFF01679F, 0xFFC7E7FF, 0xFF001E30, 0xFF8DCFFF, 0xFF004A77),
    ColorTheme.BLUE to ThemeColors(0xFF1A3FC0, 0xFFDDE1FF, 0xFF001258, 0xFFB7C4FF, 0xFF0A2A9A),
    ColorTheme.LIME to ThemeColors(0xFF4C7A1E, 0xFFDCEDC8, 0xFF1B2E00, 0xFFC5E17A, 0xFF3B5200),
    // Black: black accents on light, light-grey accents on a true black background (dark).
    ColorTheme.BLACK to ThemeColors(
        0xFF000000, 0xFFDADADA, 0xFF000000, 0xFFD0D0D0, 0xFF2B2B2B,
        darkBackground = 0xFF000000, swatch = 0xFF000000,
    ),
    ColorTheme.GRAY to ThemeColors(0xFF616161, 0xFFE0E0E0, 0xFF1C1C1C, 0xFFBDBDBD, 0xFF474747, swatch = 0xFF9E9E9E),
    // White: a white accent would vanish on a white page, so on light the accent is a very dark grey with white
    // cards on a soft grey page; on dark the accent IS white and the text on it is inverted to dark.
    ColorTheme.WHITE to ThemeColors(
        0xFF2E2E2E, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF3D3D3D,
        lightBackground = 0xFFEDEDED, swatch = 0xFFFFFFFF,
    ),
    // D219. Yellow: a bright yellow accent on a white page could not be read, so on light the cards and chips are bright yellow
    // and the accent (text, lines) is a deep gold; on dark the accent itself is the bright yellow, with dark text on it.
    ColorTheme.YELLOW to ThemeColors(0xFF7A5A00, 0xFFFFE14D, 0xFF241A00, 0xFFFFD600, 0xFF5C4600, swatch = 0xFFFFD600),
    ColorTheme.LILAC to ThemeColors(0xFF8338C9, 0xFFF0DBFF, 0xFF2C0051, 0xFFD9B8FF, 0xFF5E2A99, swatch = 0xFFA45EE6),
    ColorTheme.MAGENTA to ThemeColors(0xFFB0007E, 0xFFFFD8EE, 0xFF3D002A, 0xFFFFABDC, 0xFF8A0063, swatch = 0xFFE0009D),
)

/** The seed of the CUSTOM theme before the person picks one (D219): a teal none of the ready themes has. */
const val DEFAULT_CUSTOM_THEME = 0xFF00897B.toInt()

/**
 * A whole theme from one colour picked with the RGB tool (D219): its hue and saturation are kept, and every shade is moved in
 * lightness until it passes the same contrast rules as the ready themes — text on the accent at least 4.5 : 1, text on the light
 * container 7 : 1, the accent readable on the page — so no colour, however light, dark or grey, gives an unreadable app.
 */
fun customThemeColors(seed: Int): ThemeColors {
    val (h, s0, _) = hsl(seed)
    val s = s0.coerceAtLeast(0.0f)
    fun shade(l: Float) = Color.hsl(h, s, l.coerceIn(0f, 1f))
    fun ratio(a: Color, b: Color): Float {
        val x = a.luminanceOf()
        val y = b.luminanceOf()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
    // Light accent: as light as it can be with white text on it and on a near-white page.
    var lp = 0.60f
    while (lp > 0.02f && (ratio(shade(lp), Color.White) < 4.7f || ratio(shade(lp), Color(0xFFFEF7FF)) < 4.7f)) lp -= 0.02f
    // Light container: a pale tint; its text: a very dark shade.
    val lc = 0.90f
    var lo = 0.15f
    while (lo > 0f && ratio(shade(lc), shade(lo)) < 7.2f) lo -= 0.02f
    // Dark accent: light enough for dark text on it and to stand out on the dark page.
    var dp = 0.70f
    while (dp < 0.98f && (ratio(shade(dp), Color(0xFF101010)) < 4.7f || ratio(shade(dp), Color(0xFF141218)) < 4.7f)) dp += 0.02f
    // Dark container: dark enough for the light text used on it.
    var dc = 0.30f
    while (dc > 0.02f && ratio(shade(dc), Color(0xFFF4F4F4)) < 4.7f) dc -= 0.02f
    fun argb(c: Color): Long = c.toArgbLong()
    return ThemeColors(argb(shade(lp)), argb(shade(lc)), argb(shade(lo)), argb(shade(dp)), argb(shade(dc)), swatch = seed.toLong() and 0xFFFFFFFFL)
}

private fun Color.toArgbLong(): Long {
    val a = (alpha * 255).toInt()
    val r = (red * 255 + 0.5f).toInt()
    val g = (green * 255 + 0.5f).toInt()
    val b = (blue * 255 + 0.5f).toInt()
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}

private fun Color.luminanceOf(): Float {
    fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(red) + 0.7152f * lin(green) + 0.0722f * lin(blue)
}

/** Hue (0..360), saturation and lightness (0..1) of an ARGB colour. */
private fun hsl(argb: Int): Triple<Float, Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2
    if (max == min) return Triple(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6 else 0)) * 60
        g -> ((b - r) / d + 2) * 60
        else -> ((r - g) / d + 4) * 60
    }
    return Triple(h, s, l)
}

/** The colours of [theme]; the CUSTOM one is made from [custom]. */
fun themeColors(theme: ColorTheme, custom: Int = DEFAULT_CUSTOM_THEME): ThemeColors =
    if (theme == ColorTheme.CUSTOM) customThemeColors(custom) else ThemePalettes.getValue(theme)

private val OnDarkContainer = Color(0xFFF4F4F4)

fun appColorScheme(theme: ColorTheme, dark: Boolean, custom: Int = DEFAULT_CUSTOM_THEME): ColorScheme {
    val c = themeColors(theme, custom)
    return if (dark) {
        val base = darkColorScheme(
            primary = Color(c.darkPrimary), onPrimary = Color(c.darkOnPrimary),
            primaryContainer = Color(c.darkContainer), onPrimaryContainer = OnDarkContainer,
            secondary = Color(c.darkPrimary), onSecondary = Color(c.darkOnPrimary),
            secondaryContainer = Color(c.darkContainer), onSecondaryContainer = OnDarkContainer,
        )
        c.darkBackground?.let { base.copy(background = Color(it), surface = Color(it)) } ?: base
    } else {
        val base = lightColorScheme(
            primary = Color(c.lightPrimary), onPrimary = Color(c.lightOnPrimary),
            primaryContainer = Color(c.lightContainer), onPrimaryContainer = Color(c.lightOnContainer),
            secondary = Color(c.lightPrimary), onSecondary = Color(c.lightOnPrimary),
            secondaryContainer = Color(c.lightContainer), onSecondaryContainer = Color(c.lightOnContainer),
        )
        c.lightBackground?.let { base.copy(background = Color(it)) } ?: base
    }
}

/** The colour shown next to a theme's name in Settings. */
fun themeSwatch(theme: ColorTheme, custom: Int = DEFAULT_CUSTOM_THEME): Color = themeColors(theme, custom).let { Color(it.swatch ?: it.lightPrimary) }

/** Text colour used on top of a dark theme's container, for the contrast test. */
internal val OnContainerDark = OnDarkContainer
