package com.example.compliance.report.application.export

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M16 (R-M16-D1)：xlsx 渲染——工作表/行/单元格回读 + 表头加粗。POI 在 module-report 编译+测试类路径（implementation 依赖）。 */
class XlsxRendererTest {

    @Test
    fun `renders sheets with rows and bold header`() {
        val bytes = XlsxRenderer.render(
            listOf(
                SheetDef("Summary", listOf(listOf("A", "B"), listOf("1", "2"))),
                SheetDef("Items", listOf(listOf("K", "V"), listOf("x", "y"))),
            ),
        )
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(2, wb.numberOfSheets)
            assertEquals("Summary", wb.getSheetName(0))
            assertEquals("Items", wb.getSheetName(1))
            val summary = wb.getSheet("Summary")
            assertEquals("A", summary.getRow(0).getCell(0).stringCellValue)
            assertEquals("2", summary.getRow(1).getCell(1).stringCellValue)
            assertTrue(summary.getRow(0).getCell(0).cellStyle.font.bold)
        }
    }

    @Test
    fun `empty rows still produces a sheet`() {
        val bytes = XlsxRenderer.render(listOf(SheetDef("Empty", emptyList())))
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals("Empty", wb.getSheetName(0))
            assertEquals(0, wb.getSheet("Empty").lastRowNum.toInt() + 1)
        }
    }
}
