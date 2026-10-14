package com.notio.desktop

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The window back from the tray (D70): shown, not minimised, on a screen that exists. */
class WindowRestoreTest {
    private val screen = Rectangle(0, 0, 1920, 1080)

    @Test
    fun offScreenOnlyWhenNoScreenShowsEnough() {
        assertFalse(WindowRestore.offScreen(Rectangle(100, 100, 800, 600), listOf(screen)))
        // A second monitor that is gone: the window stood at x 2000.
        assertTrue(WindowRestore.offScreen(Rectangle(2000, 100, 800, 600), listOf(screen)))
        // A sliver of 40 px is not reachable with the mouse.
        assertTrue(WindowRestore.offScreen(Rectangle(1880, 100, 800, 600), listOf(screen)))
        assertFalse(WindowRestore.offScreen(Rectangle(2000, 100, 800, 600), listOf(screen, Rectangle(1920, 0, 1920, 1080))))
        // No screens known: left alone.
        assertFalse(WindowRestore.offScreen(Rectangle(-5000, 0, 800, 600), emptyList()))
    }

    @Test
    fun aLostOrTinyWindowGetsAWholePlaceOnARealScreen() {
        // D140: seen as it stands - left alone.
        assertNull(WindowRestore.placement(Rectangle(100, 100, 800, 600), listOf(screen)))
        // Outside every screen: the same size, in the middle of the first screen.
        assertEquals(Rectangle(560, 240, 800, 600), WindowRestore.placement(Rectangle(5000, 100, 800, 600), listOf(screen)))
        // Too small to be seen (centring alone never passed the 80 x 80 test): the starting size.
        assertEquals(Rectangle(410, 130, 1100, 820), WindowRestore.placement(Rectangle(100, 100, 0, 0), listOf(screen)))
        assertEquals(Rectangle(410, 130, 1100, 820), WindowRestore.placement(Rectangle(100, 100, 160, 28), listOf(screen)))
        // Never larger than the screen.
        assertEquals(Rectangle(0, 0, 1024, 768), WindowRestore.placement(Rectangle(5000, 0, 1600, 900), listOf(Rectangle(0, 0, 1024, 768))))
        // A display that is off has no size: not counted, the next one takes the window.
        assertEquals(Rectangle(2480, 240, 800, 600), WindowRestore.placement(Rectangle(-9000, 0, 800, 600), listOf(Rectangle(0, 0, 0, 0), Rectangle(1920, 0, 1920, 1080))))
        // No real screen known: left alone.
        assertNull(WindowRestore.placement(Rectangle(-9000, 0, 800, 600), listOf(Rectangle(0, 0, 0, 0))))
        assertNull(WindowRestore.placement(Rectangle(-9000, 0, 800, 600), emptyList()))
    }

    @Test
    fun aWindowShrunkToNothingComesBackUsable() {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait { frame = JFrame("restore").apply { setSize(0, 0); isVisible = true } }
        try {
            SwingUtilities.invokeAndWait { WindowRestore.show(frame) }
            SwingUtilities.invokeAndWait {
                val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
                assertNull(WindowRestore.placement(frame.bounds, screens))
                assertTrue(frame.width >= 320 && frame.height >= 240)
            }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun hiddenMinimisedAndLostWindowComesBack() {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame = JFrame("restore").apply { setSize(400, 300); isVisible = true }
            frame.extendedState = Frame.ICONIFIED
            frame.setLocation(-20_000, -20_000)
            frame.isVisible = false
        }
        try {
            SwingUtilities.invokeAndWait { WindowRestore.show(frame) }
            SwingUtilities.invokeAndWait {
                assertTrue(frame.isVisible)
                assertEquals(0, frame.extendedState and Frame.ICONIFIED)
                val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
                assertFalse(WindowRestore.offScreen(frame.bounds, screens))
            }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
