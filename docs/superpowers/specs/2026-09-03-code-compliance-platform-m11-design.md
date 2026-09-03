# M11 设计 — 多引擎集成（Gitleaks + Trivy）

> **状态**：草案（待用户审阅）
> **权威来源**：本设计依据 `plan.md`「扫描引擎适配规范 / 新增扫描引擎指引 / §7.3 指纹规范 / 统一 Finding 模型」与 `2026-09-02-code-compliance-platform-design.md`（base design）§1.2/§7/§8/§12。冲突时以 plan.md 与 base design 为准。
> **范围（用户 2026-09-03 确认）**：接入 **Gitleaks**（密钥/敏感信息扫描，代码类）+ **Trivy**（依赖漏洞扫描，依赖类）。依赖类 finding 的字段与指纹为**首次落地**（plan.md §7.3 依赖类指纹从未实现）。

---

## 1. 目标与完成标准

在 M1-M10 的引擎接入框架（`ScanEngineAdapter` 五方法契约 + `EngineAdapterRegistry` 自动注册 + 编排器五阶段管线）之上，按 plan.md「新增扫描引擎」八步接入两个引擎，打通「代码类 + 依赖类」双类 finding：

1. **GitleaksAdapter** —— 代码类，镜像 Semgrep 五阶段模式（`engine = "GITLEAKS"`）。
2. **TrivyAdapter** —— 依赖类（`engine = "TRIVY"`），首次落地：
   - 依赖类字段：`RawFinding` / `NewFinding` / `Finding` 实体 + **V12 迁移**补齐 `package_name / package_version / fixed_version / cve_id / cvss_score`（plan.md 统一 Finding 模型明确列出的字段，现有模型缺失）。
   - 依赖类指纹：`FingerprintGenerator.generateDependency(projectId, packageName, packageVersion, cveId)`（plan.md §7.3 字面）。
3. **编排器接线**：`ScanOrchestrator` 将 `RawFinding` 新字段透传进 `NewFinding`；`app.scan.checkout-engines` 加入 `GITLEAKS,TRIVY`（二者都扫检出目录，需触发 GitCheckout）。
4. **配置**：新增 `app.gitleaks.*`、`app.trivy.*` 段（command / timeout-seconds，镜像 `app.semgrep.*`）。

**验收**：每任务结束 `./gradlew build` 全绿；M11 完成 = 双引擎适配器 + 依赖字段/指纹 + 编排器接线 + 配置 + 单元与集成测试全绿 + 既有套件（含 M8 引擎契约、M6/M9 各 STUB 集成测试）零回归。

---

## 2. 现状（引擎接入框架已就绪）

- `ScanEngineAdapter`（module-result/engine）：`engine: String` + `supports` + 五方法（`prepareScan / executeScan / collectResult / normalizeResult / cleanup`）全带默认实现，另有兼容 `scan()` 默认管线。
- `ScanContext`：`scanTaskId / projectId / repoUrl / ref / workDir / commitId / timeoutSeconds / paramsJson / configJson`。
- `RawFinding`：`engineRuleId / ruleName / filePath / line / severity / message / codeSnippet / category` —— **无依赖类字段（本 M11 补齐）**。
- `EngineAdapterRegistry`（module-result/engine）：Spring 自动收集所有 `ScanEngineAdapter` bean，按 `engine.uppercase()` 键注册；**新引擎零注册代码**。
- 编排器（`ScanOrchestrator.executeAsync`）：`registry.get(task.engine)` → `checkout-engines` 门控检出 → 五阶段 → `ruleQueryService.publishedRuleByEngineRuleId(engine, engineRuleId)` 映射平台规则 → `findingService.upsertByFingerprint(projectId, scanTaskId, engine, normalized)` → 合规评估。
- `app.scan.checkout-engines: SEMGREP`（仅 SEMGREP 触发 GitCheckout；STUB* 测试引擎跳过，commitId 保持 null —— M8 已断言）。
- 现有测试全部用 STUB 引擎（STUB / STUBM6 / STUBM8 / STUBM9 / STUBR），无真实二进制依赖；Semgrep 适配器为 fixture + MockK 单测（无 SemgrepCli 独立测试）。

**结论**：新引擎接入 = 新增 adapter 包 + 编排器透传新字段 + 配置，框架层零改动；唯一的结构性工作是把 `RawFinding/NewFinding/Finding` 的依赖类字段与依赖类指纹补齐。

---

## 3. 统一模型扩展：依赖类字段（module-result 契约）

### 3.1 `RawFinding`（module-result/engine/ScanEngineAdapter.kt）

追加**可空依赖字段**（放参数末尾，带默认值 → 既有位置参数调用点零破坏，含 M8 测试的 7 参构造）：

```kotlin
data class RawFinding(
    val engineRuleId: String,
    val ruleName: String? = null,
    val filePath: String,
    val line: Int? = null,
    val severity: String,
    val message: String? = null,
    val codeSnippet: String? = null,
    val category: String? = null,
    // M11 依赖类字段（Trivy 使用；代码类引擎恒为 null）
    val packageName: String? = null,
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)
```

### 3.2 `NewFinding`（module-result/application/FindingService.kt）

同构追加（末尾可空 + 默认值，编排器既有 8 参调用点零破坏）：

```kotlin
data class NewFinding(
    val ruleCode: String,
    val ruleName: String?,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val category: String?,
    val message: String?,
    val codeSnippet: String?,
    val packageName: String? = null,
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)
```

### 3.3 `Finding` 实体 + V12 迁移

`module-result/domain/Finding.kt` 追加 5 个可空字段；`app-server/src/main/resources/db/migration/V12__finding_dependency_fields.sql`：

```sql
ALTER TABLE finding
    ADD COLUMN package_name    TEXT,
    ADD COLUMN package_version TEXT,
    ADD COLUMN fixed_version   TEXT,
    ADD COLUMN cve_id          VARCHAR(64),
    ADD COLUMN cvss_score      NUMERIC;
```

- 全部可空：既有 finding 行不迁移、不填默认值。
- `filePath` 语义：依赖类 finding 的 `filePath = Trivy Target`（锁文件路径，如 `package-lock.json`），保持 NOT NULL 不变。
- `FindingView`（`FindingLifecyclePort` / `FindingLifecycleService.toView`）同步追加 5 个可空字段（末尾默认值），供 admin / 报表展示。

### 3.4 兼容性

- 位置参数追加在末尾 + 默认值 → `ScanOrchestrator` 现有 `NewFinding(...)` 调用点、M8 测试 `RawFinding(...)` 调用点**编译零破坏**。
- 唯一**必须同步**的调用点：`ScanOrchestrator` 构造 `NewFinding` 时透传新字段（§6）。

---

## 4. 依赖类指纹与分流（首次落地 plan.md §7.3）

### 4.1 `FingerprintGenerator.generateDependency`

```kotlin
@Component
class FingerprintGenerator {
    fun generate(projectId: Long, ruleCode: String, filePath: String, lineNumber: Int?, codeSnippet: String?): String { ... }  // 既有代码类

    /** plan.md §7.3 依赖类指纹：sha256(projectId|packageName|packageVersion|cveId) */
    fun generateDependency(projectId: Long, packageName: String, packageVersion: String?, cveId: String): String {
        val normalized = listOf(projectId.toString(), packageName, packageVersion ?: "", cveId).joinToString("|")
        // 同 generate 的 SHA-256 十六进制摘要
    }
}
```

- 输入拼接与代码类同款 `|` 分隔（避免歧义）；`uq_finding_fp` 唯一索引对两类指纹天然兼容（哈希空间不冲突）。

### 4.2 `FindingService.upsertByFingerprint` 分流

```kotlin
val fingerprint = if (f.packageName != null || f.cveId != null)
    fingerprintGenerator.generateDependency(projectId, f.packageName!!, f.packageVersion, f.cveId!!)
else
    fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
```

- 创建 `Finding` 行时同步落 5 个依赖字段（`packageName / packageVersion / fixedVersion / cveId / cvssScore`）。
- 去重/回归语义不变：同指纹命中 → `REAPPEARED` + 状态机处置（ACTIVE 保持 / WAIVED 跳过 / FIXED·CLOSED 回归 CONFIRMED）—— 依赖类 finding 首次落地即复用同一状态机。
- 依赖类 finding 的 `ruleCode` 为平台映射规则码（CVE 规则），`severity` 已归一化。

---

## 5. GitleaksAdapter（代码类，镜像 Semgrep 模式）

包：`com.example.compliance.engineadapter.gitleaks`（module-engine-adapter）。

### 5.1 `GitleaksCli`

```kotlin
interface GitleaksCli { fun run(targetPath: String): String }   // 返回报告 JSON 内容

@Component
class ProcessGitleaksCli(
    @Value("\${app.gitleaks.timeout-seconds:300}") private val timeoutSeconds: Long,
) : GitleaksCli {
    // cmd = ["gitleaks", "dir", targetPath, "--report-format", "json", "--report-path", reportFile, "--no-banner"]
    // 关键设计（吸取 R-8.2-b 教训 + 避免 JSON 污染）：
    //   - JSON 报告经 --report-path 直接落盘（stdout/stderr 只承载日志，不被合并进 JSON）
    //   - stdout/stderr 各自重定向到独立临时文件（redirectErrorStream=false + 双 redirect），
    //     无未读管道 → 不会因管道缓冲占满造成假超时（比 SemgrepCli 的 merge 更稳）
    //   - waitFor(timeoutSeconds) + 超时 destroy/destroyForcibly（镜像 SemgrepCli）
    //   - exit 语义：0=无泄漏 / 1=有泄漏，均视为成功（返回报告内容）；其它退出码抛异常
    //   - 读 reportFile 返回；临时文件 finally 删除
}
```

### 5.2 `GitleaksResultParser`

解析 gitleaks JSON 报告（leaks 数组），映射 `RawFinding`：

| gitleaks 字段 | RawFinding |
|---|---|
| `RuleID` | `engineRuleId` |
| `File` | `filePath` |
| `StartLine` | `line` |
| `Match` / `Secret` | `codeSnippet`（优先 Match，缺省 Secret） |
| `Description` | `message` |
| `Severity`（新版有，缺省 MEDIUM 由 mapper 兜底） | `severity`（保留原生，normalize 映射） |
| — | `category = null`，`ruleName = null` |

报告结构：gitleaks `--report-format json` 输出**顶层数组** `[ {leak}, ... ]`（非对象包裹）；无泄漏 → `[]`。解析须兼容空数组。

### 5.3 `GitleaksSeverityMapper`

```kotlin
class GitleaksSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "MEDIUM"   // 缺省（旧版无 Severity 字段）
    }
}
```

### 5.4 `GitleaksAdapter`

镜像 `SemgrepAdapter` 五方法：`engine = "GITLEAKS"`；`executeScan` 调 `cli.run(scanTarget)` → 报告落盘 stdoutRef（按 scanTaskId 派生，并发隔离）；`collectResult` 读 stdout 文件 → `parser.parse`；`normalizeResult` severity 映射；`cleanup` 删临时文件（幂等）；cli 异常 → `success=false` + 不落盘 stdout（F1 同款：绝不产出假干净扫描）。`scanTarget = workDir ?: repoUrl`。

---

## 6. TrivyAdapter（依赖类）

包：`com.example.compliance.engineadapter.trivy`（module-engine-adapter）。

### 6.1 `TrivyCli`

```kotlin
interface TrivyCli { fun run(targetPath: String): String }   // 返回 JSON 内容

@Component
class ProcessTrivyCli(
    @Value("\${app.trivy.timeout-seconds:600}") private val timeoutSeconds: Long,
) : TrivyCli {
    // cmd = ["trivy", "fs", targetPath, "--format", "json", "--no-progress"]
    // 同 GitleaksCli 的稳健设计：stdout/stderr 各自重定向临时文件，JSON 只从 stdout 读取
    // exit 语义：0=成功（无论是否命中漏洞）；非 0 抛异常（trivy 默认不带 --exit-code，命中不改变退出码）
    // waitFor(timeout) + 超时 destroy（镜像 SemgrepCli）
}
```

### 6.2 `TrivyResultParser`

解析 Trivy JSON：`Results[]`，每个 Result 含 `Target / Class / Type / Vulnerabilities[]`。每个 Vulnerability 映射一个 `RawFinding`：

| Trivy 字段 | RawFinding |
|---|---|
| `VulnerabilityID`（CVE） | `engineRuleId`（plan.md 示例 `trivy.CVE-2024-XXXX` 的 CVE 粒度） |
| `PkgName` | `packageName` |
| `InstalledVersion` | `packageVersion` |
| `FixedVersion` | `fixedVersion` |
| `Severity`（CRITICAL/HIGH/MEDIUM/LOW/UNKNOWN） | `severity`（保留原生，normalize 映射） |
| `CVSS`（对象：nvd/redhat 等的 V2Score/V3Score） | `cvssScore`（取分规则：优先 `nvd.V3Score` → `nvd.V2Score` → 各 vendor 最高分；均无 → null） |
| 所属 `Target`（锁文件路径） | `filePath` |
| `Title` / `Description` | `message`（缺省 Title） |
| — | `line = null`，`codeSnippet = null`，`category = null`，`ruleName = null` |

- 无漏洞 → `Results` 空数组或 `Vulnerabilities` 为空 → 返回 `emptyList()`。
- 兼容性：Result 可能含非漏洞类型（`Class` 非 os/library 时无 `Vulnerabilities`）——只处理有 `Vulnerabilities` 的项。

### 6.3 `TrivySeverityMapper`

```kotlin
class TrivySeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "CRITICAL" -> "CRITICAL"
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "LOW"   // UNKNOWN 等兜底（与 SemgrepSeverityMapper 的 else->LOW 一致）
    }
}
```

### 6.4 `TrivyAdapter`

镜像 `SemgrepAdapter` 五方法：`engine = "TRIVY"`；`executeScan` → stdout 落盘 stdoutRef（scanTaskId 派生）；`collectResult` → `parser.parse`；`normalizeResult` severity 映射（依赖字段原样保留）；`cleanup` 幂等删临时文件；cli 异常 → `success=false`。`scanTarget = workDir ?: repoUrl`。

---

## 7. 编排器接线与配置

### 7.1 `ScanOrchestrator` 透传新字段

`ScanOrchestrator.executeAsync` 构造 `NewFinding` 处（现第 109-112 行）追加透传：

```kotlin
normalized += NewFinding(
    rule.ruleCode, rule.name, rawFinding.filePath, rawFinding.line,
    rawFinding.severity, rawFinding.category, rawFinding.message, rawFinding.codeSnippet,
    rawFinding.packageName, rawFinding.packageVersion, rawFinding.fixedVersion,
    rawFinding.cveId, rawFinding.cvssScore,
)
```

### 7.2 `application.yml`

```yaml
app:
  gitleaks:
    command: gitleaks        # 预留（ProcessBuilder 用）
    timeout-seconds: 300
  trivy:
    command: trivy           # 预留
    timeout-seconds: 600
  scan:
    checkout-engines: SEMGREP,GITLEAKS,TRIVY   # GITLEAKS/TRIVY 都扫检出目录 → 触发 GitCheckout
```

> `checkout-engines` 变更对既有测试零影响：全部测试引擎（STUB*）不在列表，commitId 保持 null（M8 断言仍成立）。

### 7.3 规则映射（零代码改动，数据驱动）

- 新引擎的规则经既有 `rule_engine_binding` 绑定：`ruleService.addEngineBinding(rule.id, AddEngineBindingCommand("GITLEAKS", "<RuleID>", null))` / `("TRIVY", "<CVE-ID>", null)`。
- `publishedRuleByEngineRuleId("GITLEAKS"|"TRIVY", engineRuleId)` 按既有 JPQL 命中已发布规则。
- **不预置种子规则**（与 M8 同策略：集成测试自建规则；CVE 规则无界，不适合预置）。

---

## 8. 测试

### 8.1 module-engine-adapter 单测（镜像 Semgrep）

| 测试 | 内容 |
|---|---|
| `GitleaksResultParserTest` | fixture `resources/gitleaks/basic.json`：多 leak 解析、空数组、缺 Severity 缺省 |
| `GitleaksSeverityMapperTest` | HIGH/MEDIUM/LOW 直通、缺省 MEDIUM |
| `GitleaksAdapterTest` | MockK cli：执行/采集/归一化/清理；cli 异常→success=false 且不落盘；scanTaskId 并发隔离；engine 名 |
| `TrivyResultParserTest` | fixture `resources/trivy/basic.json`：多 vuln、CVSS 取最大、无漏洞空结果、含非漏洞 Result 兼容 |
| `TrivySeverityMapperTest` | CRITICAL/HIGH/MEDIUM/LOW 直通、UNKNOWN→LOW |
| `TrivyAdapterTest` | 同 GitleaksAdapterTest 形态 |

> 与 Semgrep 一致：CLI 进程本身不做单测（exit 语义经 adapter 错误映射路径覆盖 + 真实运行验证）。

### 8.2 module-result 单测

- `FindingServiceTest` 扩展：依赖类 finding → 依赖指纹 + 字段落库；同指纹复现 → REAPPEARED + 状态机处置；代码类 finding 路径不变。
- `FingerprintGeneratorTest`（如已有则扩展，否则新增）：`generateDependency` 确定性、输入区分度。

### 8.3 app-server 集成测试（STUB 模式，镜像 M8）

- **新引擎契约**：注册 `STUBG` / `STUBT` 适配器（@TestConfiguration，镜像 STUBM8）→ 触发扫描 → 五阶段驱动 + cleanup finally + 非 checkout 引擎 commitId null + finding 归属。
- **checkout-engines 配置断言**：`@Value("app.scan.checkout-engines")` 注入 set，断言含 `SEMGREP / GITLEAKS / TRIVY`（证明新引擎在检出白名单）。
- 数据前缀 `M11-*`；共享 Testcontainers、`SmokeFirstClassOrderer` 不变。

### 8.4 不进 M11（真实二进制 E2E 延后）

- 与 Semgrep 同策略：**CI/测试不要求安装 gitleaks/trivy 二进制**；真实引擎端到端（本机装二进制跑真扫描）列为后续可选，不进本里程碑。

---

## 9. 涉及改动面 / 模块依赖

| 文件 | 变更 |
|---|---|
| `module-result/engine/ScanEngineAdapter.kt` | `RawFinding` +5 可空字段（末尾默认值） |
| `module-result/application/FindingService.kt` | `NewFinding` +5 字段；`upsertByFingerprint` 依赖类分流 + 落字段 |
| `module-result/infrastructure/FingerprintGenerator.kt` | `generateDependency` |
| `module-result/domain/Finding.kt` | +5 可空字段 |
| `module-result/application/FindingLifecyclePort.kt` + `FindingLifecycleService.kt` | `FindingView` +5 可空字段 + `toView` 映射 |
| `module-engine-adapter/.../gitleaks/`（新） | `GitleaksAdapter / GitleaksCli / GitleaksResultParser / GitleaksSeverityMapper` |
| `module-engine-adapter/.../trivy/`（新） | `TrivyAdapter / TrivyCli / TrivyResultParser / TrivySeverityMapper` |
| `module-scan/application/ScanOrchestrator.kt` | `NewFinding` 透传新字段 |
| `app-server/db/migration/V12__finding_dependency_fields.sql`（新） | +5 列 |
| `app-server/resources/application.yml` | `app.gitleaks.*`、`app.trivy.*`、`checkout-engines` |
| 测试 | module-engine-adapter 6 个新单测 + fixtures；module-result 单测扩展；app-server M11 集成测试 |

**模块依赖零变化**：`module-engine-adapter` 仍只依赖 common + result；`module-scan` 仍依赖 result 的 value types（`RawFinding/NewFinding` 均属契约，P2-D5 合规）；无新跨模块边。

---

## 10. 不进 M11（维持延后）

- 真实二进制 E2E（gitleaks/trivy 本机扫描）—— 后续可选。
- 质量门禁 / 豁免审批流 / 组织级看板 / 智能分析 / AI 修复建议（P2 其余项）。
- 其他引擎（SonarQube / Detekt / Dependency-Check 等）。
- 原始结果落对象存储（MinIO/S3）、`rawJson` 持久化 —— JSONB/临时文件维持现状。
