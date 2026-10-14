package com.notio.desktop

import com.notio.common.AppLogExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android D303: the app log as a JSON, TXT or LOG file (the code shared by the phone and the desktop). */
class AppLogExportTest {
    private val log = "2026-10-01 10:27:53.245 WARN     window: the window was busy 994 ms, at:\n" +
        "    java.lang.IllegalStateException: \"quoted\"\n" +
        "        at com.notio.desktop.Main.kt:1\n" +
        "2026-10-01 10:27:59.406 INFO     app: start 0.9.27, Windows 11 10.0\n" +
        "a line of another shape\n"

    @Test
    fun theEntriesKeepTheirErrorLinesAndOddLinesStayAsTheyAre() {
        val entries = AppLogExport.entries(log)
        assertEquals(3, entries.size)
        assertEquals("WARN", entries[0].level)
        assertEquals("window", entries[0].tag)
        assertEquals("the window was busy 994 ms, at:", entries[0].message)
        assertEquals(2, entries[0].details.size)
        assertEquals("2026-10-01 10:27:59.406", entries[1].time)
        assertEquals("a line of another shape", entries[2].raw)
    }

    @Test
    fun jsonIsWellFormedAndTextIsTheLogItself() {
        val json = String(AppLogExport.export(log, AppLogExport.Type.JSON, "0.9.33", "Windows 11", "2026-10-04T12:00:00Z"), Charsets.UTF_8)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("\"notio-log\"", parsed["format"].toString())
        val entries = parsed["entries"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, entries.size)
        assertTrue(entries[0].toString().contains("quoted"))
        assertEquals(log, String(AppLogExport.export(log, AppLogExport.Type.TXT, "0.9.33", "Windows 11", "x"), Charsets.UTF_8))
        assertEquals(log, String(AppLogExport.export(log, AppLogExport.Type.LOG, "0.9.33", "Windows 11", "x"), Charsets.UTF_8))
        assertEquals("Notio-log-0.9.33.json", AppLogExport.fileName("0.9.33", AppLogExport.Type.JSON))
    }
}
