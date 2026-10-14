package com.notebudget.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.notebudget.app.core.ui.theme.OnContainerDark
import com.notebudget.app.core.ui.theme.ThemePalettes
import com.notebudget.app.core.ui.theme.appColorScheme
import com.notebudget.app.domain.model.ColorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ten colour themes (D105, D115), each in a light and a dark form; the light / dark / system choice stays separate. */
class ColorThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun `there is a palette for every theme the user asked for`() {
        assertEquals(
            listOf("GREEN", "PINK", "ORANGE", "RED", "LIGHT_BLUE", "BLUE", "LIME", "BLACK", "GRAY", "WHITE", "YELLOW", "LILAC", "MAGENTA", "CUSTOM"),
            ColorTheme.entries.map { it.name },
        )
        // Every ready theme has a palette; CUSTOM is made from the colour the person picks (D219).
        assertEquals(ColorTheme.entries.toSet() - ColorTheme.CUSTOM, ThemePalettes.keys)
    }

    @Test
    fun `every theme is readable in light and dark (text on the primary colour and on its container)`() {
        for (theme in ColorTheme.entries) {
            val light = appColorScheme(theme, dark = false)
            val dark = appColorScheme(theme, dark = true)
            assertTrue("$theme light primary ${contrast(light.primary, light.onPrimary)}", contrast(light.primary, light.onPrimary) >= 4.5)
            assertTrue("$theme light container", contrast(light.primaryContainer, light.onPrimaryContainer) >= 7.0)
            assertTrue("$theme dark primary ${contrast(dark.primary, dark.onPrimary)}", contrast(dark.primary, dark.onPrimary) >= 4.5)
            assertTrue("$theme dark container", contrast(dark.primaryContainer, OnContainerDark) >= 4.5)
        }
    }

    @Test
    fun `themes look different from each other and light differs from dark`() {
        val lights = ColorTheme.entries.map { appColorScheme(it, dark = false).primary }
        assertEquals(lights.size, lights.toSet().size)
        for (theme in ColorTheme.entries) {
            assertNotEquals(appColorScheme(theme, false).primary, appColorScheme(theme, true).primary)
        }
    }

    @Test
    fun `the white theme inverts the text on its white accent and stays readable on its page`() {
        val dark = appColorScheme(ColorTheme.WHITE, dark = true)
        assertEquals(Color.White, dark.primary)
        assertTrue("dark text on the white accent", dark.onPrimary.luminance() < 0.1f)
        assertTrue(contrast(dark.primary, dark.onPrimary) >= 15.0)

        // A white accent on a white page would be invisible, so on light the accent is dark and the cards are white.
        val light = appColorScheme(ColorTheme.WHITE, dark = false)
        assertTrue(light.primary.luminance() < 0.1f)
        assertEquals(Color.White, light.primaryContainer)
        assertTrue("the accent is readable on the page", contrast(light.primary, light.background) >= 7.0)
        assertNotEquals(Color.White, light.background)
    }

    @Test
    fun `the black theme uses a true black page in dark and accents that can be seen on it`() {
        val dark = appColorScheme(ColorTheme.BLACK, dark = true)
        assertEquals(Color.Black, dark.background)
        assertTrue(contrast(dark.primary, dark.background) >= 7.0)
        assertTrue(contrast(appColorScheme(ColorTheme.BLACK, false).primary, appColorScheme(ColorTheme.BLACK, false).background) >= 7.0)
    }

    @Test
    fun `every accent can be seen on the page it is drawn on`() {
        for (theme in ColorTheme.entries) {
            val light = appColorScheme(theme, dark = false)
            val dark = appColorScheme(theme, dark = true)
            assertTrue("$theme light", contrast(light.primary, light.background) >= 4.5)
            assertTrue("$theme dark", contrast(dark.primary, dark.background) >= 4.5)
        }
    }

    @Test
    fun `a theme made from any picked colour passes the same contrast rules`() {
        // D219: 300 colours from all over the RGB cube, greys, black and white included.
        val random = java.util.Random(7)
        val seeds = List(300) { 0xFF000000.toInt() or random.nextInt(0x1000000) } + listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFF00.toInt())
        for (seed in seeds) {
            val light = appColorScheme(ColorTheme.CUSTOM, dark = false, custom = seed)
            val dark = appColorScheme(ColorTheme.CUSTOM, dark = true, custom = seed)
            val tag = Integer.toHexString(seed)
            assertTrue("$tag light primary", contrast(light.primary, light.onPrimary) >= 4.5)
            assertTrue("$tag light container", contrast(light.primaryContainer, light.onPrimaryContainer) >= 7.0)
            assertTrue("$tag dark primary", contrast(dark.primary, dark.onPrimary) >= 4.5)
            assertTrue("$tag dark container", contrast(dark.primaryContainer, OnContainerDark) >= 4.5)
            assertTrue("$tag light page", contrast(light.primary, light.background) >= 4.5)
            assertTrue("$tag dark page", contrast(dark.primary, dark.background) >= 4.5)
        }
    }
}
