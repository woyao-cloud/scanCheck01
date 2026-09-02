
## 项目简介

本项目是一个基于 Kotlin 的**代码合规扫描平台**，用于统一管理代码扫描、合规清单配置、规则管理、扫描结果集成、合规分析报告与整改闭环。

平台目标包括：

- 支持项目、应用、代码仓库统一管理
- 支持基于规则动态配置合规清单
- 支持自定义合规项详情、风险等级、整改建议
- 支持集成多种扫描引擎，如 Semgrep、SonarQube、Trivy、Dependency-Check、Detekt、Gitleaks 等
- 支持扫描结果标准化、去重、聚合与合规映射
- 支持生成扫描结果报表、合规分析报告、趋势分析报告
- 支持问题整改、豁免、复审与审计闭环

---


## 架构原则

本项目采用**模块化单体架构**，优先保证开发和部署简单，同时预留后续微服务拆分能力。

核心原则：

1. 业务模块边界清晰
2. 领域模型优先于数据库表设计
3. 扫描引擎必须通过 Adapter 接入
4. 所有扫描结果必须标准化为统一 Finding 模型
5. 合规清单、规则、报告模板必须支持版本化
6. 所有动态配置必须可审计、可回滚
7. 扫描任务必须异步执行
8. 主服务与扫描执行器职责分离
9. 不允许在业务代码中硬编码合规规则
10. 报表和合规分析必须基于统一指标模型

---

## 系统模块划分

项目建议采用如下模块结构：

```text
code-compliance-platform
├── app-server                  # 启动模块
├── module-common               # 公共能力
├── module-auth                 # 认证授权
├── module-user                 # 用户组织
├── module-project              # 项目应用
├── module-checklist            # 合规清单
├── module-rule                 # 规则中心
├── module-scan                 # 扫描任务
├── module-engine-adapter       # 扫描引擎适配
├── module-result               # 结果归一化
├── module-report               # 报表报告
├── module-remediation          # 整改闭环
├── module-notification         # 通知服务
├── module-openapi              # 对外API
└── module-admin                # 管理后台接口
```

---

## 核心领域模型

### 项目与资产

```text
Tenant          租户
Organization    组织
Project         项目
Repository      代码仓库
Application     应用
Environment     环境
```

### 合规标准与清单

```text
ComplianceStandard        合规标准
ComplianceChecklist       合规清单
ChecklistItem             合规项
ChecklistItemDetail       合规项详情
ChecklistVersion          清单版本
ChecklistBinding          项目绑定清单
```

### 规则中心

```text
Rule                      规则
RuleVersion               规则版本
RuleCategory              规则分类
RuleEngineBinding         规则与引擎绑定
RuleParameter             规则参数
RuleComplianceMapping     规则与合规项映射
RuleEvaluationPolicy      规则判定策略
```

### 扫描执行

```text
ScanTask                  扫描任务
ScanJob                   子任务
ScanEngine                扫描引擎
ScanExecutionLog          扫描执行日志
ScanArtifact              扫描产物
```

### 扫描结果

```text
Finding                   扫描发现
FindingDetail             发现详情
FindingEvidence           证据
FindingStatus             状态
FindingHistory            状态历史
FindingDuplicateGroup     去重分组
```

### 合规评估

```text
ComplianceEvaluation      合规评估任务
ChecklistItemResult       合规项结果
ChecklistSummary          清单汇总结果
ComplianceScore           合规评分
ComplianceReport          合规报告
```

### 整改与审计

```text
RemediationTask           整改任务
WaiverRequest             豁免申请
WaiverApproval            豁免审批
AuditLog                  审计日志
ReportSnapshot            报告快照
```

---

## 核心业务流程

### 扫描主流程

```text
创建扫描任务
    ↓
解析项目配置
    ↓
加载合规清单
    ↓
加载绑定规则
    ↓
分发扫描任务
    ↓
执行扫描引擎
    ↓
采集扫描结果
    ↓
结果标准化
    ↓
结果去重与聚合
    ↓
映射合规项
    ↓
生成合规结论
    ↓
生成报表与报告
```

### 合规清单配置流程

```text
创建合规标准
    ↓
创建合规清单模板
    ↓
添加合规项
    ↓
填写合规详情
    ↓
绑定自动化规则
    ↓
配置判定策略
    ↓
发布清单版本
    ↓
绑定项目
    ↓
执行扫描
    ↓
生成合规结果
```

### 整改闭环流程

```text
发现扫描问题
    ↓
确认问题
    ↓
分配责任人
    ↓
整改修复
    ↓
重新扫描
    ↓
复审确认
    ↓
关闭问题
```

---

## 扫描引擎适配规范

所有扫描引擎必须通过统一 Adapter 接入。

### Adapter 职责

```text
ScanEngineAdapter
├── supports(engineType)
├── prepareScan()
├── executeScan()
├── collectResult()
├── normalizeResult()
└── cleanup()
```

### 已规划支持的引擎

- Semgrep
- SonarQube
- Detekt
- Checkstyle
- PMD
- OWASP Dependency-Check
- Trivy
- Gitleaks
- 自定义规则引擎

### 引擎接入要求

1. 必须将原始结果转换为统一 Finding 模型
2. 必须保留原始扫描结果用于审计
3. 必须支持严重等级映射
4. 必须支持执行超时控制
5. 必须支持失败重试
6. 必须支持扫描日志采集
7. 必须支持结果去重指纹生成

---

## 统一扫描结果模型

所有扫描结果必须转换为如下统一模型：

```text
Finding
├── findingId
├── projectId
├── scanTaskId
├── engineType
├── ruleId
├── ruleCode
├── findingType
├── severity
├── title
├── description
├── filePath
├── lineNumber
├── codeSnippet
├── packageName
├── packageVersion
├── fixedVersion
├── cveId
├── cvssScore
├── licenseId
├── repository
├── branch
├── commitId
├── fingerprint
├── status
└── rawResult
```

### 严重等级统一规范

```text
CRITICAL    严重
HIGH        高危
MEDIUM      中危
LOW         低危
INFO        提示
```

---

## 规则中心规范

### 规则定义

```text
Rule
├── ruleCode
├── ruleName
├── ruleType
├── language
├── severity
├── description
├── remediation
├── status
└── version
```

### 规则执行配置

```text
RuleExecution
├── engineType
├── engineRuleId
├── parameters
├── timeoutSeconds
├── filePatterns
├── excludePatterns
└── retryPolicy
```

### 规则判定配置

```text
RuleEvaluationPolicy
├── metricType
├── operator
├── threshold
├── resultStatus
└── expression
```

### 规则状态

```text
DRAFT       草稿
TESTING     测试中
PUBLISHED   已发布
DISABLED    已停用
```

### 规则设计要求

1. 规则必须支持版本化
2. 规则必须支持发布、回滚
3. 规则必须支持参数化配置
4. 规则必须支持测试模式
5. 规则必须记录变更审计
6. 禁止在业务代码中硬编码规则逻辑
7. 自定义脚本规则必须沙箱执行

---

## 合规清单规范

### 合规清单配置项

```text
合规项编码
合规项名称
合规分类
风险等级
合规描述
合规依据
整改建议
是否必检
是否支持豁免
绑定规则
检查引擎
评分权重
通过条件
证据要求
责任人
生效时间
版本号
```

### 合规项判定结果

```text
PASS        通过
WARNING     警告
FAIL        不通过
MANUAL      待人工确认
SKIPPED     跳过
```

### 合规清单设计要求

1. 合规清单必须支持模板化
2. 合规清单必须支持版本管理
3. 合规清单发布后不可直接修改，应生成新版本
4. 项目扫描结果必须绑定当时生效的清单版本
5. 合规项必须支持动态新增、修改、停用
6. 合规项必须支持绑定一个或多个规则
7. 合规项必须支持自动检查与人工检查

---

## 报表与报告规范

### 报表类型

- 扫描结果明细报表
- 合规清单报表
- 项目合规报告
- 团队合规看板
- 趋势分析报表
- 引擎结果对比报表
- 整改跟踪报表
- 豁免报表

### 报告必须包含

1. 项目基本信息
2. 扫描任务信息
3. 合规清单版本
4. 规则版本
5. 扫描时间
6. 总体合规结论
7. 合规评分
8. 通过项、失败项、警告项
9. 高危问题列表
10. 风险分布分析
11. 整改建议
12. 审计与证据附件

### 报告输出格式

- HTML
- PDF
- Excel
- Word
- JSON

---

## 数据库规范

### 主数据库

- PostgreSQL

### 推荐表设计方向

```text
sys_user
sys_role
sys_permission
sys_tenant
org_project
repo_info

compliance_standard
compliance_checklist
checklist_item
checklist_item_detail
checklist_version
project_checklist_binding

rule_definition
rule_version
rule_engine_binding
rule_parameter
rule_compliance_mapping
rule_evaluation_policy

scan_task
scan_job
scan_engine_config
scan_execution_log

finding
finding_detail
finding_status
finding_history
finding_evidence

compliance_evaluation
checklist_item_result
compliance_score
compliance_report

remediation_task
waiver_request
waiver_approval
audit_log
```

### 数据库要求

1. 所有表必须包含 `id`
2. 所有业务表必须包含 `created_at`
3. 所有业务表必须包含 `updated_at`
4. 重要业务表应包含 `deleted`
5. 版本类表必须包含 `version`
6. 审计类日志表必须不可物理删除
7. 原始扫描结果可存储到对象存储
8. 大字段原始 JSON 不建议全部写入主表
9. 高频统计字段建议冗余或写入分析库

---

## API 规范

### 路径规范

```text
/api/v1/{module}/{resource}
```

### 示例

```text
/api/v1/projects
/api/v1/rules
/api/v1/compliance/checklists
/api/v1/scan-tasks
/api/v1/reports
```

### 响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

### 分页请求

```text
?page=1
&size=20
&sort=createdAt,desc
```

### 分页响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [],
    "page": 1,
    "size": 20,
    "total": 0
  }
}
```

---

## Kotlin 编码规范

1. 优先使用 Kotlin 语法特性，避免写成 Java 风格 Kotlin
2. 优先使用 `data class` 表示 DTO、VO、Command、Query
3. 优先使用 `sealed class` / `enum class` 表示状态和类型
4. 优先使用不可变集合
5. 优先使用 `val`，避免不必要的 `var`
6. 使用 `?.`、`let`、`takeIf` 等安全调用方式
7. 避免强制非空断言 `!!`
8. Service 层避免直接暴露 Entity 给 Controller
9. Controller 只做参数校验、协议转换
10. 业务逻辑放在 Application Service 或 Domain Service
11. 数据库访问放在 Repository
12. 外部引擎调用放在 Adapter / Infrastructure

---

## 分层规范

### Controller

职责：

- 接收 HTTP 请求
- 参数校验
- 调用 Application Service
- 返回 DTO / VO

禁止：

- 写复杂业务逻辑
- 直接访问数据库
- 直接调用外部引擎

### Application Service

职责：

- 编排业务流程
- 事务控制
- 调用领域服务
- 发布领域事件

禁止：

- 包含过多领域规则
- 直接处理引擎细节

### Domain

职责：

- 表达业务规则
- 维护领域状态
- 定义领域模型

禁止：

- 依赖 Spring Web
- 依赖具体扫描引擎实现

### Repository

职责：

- 数据持久化
- 查询封装

禁止：

- 写业务规则
- 返回大量未处理原始数据给上层

### Adapter

职责：

- 对接外部扫描引擎
- 转换外部结果
- 处理外部协议差异

禁止：

- 修改核心领域模型
- 直接写业务判定逻辑

---

## 扫描任务规范

### 扫描任务状态

```text
PENDING         待执行
PREPARING       准备中
RUNNING         执行中
SUCCESS         成功
FAILED          失败
CANCELLED       已取消
PARTIAL_SUCCESS 部分成功
```

### 扫描任务要求

1. 扫描任务必须异步执行
2. 扫描任务必须支持取消
3. 扫描任务必须支持重试
4. 扫描任务必须记录执行日志
5. 扫描任务必须记录耗时
6. 扫描任务必须支持幂等
7. 同一项目同一分支应避免重复扫描冲突

---

## 问题状态规范

```text
NEW             新发现
CONFIRMED       已确认
ASSIGNED        已分配
FIXING          整改中
FIXED           已修复
RECHECKING      复审中
CLOSED          已关闭
IGNORED         已忽略
FALSE_POSITIVE  误报
ACCEPTED_RISK   风险接受
WAIVED          豁免
```

---

## 安全要求

1. 所有接口必须认证
2. 敏感操作必须记录审计日志
3. 代码仓库凭据必须加密存储
4. 扫描执行器必须隔离运行
5. 自定义脚本规则必须沙箱执行
6. 报告下载必须鉴权
7. 原始扫描结果必须设置访问权限
8. 敏感字段必须脱敏展示
9. 不允许将密钥、Token 写入日志
10. 不允许在异常信息中泄露系统细节

---

## 测试要求

### 必须覆盖

1. Controller 参数校验测试
2. Service 核心业务测试
3. 规则判定逻辑测试
4. 合规评分测试
5. 扫描结果标准化测试
6. Adapter 转换测试
7. Repository 关键查询测试

### 推荐测试策略

- 单元测试：JUnit 5 + MockK
- 集成测试：Testcontainers
- API 测试：MockMvc 或 RestAssured
- 数据库测试：Testcontainers PostgreSQL
- 引擎适配测试：使用样例扫描结果 JSON 做转换测试

---

## 开发优先级

### P0 必须完成

- 用户认证与权限
- 项目管理
- 代码仓库绑定
- 扫描任务管理
- 扫描引擎 Adapter 基础框架
- 统一 Finding 模型
- 合规清单基础配置
- 规则基础管理
- 扫描结果展示
- 基础报表

### P1 重要能力

- 合规清单版本管理
- 规则版本管理
- 合规项判定策略
- 合规评分
- 合规分析报告
- 整改状态管理
- 通知推送

### P2 增强能力

- 多引擎集成
- 质量门禁
- 豁免审批
- 组织级合规看板
- 智能分析
- AI 修复建议

---

## Agent 开发约束

当 Agent 参与本项目开发时，必须遵守以下规则：

1. 不得随意修改已有领域模型结构
2. 不得跳过版本化机制直接修改已发布清单或规则
3. 不得在 Controller 中写复杂业务逻辑
4. 不得绕过统一 Adapter 直接调用扫描引擎
5. 不得将原始扫描结果直接暴露给前端
6. 不得硬编码合规判定规则
7. 不得删除审计日志
8. 不得修改历史扫描结果
9. 不得在未做标准化的情况下写入 Finding 表
10. 不得忽略扫描结果去重逻辑

---

## Agent 常见任务指引

### 新增扫描引擎

步骤：

1. 定义 `EngineType`
2. 创建 `XxxEngineAdapter`
3. 实现原始结果解析
4. 转换为统一 `Finding`
5. 增加严重等级映射
6. 增加指纹去重逻辑
7. 编写 Adapter 单元测试
8. 更新扫描任务调度配置

### 新增合规项

步骤：

1. 创建或修改合规清单版本
2. 添加 `ChecklistItem`
3. 配置合规详情
4. 绑定规则
5. 配置判定策略
6. 发布新版本
7. 验证项目绑定效果

### 新增规则

步骤：

1. 创建规则草稿
2. 配置规则执行引擎
3. 配置规则参数
4. 配置规则与合规项映射
5. 配置判定策略
6. 测试规则
7. 发布规则版本

### 新增报表

步骤：

1. 明确报表对象与指标
2. 定义查询模型
3. 复用统一指标服务
4. 实现报表查询接口
5. 增加分页、筛选、导出
6. 编写查询性能测试

---

## 目录约定

```text
src/main/kotlin/com/example/compliance
├── common
├── auth
├── user
├── project
├── checklist
├── rule
├── scan
├── engine
├── result
├── report
├── remediation
├── notification
├── openapi
└── admin
```

---

## 常用命令示例

以下命令供 Agent 或开发者参考，具体可根据实际项目调整。

### 构建

```bash
./gradlew build
```

### 启动

```bash
./gradlew bootRun
```

### 测试

```bash
./gradlew test
```

### 生成测试覆盖率

```bash
./gradlew jacocoTestReport
```

### 数据库迁移

```bash
./gradlew flywayMigrate
```

---

## 验收标准

功能开发完成必须满足：

1. 代码编译通过
2. 单元测试通过
3. 接口文档更新
4. 数据库迁移脚本完整
5. 关键业务路径有日志
6. 异常有统一处理
7. 权限控制正确
8. 审计日志完整
9. 无敏感信息泄露
10. 报表数据与扫描结果一致
```

---

```markdown
# CLAUDE.md

## 项目背景

这是一个基于 Kotlin 的代码合规扫描平台。

平台用于统一管理代码扫描、合规清单、规则配置、扫描结果、合规报表、合规分析报告和整改闭环。

Claude 在参与本项目时，需要理解这是一个企业级合规治理平台，而不是简单的扫描工具。

---

## 项目目标

本项目需要实现以下能力：

1. 管理项目、应用、代码仓库
2. 动态配置合规标准与合规清单
3. 自定义合规项详情
4. 配置规则、规则版本、规则判定策略
5. 集成多种扫描引擎
6. 标准化扫描结果
7. 自动计算合规结果
8. 生成扫描结果报表和合规分析报告
9. 支持问题整改、豁免、审计闭环

---

## 技术栈

- Kotlin 2.x
- Spring Boot 3.x
- Spring MVC
- Spring Security
- Spring Data JPA
- Flyway
- PostgreSQL
- Redis
- RabbitMQ 或 Kafka
- Kotlin Coroutines
- MinIO / S3
- Elasticsearch / OpenSearch
- ClickHouse
- JUnit 5
- MockK
- Testcontainers

---

## 架构约束

本项目采用模块化单体架构。

模块包括：

```text
app-server
module-common
module-auth
module-user
module-project
module-checklist
module-rule
module-scan
module-engine-adapter
module-result
module-report
module-remediation
module-notification
module-openapi
module-admin
```

Claude 在生成代码时必须遵守模块边界。

---

## 核心业务概念

### 合规标准

表示一类合规要求，例如：

- 安全编码规范
- 开源组件合规规范
- 数据隐私合规规范
- 企业研发质量红线

### 合规清单

合规标准下的具体检查清单。

例如：

```text
代码安全合规清单
开源合规清单
依赖安全清单
敏感信息检查清单
```

### 合规项

合规清单中的单个检查项。

例如：

```text
禁止 SQL 注入
禁止硬编码密码
禁止高危依赖漏洞
禁止 GPL 许可证依赖
日志必须脱敏
单元测试覆盖率必须达标
```

### 规则

规则是合规项的自动化执行依据。

例如：

```text
semgrep.sql-injection
sonarqube.S2077
trivy.CVE-2024-XXXX
gitleaks.private-key
detekt.ComplexMethod
```

### 扫描任务

一次扫描任务对应一次项目扫描执行。

扫描任务会触发多个扫描引擎，并产生多个扫描结果。

### Finding

Finding 是平台统一的扫描结果模型。

所有引擎结果都必须转换为 Finding。

---

## 核心流程

### 扫描流程

```text
创建扫描任务
    ↓
加载项目配置
    ↓
加载合规清单
    ↓
加载规则
    ↓
执行扫描引擎
    ↓
采集扫描结果
    ↓
标准化为 Finding
    ↓
去重与聚合
    ↓
映射合规项
    ↓
计算合规结论
    ↓
生成报表和报告
```

### 合规清单配置流程

```text
创建合规标准
    ↓
创建合规清单
    ↓
添加合规项
    ↓
配置合规详情
    ↓
绑定规则
    ↓
配置判定策略
    ↓
发布版本
    ↓
绑定项目
```

---

## 设计原则

Claude 在生成方案或代码时必须遵守：

1. 合规清单必须动态配置，不能硬编码
2. 规则必须支持版本化
3. 已发布清单和规则不能直接修改
4. 扫描引擎必须通过 Adapter 接入
5. 所有扫描结果必须标准化
6. 所有扫描结果必须去重
7. 所有合规结论必须可追溯
8. 报告必须关联清单版本和规则版本
9. 扫描任务必须异步执行
10. 敏感信息不能写入日志

---

## 代码风格要求

1. 使用 Kotlin 惯用写法
2. 优先使用 `val`
3. 避免使用 `!!`
4. 优先使用不可变集合
5. 使用 `data class` 定义 DTO
6. 使用 `enum class` 定义状态
7. 使用 `sealed class` 表达封闭类型
8. Service 层返回业务对象或 DTO
9. Controller 不直接返回 Entity
10. Repository 不包含业务逻辑
11. Adapter 只负责外部系统转换
12. 领域规则集中在 Domain 或 Domain Service

---

## 分层职责

### Controller

只负责：

- 接收请求
- 参数校验
- 调用 Service
- 返回响应

不允许：

- 写业务逻辑
- 直接访问数据库
- 直接调用扫描引擎

### Application Service

负责：

- 编排业务流程
- 控制事务
- 调用领域服务
- 发布事件

### Domain

负责：

- 表达业务规则
- 维护实体状态
- 执行合规判定逻辑

### Repository

负责：

- 数据访问
- 查询封装

### Adapter

负责：

- 调用外部扫描引擎
- 解析外部结果
- 转换为统一模型

---

## 扫描引擎接入规范

新增扫描引擎时必须实现：

```text
supports
prepareScan
executeScan
collectResult
normalizeResult
cleanup
```

必须完成：

1. 原始结果解析
2. 严重等级映射
3. 统一 Finding 转换
4. 指纹去重
5. 异常处理
6. 超时控制
7. 单元测试

---

## 统一 Finding 模型

所有扫描结果必须转换为：

```text
Finding
├── findingId
├── projectId
├── scanTaskId
├── engineType
├── ruleId
├── ruleCode
├── findingType
├── severity
├── title
├── description
├── filePath
├── lineNumber
├── codeSnippet
├── packageName
├── packageVersion
├── fixedVersion
├── cveId
├── cvssScore
├── licenseId
├── repository
├── branch
├── commitId
├── fingerprint
├── status
└── rawResult
```

---

## 严重等级规范

```text
CRITICAL
HIGH
MEDIUM
LOW
INFO
```

不允许引擎自定义严重等级直接进入业务层。

必须先映射为平台统一等级。

---

## 合规判定结果

```text
PASS
WARNING
FAIL
MANUAL
SKIPPED
```

---

## 问题状态

```text
NEW
CONFIRMED
ASSIGNED
FIXING
FIXED
RECHECKING
CLOSED
IGNORED
FALSE_POSITIVE
ACCEPTED_RISK
WAIVED
```

---

## 数据库要求

1. 所有表必须有 `id`
2. 所有表必须有 `created_at`
3. 所有表必须有 `updated_at`
4. 版本表必须有 `version`
5. 审计日志不能物理删除
6. 扫描原始结果建议存储对象存储
7. 大字段不要随意放入主查询
8. 高频统计字段可冗余或写入分析库

---

## API 要求

接口路径：

```text
/api/v1/{module}/{resource}
```

统一响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

分页响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [],
    "page": 1,
    "size": 20,
    "total": 0
  }
}
```

---

## Claude 回答要求

当用户提出需求时，Claude 应该：

1. 先判断需求属于哪个模块
2. 再判断是否影响现有领域模型
3. 再判断是否需要数据库变更
4. 再判断是否需要版本化
5. 再判断是否需要审计日志
6. 最后给出实现方案或代码

---

## Claude 编码约束

Claude 在生成代码时不得：

1. 硬编码合规规则
2. 绕过 Adapter 调用扫描引擎
3. 跳过结果标准化
4. 跳过结果去重
5. 修改历史扫描结果
6. 删除审计日志
7. 在 Controller 写复杂逻辑
8. 直接返回数据库 Entity
9. 忽略权限控制
10. 输出敏感信息到日志

---

## Claude 推荐思考顺序

当接到新功能时，按以下顺序分析：

```text
1. 该功能属于哪个业务模块？
2. 是否涉及领域模型变更？
3. 是否需要新增数据库表或字段？
4. 是否需要版本化？
5. 是否需要审计？
6. 是否影响扫描流程？
7. 是否影响合规判定？
8. 是否影响报表指标？
9. 是否需要权限控制？
10. 是否需要测试？
```

---

## 常见任务处理建议

### 新增合规项

应包含：

- 合规项编码
- 合规项名称
- 合规分类
- 风险等级
- 合规描述
- 整改建议
- 是否必检
- 是否可豁免
- 绑定规则
- 判定策略
- 版本号

### 新增规则

应包含：

- 规则编码
- 规则名称
- 规则类型
- 适用语言
- 风险等级
- 扫描引擎
- 引擎规则 ID
- 规则参数
- 判定策略
- 规则版本

### 新增扫描引擎

应包含：

- 引擎类型
- 执行配置
- 原始结果解析
- Finding 转换
- 严重等级映射
- 去重指纹
- 异常处理
- 单元测试

### 新增报表

应包含：

- 报表类型
- 查询维度
- 统计指标
- 数据来源
- 权限控制
- 导出格式
- 性能方案

---

## 测试要求

Claude 生成业务代码时，应同时考虑测试。

必须覆盖：

1. 核心业务规则
2. 参数校验
3. 状态流转
4. 合规判定
5. 评分计算
6. Finding 标准化
7. Adapter 转换
8. 关键查询逻辑

---

## 验收标准

Claude 生成的方案或代码应满足：

1. 符合项目架构
2. 符合 Kotlin 编码规范
3. 符合模块边界
4. 可测试
5. 可审计
6. 可扩展
7. 不破坏已有领域模型
8. 不引入隐式全局状态
9. 不产生安全漏洞
10. 不造成报表口径不一致

---

## 项目优先级

### P0

- 用户认证与权限
- 项目管理
- 扫描任务
- 扫描引擎适配
- 统一 Finding
- 合规清单基础配置
- 规则基础管理
- 基础报表

### P1

- 清单版本管理
- 规则版本管理
- 合规评分
- 合规分析报告
- 整改闭环
- 通知推送

### P2

- 多引擎集成
- 质量门禁
- 豁免审批
- 组织级看板
- 智能分析
- AI 修复建议

## 本地运行环境

使用 docker compose 在本地运行

```