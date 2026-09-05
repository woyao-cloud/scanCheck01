package com.example.compliance.report.application.export

import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/** xlsx 渲染（Apache POI XSSFWorkbook，spec R-M16-D1）：行模型 → 工作表，首行表头加粗。 */
object XlsxRenderer {
    fun render(sheets: List<SheetDef>): ByteArray {
        XSSFWorkbook().use { wb ->
            val headerStyle: CellStyle = wb.createCellStyle().apply {
                val font = wb.createFont().apply { bold = true }
                setFont(font)
            }
            sheets.forEach { def ->
                val sheet = wb.createSheet(def.name)
                def.rows.forEachIndexed { r, values ->
                    val row = sheet.createRow(r)
                    values.forEachIndexed { c, v -> row.createCell(c).setCellValue(v) }
                    if (r == 0) row.forEach { it.cellStyle = headerStyle }
                }
            }
            return ByteArrayOutputStream().use { out -> wb.write(out); out.toByteArray() }
        }
    }
}
