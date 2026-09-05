# 代码合规扫描平台 M16 — 报表导出（Excel/PDF）+ 审计日志查询 设计

> 本文档是 M16 里程碑的整体规划设计：补齐基线 spec §7 明文延后的「导出（Excel/PDF/HTML）」能力，并为「审计闭环」补上读侧（审计日志查询）。锁定目标、范围、关键设计决策与测试策略。里程碑关闭时另行出具实施计划，按序交付、逐里程碑确认。

**基线 spec：** `docs/superpowers/specs/2026-09-02-code-compliance-platform-design.md`（全局约束/枚举/模块划分/安全红线继续约束本阶段）
**既有阶段 spec：** `2026-09-03-code-compliance-platform-phase2-design.md`（M6–M9）、`2026-09-03-code-compliance-platform-m10-design.md`（M10 收口）、`2026-09-04-code-compliance-platform-m12-m14-design.md`（M12–M14）、`2026-09-04-code-compliance-platform-m15-design.md`（M15）
**前置交付：** M15（HEAD `ccda447`，已 push `origin/main`）。M16 范围经用户确认分三批：**M16 = 报表导出 + 审计查询**（本 spec）；M17 = 通知渠道真实投递；M18 = Quartz 定时扫描调度。

---

## 1. 目标与范围

基线 spec §7 明文「导出（Excel/PDF/HTML）列入后续里程碑，首发仅 JSON」——至今 `GET /api/v1/reports/snapshots/{id}/export?format=` 仅支持 `json`/`html`（`HtmlReportRenderer` 固定模板），Excel/PDF 从未交付；同时 `audit_log` 表 + `AuditService.record()`（M0–M5）只写不读，平台目标「支持问题整改、豁免、复审与审计闭环」的审计读侧缺失。M16 收口这两项。

**本里程碑目标：**
1. **报表导出**：从不可变报告快照（P3-D3，JSONB payload）生成 **xlsx（Excel）** 与 **pdf**，与既有 `json`/`html` 并存于同一 export 端点；布局按快照类型感知（SCAN_SUMMARY/COMPLIANCE/TREND），导出动作记录审计。
2. **审计日志查询**：AUDITOR/ADMIN 可检索 `audit_log`（多条件过滤 + 分页），闭环「审计」职责域的读侧；查询严格只读，`audit_log` 只增不改不删红线不变。

### 1.1 非目标（YAGNI，仍延后）

- **PDF 精美排版**（HTML→PDF 管道/weasyprint）——本轮 PDF 为简单表格布局（嵌套 items 拍平为多行），满足交付即可
- **批量导出**（多快照打包 zip）、**导出文件落盘/对象存储**——导出为即时生成字节流式返回，不落盘
- **xlsx 样式定制/模板驱动导出**——布局按快照类型固定，不读报告模板
- **审计单查端点**（`GET /audit-logs/{id}`——列表行已含全部字段）、**审计归档/保留策略/批量清理**
- **审计导出**（把 audit 结果也做成 Excel）——导出能力本轮只面向报告快照
- **OpenAPI/对外 API 侧的导出**、**前端下载页**（`Content-Disposition` 附件直出，前端可直接消费）
- **M17/M18**（通知投递、Quartz 调度）——已另行排队

---

## 2. 决策记录（M16 新增/变更）

| 编号 | 问题 | 决策 |
|---|---|---|
| **R-M16-D1** | 导出格式与依赖 | **xlsx**（`org.apache.poi:poi-ooxml`，Spring Boot 3.3.5 BOM 管版本≈5.2.5）+ **pdf**（`com.github.librepdf:openpdf`，`gradle/libs.versions.toml` catalog 显式 pin `1.3.43`（LGPL；实施时若已有更新稳定版可小幅上浮，plan 逐字以 `1.3.43` 为准——若组织许可政策不接受 LGPL 则退 Apache PDFBox，渲染器接口 `SheetDefs→ByteArray` 不变仅换实现）。`json`/`html` 保持不动。零 DDL。 |
| **R-M16-D2** | 布局模型与渲染分离 | 纯函数 `SheetDef(name, rows: List<List<String>>)` 行模型：`payload → List<SheetDef>` **按快照类型感知**（SCAN_SUMMARY 单表 / COMPLIANCE Summary+Items 双表 / TREND 时序表 / 兜底键值表）。`XlsxRenderer`（POI）与 `PdfRenderer`（OpenPDF）**共用同一行模型**——结构映射只写一次，DRY。 |
| **R-M16-D3** | 端点形态 | `export` 统一改返 `ResponseEntity<Any>`：`json`/`html` 维持 `{data:…}`（`ResponseEntity.ok(ApiResponse.ok(...))`，HTTP 响应体不变，既有测试仍绿）；`xlsx`/`pdf` 返回二进制 + `Content-Type` + `Content-Disposition: attachment; filename="report-<id>-<type>.<ext>"`。URL 与参数不变 → 客户端零破坏；未支持 format 仍 400。 |
| **R-M16-D4** | 导出审计留痕 | 每次二进制导出经 `AuditService.record(action="REPORT_EXPORT", module="report", resourceType="report_snapshot", resourceId=<id>, userId=actorId, detail={format,snapshotType})`。actorId 取真实 principal（现有 export 缺 actorId 参数，顺带补齐——与 `generate` 相同解析，String principal 回落 1L）。 |
| **R-M16-D5** | 审计查询宿主 | `AuditQueryService` 置于 **module-common**（近审计域，直接复用 `AuditLogRepository`，其他模块可复用）；`AuditLogController` 置于 **module-admin**（既有跨模块读聚合家，已依赖 module-common）。路径 **`/api/v1/audit-logs`** 避开 `/api/v1/admin/**`（SecurityConfig 已整锁 `hasRole("ADMIN")`，AUDITOR 会在 filter 层被 403 拦截）。 |
| **R-M16-D6** | 审计过滤与分页 | JPA **`Specification`** 多可选过滤 AND 组合（module/action/userId/resourceType/resourceId/occurredAt from/to 时间窗）；`page<0 → 400`、`size` 钳制 `[1,100]`、固定 **id DESC**（镜像 report list C2 硬化）。`AuditLogRepository` 增 `JpaSpecificationExecutor<AuditLog>`。过滤轴达 7 维，派生查询会 2⁷ 组合爆炸——Specification 是正确工具（report list 4 分支不走 Specification 的 YAGNI 不构成反例）。 |
| **R-M16-D7** | RBAC | 审计查询 `@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")`（审计是 AUDITOR 职责域，M12 模板 versions 端点同档先例）；报表导出保持「认证用户即可」（SecurityConfig `anyRequest().authenticated()` 兜底，无方法级限制），行为不变。 |
| **R-M16-D8** | 测试三层 | 单测（`ReportExportModelTest`/`XlsxRendererTest`/`PdfRendererTest`/`ReportExportServiceTest`/`AuditQueryServiceTest` + 既有 `ReportSnapshotControllerTest` 扩展）+ 集成（`M16ReportExportIntegrationTest` / `M16AuditLogIntegrationTest`，`M16-*` 前缀，live Testcontainers PG）+ `./gradlew build` 门禁。 |

---

## 3. 组件与数据流

### 3.1 总览

```
┌─ 报表导出（module-report）─────────────────────────────┐
│  GET /reports/snapshots/{id}/export?format=xlsx|pdf     │
│  ① ReportExportService 载入不可变快照（readOnly）        │
│  ② ReportExportModel: payload → List<SheetDef>（类型感知）│
│  ③ XlsxRenderer / PdfRenderer: SheetDefs → ByteArray     │
│  ④ ResponseEntity 二进制 + Content-Disposition + 审计留痕 │
└────────────────────────────────────────────────────────┘
┌─ 审计查询（module-common + module-admin）───────────────┐
│  GET /api/v1/audit-logs?module=&action=&userId=&...      │
│  ① AuditLogController（module-admin，ADMIN/AUDITOR）      │
│  ② AuditQueryService（module-common）: filter→Specification│
│  ③ AuditLogRepository + JpaSpecificationExecutor          │
│  ④ PageResponse<AuditLogView>（id DESC，size 钳 [1,100]）  │
└────────────────────────────────────────────────────────┘
```

### 3.2 A. 报表导出组件（module-report 新增 4 文件 + 1 控制器改造）

| 组件 | 位置 | 职责 |
|---|---|---|
| `SheetDef`（`data class(name, rows: List<List<String>>)`） | `report/application/export/` | 行模型——渲染器与结构映射的解耦点 |
| `ReportExportModel`（object，纯函数） | `report/application/export/` | `sheetsFor(snapshotType, payload): List<SheetDef>`——三类型感知 + 兜底（见下方布局规范） |
| `XlsxRenderer`（object） | `report/application/export/` | `render(sheets): ByteArray`——POI `XSSFWorkbook`，每 SheetDef 一 sheet，首行表头加粗 |
| `PdfRenderer`（object） | `report/application/export/` | `render(sheets): ByteArray`——OpenPDF `Document` + `PdfPTable`，标题元数据（report #id / type / template vN / generatedAt）+ 每 SheetDef 一表 |
| `ReportExportService`（@Service） | `report/application/export/` | `exportXlsx(id, actorId): ByteArray` / `exportPdf(id, actorId): ByteArray`——readOnly 载入快照 → 构建 SheetDefs → 渲染 → `AuditService.record(REPORT_EXPORT)` → 返回字节；快照缺失 → `BusinessException(404)` |
| `ReportSnapshotController.export`（改造） | `report/api/` | 返型 `ApiResponse<Any>` → `ResponseEntity<Any>`；`json`/`html` 分支行为不变；`xlsx`/`pdf` 分支设 Content-Type + Content-Disposition；新增 `authentication` 参数取 actorId（R-M16-D4） |

**布局规范（`ReportExportModel`，payload 根元素区分对象/数组）：**

| snapshotType | payload 根 | Sheet 布局 |
|---|---|---|
| `SCAN_SUMMARY` | 对象 `{scanTaskId, engine, status, findingCount, bySeverity}` | 单表 `ScanSummary`：表头 `[ScanTaskId, Engine, Status, FindingCount, Critical, High, Medium, Low]` + 1 行；`bySeverity` 按 CRITICAL/HIGH/MEDIUM/LOW 取（大小写不敏感，缺 → `0`） |
| `COMPLIANCE` | 对象 `{projectId, evaluationId, score, totalItems, passed, failed, warning, manual, skipped, items[], checklistVersionId}` | 双表 `Summary`：表头 `[ProjectId, EvaluationId, Score, TotalItems, Passed, Failed, Warning, Manual, Skipped, ChecklistVersionId]` + 1 行（score 为 null → 空串）；`Items`：表头 `[ItemCode, Result, FindingCount]` + items 逐行 |
| `TREND` | **JSON 数组** `[{evaluatedAt, score, failed}]` | 单表 `Trend`：表头 `[EvaluatedAt, Score, Failed]` + 逐点一行（score null → 空串） |
| 兜底（未知/空） | 对象 → 表 `Data`：`[Key, Value]` 顶层键值（镜像 `HtmlReportRenderer`）；数组 → 表 `Data`：`[Index, Value]` | 空 payload → 单空表，仍产出合法 xlsx/pdf（不 400） |

**端点契约（`GET /api/v1/reports/snapshots/{id}/export`）：**

| format | 返回 | Content-Type | Content-Disposition |
|---|---|---|---|
| `json` | `{data: <payload tree>}` | `application/json`（既有） | 无（既有） |
| `html` | `{data: "<html…>"}` | `application/json`（既有，字符串包裹） | 无（既有） |
| `xlsx` | 二进制 | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | `attachment; filename="report-<id>-<snapshotType lowercase>.xlsx"` |
| `pdf` | 二进制 | `application/pdf` | `attachment; filename="report-<id>-<snapshotType lowercase>.pdf"` |
| 其他 | `{data:…}` 400 `unsupported export format: <f>` | `application/json`（既有错误形态） | 无 |

### 3.3 B. 审计查询组件（module-common 新增 2 + module-admin 新增 2 + 1 仓库扩展）

| 组件 | 位置 | 职责 |
|---|---|---|
| `AuditLogFilter`（`data class`） | `module-common/common/audit/` | `(module, action, userId, resourceType, resourceId, from, to)` 全可空 |
| `AuditQueryService`（@Service） | `module-common/common/audit/` | `search(filter, page, size): Page<AuditLog>`——AND 组合 Specification；`page<0→400`、`size.coerceIn(1,100)`、`Sort id DESC` |
| `AuditLogRepository`（改造） | `module-common/common/audit/` | `interface AuditLogRepository : JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog>` |
| `AuditLogView`（`data class`） | `module-admin/admin/api/` | `(id, userId, action, module, resourceType, resourceId, detail, ip, occurredAt)`；`detail` 原样字符串（JSONB 原始文本，客户端自行解析） |
| `AuditLogController`（@RestController） | `module-admin/admin/api/` | `GET /api/v1/audit-logs`，`@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")`，全部过滤参数可空，→ `ApiResponse<PageResponse<AuditLogView>>` |

**端点契约（`GET /api/v1/audit-logs`）：**

| 参数 | 类型 | 说明 |
|---|---|---|
| `module` / `action` | String? | 精确匹配（audit 动作名固定枚举集，精确即可，无 LIKE） |
| `userId` / `resourceId` | Long? | 精确匹配 |
| `resourceType` | String? | 精确匹配 |
| `from` / `to` | Instant?（ISO-8601） | `occurredAt >= from` / `<= to`（含边界） |
| `page` / `size` | Int | 默认 0 / 20；`page<0→400`；`size.coerceIn(1,100)` |
| — | — | 排序固定 `id DESC`（分页确定性）；空过滤 = 全量倒序 |

**RBAC**：方法级 `@PreAuthorize`；SecurityConfig 无需新增路径规则（`/api/v1/audit-logs` 落 `anyRequest().authenticated()`，方法注解接管角色）。`/api/v1/audit-logs` 不得改挂 `/api/v1/admin/**` 前缀（R-M16-D5，filter 层会把 AUDITOR 挡 403）。

### 3.4 配置

**零新增配置**：导出为端点驱动、审计为查询，均无新 application.yml 键、无 DDL、无 Flyway 迁移（复用 `audit_log` + `report_snapshot`）。仅 `gradle/libs.versions.toml` 增 `openpdf` 版本 + `poi-ooxml`/`openpdf` 两个 library 别名。

### 3.5 测试

**单元（module-report / module-common）：**
- `ReportExportModelTest`：SCAN_SUMMARY（bySeverity 展开、缺失档位补 0）、COMPLIANCE（Summary + Items 双表逐行）、TREND（数组根逐行、score null → 空串）、兜底对象/数组、空 payload → 空表不 400
- `XlsxRendererTest`：渲染后 POI `XSSFWorkbook(ByteArrayInputStream)` 回读——sheet 名、表头单元格、数据行值逐一断言
- `PdfRendererTest`：渲染后 OpenPDF `PdfReader` 回读——页数 ≥1、`%PDF` 魔数、文本含关键单元格（PdfReader 文本抽取无需额外测试依赖）
- `ReportExportServiceTest`：mock snapshotRepository + AuditService——exportXlsx/exportPdf 载入快照 → 渲染 → 断言 `AuditService.record` 收到 `REPORT_EXPORT`/`resourceId`/`userId`；快照缺失 → 404
- `ReportSnapshotControllerTest`（扩展既有）：json/html 断言不变；xlsx/pdf → 200 + Content-Type + Content-Disposition + 字节；未支持 format → 400
- `AuditQueryServiceTest`（module-common）：mock AuditLogRepository——search 委托 Specification、负 page → 400、size 钳制

**集成（app-server，live Testcontainers PG，`M16-*` 数据前缀）：**
- `report/M16ReportExportIntegrationTest`：镜像 `M12ReportIntegrationTest` setup（项目 + checklist version + PUBLISHED 模板）→ 生成 COMPLIANCE 快照 → `GET /reports/snapshots/{id}/export?format=xlsx` 断言 200 + Content-Type + Content-Disposition + 非空字节（`XSSFWorkbook` 可开）；`format=pdf` → 200 + `application/pdf` + 字节以 `%PDF` 开头；`format=bad` → 400；导出后 `audit_log` 增 `REPORT_EXPORT` 行（经 repository 断言）。`@WithMockUser`
- `admin/M16AuditLogIntegrationTest`：`AuditService.record` 播种多条目（区分 module/action/userId + detail 标记 `m16-audit`）→ AUDITOR 全量倒序 + 单/多条件过滤（module/action/userId/resourceType+resourceId/from-to 时间窗）+ 分页切片（size=2 翻页）+ 负 page→400 + size 钳制（size=500→100）；ADMIN → 200、COMPLIANCE_MANAGER → 403、未认证 → 401。`@WithMockUser(roles=…)`

**验证：** `./gradlew build` 全绿（module-report 单测 + module-common 单测 + app-server 集成套件 + 共享 Testcontainers PG）。

---

## 4. 顺序与依赖

| 里程碑 | 主题 | 前置 |
|---|---|---|
| **M16** | 报表导出（Excel/PDF）+ 审计日志查询 | M15（快照 P3-D3、audit_log、C2 分页硬化均就绪）。无 DDL、无新运行时配置。 |
| M17 | 通知渠道真实投递 | M16 |
| M18 | Quartz 定时扫描调度 | M16/M17 |

建议任务划分（实施计划最终确定）：
1. **导出核心**：目录增 `poi-ooxml`/`openpdf` + `SheetDef`/`ReportExportModel`/`XlsxRenderer`/`PdfRenderer` + 四套单测（独立可测，独立审查门）
2. **导出接线**：`ReportExportService`（含审计留痕）+ `ReportSnapshotController` 改 `ResponseEntity` + 控制器测试扩展
3. **审计查询**：`AuditLogFilter`/`AuditQueryService`/`AuditLogRepository` 扩展（module-common）+ `AuditLogView`/`AuditLogController`（module-admin）+ 单测
4. **M16 集成测试**：`M16ReportExportIntegrationTest` + `M16AuditLogIntegrationTest` + 全量 build

---

## 5. 全局约束（本阶段隐式生效，逐字沿用基线 §3.1/§4.8/§11/§13、phase2 §2、M12–14 §7、M15 §5）

- **模块依赖**：module-report 只增 `poi-ooxml`（BOM 管版本）+ `openpdf`（catalog pin）；module-admin **零新模块依赖**（module-common 已在依赖面内）；module-common 零新运行时依赖（`JpaSpecificationExecutor` 属 `starter-data-jpa`，已 api 暴露）
- **红线**：`audit_log` 只增不改不删——`AuditQueryService` 严格只读，绝无写路径；报告快照不可变——导出纯读，`ReportService` 零改动；不硬编码合规规则；历史扫描结果不可改
- **无 DDL**：复用 `audit_log` + `report_snapshot`，零 Flyway 迁移
- **共享 Testcontainers**（`max_connections=300` 保持）；数据前缀 `M16-*` 与既有里程碑不相交
- **RBAC 顺序敏感**：`/api/v1/audit-logs` 不得挂 `/api/v1/admin/**` 前缀（R-M16-D5）；报表导出角色面不变（认证用户即可）
- **指标口径**：导出只读快照 payload，不改动 `ReportMetrics`/`ReportService` 的聚合口径
- **许可证**：OpenPDF 为 LGPL——若组织许可政策不接受，退 Apache PDFBox（`SheetDefs→ByteArray` 渲染器接口不变，仅换实现；`%PDF` 魔数断言与 `PdfReader` 回读方式随实现调整）
