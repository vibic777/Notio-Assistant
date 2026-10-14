package com.notebudget.app.feature.drawing

import android.graphics.BitmapFactory
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.notebudget.app.NoteBudgetApplication
import com.notebudget.app.domain.model.DomainResult
import com.notebudget.app.domain.model.Folder
import com.notebudget.app.domain.model.Note
import com.notebudget.app.domain.model.NoteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D294 (owner, 2026-10-01: "in a drawing and a blueprint, Back saves the step before, not the current one"): the last stroke
 * must be on disk after the screen goes away, not only the autosave of the step before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingSaveTest {
    private val app = ApplicationProvider.getApplicationContext<NoteBudgetApplication>()
    private val container get() = app.container

    private fun note(): Note = runBlocking {
        val folder = (container.folderUseCases.create("F", "#3366FF", "SOFT") as DomainResult.Success<Folder>).value
        (container.noteUseCases.create(folder.id, "Picture", NoteType.DRAWING) as DomainResult.Success<Note>).value
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun stroke(vm: DrawingViewModel, y: Float) {
        vm.pointerDown(100f, y)
        for (x in 110..600 step 10) vm.pointerMove(x.toFloat(), y)
        vm.pointerUp()
    }

    @Test
    fun `the stroke drawn last is saved when the screen goes away right after it`() {
        val note = note()
        val store = ViewModelStore()
        val vm = DrawingViewModel(note.id, container.drawingStore, container.vectorStore, container.noteUseCases, container.appScope)
        store.put("drawing", vm)
        waitFor { vm.loaded.value }
        vm.chooseTool(DrawingTool.PENCIL)

        stroke(vm, 200f)
        vm.saveNow() // the autosave of the first step
        waitFor { runBlocking { container.drawingStore.read(note.id) } != null }
        val firstStep = runBlocking { container.drawingStore.read(note.id) }!!

        stroke(vm, 400f) // the step that used to be lost
        vm.saveNow() // what the screen does when it stops on Back
        store.clear() // ...and the ViewModel goes away at once

        waitFor { !runBlocking { container.drawingStore.read(note.id) }!!.contentEquals(firstStep) }
        val saved = runBlocking { container.drawingStore.read(note.id) }
        assertNotNull(saved)
        assertNotEquals("the last stroke is on disk, not only the step before it", firstStep.toList(), saved!!.toList())
        // And it really has the second line: a pixel on it is drawn.
        val bitmap = BitmapFactory.decodeByteArray(saved, 0, saved.size)
        assertNotNull(bitmap)
    }

    @Test
    fun `a single first step is there when the note is opened again right after it was left`() {
        // D297 (owner, 2026-10-04: "one step, leave, and it is not saved"): the write of the step was still running when the note
        // was opened again, the old files were read, and the next save put that older picture over the step.
        val note = note()
        val store = ViewModelStore()
        val vm = DrawingViewModel(note.id, container.drawingStore, container.vectorStore, container.noteUseCases, container.appScope)
        store.put("drawing", vm)
        waitFor { vm.loaded.value }
        vm.chooseTool(DrawingTool.PENCIL)
        stroke(vm, 200f)
        store.clear() // Back at once, before any autosave

        val again = DrawingViewModel(note.id, container.drawingStore, container.vectorStore, container.noteUseCases, container.appScope)
        waitFor { again.loaded.value }
        assertNotEquals("the stroke is in the picture that was loaded", 0, again.working.getPixel(300, 200) ushr 24)
    }

    @Test
    fun `a single shape of a blueprint is saved at once`() {
        val note = runBlocking {
            val folder = (container.folderUseCases.create("B", "#3366FF", "SOFT") as DomainResult.Success<Folder>).value
            (container.noteUseCases.create(folder.id, "Plan", NoteType.BLUEPRINT) as DomainResult.Success<Note>).value
        }
        val vm = DrawingViewModel(note.id, container.drawingStore, container.vectorStore, container.noteUseCases, container.appScope, DrawingKind.BLUEPRINT)
        waitFor { vm.loaded.value }
        vm.textAt.value = com.notebudget.app.feature.drawing.Point(100f, 100f)
        vm.placeText("A", 40f, 0)
        // No Back, no stop of the screen, no wait for an autosave: the step itself is the save.
        waitFor { runBlocking { container.vectorStore.read(note.id) } != null }
        assertNotNull(runBlocking { container.vectorStore.read(note.id) })
    }
}
