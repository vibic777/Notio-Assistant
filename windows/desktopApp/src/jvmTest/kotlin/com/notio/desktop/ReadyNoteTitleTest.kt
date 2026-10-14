package com.notio.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadyNoteTitleTest {

    @Test
    fun anEmptyFolderStartsAtOne() {
        assertEquals("Аудио 1", ReadyNoteTitle.next("Аудио", emptyList()))
    }

    @Test
    fun theSmallestFreeNumberIsTaken() {
        assertEquals("Аудио 3", ReadyNoteTitle.next("Аудио", listOf("Аудио 1", "Аудио 2", "Аудио 4")))
        assertEquals("Аудио 5", ReadyNoteTitle.next("Аудио", listOf("Аудио 4", "Аудио 2", "Аудио 3", "Аудио 1")))
    }

    @Test
    fun caseAndSpacesAroundAreIgnored_otherKindsDoNotCount() {
        assertEquals(
            "Запись камеры 2",
            ReadyNoteTitle.next("Запись камеры", listOf(" запись КАМЕРЫ 1 ", "Бюджет 2", "Запись камеры 1 копия")),
        )
    }
}
