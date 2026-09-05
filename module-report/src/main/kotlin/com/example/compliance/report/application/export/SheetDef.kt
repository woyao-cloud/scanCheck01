package com.example.compliance.report.application.export

/** 导出行模型：SheetDef(name, rows)。结构映射在 ReportExportModel，字节输出在渲染器（spec R-M16-D2）。 */
data class SheetDef(val name: String, val rows: List<List<String>>)
