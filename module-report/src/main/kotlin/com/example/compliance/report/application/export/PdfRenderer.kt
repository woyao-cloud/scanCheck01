package com.example.compliance.report.application.export

import com.lowagie.text.Document
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream

/** PDF 渲染（OpenPDF，spec R-M16-D1）：title/meta 段落 + 每 SheetDef 一个表格；空表跳过。 */
object PdfRenderer {
    fun render(title: String, meta: String, sheets: List<SheetDef>): ByteArray {
        val out = ByteArrayOutputStream()
        val document = Document(PageSize.A4)
        PdfWriter.getInstance(document, out)
        document.open()
        try {
            document.add(Paragraph(title))
            document.add(Paragraph(meta))
            sheets.forEach { def ->
                val headerRow = def.rows.firstOrNull { it.isNotEmpty() } ?: return@forEach
                val table = PdfPTable(headerRow.size)
                def.rows.forEach { row -> if (row.isNotEmpty()) row.forEach { table.addCell(it) } }
                document.add(table)
            }
        } finally {
            document.close()
        }
        return out.toByteArray()
    }
}
