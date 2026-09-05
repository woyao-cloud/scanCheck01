package com.example.compliance.report.application.export

import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M16 (R-M16-D1)：PDF 渲染——%PDF 魔数 + 可解析 + 内容含 title/表单元格。OpenPDF 在 module-report 测试类路径。 */
class PdfRendererTest {

    @Test
    fun `renders a valid pdf with title meta and tables`() {
        val bytes = PdfRenderer.render(
            title = "Report #3 (COMPLIANCE)",
            meta = "template v1",
            sheets = listOf(
                SheetDef("Summary", listOf(listOf("A", "B"), listOf("1", "2"))),
                SheetDef("Items", listOf(listOf("K", "V"))),
            ),
        )
        assertTrue(bytes.size > 500)
        assertEquals(0x25.toByte(), bytes[0]) // %
        assertEquals(0x50.toByte(), bytes[1]) // P
        assertEquals(0x44.toByte(), bytes[2]) // D
        assertEquals(0x46.toByte(), bytes[3]) // F
        val reader = PdfReader(bytes)
        try {
            assertEquals(1, reader.numberOfPages)
            // OpenPDF 1.3.43：PdfTextExtractor 为实例 API（iText 5 的静态 getTextFromPage 已移除）
            val text = PdfTextExtractor(reader).getTextFromPage(1)
            assertTrue("Report #3 (COMPLIANCE)" in text)
            assertTrue("A" in text)
        } finally {
            reader.close()
        }
    }

    @Test
    fun `empty sheets render a valid empty pdf`() {
        val bytes = PdfRenderer.render("T", "m", listOf(SheetDef("Data", emptyList())))
        assertTrue(bytes.size > 500)
        assertEquals(0x25.toByte(), bytes[0])
    }
}
