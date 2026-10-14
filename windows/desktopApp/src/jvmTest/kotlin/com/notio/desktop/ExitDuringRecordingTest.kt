package com.notio.desktop

import com.notio.shared.data.AppPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Exit during a recording (the user, 2026-09-28): Notio ends only after the recording is written into its note. */
class ExitDuringRecordingTest {
    @Test
    fun exitWaitsForASaveAndGoesAtOnceWithoutOne() = runBlocking {
        val home = Files.createTempDirectory("notio-test").toFile()
        val controller = NotioController(AppPaths(home))
        try {
            var ended = false
            controller.finishRecordingsThen { ended = true }
            withTimeout(2_000) { while (!ended) delay(10) }

            ended = false
            PendingSaves.begin()
            controller.finishRecordingsThen { ended = true }
            delay(300)
            assertFalse(ended, "Exit must wait while a recording is being saved")
            PendingSaves.end()
            withTimeout(2_000) { while (!ended) delay(10) }
            assertTrue(ended)
        } finally {
            controller.close()
            home.deleteRecursively()
        }
    }
}
