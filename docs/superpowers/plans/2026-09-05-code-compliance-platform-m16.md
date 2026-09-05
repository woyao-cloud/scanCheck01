# M16 报表导出 + 审计日志查询 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 输出面交付两件事——① 报告快照二进制导出 **xlsx（Apache POI）+ pdf（OpenPDF）**，复用既有 json/html 导出端点，导出动作写审计 `REPORT_EXPORT`；② **审计日志查询 API** `/api/v1/audit-logs`（ADMIN/AUDITOR），多可选 AND 过滤 + 分页（镜像 C2 硬化）。

**Architecture:** 导出采用「行模型 + 双渲染器」DRY 设计（spec R-M16-D2）：`ReportExportModel.sheetsFor(snapshotType, payload)` 纯函数把不可变快照 payload → `List<SheetDef(name, rows: List<List<String>>)>`（类型感知：SCAN_SUMMARY 单表 / COMPLIANCE Summary+Items 双表 / TREND 时序表 / 兜底键值表；根元素对象/数组双形态），`XlsxRenderer`（POI XSSFWorkbook）与 `PdfRenderer`（OpenPDF）只负责把行模型吐成字节——结构映射与输出解耦，两渲染器零重复。`ReportExportService` 编排：只读载入快照 → 渲染 → 写审计 `REPORT_EXPORT`（REQUIRES_NEW 既有模式），返回 `ExportArtifact(filename, bytes)`；控制器对 xlsx/pdf 返回二进制 `ResponseEntity<Any>`（Content-Type + `Content-Disposition: attachment`），json/html 保持 `{data:...}` 封装不变。审计查询按 spec R-M16-D5/D6：`AuditQueryService`（module-common，临近审计领域）+ JPA `Specification` 多可选 AND + 空过滤 null spec（全量），`AuditLogController`（module-admin，跨模块只读聚合宿主）路径 `/api/v1/audit-logs` 避开 `/admin/**` 路径守卫使 AUDITOR 可达，方法级 `@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")` 双保险。

**Tech Stack:** Apache POI `poi-ooxml`（**版本目录显式 pin `5.2.5`**——Spring Boot 3.3.5 BOM 不管理 POI，grep 实测 0 条目，原「BOM 管版本」假设有误，Task 16.1 实现反馈修正）；OpenPDF `com.github.librepdf:openpdf`（**不在 Boot BOM，版本目录显式 pin `1.3.43`**）；Spring Data JPA `Specification`（module-common 已有 `api(spring-boot-starter-data-jpa)`）；JUnit 5 + MockK + kotlin-test（convention plugin 全局 test 依赖）。无 DDL、无新配置。

**Spec:** `docs/superpowers/specs/2026-09-05-code-compliance-platform-m16-design.md`（本计划从 spec 论证；执行者须同时读 spec 与计划，冲突以 spec 为权威）

## Global Constraints

- **依赖边界**：module-report 增 `implementation(libs.poi.ooxml)`（**版本目录 pin 5.2.5**——Spring Boot 3.x BOM 不再管理 POI）+ `implementation(libs.openpdf)`（版本目录 pin 1.3.43，catalog 增 `[versions] poi/openpdf` + 两 library 条目）；module-common 增 **AuditQueryService/AuditLogFilter 两个类**（既有 spring-data-jpa api 依赖已足，零新依赖——「M-series frozen」指运行依赖，不含类）；module-admin 复用既有对 module-common 的依赖；**app-server 不引入 POI**（module-report 的 `implementation` 不透传——集成测试做字节级魔数验证而非 XSSFWorkbook 回读）。
- **行模型契约（R-M16-D2）**：`data class SheetDef(val name: String, val rows: List<List<String>>)`；`ReportExportModel.sheetsFor(snapshotType, payload): List<SheetDef>`——SCAN_SUMMARY → 单表 `ScanSummary`；COMPLIANCE → 双表 `Summary` + `Items`；TREND → 单表 `Trend`（**payload 根为 JSON 数组**，`root.isArray` 分支）；未知类型/非法 payload → 兜底 `Data`（对象→Key/Value，数组→Index/Value，其余→仅表头）。`score` 等可空数值经 `cell()` 空值→空串（`node.isNull` 检查，避免 `asText()` 产字面 "null"）；`bySeverity` 键大小写双态防御（`get(key) ?: get(key.lowercase())`），缺省补 "0"。
- **导出产物（R-M16-P1，spec §3.2 精化）**：`ReportExportService` 返回 `data class ExportArtifact(val filename: String, val bytes: ByteArray)`——spec §3.2 的 `exportXlsx(id, actorId): ByteArray` 缺文件名；控制器 Content-Disposition 需要 `snapshotType`，故服务返回产物对象（filename = `report-<id>-<snapshotType.lowercase()>.<ext>`）。
- **PDF 渲染签名（R-M16-P2）**：`PdfRenderer.render(title: String, meta: String, sheets: List<SheetDef>): ByteArray`——title/meta 是 PDF 段落（非表行）；每 SheetDef 一个 `PdfPTable`（首非空行定列数，空表跳过）。
- **导出语义（R-M16-D3/D4）**：xlsx/pdf → 200 + Content-Type + `Content-Disposition: attachment; filename="..."` + 字节体；未知 format → `throw BusinessException(400, "unsupported export format: $format")`（GlobalExceptionHandler → 400 `{code:400}`）；json/html 分支返回 `ResponseEntity<Any>(ApiResponse.ok(...), HttpStatus.OK)` **保持 `{data:...}` 形状不变**（既有测试 `$.data.findingCount` 不破坏）；导出动作写 `auditService.record(action="REPORT_EXPORT", module="report", resourceType="report_snapshot", resourceId=<id>, userId=actorId, detail=JSON)`——actorId 取 `(authentication?.principal as? AuthPrincipal)?.userId ?: 1L`（既有 generate 先例）；detail 必须合法 JSON（Ruling #34 先例）。
- **审计查询（R-M16-D5/D6/D7）**：`AuditQueryService.search(filter, page, size): Page<AuditLog>`——负 page → 400，`size.coerceIn(1, 100)`，`Sort.by(DESC, "id")`，空过滤 → **null Specification**（`findAll(null, pageable)` = 全量，镜像 C2 硬化）；过滤字段 module/action/userId/resourceType/resourceId/occurredAt[from,to] 全可选 AND；`AuditLogRepository` 增 `JpaSpecificationExecutor<AuditLog>`；`AuditLogController` 位于 module-admin `admin/api/`，`@RequestMapping("/api/v1/audit-logs")` + `@GetMapping` + 方法级 `@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")`（SecurityConfig 无此路径规则 → 认证即可 + 方法门控，AUDITOR 不被 `/admin/**` 的 hasRole("ADMIN") 拦截）。
- **视图位置与形状（R-M16-P5）**：`AuditLogView` 在 module-admin `admin/api/AuditLogView.kt`（spec §3.3 逐字位置 `module-admin/admin/api/`）；字段 `(id, userId, action, module, resourceType, resourceId, detail, ip, occurredAt)`——**含 `detail: String?`（spec §3.3 明文：JSONB 原样字符串，客户端自行解析）**；`AuditQueryService` 返回领域 `Page<AuditLog>`，控制器映射 `PageView<AuditLogView>`（`PageView<T>` = `PageResponse<T>` typealias，`{items,page,size,total}`）。
- **集成测试播种（R-M16-P3/P4）**：COMPLIANCE 快照经仓库直接播种固定 payload（生成链路需评估数据，重；生成已由 M12 覆盖）；模板经生命周期端点 draft+publish **COMPLIANCE 类型**（避免触碰 M12 C6 独占的 SCAN_SUMMARY/TREND 模板写入），templateId 从 draft 响应 `$.data.templateId` 读（`TemplateVersionView` 含该字段，勿硬编码 1L）；集成测试 **不 import POI**（app-server 编译类路径无 poi-ooxml）——xlsx 验 ZIP 魔数 `0x50 0x4B 0x03 0x04`，pdf 验 `%PDF` 魔数 + 非空；工作簿内容正确性由 Task 16.1 单测锚定。
- **共享 Testcontainers**：max_connections=300 保持（改不得）；集成测试数据前缀 `M16-*`（`M16RP` 项目名、`M16-IT-*` itemCode、`M16_AUDIT_*` 审计 module 标记、`m16-*` 用户名）与既有里程碑不相交。
- **红线**：不硬编码合规规则；历史扫描结果不可改；无 DDL（审计查询复用 audit_log 表与既有索引，零迁移）。

---

### Task 16.1: 导出核心 — 依赖 + 行模型 + 双渲染器 + 单测

**Files:**
- Modify: `gradle/libs.versions.toml`（`[versions]` 增 `openpdf`；`[libraries]` 增 `poi-ooxml` + `openpdf`）
- Modify: `module-report/build.gradle.kts`（增两 implementation）
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/export/SheetDef.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/export/ReportExportModel.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/export/XlsxRenderer.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/export/PdfRenderer.kt`
- Test: Create `module-report/src/test/kotlin/com/example/compliance/report/application/export/ReportExportModelTest.kt`
- Test: Create `module-report/src/test/kotlin/com/example/compliance/report/application/export/XlsxRendererTest.kt`
- Test: Create `module-report/src/test/kotlin/com/example/compliance/report/application/export/PdfRendererTest.kt`

**Interfaces:**
- Produces: `SheetDef(name: String, rows: List<List<String>>)`；`ReportExportModel.sheetsFor(snapshotType: String, payload: String): List<SheetDef>`（Task 16.2 `ReportExportService` 与 Task 16.4 集成测试消费）；`XlsxRenderer.render(sheets: List<SheetDef>): ByteArray`（对象，无状态）；`PdfRenderer.render(title: String, meta: String, sheets: List<SheetDef>): ByteArray`。依赖：`libs.poi.ooxml`（`org.apache.poi:poi-ooxml`，**无 version.ref**——Boot BOM 管）；`libs.openpdf`（`com.github.librepdf:openpdf`，version.ref=openpdf=1.3.43）。

- [ ] **Step 1: 写失败测试（ReportExportModelTest）**

创建 `module-report/src/test/kotlin/com/example/compliance/report/application/export/ReportExportModelTest.kt`：

```kotlin
package com.example.compliance.report.application.export

import kotlin.test.Test
import kotlin.test.assertEquals

/** M16 (R-M16-D2)：payload → 行模型——类型感知布局 + 根元素对象/数组双形态 + 兜底。 */
class ReportExportModelTest {

    @Test
    fun `scan summary maps severity and headers`() {
        val payload = """
            {"scanTaskId":77,"engine":"SEMGREP","status":"SUCCESS","findingCount":3,
             "bySeverity":{"CRITICAL":1,"HIGH":0,"MEDIUM":2,"LOW":0}}
        """.trimIndent()
        val sheets = ReportExportModel.sheetsFor("SCAN_SUMMARY", payload)
        assertEquals(1, sheets.size)
        assertEquals("ScanSummary", sheets[0].name)
        val rows = sheets[0].rows
        assertEquals(listOf("ScanTaskId", "Engine", "Status", "FindingCount", "Critical", "High", "Medium", "Low"), rows[0])
        assertEquals(listOf("77", "SEMGREP", "SUCCESS", "3", "1", "0", "2", "0"), rows[1])
    }

    @Test
    fun `compliance maps summary and items`() {
        val payload = """
            {"projectId":5,"evaluationId":7,"score":88.5,"totalItems":2,"passed":1,"failed":1,
             "warning":0,"manual":0,"skipped":0,"checklistVersionId":9,
             "items":[{"itemCode":"IT-1","result":"PASS","findingCount":0},
                      {"itemCode":"IT-2","result":"FAIL","findingCount":3}]}
        """.trimIndent()
        val sheets = ReportExportModel.sheetsFor("COMPLIANCE", payload)
        assertEquals(2, sheets.size)
        assertEquals("Summary", sheets[0].name)
        assertEquals("Items", sheets[1].name)
        assertEquals(listOf("5", "7", "88.5", "2", "1", "1", "0", "0", "0", "9"), sheets[0].rows[1])
        assertEquals(listOf("ItemCode", "Result", "FindingCount"), sheets[1].rows[0])
        assertEquals(listOf("IT-1", "PASS", "0"), sheets[1].rows[1])
        assertEquals(listOf("IT-2", "FAIL", "3"), sheets[1].rows[2])
    }

    @Test
    fun `trend maps top-level array and null score to empty`() {
        val payload = """[{"evaluatedAt":"2026-09-01T00:00:00Z","score":null,"failed":2},
                          {"evaluatedAt":"2026-09-02T00:00:00Z","score":80.5,"failed":1}]"""
        val sheets = ReportExportModel.sheetsFor("TREND", payload)
        assertEquals(1, sheets.size)
        assertEquals("Trend", sheets[0].name)
        val rows = sheets[0].rows
        assertEquals(listOf("EvaluatedAt", "Score", "Failed"), rows[0])
        assertEquals(listOf("2026-09-01T00:00:00Z", "", "2"), rows[1])
        assertEquals(listOf("2026-09-02T00:00:00Z", "80.5", "1"), rows[2])
    }

    @Test
    fun `unknown type with object payload falls back to key value`() {
        val sheets = ReportExportModel.sheetsFor("UNKNOWN", """{"a":1,"b":null}""")
        val rows = sheets[0].rows
        assertEquals(listOf("Key", "Value"), rows[0])
        assertEquals(listOf("a", "1"), rows[1])
        assertEquals(listOf("b", ""), rows[2])
    }

    @Test
    fun `unknown type with array payload falls back to index value`() {
        val sheets = ReportExportModel.sheetsFor("UNKNOWN", """["x","y"]""")
        val rows = sheets[0].rows
        assertEquals(listOf("Index", "Value"), rows[0])
        assertEquals(listOf("0", "x"), rows[1])
        assertEquals(listOf("1", "y"), rows[2])
    }

    @Test
    fun `invalid payload with scan type renders empty scan sheet`() {
        val sheets = ReportExportModel.sheetsFor("SCAN_SUMMARY", "{not json")
        val rows = sheets[0].rows
        assertEquals(listOf("ScanTaskId", "Engine", "Status", "FindingCount", "Critical", "High", "Medium", "Low"), rows[0])
        assertEquals(listOf("", "", "", "", "0", "0", "0", "0"), rows[1])
    }

    @Test
    fun `invalid payload with unknown type renders header only`() {
        val sheets = ReportExportModel.sheetsFor("UNKNOWN", "{not json")
        assertEquals(listOf("Key", "Value"), sheets[0].rows[0])
        assertEquals(1, sheets[0].rows.size)
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.application.export.ReportExportModelTest"`（依赖/类尚不存在 → 编译失败）
Expected: FAIL（Unresolved reference / class not found）

- [ ] **Step 3: 加依赖（catalog + module-report build）**

`gradle/libs.versions.toml` `[versions]` 段追加两行（**poi 显式 pin**——Spring Boot 3.3.5 BOM 不管理 POI，grep 实测 0 条目）：

```toml
poi = "5.2.5"
openpdf = "1.3.43"
```

`gradle/libs.versions.toml` `[libraries]` 段追加两行（**poi 与 openpdf 均显式 pin**）：

```toml
poi-ooxml = { module = "org.apache.poi:poi-ooxml", version.ref = "poi" }
openpdf = { module = "com.github.librepdf:openpdf", version.ref = "openpdf" }
```

`module-report/build.gradle.kts` `dependencies` 块追加（放在 `implementation(project(":module-checklist"))` 之后、`testImplementation(...)` 之前）：

```kotlin
    // M16 导出：poi/openpdf 都不在 spring-boot-dependencies BOM，版本目录显式 pin
    implementation(libs.poi.ooxml)
    implementation(libs.openpdf)
```

- [ ] **Step 4: 写最小实现（行模型 + 双渲染器，4 文件）**

创建 `module-report/src/main/kotlin/com/example/compliance/report/application/export/SheetDef.kt`：

```kotlin
package com.example.compliance.report.application.export

/** 导出行模型：SheetDef(name, rows)。结构映射在 ReportExportModel，字节输出在渲染器（spec R-M16-D2）。 */
data class SheetDef(val name: String, val rows: List<List<String>>)
```

创建 `module-report/src/main/kotlin/com/example/compliance/report/application/export/ReportExportModel.kt`：

```kotlin
package com.example.compliance.report.application.export

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** 快照 payload → List<SheetDef>（spec R-M16-D2）：类型感知布局，SCAN_SUMMARY 单表 / COMPLIANCE 双表 /
 *  TREND 时序表 / 兜底键值表。根元素双形态：SCAN_SUMMARY/COMPLIANCE 为对象，TREND 为顶层 JSON 数组。 */
object ReportExportModel {
    private val mapper = ObjectMapper()

    private val SCAN_HEADER = listOf("ScanTaskId", "Engine", "Status", "FindingCount", "Critical", "High", "Medium", "Low")
    private val COMPLIANCE_SUMMARY_HEADER = listOf("ProjectId", "EvaluationId", "Score", "TotalItems", "Passed", "Failed", "Warning", "Manual", "Skipped", "ChecklistVersionId")
    private val COMPLIANCE_ITEMS_HEADER = listOf("ItemCode", "Result", "FindingCount")
    private val TREND_HEADER = listOf("EvaluatedAt", "Score", "Failed")
    private val FALLBACK_HEADER = listOf("Key", "Value")
    private val ARRAY_FALLBACK_HEADER = listOf("Index", "Value")

    fun sheetsFor(snapshotType: String, payload: String): List<SheetDef> {
        val root = runCatching { mapper.readTree(payload) }.getOrElse { mapper.nullNode() }
        return when (snapshotType) {
            "SCAN_SUMMARY" -> listOf(scanSummary(root))
            "COMPLIANCE" -> compliance(root)
            "TREND" -> listOf(trend(root))
            else -> listOf(fallback(root))
        }
    }

    private fun scanSummary(root: JsonNode): SheetDef {
        val bySeverity = root.get("bySeverity")?.takeUnless { it.isNull } ?: mapper.createObjectNode()
        fun sev(key: String): String = str(bySeverity.get(key) ?: bySeverity.get(key.lowercase()), "0")
        return SheetDef(
            "ScanSummary",
            listOf(
                SCAN_HEADER,
                listOf(
                    cell(root, "scanTaskId"), cell(root, "engine"), cell(root, "status"), cell(root, "findingCount"),
                    sev("CRITICAL"), sev("HIGH"), sev("MEDIUM"), sev("LOW"),
                ),
            ),
        )
    }

    private fun compliance(root: JsonNode): List<SheetDef> {
        val summaryRow = listOf(
            cell(root, "projectId"), cell(root, "evaluationId"), cell(root, "score"), cell(root, "totalItems"),
            cell(root, "passed"), cell(root, "failed"), cell(root, "warning"), cell(root, "manual"),
            cell(root, "skipped"), cell(root, "checklistVersionId"),
        )
        val itemRows = (root.get("items") ?: mapper.createArrayNode()).map { item ->
            listOf(cell(item, "itemCode"), cell(item, "result"), cell(item, "findingCount"))
        }
        return listOf(
            SheetDef("Summary", listOf(COMPLIANCE_SUMMARY_HEADER, summaryRow)),
            SheetDef("Items", listOf(COMPLIANCE_ITEMS_HEADER) + itemRows),
        )
    }

    private fun trend(root: JsonNode): SheetDef {
        val rows = if (root.isArray) {
            root.map { p -> listOf(cell(p, "evaluatedAt"), cell(p, "score"), cell(p, "failed")) }
        } else {
            emptyList()
        }
        return SheetDef("Trend", listOf(TREND_HEADER) + rows)
    }

    private fun fallback(root: JsonNode): SheetDef {
        val header: List<String>
        val rows: List<List<String>>
        when {
            root.isArray -> {
                header = ARRAY_FALLBACK_HEADER
                rows = root.mapIndexed { i, v -> listOf(i.toString(), if (v.isNull) "" else v.asText()) }
            }
            root.isObject -> {
                header = FALLBACK_HEADER
                rows = root.fields().asSequence().map { (k, v) -> listOf(k, if (v.isNull) "" else v.asText()) }.toList()
            }
            else -> {
                header = FALLBACK_HEADER
                rows = emptyList()
            }
        }
        return SheetDef("Data", listOf(header) + rows)
    }

    /** 字段取串：缺省/显式 null → 空串（JSON `"score":null` 的 NullNode.asText() 会产字面 "null"，必须拦）。 */
    private fun cell(root: JsonNode, name: String): String {
        val node = root.get(name) ?: return ""
        return if (node.isNull) "" else node.asText()
    }

    private fun str(node: JsonNode?, default: String): String = when {
        node == null || node.isNull || node.asText().isEmpty() -> default
        else -> node.asText()
    }
}
```

创建 `module-report/src/main/kotlin/com/example/compliance/report/application/export/XlsxRenderer.kt`：

```kotlin
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
```

创建 `module-report/src/main/kotlin/com/example/compliance/report/application/export/PdfRenderer.kt`：

```kotlin
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
```

- [ ] **Step 5: 运行模型测试验证通过**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.application.export.ReportExportModelTest"`
Expected: PASS（7 tests）

- [ ] **Step 6: 写渲染器测试（XlsxRendererTest + PdfRendererTest）**

创建 `module-report/src/test/kotlin/com/example/compliance/report/application/export/XlsxRendererTest.kt`：

```kotlin
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
```

创建 `module-report/src/test/kotlin/com/example/compliance/report/application/export/PdfRendererTest.kt`：

```kotlin
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
            val text = PdfTextExtractor(reader).getTextFromPage(1) // OpenPDF 1.3.43：PdfTextExtractor 为实例 API（iText 5 静态形式已移除）
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
```

- [ ] **Step 7: 运行全部导出单测验证通过**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.application.export.*"`
Expected: PASS（7 model + 2 xlsx + 2 pdf = 11 tests）；`./gradlew :module-report:build` 全绿

- [ ] **Step 8: 提交**

```bash
git add gradle/libs.versions.toml module-report/build.gradle.kts \
  module-report/src/main/kotlin/com/example/compliance/report/application/export/ \
  module-report/src/test/kotlin/com/example/compliance/report/application/export/
git commit -m "feat(report): export core — SheetDef model + xlsx/pdf renderers (poi/openpdf) (m16)"
```

---

### Task 16.2: 导出接线 — ReportExportService + 控制器 + 切片测试扩展

**Files:**
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/export/ReportExportService.kt`
- Modify: `module-report/src/main/kotlin/com/example/compliance/report/api/ReportSnapshotController.kt`
- Test: Modify `module-report/src/test/kotlin/com/example/compliance/report/api/ReportSnapshotControllerTest.kt`
- Test: Create `module-report/src/test/kotlin/com/example/compliance/report/application/export/ReportExportServiceTest.kt`

**Interfaces:**
- Consumes: `SheetDef`/`ReportExportModel`/`XlsxRenderer`/`PdfRenderer`（Task 16.1）；`ReportSnapshotRepository`（module-report infrastructure）；`AuditService.record(action, module, userId=null, resourceType=null, resourceId=null, detail=null, ip=null)`（module-common，REQUIRES_NEW）；`AuthPrincipal`（module-common）。
- Produces: `ExportArtifact(filename: String, bytes: ByteArray)`；`ReportExportService.exportXlsx(id: Long, actorId: Long?): ExportArtifact`；`ReportExportService.exportPdf(id: Long, actorId: Long?): ExportArtifact`；控制器 `export` 返回类型从 `ApiResponse<Any>` 改为 `ResponseEntity<Any>`（Task 16.4 集成测试经 HTTP 消费；既有 json/html 行为零变化）。

- [ ] **Step 1: 写失败测试（扩展 ReportSnapshotControllerTest）**

修改 `module-report/src/test/kotlin/com/example/compliance/report/api/ReportSnapshotControllerTest.kt`：
1. 类内追加 `@Autowired lateinit var exportService: ReportExportService`
2. `@TestConfiguration GenServiceConfig` 内追加 `@Bean fun reportExportService(): ReportExportService = mockk()`
3. 文件头 import 追加（加到既有 import 之后）：`com.example.compliance.report.application.export.ExportArtifact`、`com.example.compliance.report.application.export.ReportExportService`、`org.springframework.http.HttpHeaders`、`org.springframework.test.web.servlet.result.MockMvcResultMatchers.content`、`org.springframework.test.web.servlet.result.MockMvcResultMatchers.header`
4. 类尾（既有 `detail and export return content` 测试之后）追加三个测试：

```kotlin
    @Test
    fun `export xlsx returns binary attachment`() {
        every { exportService.exportXlsx(3L, 1L) } returns
            ExportArtifact("report-3-scan_summary.xlsx", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-3-scan_summary.xlsx\""))
            .andExpect(content().bytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `export pdf returns binary attachment`() {
        every { exportService.exportPdf(3L, 1L) } returns
            ExportArtifact("report-3-scan_summary.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-3-scan_summary.pdf\""))
            .andExpect(content().bytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)))
    }

    @Test
    fun `unsupported export format is 400`() {
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=bad"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
```

注：`addFilters = false` + 无认证 → `authentication` 为 null → actorId 兜底 1L，故 mock 参数为 `exportXlsx(3L, 1L)`。既有 `detail and export return content` 测试**不改动**——json 分支返回体形状不变。**另需类级注解**：`@Import(GlobalExceptionHandler::class)` + import `com.example.compliance.common.exception.GlobalExceptionHandler`——`@WebMvcTest` 扫描根在 `com.example.compliance.report`（ReportTestConfig 包），module-common 的 `@ControllerAdvice` 不在范围；本测试是首个在切片内断言 BusinessException→400 的用例，不显式注册则 400 断言 500（Task 16.2 实现反馈修正）。

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.api.ReportSnapshotControllerTest"`
Expected: FAIL（ReportExportService 类不存在 / 构造器不匹配）

- [ ] **Step 3: 写最小实现（ReportExportService + 控制器改造）**

创建 `module-report/src/main/kotlin/com/example/compliance/report/application/export/ReportExportService.kt`：

```kotlin
package com.example.compliance.report.application.export

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 二进制导出产物：filename（Content-Disposition 用）+ bytes（spec R-M16-P1）。 */
data class ExportArtifact(val filename: String, val bytes: ByteArray)

/** 快照 → xlsx/pdf 二进制导出（spec R-M16-D2/D3/D4）：只读载入不可变快照，纯渲染，导出动作写审计 REPORT_EXPORT。 */
@Service
class ReportExportService(
    private val snapshotRepository: ReportSnapshotRepository,
    private val auditService: AuditService,
) {

    @Transactional(readOnly = true)
    fun exportXlsx(id: Long, actorId: Long?): ExportArtifact {
        val snapshot = snapshot(id)
        val bytes = XlsxRenderer.render(ReportExportModel.sheetsFor(snapshot.snapshotType, snapshot.payload))
        recordAudit(snapshot, "xlsx", actorId)
        return ExportArtifact(filename(snapshot, "xlsx"), bytes)
    }

    @Transactional(readOnly = true)
    fun exportPdf(id: Long, actorId: Long?): ExportArtifact {
        val snapshot = snapshot(id)
        val bytes = PdfRenderer.render(
            title = "Report #${snapshot.id} (${snapshot.snapshotType})",
            meta = "template v${snapshot.templateVersionNo} · generatedAt ${snapshot.generatedAt}",
            sheets = ReportExportModel.sheetsFor(snapshot.snapshotType, snapshot.payload),
        )
        recordAudit(snapshot, "pdf", actorId)
        return ExportArtifact(filename(snapshot, "pdf"), bytes)
    }

    private fun snapshot(id: Long): ReportSnapshot = snapshotRepository.findById(id)
        .orElseThrow { BusinessException(404, "report snapshot not found: $id") }

    private fun filename(snapshot: ReportSnapshot, ext: String) =
        "report-${snapshot.id}-${snapshot.snapshotType.lowercase()}.$ext"

    private fun recordAudit(snapshot: ReportSnapshot, format: String, actorId: Long?) {
        // Ruling #34 先例：audit_log.detail 是 JSONB，detail 必须传合法 JSON
        auditService.record(
            action = "REPORT_EXPORT",
            module = "report",
            resourceType = "report_snapshot",
            resourceId = snapshot.id,
            userId = actorId,
            detail = """{"format":"$format","snapshotType":"${snapshot.snapshotType}"}""",
        )
    }
}
```

修改 `module-report/src/main/kotlin/com/example/compliance/report/api/ReportSnapshotController.kt`（**全文替换**，既有 generate/list/detail 逻辑逐字保留）：

```kotlin
package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.api.dto.GenerateRequest
import com.example.compliance.report.api.dto.SnapshotSummaryView
import com.example.compliance.report.api.dto.SnapshotView
import com.example.compliance.report.application.ReportGenerationService
import com.example.compliance.report.application.export.ExportArtifact
import com.example.compliance.report.application.export.ReportExportService
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 报告快照生成/查询/导出（spec §3.3；认证用户即可，RBAC 见 SecurityConfig）。
 *  json/html 走 {data:...} 封装；xlsx/pdf 走二进制附件（spec R-M16-D3）。 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportSnapshotController(
    private val generationService: ReportGenerationService,
    private val exportService: ReportExportService,
) {

    @PostMapping("/{type}/generate")
    fun generate(
        @PathVariable type: String,
        @RequestBody(required = false) req: GenerateRequest?,
        authentication: Authentication?,
    ): ApiResponse<SnapshotView> {
        val actorId = (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
        val snapshot = generationService.generate(type, req?.projectId, req?.scanTaskId, actorId)
        return ApiResponse.ok(SnapshotView.from(snapshot))
    }

    @GetMapping("/snapshots")
    fun list(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<SnapshotSummaryView>> {
        val result = generationService.list(projectId, type, page, size)
        return ApiResponse.ok(
            PageResponse(
                items = result.content.map { SnapshotSummaryView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }

    @GetMapping("/snapshots/{id}")
    fun detail(@PathVariable id: Long): ApiResponse<SnapshotView> =
        ApiResponse.ok(SnapshotView.from(generationService.detail(id)))

    @GetMapping("/snapshots/{id}/export")
    fun export(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "json") format: String,
        authentication: Authentication?,
    ): ResponseEntity<Any> {
        val actorId = (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
        return when (format) {
            "json", "html" -> ResponseEntity<Any>(ApiResponse.ok(generationService.export(id, format)), HttpStatus.OK)
            "xlsx" -> binary(exportService.exportXlsx(id, actorId), XLSX_MEDIA_TYPE)
            "pdf" -> binary(exportService.exportPdf(id, actorId), PDF_MEDIA_TYPE)
            else -> throw BusinessException(400, "unsupported export format: $format")
        }
    }

    private fun binary(artifact: ExportArtifact, mediaType: MediaType): ResponseEntity<Any> {
        val headers = HttpHeaders().apply {
            contentType = mediaType
            set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${artifact.filename}\"")
        }
        return ResponseEntity<Any>(ByteArrayResource(artifact.bytes), headers, HttpStatus.OK)
    }

    companion object {
        private val XLSX_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        private val PDF_MEDIA_TYPE = MediaType.parseMediaType("application/pdf")
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.api.ReportSnapshotControllerTest"`
Expected: PASS（既有 3 tests + 新增 3 = 6 tests）

- [ ] **Step 5: 写失败测试（ReportExportServiceTest，spec §3.5 明文单测）**

创建 `module-report/src/test/kotlin/com/example/compliance/report/application/export/ReportExportServiceTest.kt`：

```kotlin
package com.example.compliance.report.application.export

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import kotlin.test.assertFailsWith

/** M16 (R-M16-D4)：导出服务——载入快照 → 渲染字节 → 审计 REPORT_EXPORT 留痕（含 format detail）；缺失 → 404。 */
class ReportExportServiceTest {

    private val snapshotRepo = mockk<ReportSnapshotRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ReportExportService(snapshotRepo, auditService)

    private fun snapshot() = ReportSnapshot().apply {
        id = 3L; templateId = 1L; templateVersionNo = 2; projectId = 5L
        snapshotType = "COMPLIANCE"
        payload = """{"score":88.5,"items":[]}"""
        generatedAt = Instant.parse("2026-09-01T00:00:00Z")
    }

    @Test
    fun `exportXlsx renders bytes and records audit`() {
        every { snapshotRepo.findById(3L) } returns Optional.of(snapshot())
        val artifact = service.exportXlsx(3L, 42L)
        assertEquals("report-3-compliance.xlsx", artifact.filename)
        assertTrue(artifact.bytes.size > 500)
        verify {
            auditService.record(
                action = "REPORT_EXPORT", module = "report", userId = 42L,
                resourceType = "report_snapshot", resourceId = 3L,
                detail = match { it!!.contains("\"format\":\"xlsx\"") }, ip = null,
            )
        }
    }

    @Test
    fun `exportPdf renders bytes and records audit`() {
        every { snapshotRepo.findById(3L) } returns Optional.of(snapshot())
        val artifact = service.exportPdf(3L, 42L)
        assertEquals("report-3-compliance.pdf", artifact.filename)
        assertEquals(0x25.toByte(), artifact.bytes[0])
        verify {
            auditService.record(
                action = "REPORT_EXPORT", module = "report", userId = 42L,
                resourceType = "report_snapshot", resourceId = 3L,
                detail = match { it!!.contains("\"format\":\"pdf\"") }, ip = null,
            )
        }
    }

    @Test
    fun `missing snapshot is 404`() {
        every { snapshotRepo.findById(99L) } returns Optional.empty()
        assertFailsWith<BusinessException> { service.exportXlsx(99L, 1L) }
    }
}
```

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.application.export.ReportExportServiceTest"`
Expected: FAIL（未编译/断言不符——Step 5 写在实现之后属确认回归，若已绿直接通过）

- [ ] **Step 6: 全量 module-report 回归**

Run: `./gradlew :module-report:build`
Expected: BUILD SUCCESSFUL（含 Task 16.1 导出单测 11 + 服务单测 3 + 控制器 6 + 既有 M12 单测）

- [ ] **Step 7: 提交**

```bash
git add module-report/src/main/kotlin/com/example/compliance/report/application/export/ReportExportService.kt \
  module-report/src/main/kotlin/com/example/compliance/report/api/ReportSnapshotController.kt \
  module-report/src/test/kotlin/com/example/compliance/report/api/ReportSnapshotControllerTest.kt \
  module-report/src/test/kotlin/com/example/compliance/report/application/export/ReportExportServiceTest.kt
git commit -m "feat(report): xlsx/pdf binary export via ReportExportService + audit (m16)"
```

---

### Task 16.3: 审计查询 — 过滤/查询服务（module-common）+ 控制器（module-admin）+ 单测

**Files:**
- Modify: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogRepository.kt`（增 `JpaSpecificationExecutor<AuditLog>`）
- Create: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogFilter.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditQueryService.kt`
- Test: Create `module-common/src/test/kotlin/com/example/compliance/common/audit/AuditQueryServiceTest.kt`
- Create: `module-admin/src/main/kotlin/com/example/compliance/admin/api/AuditLogView.kt`
- Create: `module-admin/src/main/kotlin/com/example/compliance/admin/api/AuditLogController.kt`
- Test: Create `module-admin/src/test/kotlin/com/example/compliance/admin/AdminTestConfig.kt`（`@SpringBootConfiguration` 标记，`@WebMvcTest` 需要——镜像 module-report `ReportTestConfig`；brief 缺口，Task 16.3 实现反馈）

**Interfaces:**
- Consumes: `AuditLog`（entity，字段 userId/action/module/resourceType/resourceId/ip/occurredAt，表 `audit_log`）；`AuditLogRepository : JpaRepository<AuditLog, Long>`（module-common）；`ApiResponse`/`PageView<T>`（module-common api，`PageView<T> = PageResponse<T>` typealias）；`BusinessException`（module-common）。
- Produces: `AuditLogFilter(module, action, userId, resourceType, resourceId, from, to)` 全可选；`AuditQueryService.search(filter, page, size): Page<AuditLog>`（负 page → BusinessException(400)，size.coerceIn(1,100)，id DESC，空过滤 → null Specification）；`AuditLogView(id, userId, action, module, resourceType, resourceId, detail, ip, occurredAt)`（detail 为 JSONB 原样字符串）+ `from(AuditLog)`；`AuditLogController.list(...) : ApiResponse<PageView<AuditLogView>>`（`GET /api/v1/audit-logs`，`@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")`）。Task 16.4 集成测试经 HTTP 消费。

- [ ] **Step 1: 写失败测试（AuditQueryServiceTest）**

创建 `module-common/src/test/kotlin/com/example/compliance/common/audit/AuditQueryServiceTest.kt`：

```kotlin
package com.example.compliance.common.audit

import com.example.compliance.common.exception.BusinessException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification

/** M16 (R-M16-D6)：审计查询服务——负 page 400、size 钳制、空过滤 null spec。 */
class AuditQueryServiceTest {

    private val repo = mockk<AuditLogRepository>(relaxed = true)
    private val service = AuditQueryService(repo)

    @Test
    fun `negative page throws 400`() {
        assertFailsWith<BusinessException> { service.search(AuditLogFilter(), -1, 20) }
    }

    @Test
    fun `size is clamped to 100`() {
        every { repo.findAll(any<Specification<AuditLog>>(), any<Pageable>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 100), 0L)
        service.search(AuditLogFilter(module = "M"), 0, 500)
        verify { repo.findAll(any<Specification<AuditLog>>(), match<Pageable> { it.pageSize == 100 }) }
    }

    @Test
    fun `empty filter passes null specification`() {
        every { repo.findAll(null, any<Pageable>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0L)
        service.search(AuditLogFilter(), 0, 20)
        verify { repo.findAll(null, match<Pageable> { it.pageNumber == 0 }) }
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :module-common:test --tests "com.example.compliance.common.audit.AuditQueryServiceTest"`
Expected: FAIL（AuditQueryService 不存在）

- [ ] **Step 3: 写最小实现（module-common：repository/filter/service）**

修改 `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogRepository.kt`（全文替换）：

```kotlin
package com.example.compliance.common.audit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface AuditLogRepository : JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog>
```

创建 `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogFilter.kt`：

```kotlin
package com.example.compliance.common.audit

import java.time.Instant

/** 审计日志查询过滤（全可选 AND，spec R-M16-D6）：空过滤 → 全量。 */
data class AuditLogFilter(
    val module: String? = null,
    val action: String? = null,
    val userId: Long? = null,
    val resourceType: String? = null,
    val resourceId: Long? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
```

创建 `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditQueryService.kt`：

```kotlin
package com.example.compliance.common.audit

import com.example.compliance.common.exception.BusinessException
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 审计日志查询（spec R-M16-D5/D6）：多可选 AND 过滤 + 分页；空过滤 → null Specification（全量）。
 *  负 page 400、size 钳制 [1,100]、固定 id 倒序——镜像 ReportGenerationService.list C2 硬化。 */
@Service
class AuditQueryService(private val repository: AuditLogRepository) {

    @Transactional(readOnly = true)
    fun search(filter: AuditLogFilter, page: Int, size: Int): Page<AuditLog> {
        if (page < 0) throw BusinessException(400, "page must be non-negative")
        val pageable = PageRequest.of(page, size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "id"))
        return repository.findAll(filter.toSpecification(), pageable)
    }

    private fun AuditLogFilter.toSpecification(): Specification<AuditLog>? {
        val anyFilter = module != null || action != null || userId != null ||
            resourceType != null || resourceId != null || from != null || to != null
        if (!anyFilter) return null
        return Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            module?.let { predicates += cb.equal(root.get<String>("module"), it) }
            action?.let { predicates += cb.equal(root.get<String>("action"), it) }
            userId?.let { predicates += cb.equal(root.get<Long>("userId"), it) }
            resourceType?.let { predicates += cb.equal(root.get<String>("resourceType"), it) }
            resourceId?.let { predicates += cb.equal(root.get<Long>("resourceId"), it) }
            from?.let { predicates += cb.greaterThanOrEqualTo(root.get<Instant>("occurredAt"), it) }
            to?.let { predicates += cb.lessThanOrEqualTo(root.get<Instant>("occurredAt"), it) }
            cb.and(*predicates.toTypedArray())
        }
    }
}
```

- [ ] **Step 4: 运行 module-common 测试验证通过**

Run: `./gradlew :module-common:test --tests "com.example.compliance.common.audit.AuditQueryServiceTest"`
Expected: PASS（3 tests）；既有 AuditServiceTest 不回归

- [ ] **Step 5: 写失败测试（module-admin 控制器切片 + 视图）**

创建 `module-admin/src/test/kotlin/com/example/compliance/admin/api/AuditLogControllerTest.kt`（若 `module-admin/src/test` 目录不存在则一并创建；`@WebMvcTest(AuditLogController::class)` + `@AutoConfigureMockMvc(addFilters = false)`，镜像 ReportSnapshotControllerTest）：

```kotlin
package com.example.compliance.admin.api

import com.example.compliance.common.audit.AuditLog
import com.example.compliance.common.audit.AuditLogFilter
import com.example.compliance.common.audit.AuditQueryService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M16 (R-M16-D7)：审计查询端点切片——过滤参数透传 + PageView 封装。 */
@WebMvcTest(AuditLogController::class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var query: AuditQueryService

    @TestConfiguration
    class QueryConfig {
        @Bean
        fun auditQueryService(): AuditQueryService = mockk()
    }

    private fun auditLog(id: Long) = AuditLog().apply {
        this.id = id
        action = "CREATE"; module = "project"; userId = 1L
        resourceType = "Project"; resourceId = 9L
        detail = """{"k":"v"}"""; occurredAt = Instant.parse("2026-09-01T00:00:00Z")
    }

    @Test
    fun `list returns paged audit views`() {
        val page = PageImpl(
            listOf(auditLog(1L)),
            PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")),
            1L,
        )
        every { query.search(any(), 0, 20) } returns page
        mockMvc.perform(get("/api/v1/audit-logs").param("module", "project").param("action", "CREATE"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(1))
            .andExpect(jsonPath("$.data.items[0].module").value("project"))
            .andExpect(jsonPath("$.data.items[0].detail").value("""{"k":"v"}"""))
            .andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-09-01T00:00:00Z"))
    }
}
```

- [ ] **Step 6: 运行验证失败**

Run: `./gradlew :module-admin:test --tests "com.example.compliance.admin.api.AuditLogControllerTest"`
Expected: FAIL（AuditLogController/AuditLogView 不存在）

- [ ] **Step 7: 写最小实现（module-admin：view + controller）**

创建 `module-admin/src/main/kotlin/com/example/compliance/admin/api/AuditLogView.kt`：

```kotlin
package com.example.compliance.admin.api

import com.example.compliance.common.audit.AuditLog

/** 审计日志查询视图（spec §3.3）：detail 原样字符串（JSONB 原始文本，客户端自行解析）。 */
data class AuditLogView(
    val id: Long,
    val userId: Long?,
    val action: String,
    val module: String,
    val resourceType: String?,
    val resourceId: Long?,
    val detail: String?,
    val ip: String?,
    val occurredAt: String,
) {
    companion object {
        fun from(e: AuditLog) = AuditLogView(
            e.id!!, e.userId, e.action, e.module, e.resourceType, e.resourceId, e.detail, e.ip, e.occurredAt.toString(),
        )
    }
}
```

创建 `module-admin/src/main/kotlin/com/example/compliance/admin/api/AuditLogController.kt`：

```kotlin
package com.example.compliance.admin.api

import com.example.compliance.admin.api.AuditLogView
import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageView
import com.example.compliance.common.audit.AuditLogFilter
import com.example.compliance.common.audit.AuditQueryService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant

/** 审计日志查询（spec R-M16-D5/D6/D7）：/api/v1/audit-logs 避开 /admin/ 前缀路径守卫使 AUDITOR 可达，
 *  方法级 @PreAuthorize(ADMIN,AUDITOR) 双保险（SecurityConfig 无此路径规则 → 认证即可 + 方法门控）。 */
@RestController
@RequestMapping("/api/v1/audit-logs")
class AuditLogController(private val query: AuditQueryService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    fun list(
        @RequestParam(required = false) module: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) resourceType: String?,
        @RequestParam(required = false) resourceId: Long?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageView<AuditLogView>> {
        val filter = AuditLogFilter(module, action, userId, resourceType, resourceId, from, to)
        val result = query.search(filter, page, size)
        return ApiResponse.ok(
            PageView(
                items = result.content.map { AuditLogView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }
}
```

- [ ] **Step 8: 运行测试验证通过 + 全量回归**

Run: `./gradlew :module-admin:test --tests "com.example.compliance.admin.api.AuditLogControllerTest"` → PASS（1 test）
Run: `./gradlew :module-common:build :module-admin:build` → BUILD SUCCESSFUL

- [ ] **Step 9: 提交**

```bash
git add module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogRepository.kt \
  module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogFilter.kt \
  module-common/src/main/kotlin/com/example/compliance/common/audit/AuditQueryService.kt \
  module-common/src/test/kotlin/com/example/compliance/common/audit/AuditQueryServiceTest.kt \
  module-admin/src/main/kotlin/com/example/compliance/admin/api/AuditLogView.kt \
  module-admin/src/main/kotlin/com/example/compliance/admin/api/AuditLogController.kt \
  module-admin/src/test/kotlin/com/example/compliance/admin/api/AuditLogControllerTest.kt
git commit -m "feat(audit): audit log query API — specification filters + admin/auditor endpoint (m16)"
```

---

### Task 16.4: M16 集成测试 + 全量构建

**Files:**
- Test: Create `app-server/src/test/kotlin/com/example/compliance/report/M16ReportExportIntegrationTest.kt`
- Test: Create `app-server/src/test/kotlin/com/example/compliance/admin/M16AuditLogIntegrationTest.kt`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`（app-server，共享 Testcontainers PG 16）、`ProjectService.create(CreateProjectCommand(...))`、`ReportSnapshotRepository`（module-report）、`AuditLogRepository`/`AuditService`（module-common）、导出/审计端点（Task 16.2/16.3 产物）。
- Produces: 两个端到端测试类——导出（COMPLIANCE 快照 → xlsx/pdf 字节 + 审计留痕 + 400）与审计查询（过滤/分页/RBAC）。`./gradlew build` 全绿为验收门。

- [ ] **Step 1: 写失败测试（导出 e2e）**

创建 `app-server/src/test/kotlin/com/example/compliance/report/M16ReportExportIntegrationTest.kt`：

```kotlin
package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.audit.AuditLogRepository
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M16 端到端：COMPLIANCE 快照 → xlsx/pdf 二进制导出（Content-Type/Content-Disposition/魔数可验）+ REPORT_EXPORT 审计留痕 + 未知格式 400。
 *  模板走生命周期端点 draft+publish COMPLIANCE（不触碰 M12 C6 独占的 SCAN_SUMMARY/TREND 模板写入）；
 *  快照经仓库直接播种固定 payload（生成链路需评估数据，已由 M12 覆盖）。
 *  app-server 无 POI 编译类路径（module-report implementation 不透传）→ 字节级 ZIP/%PDF 魔数验证而非 Workbook 回读。 */
@AutoConfigureMockMvc
class M16ReportExportIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var snapshotRepository: ReportSnapshotRepository
    @Autowired lateinit var auditLogRepository: AuditLogRepository

    private val objectMapper = ObjectMapper()

    private val payload = """{"projectId":5,"evaluationId":7,"score":88.5,"totalItems":2,"passed":1,"failed":1,"warning":0,"manual":0,"skipped":0,"checklistVersionId":9,"items":[{"itemCode":"M16-IT-1","result":"PASS","findingCount":0},{"itemCode":"M16-IT-2","result":"FAIL","findingCount":3}]}"""

    // 计数须放 companion（JUnit5 默认 PER_METHOD：实例字段每次测试重置 → 固定 code 第二次起抛
    // "project code already exists"）。M10AdminIntegrationTest 同款实证修正先例（brief 固定 M16RP 有缺陷）。
    companion object {
        @JvmStatic
        private var seedCounter = 0
    }

    private fun seedSnapshot(): Long {
        seedCounter++
        val project = projectService.create(CreateProjectCommand("M16RP$seedCounter", "M16 report", null, null))
        // 模板 draft/publish 需 ADMIN/COMPLIANCE_MANAGER（SecurityConfig /api/v1/reports/templates/**），
        // 而导出测试方法以 @WithMockUser(DEVELOPER) 认证——播种请求显式提权 ADMIN（M12 同款 .with 覆盖模式）。
        val admin = SecurityMockMvcRequestPostProcessors.user("m16-admin").roles("ADMIN")
        val draftResp = mockMvc.perform(post("/api/v1/reports/templates/COMPLIANCE/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"M16 compliance","sections":{"sections":[{"title":"Summary"}]}}""")
                .with(admin))
            .andExpect(status().isOk)
            .andReturn()
        val tplId = objectMapper.readTree(draftResp.response.contentAsString)["data"]["templateId"].asLong()
        mockMvc.perform(post("/api/v1/reports/templates/COMPLIANCE/publish").with(admin))
            .andExpect(status().isOk)
        // apply 隐式接收者遮蔽：RHS `templateId`/`payload` 会解析到 ReportSnapshot 自身成员
        // （templateId 静默赋 0 / payload 是未初始化 lateinit → UninitializedPropertyAccessException），
        // 必须改名或加标签限定以引用外层作用域（16.4 实证修正 brief 代码）。
        val saved = snapshotRepository.save(ReportSnapshot().apply {
            this.templateId = tplId
            templateVersionNo = 1
            this.projectId = project.id
            snapshotType = "COMPLIANCE"
            this.payload = this@M16ReportExportIntegrationTest.payload
            generatedBy = 1L
            generatedAt = Instant.now()
        })
        return saved.id!!
    }

    @Test
    @WithMockUser(username = "m16-user", roles = ["DEVELOPER"])
    fun `export xlsx and pdf return binary attachments and record audit`() {
        val id = seedSnapshot()

        mockMvc.perform(get("/api/v1/reports/snapshots/$id/export").param("format", "xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-$id-compliance.xlsx\""))
            .andReturn().response.contentAsByteArray.also { bytes ->
                assertTrue(bytes.size > 1000, "xlsx bytes should be non-trivial, was ${bytes.size}")
                assertTrue(
                    bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte(),
                    "xlsx must be a ZIP archive (PK magic)",
                )
            }

        mockMvc.perform(get("/api/v1/reports/snapshots/$id/export").param("format", "pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-$id-compliance.pdf\""))
            .andReturn().response.contentAsByteArray.also { bytes ->
                assertTrue(bytes.size > 500, "pdf bytes should be non-trivial, was ${bytes.size}")
                assertTrue(
                    bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
                        bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte(),
                    "pdf must start with %PDF",
                )
            }

        val exportAudits = auditLogRepository.findAll().filter { it.action == "REPORT_EXPORT" && it.resourceId == id }
        assertEquals(2, exportAudits.size, "xlsx + pdf 两次导出各留一条审计")
        assertTrue(exportAudits.all { it.module == "report" && it.resourceType == "report_snapshot" })
        // 实证：PG jsonb 规范化空白（"format": "xlsx" 冒号后有空格），brief 的逐字 contains("\"format\":\"xlsx\"")
        // 对活库永不命中 → 改为解析 detail JSON 断言 format 字段（16.4 实证修正，语义等价且不受 jsonb 规范化影响）。
        val formats = exportAudits.map { objectMapper.readTree(it.detail!!)["format"].asText() }
        assertTrue("xlsx" in formats, "xlsx format audit detail missing: " + exportAudits.map { it.detail })
        assertTrue("pdf" in formats, "pdf format audit detail missing: " + exportAudits.map { it.detail })
    }

    @Test
    @WithMockUser(username = "m16-user", roles = ["DEVELOPER"])
    fun `unsupported export format is 400`() {
        val id = seedSnapshot()
        mockMvc.perform(get("/api/v1/reports/snapshots/$id/export").param("format", "bad"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
```

- [ ] **Step 2: 写失败测试（审计查询 e2e）**

创建 `app-server/src/test/kotlin/com/example/compliance/admin/M16AuditLogIntegrationTest.kt`：

```kotlin
package com.example.compliance.admin

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.audit.AuditLog
import com.example.compliance.common.audit.AuditLogRepository
import com.example.compliance.common.audit.AuditService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M16 端到端：审计日志查询（spec R-M16-D5/D6/D7）——多可选 AND 过滤、时间窗、分页/钳制/负页 400、
 *  RBAC（ADMIN/AUDITOR 200、COMPLIANCE_MANAGER 403、未认证 401）。module=M16_AUDIT_* 每测试独有 → 确定性隔离。 */
@AutoConfigureMockMvc
class M16AuditLogIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var auditLogRepository: AuditLogRepository

    @Test
    @WithMockUser(username = "m16-auditor", roles = ["AUDITOR"])
    fun `auditor filters by module action user and resource`() {
        val module = "M16_AUDIT_F"
        auditService.record(action = "CREATE", module = module, userId = 7L, resourceType = "Project", resourceId = 11L)
        auditService.record(action = "UPDATE", module = module, userId = 8L, resourceType = "Rule", resourceId = 22L)
        auditService.record(action = "DELETE", module = module, userId = 9L, resourceType = "Project", resourceId = 33L)

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.items[0].action").value("DELETE")) // id DESC：最后插入的 DELETE 最先
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("action", "UPDATE"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("UPDATE"))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("userId", "8"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("resourceType", "Project"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("resourceType", "Project").param("resourceId", "11"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
    }

    @Test
    @WithMockUser(username = "m16-auditor", roles = ["AUDITOR"])
    fun `auditor time window pagination and clamps`() {
        val module = "M16_AUDIT_T"
        val now = Instant.now()
        auditLogRepository.save(audit(module, "OLD", now.minusSeconds(5 * 86400)))
        auditLogRepository.save(audit(module, "RECENT", now.minusSeconds(86400)))
        auditLogRepository.save(audit(module, "NOW", now))

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("from", now.minusSeconds(2 * 86400).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
        // to 单侧：occurredAt <= now-1d → OLD + RECENT（边界含等）
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("to", now.minusSeconds(86400).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
        // from+to 双侧窗：now-2d .. now-1d → 仅 RECENT
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module)
                .param("from", now.minusSeconds(2 * 86400).toString())
                .param("to", now.minusSeconds(86400).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("RECENT"))

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("page", "0").param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.total").value(3))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("page", "1").param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("size", "500"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.size").value(100))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("page", "-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }

    @Test
    fun `rbac admin allowed cm forbidden unauthenticated 401`() {
        val module = "M16_AUDIT_R"
        auditService.record(action = "CREATE", module = module)
        val admin = SecurityMockMvcRequestPostProcessors.user("m16-admin").roles("ADMIN")
        val cm = SecurityMockMvcRequestPostProcessors.user("m16-cm").roles("COMPLIANCE_MANAGER")
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).with(admin))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).with(cm))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module))
            .andExpect(status().isUnauthorized)
    }

    private fun audit(module: String, action: String, occurredAt: Instant) = AuditLog().apply {
        this.module = module
        this.action = action
        userId = 1L
        this.detail = """{"marker":"m16-audit"}"""
        this.occurredAt = occurredAt
    }
}
```

- [ ] **Step 3: 运行验证失败**

Run: `./gradlew :app-server:test --tests "com.example.compliance.report.M16ReportExportIntegrationTest" --tests "com.example.compliance.admin.M16AuditLogIntegrationTest"`
Expected: FAIL（新端点尚未实现/断言不符）——注意先确保 Task 16.2/16.3 已合入

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :app-server:test --tests "com.example.compliance.report.M16ReportExportIntegrationTest" --tests "com.example.compliance.admin.M16AuditLogIntegrationTest"`
Expected: PASS（M16 导出 2 + 审计 3 = 5 tests，live Testcontainers PG）

- [ ] **Step 5: 全量构建门**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL——既有 234 + M16 新增全绿（module-report 17 + module-common 3 + module-admin 1 + app-server 5）

- [ ] **Step 6: 提交**

```bash
git add app-server/src/test/kotlin/com/example/compliance/report/M16ReportExportIntegrationTest.kt \
  app-server/src/test/kotlin/com/example/compliance/admin/M16AuditLogIntegrationTest.kt
git commit -m "test(app-server): M16 e2e — report binary export + audit log query (m16)"
```

---

## 预检冲突扫描（派发前核对）

| 任务对 | 生产 vs 消费 | 结论 |
|---|---|---|
| 16.1 → 16.2 | `ReportExportModel/XlsxRenderer/PdfRenderer` vs `ReportExportService` | 逐字一致（接口块已固化）；`ExportArtifact` 在 16.2 定义，16.1 不引用 |
| 16.1 → 16.4 | 渲染产物字节 vs 集成测试 ZIP/%PDF 魔数 | 无冲突（测试不依赖 POI 回读，R-M16-P4） |
| 16.2 → 16.4 | 导出端点二进制响应 vs e2e 断言 | Content-Disposition `report-<id>-compliance.<ext>` 与 `filename(snapshot, ext)` 逐字一致 |
| 16.3(common) → 16.3(admin) | `AuditQueryService/AuditLogFilter` vs `AuditLogController/AuditLogView` | 签名逐字一致（`search(filter,page,size): Page<AuditLog>`、`PageView<AuditLogView>` typealias） |
| 16.3 → 16.4 | 审计端点 vs 审计 e2e | `M16_AUDIT_*` 前缀与既有里程碑不相交；`from` ISO-8601 Instant 转换 Spring 原生支持 |
| 16.2 → 既有 M12 | `ReportSnapshotController.export` 返回类型变化 vs 既有切片测试 | json/html 返回体形状不变（`ResponseEntity<Any>(ApiResponse.ok(...), OK)` 序列化结果同前）→ 既有 `$.data.findingCount` 断言不破坏 |
| 16.1/16.2 → module-common | `AuditService.record` 消费 | 签名逐字（已有 `AuditService.kt` 源码核验）；detail 合法 JSON（Ruling #34 先例） |
| 16.3(common) 自身 | `AuditLogRepository` 增 `JpaSpecificationExecutor` vs 既有 `AuditServiceTest` | 接口多继承不改既有方法 → 无破坏 |

**Rulings（plan 撰写期已固化，派发时重申）：**
- **R-M16-P1** `ExportArtifact(filename, bytes)` 替代 spec §3.2 的裸 `ByteArray` 返回——控制器 Content-Disposition 需要 `snapshotType` 派生文件名。
- **R-M16-P2** `PdfRenderer.render(title, meta, sheets)`——title/meta 是 PDF 段落，非表行。
- **R-M16-P3** 集成测试经仓库播种 COMPLIANCE 快照（生成需评估数据，重）；模板走生命周期端点 draft+publish COMPLIANCE（避开 M12 C6 SCAN_SUMMARY/TREND 独占）。
- **R-M16-P4** 集成测试不 import POI（app-server 编译类路径无 poi-ooxml）——ZIP/%PDF 魔数验证；工作簿正确性由 16.1 单测锚定。
- **R-M16-P5** `AuditLogView` 放 module-admin `admin/api/`（spec §3.3 逐字位置，镜像 AdminController）；含 `detail` 原样字符串（JSONB）；查询服务留 module-common 领域层。
- **R-M16-P6** module-common「M-series frozen」指运行依赖不变（既有 spring-data-jpa api 依赖已足）；新增类不违反。
