package com.notebudget.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text colour on a strongly coloured card (D273). */
class ReadableOnTest {
    private val dark = Color(0xFF1C1B1F)
    private val light = Color(0xFFF4EFF4)

    @Test
    fun `the theme colour is kept where it reads well`() {
        assertEquals(dark, readableOn(Color.White, dark))
        assertEquals(light, readableOn(Color(0xFF101010), light))
    }

    @Test
    fun `dark text on a dark card turns light, light text on a light card turns dark`() {
        assertTrue(readableOn(Color(0xFF1A237E), dark).luminance() > 0.5f)
        assertTrue(readableOn(Color(0xFFFFEB3B), light).luminance() < 0.5f)
    }
}
