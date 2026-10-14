package com.notio.desktop

import com.notio.shared.data.AppPaths
import com.notio.shared.data.NotioDatabase
import com.notio.shared.domain.Folder
import com.notio.shared.domain.Note
import com.notio.shared.domain.NoteType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * How long a save takes to come back on a big data folder (the user, 2026-09-28: "the app is a little slow at work"): 2000 notes in
 * 20 folders, pictures, reminders, a full trash. Prints the times; fails only on something far too slow for a desktop.
 */
class LoadSpeedTest {
    @Test
    fun aSaveOnABigDataFolderComesBackQuickly() = runBlocking {
        val home = Files.createTempDirectory("notio-speed").toFile()
        try {
            val now = System.currentTimeMillis()
            NotioDatabase.open(AppPaths(home).database).use { db ->
                db.transaction {
                    repeat(20) { f ->
                        db.upsertFolder(Folder("f$f", "Folder $f", "#3366FF", "SOFT", null, f, null, 0, com.notio.shared.domain.FeaturedLayout.COMPACT, now, now))
                    }
                    repeat(2000) { i ->
                        val type = when (i % 10) { 0 -> NoteType.DRAWING; 1 -> NoteType("PHOTO"); else -> NoteType.TEXT }
                        db.upsertNote(
                            Note(
                                "n$i", "f${i % 20}", "Note $i", type, null, false, false, false, "text of note $i\n".repeat(40), now, now,
                                if (i % 50 == 0) now else null, null, sortOrder = i,
                            ),
                        )
                    }
                }
            }
            // The pictures of drawings and photos are files, as on a real computer.
            repeat(2000) { i -> if (i % 10 < 2) File(home, "attachments/n$i").apply { mkdirs() }.resolve("drawing.png").writeBytes(ByteArray(10)) }
            val controller = NotioController(AppPaths(home))
            try {
                withTimeout(6_000) { while (!controller.state.value.loaded) delay(5) }
                controller.selectFolder("f1")
                withTimeout(6_000) { while (controller.state.value.selectedFolderId != "f1") delay(5) }
                delay(200)
                // A refresh on unchanged data leaves the state as it is (equal values), so each round changes one title first.
                val times = (1..5).map { round ->
                    NotioDatabase.open(AppPaths(home).database).use { other -> other.note("n1")?.let { other.upsertNote(it.copy(title = "Note 1 · $round")) } }
                    val before = controller.state.value
                    val t0 = System.nanoTime()
                    controller.refresh()
                    withTimeout(6_000) { while (controller.state.value === before) delay(1) }
                    (System.nanoTime() - t0) / 1_000_000
                }
                println("refresh on 2000 notes: $times ms")
                assertTrue(times.drop(1).average() < 300, "a refresh took $times ms")
            } finally {
                controller.close()
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
