package com.notebudget.app.domain.model

import com.notebudget.app.data.transfer.BackupCrypto
import com.notebudget.app.data.transfer.TransferError
import com.notebudget.app.data.transfer.TransferException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FormatsAndOrderTest {
    private val utc: ZoneId = ZoneOffset.UTC
    // 2026-09-20 15:07:09 UTC
    private val moment = 1_789_916_829_000L

    private fun note(id: String, title: String, created: Long = 0, updated: Long = 0, pinned: Boolean = false, pinnedAt: Long = 0, order: Int = 0, color: String? = null, type: String = "TEXT") =
        Note(id, "f", title, NoteType(type), color, pinned, false, false, null, created, updated, pinnedAt = pinnedAt, sortOrder = order)

    private fun titles(list: List<Note>) = list.map { it.title }

    // ---- date and time format ----

    @Test
    fun `a fixed date and time format is written the chosen way`() {
        val f = DateTimeFormats(DateStyle.DMY_DOT, TimeStyle.H24_SEC)
        assertEquals("20.09.2026", f.formatDate(moment, utc))
        assertEquals("15:07:09", f.formatTime(moment, utc))
        assertEquals("20.09.2026 15:07:09", f.format(moment, utc))
        assertEquals("09/20/2026", DateTimeFormats(DateStyle.MDY_SLASH).formatDate(moment, utc))
        assertEquals("03:07 PM", DateTimeFormats(time = TimeStyle.H12).formatTime(moment, utc, java.util.Locale.US))
    }

    @Test
    fun `formats survive storage and a damaged value gives the defaults`() {
        val f = DateTimeFormats(DateStyle.YMD_DASH, TimeStyle.H12)
        assertEquals(f, DateTimeFormats.decode(f.encode()))
        assertEquals(DateTimeFormats(), DateTimeFormats.decode("not json"))
        assertEquals(DateTimeFormats(), DateTimeFormats.decode(null))
        assertEquals(DateTimeFormats(DateStyle.DMY_DASH), DateTimeFormats.decode("""{"date":"DMY_DASH","future":1}"""))
    }

    // ---- export file names ----

    @Test
    fun `an export name is built from the parts the user chose`() {
        val plain = ExportNaming()
        assertEquals("Trip_Photo 1", plain.baseName("Trip", "Photo 1", 0, 0, moment, utc))
        assertEquals("Trip", plain.baseName("Trip", null, 0, 0, moment, utc))
        val stamped = ExportNaming(useItemTitle = false, useDate = true, useTime = true)
        assertEquals("Trip_2026-09-20_15-07-09", stamped.baseName("Trip", "x", 0, 0, moment, utc))
        val solid = ExportNaming(useNoteTitle = false, useDate = true, useTime = true, dateOrder = DateOrder.DMY, dateSeparator = StampSeparator.SOLID, timeSeparator = StampSeparator.SOLID, twelveHour = true, joiner = NameJoiner.DASH)
        assertEquals("x-20092026-030709PM", solid.baseName("Trip", "x", 0, 0, moment, utc))
        val dots = ExportNaming(useNoteTitle = false, useTime = true, timeSeparator = StampSeparator.DOT)
        assertEquals("15.07.09", dots.baseName("Trip", null, 0, 0, moment, utc))
    }

    @Test
    fun `the stamp comes from the export, the creation or the change`() {
        val n = ExportNaming(useNoteTitle = false, useDate = true, stampSource = StampSource.CREATED)
        assertEquals("1970-01-01", n.baseName("t", null, 1000, 5_000_000_000, moment, utc))
        assertEquals("1970-02-27", n.copy(stampSource = StampSource.MODIFIED).baseName("t", null, 1000, 5_000_000_000, moment, utc))
    }

    @Test
    fun `characters a file system refuses never reach the name and an empty name gets a fallback`() {
        assertEquals("a_b_c", ExportNaming().baseName("a/b:c", null, 0, 0, moment, utc))
        assertEquals("note", ExportNaming(useNoteTitle = false, useItemTitle = false).baseName("x", null, 0, 0, moment, utc))
    }

    // ---- ordering ----

    @Test
    fun `the note pinned last comes first among the pinned ones`() {
        val list = listOf(note("1", "old pin", pinned = true, pinnedAt = 10), note("2", "plain"), note("3", "new pin", pinned = true, pinnedAt = 20))
        assertEquals(listOf("new pin", "old pin", "plain"), titles(Sorting.notes(list, SortOption.NOTES_DEFAULT)))
    }

    @Test
    fun `my order follows the dragged positions`() {
        val list = listOf(note("1", "c", order = 2), note("2", "a", order = 0), note("3", "b", order = 1))
        assertEquals(listOf("a", "b", "c"), titles(Sorting.notes(list, SortOption(SortKey.MANUAL, false))))
    }

    @Test
    fun `by day only ignores the time and by time of day ignores the day`() {
        val day = 86_400_000L
        val a = note("a", "a", created = 3 * day + 9 * 3_600_000L)
        val b = note("b", "b", created = 1 * day + 20 * 3_600_000L)
        val c = note("c", "c", created = 3 * day + 1 * 3_600_000L)
        val list = listOf(a, b, c)
        // Same day 3: a and c are ordered by name, b (day 1) is earlier.
        assertEquals(listOf("b", "a", "c"), titles(Sorting.notes(list, SortOption(SortKey.CREATED_DATE, false), zone = utc)))
        // Time of day: c (01:00), a (09:00), b (20:00).
        assertEquals(listOf("c", "a", "b"), titles(Sorting.notes(list, SortOption(SortKey.CREATED_TIME, false), zone = utc)))
    }

    @Test
    fun `notes with the same tags sit together and untagged ones are last`() {
        val list = listOf(note("1", "x"), note("2", "y"), note("3", "z"))
        val tags = mapOf("1" to listOf(Tag("t2", "work")), "3" to listOf(Tag("t1", "home")))
        assertEquals(listOf("z", "x", "y"), titles(Sorting.notes(list, SortOption(SortKey.TAGS, false), tags)))
        assertEquals(listOf("x", "z", "y"), titles(Sorting.notes(list, SortOption(SortKey.TAGS, true), tags)))
    }

    @Test
    fun `colours group by hue and greys come after the colours`() {
        val red1 = Sorting.colorRank("#FF0000")!!
        val red2 = Sorting.colorRank("#FF0000")!!
        val green = Sorting.colorRank("#00FF00")!!
        val blue = Sorting.colorRank("#0000FF")!!
        val grey = Sorting.colorRank("#808080")!!
        assertEquals(red1, red2)
        assertTrue(red1 < green && green < blue && blue < grey)
        assertEquals(null, Sorting.colorRank(null))
    }

    @Test
    fun `pinned folders stay first and the number of notes can order folders`() {
        fun folder(id: String, name: String, pinned: Boolean = false, pinnedAt: Long = 0) =
            Folder(id, name, "#3366FF", "SOFT", null, 0, null, 0, FeaturedLayout.COMPACT, 0, 0, isPinned = pinned, pinnedAt = pinnedAt)
        val list = listOf(folder("a", "A"), folder("b", "B", true, 5), folder("c", "C", true, 9))
        assertEquals(listOf("C", "B", "A"), Sorting.folders(list, SortOption.FOLDERS_DEFAULT).map { it.name })
        val counts = mapOf("a" to 5, "b" to 1, "c" to 3)
        assertEquals(listOf("C", "B", "A"), Sorting.folders(list, SortOption(SortKey.COUNT, true), counts).map { it.name })
        assertEquals(listOf("A"), Sorting.folders(listOf(list[0]), SortOption(SortKey.COUNT, true), counts).map { it.name })
    }

    // ---- protected backups ----

    private fun protect(data: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password).use { it.write(data) }
        return out.toByteArray()
    }

    @Test
    fun `a protected backup opens with its password and only with it`() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val file = protect(data, "s3cret")
        assertTrue(BackupCrypto.isProtected(file.copyOf(8)))
        assertFalse(BackupCrypto.isProtected(data.copyOf(8)))
        assertArrayEquals(data, BackupCrypto.decrypt(ByteArrayInputStream(file), "s3cret").readBytes())
        try {
            BackupCrypto.decrypt(ByteArrayInputStream(file), "wrong")
            fail("a wrong password must be refused")
        } catch (e: TransferException) {
            assertEquals(TransferError.WRONG_PASSWORD, e.error)
        }
    }

    @Test
    fun `a cut or changed protected backup is refused and an empty one works`() {
        val file = protect(ByteArray(150_000) { 7 }, "pw")
        try {
            BackupCrypto.decrypt(ByteArrayInputStream(file.copyOf(file.size - 30)), "pw").readBytes()
            fail("a cut file must be refused")
        } catch (e: TransferException) {
            assertEquals(TransferError.CORRUPT, e.error)
        }
        val flipped = file.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        try {
            BackupCrypto.decrypt(ByteArrayInputStream(flipped), "pw").readBytes()
            fail("a changed file must be refused")
        } catch (e: TransferException) {
            assertEquals(TransferError.CORRUPT, e.error)
        }
        assertEquals(0, BackupCrypto.decrypt(ByteArrayInputStream(protect(ByteArray(0), "pw")), "pw").readBytes().size)
    }

    @Test
    fun `peek keeps the beginning of the stream`() {
        val (stream, isProtected) = BackupCrypto.peek(ByteArrayInputStream("PK\u0003\u0004hello world".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(isProtected)
        assertEquals("PK\u0003\u0004hello world", String(stream.readBytes(), Charsets.ISO_8859_1))
    }
}
