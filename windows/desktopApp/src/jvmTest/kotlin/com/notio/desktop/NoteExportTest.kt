package com.notio.desktop

import com.notio.shared.transfer.PdfBlock
import com.notio.shared.transfer.PdfBoxWriter
import com.notio.shared.transfer.PdfStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop side of a single-note export (AGENT.md D27): the app's Roboto in PDFs, drawings flattened to PNG and JPEG. */
class NoteExportTest {
    @Test
    fun `the packaged Roboto is found and embedded`() {
        assertNotNull(PdfBoxWriter.classpathRoboto())
        val bytes = PdfBoxWriter().write(listOf(PdfBlock("Покупки", PdfStyle.TITLE), PdfBlock("Молоко — 2 л")))
        assertEquals("%PDF", String(bytes, 0, 4))
        org.apache.pdfbox.Loader.loadPDF(bytes).use { doc ->
            val page = doc.getPage(0)
            val fonts = page.resources.fontNames.map { page.resources.getFont(it).name }
            assertTrue(fonts.toString(), fonts.all { "Roboto" in it })
            assertTrue(org.apache.pdfbox.text.PDFTextStripper().getText(doc).contains("Молоко — 2 л"))
        }
    }

    @Test
    fun `a drawing is flattened to PNG and re-encoded as JPEG`() {
        DrawingRenderer.useFonts()
        val png = DrawingRenderer.flatten(null, emptyList())
        assertEquals(0x89.toByte(), png[0])
        val jpeg = DrawingRenderer.pngToJpeg(png)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
    }
}
