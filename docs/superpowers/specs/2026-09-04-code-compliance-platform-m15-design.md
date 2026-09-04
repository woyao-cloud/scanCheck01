# 代码合规扫描平台 M15 — SonarQube 引擎接入设计

> 本文档是 M15 里程碑的整体规划设计：接入第 6 个扫描引擎 SonarQube（服务端引擎范式）。锁定目标、范围、关键设计决策与测试策略。里程碑关闭时另行出具实施计划，按序交付、逐里程碑确认。

**基线 spec：** `docs/superpowers/specs/2026-09-02-code-compliance-platform-design.md`（全局约束/枚举/模块划分/安全红线继续约束本阶段）
**既有阶段 spec：** `2026-09-03-code-compliance-platform-phase2-design.md`（M6–M9）、`2026-09-03-code-compliance-platform-m11-design.md`（多引擎）、`2026-09-04-code-compliance-platform-m12-m14-design.md`（M12–M14，已交付）
**前置交付：** M14（HEAD `8c2f895`，local main 未 push）

---

## 1. 目标与范围

平台目标「支持集成多种扫描引擎，如 Semgrep、SonarQube、Trivy、Dependency-Check、Detekt、Gitleaks」——现已接入 Semgrep/Gitleaks/Trivy/Dependency-Check/Detekt 五个 CLI 引擎；**SonarQube 是最后一个缺失引擎**，且是首个**服务端型引擎**（分析在 SQ 服务器侧异步执行，结果经 REST API 拉取），与既有 CLI 引擎范式不同，需在既有五方法契约内做适配层设计。

**本里程碑目标：**
1. 接入 `SONARQUBE` 引擎：`sonar-scanner` 提交分析 → 轮询 CE task → 拉取 issue → 标准化为代码类 RawFinding（P3-D8 both-null）。
2. **五方法 `ScanEngineAdapter` 契约不变**：轮询在 `executeScan` 内同步完成（语义与 CliExecutor 超时一致）。
3. 凭证复用 M3 加密机制：`Repository.credentialRef` 承载 SonarQube token，编排器解密注入 `ScanContext`。
4. 测试三层（STUBSONAR 桩 e2e + HTTP mock 单测 + 门控真服务器 e2e），CI 无需真 SQ 服务器。

### 1.1 非目标（YAGNI，仍延后）

- SonarQube Webhook 回调（异步两段状态机）——本轮轮询足矣，改动面最小
- 多凭证模型（一 repo 同时承载 git + SQ 多个 token）——`credentialRef` 单凭证先支持 SQ；多凭证后续里程碑
- SonarQube issue 分页拉取（total > 500）——首屏 `ps=500` 单页；超量后续里程碑
- SonarQube 质量门禁（quality gate）同步、分支/PR 分析、增量分析配置
- 其他服务端型引擎（如 SonarCloud、自定义 REST 引擎）——SonarQube 为首个范式样板
- 真 SQ 服务器纳入 CI 强制门禁——仅门控可选运行

---

## 2. 决策记录（M15 新增/变更）

| 编号 | 问题 | 决策 |
|---|---|---|
| **R-M15-D1** | 完成等待方式 | **executeScan 内同步轮询 CE task**（契约不变）。`executeScan`：跑 sonar-scanner → 正则提取 CE taskId → 轮询 `/api/ce/task` 至 SUCCESS/FAILED/CANCELED 或超时 → 拉取 issue JSON 落盘 stdout 文件。语义与本地 CLI 引擎一致（阻塞分钟级，@Async 编排器内无害）。 |
| **R-M15-D2** | 凭证存储 | 复用 `Repository.credentialRef`（M3 AES-GCM 加密）承载 SonarQube token；编排器构造 ScanContext 时 `runCatching { credentialCrypto.decrypt(repo.credentialRef) }.getOrNull()` 注入 `ScanContext.credentialToken`。无 DDL。既有引擎忽略该字段，零行为变化。 |
| **R-M15-D3** | token 传递不进 argv | **CliExecutor.Config 追加默认 `env: Map<String,String> = emptyMap()`**（非破坏，既有命名参数调用点零改动）；ProcessSonarQubeCli 设 `SONAR_TOKEN` 环境变量。token 绝不进进程参数（进程列表泄露）。 |
| **R-M15-D4** | projectKey 派生 | 适配器从 `context.repoUrl` 派生：`repoUrl.substringAfterLast("/").removeSuffix(".git")`。无 DDL、无新契约字段。SQ 服务器侧按 key 聚合，URL 稳定则 key 稳定（URL 变更 → 新 SQ 项目，可接受）。后续里程碑可加显式配置字段覆盖。 |
| **R-M15-D5** | 结果获取时机 | **issue 拉取在 executeScan**（SUCCESS 后 GET `/api/issues/search?componentKeys=<key>&resolved=false&ps=500`，JSON 写 stdout 文件）；`collectResult` 纯本地解析（与其他引擎 stdout-file 语义一致，collect 无网络）。 |
| **R-M15-D6** | severity 映射 | BLOCKER→CRITICAL、CRITICAL→HIGH、MAJOR→MEDIUM、MINOR→LOW、INFO→LOW、else→LOW。**首个可达 CRITICAL 的引擎**（SQ 有 BLOCKER）。parser 原生透传、normalizeResult 映射（与其他引擎一致）。 |
| **R-M15-D7** | HTTP 客户端 | Spring `RestClient`（spring-web，BOM 管版本），构造器注入（测试可注入绑定 MockRestServiceServer 的 RestClient）。无新外部依赖。module-engine-adapter 增 `implementation("org.springframework:spring-web")`。 |
| **R-M15-D8** | 测试策略 | 三层：(a) 单元——parser/mapper/adapter + `SonarQubeApiClient`（MockRestServiceServer，spring-test 已在 testImplementation）+ fixture；(b) STUBSONAR 桩 e2e（镜像 M11/M14，`M15-*` 前缀）；(c) 门控 `APP_SONAR_E2E=true` 真服务器 e2e（可选，需 operator 提供 SQ + sonar-scanner）。 |
| **R-M15-D9** | CE taskId 提取 | 从 sonar-scanner 合并输出正则提取 `ce/task\?id=([A-Za-z0-9_-]+)`；提取失败 → 扫描失败（F1：不产出假干净扫描）。 |
| **R-M15-D10** | 引擎类型 | 代码类引擎：RawFinding 仅 8 代码字段，依赖字段恒 null（P3-D8 both-null）。category = issue.type（BUG/VULNERABILITY/CODE_SMELL，信息性字符串，无枚举约束）。 |

---

## 3. 组件与数据流

### 3.1 引擎总览

```
┌─ ScanOrchestrator (@Async) ────────────────────────────────┐
│ 1. checkout-engines ∋ SONARQUBE → GitCheckout → workDir    │
│ 2. credentialToken = decrypt(repo.credentialRef)           │
│ 3. ScanContext(scanTaskId, projectId, repoUrl, ref,        │
│                 workDir, commitId, credentialToken)        │
│ 4. five-stage: prepare → execute → collect → normalize     │
└────────────────────────────────────────────────────────────┘
                 │
                 ▼  executeScan（三段，阻塞至完成/超时）
┌─ SonarQubeAdapter ────────────────────────────────────────┐
│ ① ProcessSonarQubeCli.run(workDir, projectKey, token,     │
│     serverUrl) → 合并输出（含 CE task URL）                 │
│ ② 正则提取 taskId → SonarQubeApiClient.ceTaskStatus()      │
│    轮询 5s 步长 → SUCCESS/FAILED/CANCELED/超时              │
│ ③ SonarQubeApiClient.issues() → issue JSON → stdout 文件    │
└────────────────────────────────────────────────────────────┘
                 │
                 ▼  collectResult：读 stdout 文件 → SonarQubeResultParser → RawFinding（代码类）
```

### 3.2 组件清单（module-engine-adapter 新增 5 文件 + 1 契约字段）

| 组件 | 位置 | 职责 |
|---|---|---|
| `SonarQubeCli`（interface + `ProcessSonarQubeCli` @Component） | `engineadapter/sonarqube/` | `run(workDir, projectKey, token, serverUrl): String`——`sonar-scanner` 五参数（`-Dsonar.projectKey/-Dsonar.host.url/-Dsonar.projectBaseDir=<workDir>/-Dsonar.sources=.`），`SONAR_TOKEN` env，mergeErrorStream=true，successExitCodes={0}，返回合并输出（含 CE task URL） |
| `SonarQubeApiClient` @Component | `engineadapter/sonarqube/` | `ceTaskStatus(serverUrl, taskId, token): String`（解析 `task.status`）；`issues(serverUrl, projectKey, token): String`（原始 JSON）。Bearer token 认证 |
| `SonarQubeResultParser` @Component | `engineadapter/sonarqube/` | issue JSON → `List<RawFinding>`（代码类 8 字段；component 去 `projectKey:` 前缀→filePath；line；severity 原生；message；category=type） |
| `SonarQubeSeverityMapper` @Component | `engineadapter/sonarqube/` | R-M15-D6 映射表 |
| `SonarQubeAdapter` @Component | `engineadapter/sonarqube/` | `engine="SONARQUBE"`，五方法（executeScan 三段 + F1 语义） |
| `ScanContext.credentialToken: String?` | module-result `ScanEngineAdapter.kt` | **追加尾部默认值** → 既有位置调用点零破坏（同 RawFinding 依赖字段模式） |
| `CliExecutor.Config.env: Map<String,String>` | module-engine-adapter `cli/CliExecutor.kt` | **追加默认空 Map**（非破坏）；`run` 内 `pb.environment().putAll(env)` |

### 3.3 编排器改动（module-scan `ScanOrchestrator`）

- 注入 `CredentialCrypto`（module-project infrastructure，module-scan 已依赖 module-project）
- 构造 ScanContext 追加：`credentialToken = repo.credentialRef?.let { runCatching { credentialCrypto.decrypt(it) }.getOrNull() }`（防御性：凭证损坏不阻断扫描，SQ 引擎会在认证时显式失败）
- 其余零改动；既有五引擎忽略新字段

### 3.4 executeScan 语义（F1 对齐既有引擎）

成功路径：scanner 退出码 0 → 提取 taskId → CE SUCCESS → issue JSON 落盘 → `ScanExecutionResult(success=true, stdoutRef=...)`。

失败路径（任一 → `success=false`，**不落盘 stdout**，绝不产出假干净扫描）：token 缺失 / scanner 非 0 退出（含 stderr tail 诊断）/ taskId 提取失败 / CE FAILED 或 CANCELED / 轮询超时（`app.sonarqube.timeout-seconds`）/ issue 拉取失败。

### 3.5 配置（app-server `application.yml`）

```yaml
  sonarqube:
    server-url: ${SONARQUBE_SERVER_URL:http://localhost:9000}   # 必配于真实环境；默认 localhost 便于启动
    timeout-seconds: 900                                          # 轮询总超时（CE 任务时长）
  scan:
    checkout-engines: SEMGREP,GITLEAKS,TRIVY,DEPENDENCYCHECK,DETEKT,SONARQUBE   # SONARQUBE 扫检出目录 → 触发 GitCheckout
```

### 3.6 测试

**单元（module-engine-adapter）：**
- `SonarQubeResultParserTest`：fixture `sonarqube/issues.json`（2 issue：MAJOR code smell + CRITICAL vulnerability）→ 断言 engineRuleId=rule、filePath（去 projectKey 前缀）、line、severity 原生、message、category=type、依赖字段 null；空/非法 → emptyList
- `SonarQubeSeverityMapperTest`：五档直通 + else→LOW + 小写
- `SonarQubeApiClientTest`：MockRestServiceServer——ceTaskStatus 返回 SUCCESS / FAILED；issues 返回 fixture；断言 Bearer header
- `SonarQubeAdapterTest`：mock Cli + ApiClient——scanner 输出含 task URL → 提取 → SUCCESS → issues 落盘 → collect → normalize（BLOCKER→CRITICAL）；失败路径（token 缺失/taskId 缺失/CE FAILED/超时/api 失败 → success=false 无 stdout）；cleanup；engine 名
- `CliExecutor` env 生效：随 adapter 测试覆盖（不直接单测 Process*Cli，先例）

**集成（app-server）：**
- `M15EngineIntegrationTest`：STUBSONAR 内联桩（镜像 M11/M14）——代码类 finding 端到端落库（`M15-*` 前缀）+ `checkout-engines` 断言（含 SONARQUBE 与既有五引擎）+ commitId null（STUB 不在列表）
- 门控（可选）：`@EnabledIfEnvironmentVariable(named="APP_SONAR_E2E", matches="true")` 真 SQ 服务器 e2e——operator 提供 `sonar-scanner` + SQ 服务器；默认 suite 跳过

**验证：** `./gradlew build` 全绿（module-engine-adapter 单测 + app-server 集成套件 + 共享 Testcontainers PG）。

---

## 4. 顺序与依赖

| 里程碑 | 主题 | 前置 |
|---|---|---|
| **M15** | SonarQube 引擎接入 | M14（五方法契约、CliExecutor、P3-D8 均已就绪）。无 DDL、无新运行时依赖。 |

建议任务划分（实施计划最终确定）：
1. **契约与管线**：`CliExecutor.Config.env` + `ScanContext.credentialToken` + 编排器凭证注入（跨模块 plumbing，独立审查门）
2. **SonarQube 引擎**：Cli/ApiClient/Parser/Mapper/Adapter + 单元测试 + fixture + spring-web 依赖
3. **集成测试 + 配置**：`M15EngineIntegrationTest` + `application.yml` + 全量 build

---

## 5. 全局约束（本阶段隐式生效，逐字沿用基线 §3.1/§4.8/§11/§13、phase2 §2、M12–14 §7）

- **模块依赖**：module-engine-adapter 只增 spring-web（BOM 管版本）；不新增对 module-project 的依赖（凭证经编排器注入，不经 adapter 直取）
- **P3-D8**：SonarQube 代码类引擎 both-null，恒不设依赖字段
- **RawFinding.severity 归一化**：LOW/MEDIUM/HIGH/CRITICAL；STUB 桩返回归一化值
- **checkout-engines 门控**：SONARQUBE ∈ 列表 → GitCheckout；STUBSONAR ∉ 列表 → commitId null
- **共享 Testcontainers**（max_connections=300 保持）；数据前缀 `M15-*` 与既有里程碑不相交
- **红线**：不硬编码合规规则；历史扫描结果不可改；无 DDL 变更（M15 复用 credentialRef，零迁移）
- **M15 计划缺陷修正先例**：`@EnabledIfEnvironmentVariable(named=...)`（R-M13-5）；`FindingView.lineNumber`（R-M14-7）
