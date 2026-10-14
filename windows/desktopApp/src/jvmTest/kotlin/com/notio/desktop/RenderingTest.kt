package com.notio.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D83: the window is drawn by the processor only where every display adapter is a virtual or basic one. */
class RenderingTest {
    @Test
    fun aVirtualAdapterAloneMeansDrawingByTheProcessor() {
        assertEquals(Rendering.SOFTWARE, Rendering.autoFor(listOf("VMware SVGA 3D")))
        assertEquals(Rendering.SOFTWARE, Rendering.autoFor(listOf("Microsoft Basic Display Adapter")))
        assertEquals(Rendering.SOFTWARE, Rendering.autoFor(listOf("Microsoft Hyper-V Video", "Microsoft Remote Display Adapter")))
    }

    @Test
    fun aRealGraphicsCardKeepsTheGraphicsCard() {
        assertEquals(Rendering.HARDWARE, Rendering.autoFor(listOf("NVIDIA GeForce RTX 3060")))
        assertEquals(Rendering.HARDWARE, Rendering.autoFor(listOf("Intel(R) UHD Graphics 620", "Microsoft Basic Render Driver")))
        // Nothing known about the adapters: Compose's own choice, as before.
        assertEquals(Rendering.HARDWARE, Rendering.autoFor(emptyList()))
        assertTrue(Rendering.isVirtual("VirtualBox Graphics Adapter (WDDM)"))
        assertFalse(Rendering.isVirtual("AMD Radeon(TM) Graphics"))
    }

    @Test
    fun theChoiceOfThePersonIsKept() {
        val dir = Files.createTempDirectory("notio-test").toFile()
        try {
            assertEquals(Rendering.AUTO, Rendering.mode(dir))
            Rendering.setMode(dir, Rendering.DIRECT3D)
            assertEquals(Rendering.DIRECT3D, Rendering.mode(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
