package com.notio.desktop

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.notio.shared.data.AppPaths
import com.notio.shared.domain.NoteType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * The picture of a drawing and of a blueprint on its card (Android D160 / D161; user, 2026-09-27: "the blueprint is not seen at all, the
 * drawing without its background"): a blueprint with shapes only is drawn first, both lie on white paper in the sheet's proportions.
 * Saves `build/screenshots/2d-drawing-cards.png` for looking through.
 */
class DrawingCardTest {
    @Test
    fun aBlueprintAndADrawingShowTheirPicturesOnTheCards() = runBlocking {
        val home = Files.createTempDirectory("notio-cards").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var loaded = false
            controller.loadDemo { it.getOrThrow(); loaded = true }
            waitFor { loaded }
            var made = false
            controller.createNote("demo-work", "Blueprint", NoteType.BLUEPRINT, { error(it.toString()) }) { made = true }
            waitFor { made }
            controller.back()
            controller.selectFolder("demo-work")
            waitFor { controller.state.value.notes.any { it.type == NoteType.BLUEPRINT } && controller.state.value.notes.any { it.id == "demo-drawing" } }
            val blueprint = controller.state.value.notes.first { it.type == NoteType.BLUEPRINT }
            val doc = VectorDocument(shapes = listOf(Shape("r1", "RECT", listOf(Point(100f, 100f), Point(900f, 700f)), strokeColor = 0xFF2962FF.toInt(), strokeWidth = 12f)))
            File(home, "attachments/${blueprint.id}").apply { mkdirs() }.resolve("vector.json").writeBytes(doc.encode())

            val blueprintPicture = assertNotNull(controller.drawingPicture(blueprint), "a blueprint with shapes only has a picture")
            assertEquals(DrawingRenderer.WIDTH.toFloat() / DrawingRenderer.HEIGHT, blueprintPicture.width.toFloat() / blueprintPicture.height, 0.01f)
            assertTrue(maxOf(blueprintPicture.width, blueprintPicture.height) <= 480, "made small")
            val drawing = controller.state.value.notes.first { it.id == "demo-drawing" }
            assertNotNull(controller.drawingPicture(drawing), "the drawing's PNG")
            // Kept: the same picture again until the note changes.
            assertTrue(controller.drawingPicture(blueprint) === controller.drawingPicture(blueprint))

            val scene = ImageComposeScene(1100, 800, Density(1f)) {
                NotioApp(controller, WindowChrome.none(), HomeActions({}, {}, {}, {}, {}), SnackbarHostState())
            }
            scene.render(0)
            delay(300)
            scene.render(1_000_000_000)
            delay(300)
            scene.render(3_000_000_000)
            delay(300)
            scene.render(4_000_000_000)
            val image = scene.render(6_000_000_000)
            val out = File("build/screenshots/2d-drawing-cards.png").apply { parentFile.mkdirs() }
            out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(300) { if (condition()) return; delay(50) }
        error("timed out")
    }
}
