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

# 一、平台建设目标

## 1.1 核心目标

建设一个统一的代码合规扫描与合规治理平台，实现：

1. **代码资产接入**
   - 接入代码仓库、应用、项目、服务
   - 支持多语言、多仓库、多团队管理

2. **合规标准与清单管理**
   - 支持自定义合规标准
   - 支持动态配置合规清单
   - 支持合规项与规则绑定
   - 支持合规项自定义描述、整改建议、风险等级、证据要求

3. **规则中心**
   - 支持规则动态配置
   - 支持规则版本管理
   - 支持规则发布、灰度、回滚
   - 支持规则与合规项映射

4. **扫描执行与结果集成**
   - 支持集成静态代码扫描、依赖扫描、敏感信息扫描、许可证扫描、质量扫描等
   - 支持统一扫描结果模型
   - 支持扫描任务编排、调度、异步执行

5. **合规分析与报告**
   - 自动生成扫描结果报表
   - 自动生成合规分析报告
   - 支持风险评分、合规通过率、趋势分析、整改跟踪

6. **闭环治理**
   - 支持问题确认、分配、整改、豁免、复审
   - 支持审计日志与合规证据留存

---

# 二、平台业务范围

## 2.1 支持的合规扫描类型

平台初期建议支持以下扫描能力：

| 扫描类型 | 说明 | 可集成工具示例 |
|---|---|---|
| 静态代码扫描 | 代码规范、安全漏洞、缺陷模式 | Semgrep、SonarQube、CodeQL、Checkstyle、PMD、Detekt |
| 依赖组件扫描 | 第三方依赖漏洞、版本风险 | OWASP Dependency-Check、Trivy、Snyk、OSV-Scanner |
| 许可证合规扫描 | 开源协议合规风险 | license-checker、licensee、FOSSA、Scancode |
| 敏感信息扫描 | 密钥、密码、Token、内部地址 | gitleaks、trufflehog、自定义规则 |
| 容器/制品扫描 | 镜像漏洞、基础镜像合规 | Trivy、Grype |
| 配置合规扫描 | CI/CD、仓库配置、分支保护策略 | 自研规则引擎 |
| 编码规范扫描 | 团队内部编码规范 | Detekt、Checkstyle、PMD、ESLint |

---

# 三、总体架构设计

建议采用**模块化单体优先，预留微服务拆分能力**的架构。

原因：

- 合规扫描平台早期核心是规则、清单、扫描、报告
- 过早微服务化会增加复杂度
- 后期可将扫描引擎、任务调度、报告服务独立拆分

---

## 3.1 总体架构分层

平台整体分为五层：

```text
┌────────────────────────────────────────────┐
│                用户交互层                  │
│  管理后台 / 合规看板 / 报告中心 / 开放API   │
├────────────────────────────────────────────┤
│                应用服务层                  │
│  项目管理 / 合规清单 / 规则中心 / 扫描任务   │
│  报告服务 / 通知服务 / 权限服务             │
├────────────────────────────────────────────┤
│                领域模型层                  │
│  合规模型 / 规则模型 / 扫描模型 / 结果模型   │
│  报告模型 / 整改模型 / 审计模型             │
├────────────────────────────────────────────┤
│                基础设施层                  │
│  PostgreSQL / Redis / MinIO / MQ           │
│  Elasticsearch / ClickHouse / 对象存储      │
├────────────────────────────────────────────┤
│                扫描执行层                  │
│  扫描调度器 / 引擎适配器 / 结果标准化器       │
│  Semgrep / SonarQube / Trivy / Detekt等    │
└────────────────────────────────────────────┘
```

---

# 四、系统模块划分

建议划分为以下核心模块。

---

## 4.1 用户与权限模块

负责平台用户、组织、角色、权限控制。

### 主要能力

- 用户管理
- 组织/团队管理
- 项目管理
- 角色管理
- 权限控制
- 操作审计
- 多租户支持

### 建议角色

| 角色 | 说明 |
|---|---|
| 平台管理员 | 维护系统、规则、引擎、模板 |
| 安全合规管理员 | 维护合规标准、合规清单、审批豁免 |
| 项目负责人 | 管理项目、查看报告、推动整改 |
| 开发人员 | 查看问题、提交整改、申请豁免 |
| 审计人员 | 查看合规报告、审计记录、证据材料 |

---

## 4.2 项目与应用管理模块

管理被扫描的代码资产。

### 主要对象

- 组织
- 应用
- 项目
- 代码仓库
- 分支
- 环境
- 扫描配置

### 主要能力

- 项目创建
- Git 仓库绑定
- 分支管理
- 扫描范围配置
- 扫描策略绑定
- 合规清单绑定
- 项目成员权限
- 项目标签与分类

---

## 4.3 合规标准管理模块

用于管理企业或组织内部的合规标准。

### 示例标准

- 安全编码规范
- 开源组件合规规范
- 数据隐私合规规范
- 金融级代码安全规范
- 内部研发质量红线
- 云原生应用安全基线

### 主要能力

- 标准创建
- 标准版本管理
- 标准启用/停用
- 标准下合规项管理
- 标准模板复制
- 标准引用行业规范

---

## 4.4 合规清单管理模块

这是平台核心模块之一。

目标：支持业务人员或安全合规人员**通过界面动态配置合规清单**，而不是硬编码。

### 核心概念

| 概念 | 说明 |
|---|---|
| 合规清单模板 | 可复用的合规检查模板 |
| 合规清单实例 | 绑定到具体项目后的清单 |
| 合规项 | 清单中的单个合规要求 |
| 合规项详情 | 合规项的描述、依据、整改建议、风险等级 |
| 规则绑定 | 合规项绑定自动化扫描规则 |
| 人工检查项 | 无法自动扫描，需要人工确认 |

### 合规清单示例

```text
代码安全合规清单 V1.2
├── SQL注入检查
├── 硬编码密码检查
├── 高危依赖漏洞检查
├── 开源许可证合规检查
├── 敏感信息泄露检查
├── 日志脱敏检查
├── 单元测试覆盖率达标
└── 安全编码规范检查
```

### 动态配置能力

支持配置：

- 合规项名称
- 合规项编码
- 合规分类
- 风险等级
- 合规描述
- 合规依据
- 整改建议
- 是否必检
- 是否支持豁免
- 自动化规则
- 检查引擎
- 评分权重
- 通过条件
- 证据要求
- 责任人
- 生效时间
- 版本号

---

## 4.5 规则中心模块

规则中心用于统一管理所有自动化检查规则。

### 规则类型

| 规则类型 | 说明 |
|---|---|
| 静态代码规则 | 检查代码漏洞、坏味道、安全缺陷 |
| 依赖规则 | 检查依赖漏洞、许可证、版本黑名单 |
| 敏感信息规则 | 检查密钥、密码、Token |
| 配置规则 | 检查仓库配置、流水线配置 |
| 质量规则 | 覆盖率、重复率、复杂度 |
| 自定义脚本规则 | 支持用户自定义检测逻辑 |
| 组合规则 | 多个规则组合成一个合规项 |

### 规则配置内容

```text
规则基本信息
├── 规则编码
├── 规则名称
├── 规则分类
├── 适用语言
├── 风险等级
├── 规则描述
├── 修复建议
└── 状态

规则执行配置
├── 扫描引擎
├── 引擎规则ID
├── 规则参数
├── 文件范围
├── 排除路径
├── 严重级别映射
└── 执行超时时间

合规映射配置
├── 关联合规标准
├── 关联合规项
├── 命中后结论
├── 评分影响
└── 是否阻断发布
```

### 规则版本管理

建议支持：

- 草稿
- 测试
- 发布
- 停用
- 回滚
- 历史版本对比
- 灰度发布

---

## 4.6 扫描引擎适配模块

平台需要统一接入不同扫描引擎。

### 引擎适配器抽象

每个扫描引擎通过适配器接入，统一输出标准结果。

```text
ScanEngineAdapter
├── SemgrepAdapter
├── SonarQubeAdapter
├── DependencyCheckAdapter
├── TrivyAdapter
├── DetektAdapter
├── GitleaksAdapter
└── CustomRuleAdapter
```

### 统一扫描结果模型

不同引擎输出格式不同，平台需要统一转换为标准模型。

例如：

```text
Finding
├── findingId
├── projectId
├── scanTaskId
├── engineType
├── ruleId
├── ruleCode
├── severity
├── filePath
├── lineNumber
├── codeSnippet
├── message
├── cveId
├── licenseId
├── dependencyName
├── fingerprint
├── status
└── rawResult
```

---

## 4.7 扫描任务编排模块

负责扫描任务的创建、调度、执行、重试、结果汇总。

### 扫描触发方式

- 手动触发
- 定时触发
- 代码仓库 Webhook 触发
- CI/CD Pipeline 触发
- OpenAPI 触发
- 合规周期检查触发

### 扫描任务流程

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

---

## 4.8 结果归一化与合规模计算模块

该模块是合规分析的核心。

### 主要职责

1. 将不同引擎的扫描结果归一化
2. 将扫描结果映射到规则
3. 将规则映射到合规项
4. 计算合规项是否通过
5. 计算清单通过率
6. 计算项目合规评分
7. 生成风险等级结论

### 合规项判定逻辑示例

```text
如果命中高危漏洞数 = 0，则合规项通过
如果命中中危漏洞数 <= 5，则合规项警告
如果命中高危漏洞数 > 0，则合规项不通过
```

### 支持动态判定规则

建议通过规则表达式配置，例如：

```text
severity = HIGH and count > 0 => FAIL
severity = MEDIUM and count > 10 => WARNING
coverage < 60 => FAIL
license in [GPL-3.0] => FAIL
```

---

## 4.9 报表中心模块

提供扫描结果报表和合规报表。

### 报表类型

| 报表类型 | 说明 |
|---|---|
| 扫描结果明细报表 | 展示每个漏洞/问题详情 |
| 合规清单报表 | 展示合规项通过/失败/警告 |
| 项目合规报告 | 单项目合规结论 |
| 团队合规看板 | 按团队汇总合规情况 |
| 趋势分析报表 | 展示问题数量、风险趋势 |
| 引擎结果对比报表 | 不同扫描引擎结果汇总 |
| 整改跟踪报表 | 问题整改进度 |
| 豁免报表 | 豁免项、豁免原因、审批记录 |

### 报表指标

- 扫描次数
- 问题总数
- 高危问题数
- 中危问题数
- 合规项总数
- 合规通过数
- 合规失败数
- 合规通过率
- 合规评分
- 平均整改时长
- 逾期未整改数量
- 重复问题数量
- 新增/关闭问题趋势

---

## 4.10 合规分析报告模块

相比普通报表，合规分析报告更强调“结论、风险、原因、建议”。

### 报告内容结构

```text
1. 报告概览
   - 项目信息
   - 扫描时间
   - 合规清单版本
   - 总体结论
   - 合规评分

2. 合规结论
   - 通过项
   - 未通过项
   - 警告项
   - 人工待确认项

3. 风险分析
   - 高危问题分布
   - 模块风险分布
   - 文件风险热点
   - 依赖风险分布

4. 差距分析
   - 与合规标准差距
   - 与上一版本差距
   - 新增风险
   - 已修复风险

5. 根因分析
   - 高频规则
   - 高频问题类型
   - 高频责任人
   - 高频模块

6. 整改建议
   - 按风险优先级排序
   - 按修复成本排序
   - 按责任人分配

7. 附件与证据
   - 扫描原始结果
   - 人工确认记录
   - 豁免审批记录
```

### 报告输出格式

- HTML 在线报告
- PDF 报告
- Excel 明细
- Word 合规报告
- JSON 结构化数据
- 邮件定时推送

---

## 4.11 整改与闭环模块

合规扫描不只是发现问题，还要推动整改。

### 问题状态机

```text
NEW 新发现
    ↓
CONFIRMED 已确认
    ↓
ASSIGNED 已分配
    ↓
FIXING 整改中
    ↓
FIXED 已修复
    ↓
RECHECKING 复审中
    ↓
CLOSED 已关闭
```

同时支持：

```text
IGNORED 忽略
FALSE_POSITIVE 误报
ACCEPTED_RISK 风险接受
WAIVED 豁免
```

### 豁免流程

```text
开发申请豁免
    ↓
安全/合规审批
    ↓
设置豁免有效期
    ↓
记录豁免原因
    ↓
纳入审计记录
```

---

## 4.12 通知与集成模块

### 通知渠道

- 站内信
- 邮件
- 企业微信
- 钉钉
- 飞书
- Webhook
- 短信（可选）

### 集成系统

- GitLab / GitHub / Gitea / Bitbucket
- Jenkins / GitLab CI / Tekton / Argo Workflows
- Jira / 禅道 / ONES
- SSO / LDAP / OAuth2 / OIDC
- 制品库 / 镜像仓库
- CMDB / 应用管理平台

---

# 五、Kotlin 技术栈规划

建议平台后端使用 Kotlin 作为主要开发语言。

## 5.1 后端技术栈

| 类型 | 技术选型 | 说明 |
|---|---|---|
| 开发语言 | Kotlin 2.x | 主语言 |
| 基础框架 | Spring Boot 3.x | 成熟生态，适合企业平台 |
| Web 框架 | Spring MVC | REST API |
| 安全认证 | Spring Security | 登录、权限、OAuth2 |
| ORM | Spring Data JPA / Exposed / jOOQ | 推荐 JPA + jOOQ 组合 |
| 数据库迁移 | Flyway | 数据库版本管理 |
| 参数校验 | Kotlin Bean Validation | 请求参数校验 |
| 异步任务 | Kotlin Coroutines / Spring Async | 扫描任务异步化 |
| 任务调度 | Quartz / XXL-JOB / Temporal | 定时扫描与任务编排 |
| 消息队列 | RabbitMQ / Kafka | 扫描任务与结果事件 |
| 缓存 | Redis | 会话、配置缓存、任务锁 |
| 搜索 | Elasticsearch / OpenSearch | 扫描结果检索 |
| 分析存储 | ClickHouse | 大量扫描结果分析 |
| 对象存储 | MinIO / S3 | 报告文件、原始扫描结果 |
| 日志 | Logback + ELK | 日志采集分析 |
| 监控 | Micrometer + Prometheus + Grafana | 系统监控 |
| 链路追踪 | OpenTelemetry | 分布式追踪 |
| 文档 | SpringDoc OpenAPI | API 文档 |
| 测试 | JUnit 5 + MockK + Testcontainers | 单元/集成测试 |

---

## 5.2 推荐工程结构

建议采用模块化单体结构：

```text
code-compliance-platform
├── app-server                  // 启动模块
├── module-common               // 公共能力
├── module-auth                 // 认证授权
├── module-user                 // 用户组织
├── module-project              // 项目应用
├── module-checklist            // 合规清单
├── module-rule                 // 规则中心
├── module-scan                 // 扫描任务
├── module-engine-adapter       // 扫描引擎适配
├── module-result               // 结果归一化
├── module-report               // 报表报告
├── module-remediation          // 整改闭环
├── module-notification         // 通知服务
├── module-openapi              // 对外API
└── module-admin                // 管理后台接口
```

---

## 5.3 包结构建议

以扫描模块为例：

```text
com.example.compliance.scan
├── api
│   ├── ScanTaskController.kt
│   └── ScanEngineController.kt
├── application
│   ├── ScanTaskService.kt
│   └── ScanTaskOrchestrator.kt
├── domain
│   ├── ScanTask.kt
│   ├── ScanTaskStatus.kt
│   ├── ScanEngineType.kt
│   └── ScanResult.kt
├── infrastructure
│   ├── repository
│   ├── mapper
│   └── event
└── engine
    ├── ScanEngineAdapter.kt
    ├── SemgrepAdapter.kt
    ├── SonarQubeAdapter.kt
    └── TrivyAdapter.kt
```

---

# 六、核心领域模型规划

以下是平台核心领域对象。

---

## 6.1 组织与项目

```text
Tenant          租户
Organization    组织
Project         项目
Repository      代码仓库
Application     应用
Environment     环境
```

---

## 6.2 合规标准与清单

```text
ComplianceStandard        合规标准
ComplianceChecklist       合规清单
ChecklistItem             合规项
ChecklistItemDetail       合规项详情
ChecklistVersion          清单版本
ChecklistBinding          项目绑定清单
```

---

## 6.3 规则中心

```text
Rule                      规则
RuleVersion               规则版本
RuleCategory              规则分类
RuleEngineBinding         规则与引擎绑定
RuleParameter             规则参数
RuleComplianceMapping     规则与合规项映射
RuleEvaluationPolicy      规则判定策略
```

---

## 6.4 扫描执行

```text
ScanTask                  扫描任务
ScanJob                   子任务
ScanEngine                扫描引擎
ScanExecutionLog          扫描执行日志
ScanArtifact              扫描产物
```

---

## 6.5 扫描结果

```text
Finding                   扫描发现
FindingDetail             发现详情
FindingEvidence           证据
FindingStatus             状态
FindingHistory            状态历史
FindingDuplicateGroup     去重分组
```

---

## 6.6 合规评估

```text
ComplianceEvaluation      合规评估任务
ChecklistItemResult       合规项结果
ChecklistSummary          清单汇总结果
ComplianceScore           合规评分
ComplianceReport          合规报告
```

---

## 6.7 整改与审计

```text
RemediationTask           整改任务
WaiverRequest             豁免申请
WaiverApproval            豁免审批
AuditLog                  审计日志
ReportSnapshot            报告快照
```

---

# 七、动态合规清单设计

这是平台的重点能力。

---

## 7.1 设计原则

合规清单配置要做到：

1. **页面可配置**
   - 无需改代码即可创建合规项
   - 无需发版即可调整清单

2. **版本化**
   - 每次清单变更生成新版本
   - 历史扫描结果关联当时清单版本

3. **规则绑定**
   - 合规项可绑定一个或多个规则
   - 支持自动检查项和人工检查项

4. **可判定**
   - 支持配置通过条件
   - 支持风险等级映射
   - 支持阻断策略

5. **可扩展**
   - 支持自定义字段
   - 支持自定义证据材料
   - 支持自定义报告模板

---

## 7.2 合规清单配置流程

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

---

## 7.3 合规项配置示例

```text
合规项编码: SEC-001
合规项名称: 禁止SQL注入
合规分类: 应用安全
风险等级: 高危
合规描述: 代码中不得存在SQL注入漏洞
合规依据: 企业安全编码规范 3.2.1
整改建议: 使用参数化查询或ORM安全API
是否必检: 是
是否可豁免: 否
绑定规则:
  - semgrep.sql-injection
  - sonarqube.S2077
判定策略:
  - 命中高危问题数 > 0 => 不通过
  - 命中中危问题数 > 5 => 警告
  - 其他 => 通过
```

---

## 7.4 合规清单动态化实现方式

推荐采用：

### 方式一：配置化规则模型

适合大多数合规项。

通过数据库存储：

- 合规项
- 规则
- 参数
- 判定表达式
- 报告模板

优点：

- 稳定
- 易审计
- 易维护
- 非开发人员可配置

---

### 方式二：规则表达式引擎

用于动态判定合规结果。

可选方案：

- Spring Expression Language，即 SpEL
- Aviator
- MVEL
- JEXL
- OPA/Rego，适合复杂策略

建议初期使用 **SpEL 或 Aviator**，不要一开始就开放任意脚本。

示例表达式：

```text
# highCount > 0 ? 'FAIL' : 'PASS'
# mediumCount > 10 ? 'WARNING' : 'PASS'
```

---

### 方式三：Kotlin Script 自定义规则

适合高级场景，例如复杂代码分析。

但要注意安全风险，需要：

- 沙箱执行
- 资源限制
- 超时控制
- 权限隔离
- 白名单 API
- 审计日志

初期不建议默认开放给用户，仅管理员或平台内置使用。

---

# 八、规则引擎设计规划

规则中心建议抽象为三层：

```text
规则定义层
    ↓
规则执行层
    ↓
结果判定层
```

---

## 8.1 规则定义层

负责描述规则本身。

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

---

## 8.2 规则执行层

负责描述规则如何被执行。

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

---

## 8.3 结果判定层

负责将扫描结果转换为合规结论。

```text
RuleEvaluationPolicy
├── metricType
├── operator
├── threshold
├── resultStatus
└── expression
```

示例：

```text
metricType: VULNERABILITY_COUNT
severity: HIGH
operator: GREATER_THAN
threshold: 0
resultStatus: FAIL
```

---

# 九、扫描结果标准化设计

不同扫描引擎结果差异很大，必须建立统一模型。

---

## 9.1 扫描引擎输出差异

| 引擎 | 输出重点 |
|---|---|
| Semgrep | 文件、行号、规则、代码片段 |
| SonarQube | issue、严重级别、质量类型 |
| Trivy | 依赖、CVE、版本、修复版本 |
| Dependency-Check | 依赖、CVE、CVSS、CPE |
| Gitleaks | 密钥、文件、提交、行号 |
| License Scanner | 依赖、许可证、协议风险 |

---

## 9.2 标准化字段

```text
统一结果模型
├── engineType
├── engineFindingId
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
└── rawJson
```

---

## 9.3 去重策略

建议通过指纹去重：

```text
fingerprint = hash(
    project + ruleCode + filePath + lineNumber + codeSnippet
)
```

依赖类问题：

```text
fingerprint = hash(
    project + dependency + version + cveId
)
```

许可证类问题：

```text
fingerprint = hash(
    project + dependency + licenseId
)
```

---

# 十、合规评分模型设计

建议平台支持多维度评分。

---

## 10.1 评分维度

| 维度 | 说明 |
|---|---|
| 安全漏洞 | 高危、中危漏洞数量 |
| 依赖风险 | 已知漏洞依赖、黑名单依赖 |
| 许可证风险 | GPL、AGPL等高风险协议 |
| 敏感信息 | 密钥、密码泄露 |
| 代码质量 | 覆盖率、重复率、复杂度 |
| 规范符合度 | 编码规范命中情况 |
| 整改完成率 | 问题整改闭环情况 |

---

## 10.2 评分示例

```text
总分 = 100

扣分规则：
- 高危问题每个扣 5 分
- 中危问题每个扣 2 分
- 低危问题每个扣 0.5 分
- 必检项不通过每个扣 10 分
- 逾期未整改每个扣 3 分

最低分 = 0
```

也可以采用加权模型：

```text
合规评分 =
  安全评分 * 40% +
  依赖合规评分 * 25% +
  许可证合规评分 * 15% +
  质量评分 * 10% +
  整改闭环评分 * 10%
```

---

# 十一、报表与分析报告设计

---

## 11.1 扫描结果报表

### 页面能力

- 按项目查看扫描结果
- 按引擎查看结果
- 按风险等级筛选
- 按文件路径筛选
- 按规则筛选
- 按状态筛选
- 导出 Excel
- 查看详情与修复建议

### 报表字段

```text
问题ID
项目名称
扫描任务
引擎类型
规则编码
问题标题
严重级别
文件路径
行号
责任人
状态
发现时间
修复时间
整改时长
```

---

## 11.2 合规清单报表

### 展示内容

```text
合规清单名称
清单版本
项目名称
扫描时间
合规项总数
通过数
失败数
警告数
人工待确认数
合规通过率
合规评分
总体结论
```

### 合规项明细

```text
合规项编码
合规项名称
合规分类
风险等级
绑定规则数
命中问题数
判定结果
是否必检
是否豁免
整改建议
责任人
```

---

## 11.3 合规分析报告

报告要面向管理层和安全合规团队，不只是明细数据。

### 报告结构

```text
1. 执行摘要
2. 合规总体结论
3. 合规清单结果
4. 高危问题清单
5. 风险分布分析
6. 趋势变化分析
7. 部门/团队对比
8. 整改进度分析
9. 豁免与风险接受
10. 后续改进建议
```

---

# 十二、接口规划

建议提供标准 REST API。

---

## 12.1 合规清单接口

```text
POST   /api/v1/compliance/standards
GET    /api/v1/compliance/standards
POST   /api/v1/compliance/checklists
GET    /api/v1/compliance/checklists
POST   /api/v1/compliance/checklists/{id}/items
PUT    /api/v1/compliance/checklists/{id}/publish
POST   /api/v1/projects/{projectId}/bind-checklist
```

---

## 12.2 规则接口

```text
POST   /api/v1/rules
GET    /api/v1/rules
PUT    /api/v1/rules/{id}
POST   /api/v1/rules/{id}/publish
POST   /api/v1/rules/{id}/disable
GET    /api/v1/rules/{id}/versions
```

---

## 12.3 扫描接口

```text
POST   /api/v1/projects/{projectId}/scan-tasks
GET    /api/v1/scan-tasks/{taskId}
POST   /api/v1/scan-tasks/{taskId}/cancel
GET    /api/v1/scan-tasks/{taskId}/findings
GET    /api/v1/scan-tasks/{taskId}/compliance-results
```

---

## 12.4 报告接口

```text
GET    /api/v1/reports/scan-summary
GET    /api/v1/reports/compliance-summary
GET    /api/v1/reports/trend
POST   /api/v1/reports/compliance/generate
GET    /api/v1/reports/{reportId}/download
```

---

## 12.5 开放接口

供 CI/CD 调用：

```text
POST   /openapi/v1/scan/trigger
GET    /openapi/v1/scan/{taskId}/status
GET    /openapi/v1/scan/{taskId}/result
GET    /openapi/v1/project/{projectId}/compliance-status
```

---

# 十三、数据库设计方向

建议主数据库使用 PostgreSQL。

---

## 13.1 核心表

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

---

## 13.2 大结果量处理策略

如果扫描结果量较大，建议：

- 明细数据写入 PostgreSQL
- 聚合统计写入 ClickHouse 或 Elasticsearch
- 原始 JSON 存储到 MinIO / S3
- 报表查询走分析库
- 明细搜索走 Elasticsearch

---

# 十四、部署架构规划

---

## 14.1 初期部署架构

推荐容器化部署：

```text
Nginx / Gateway
    ↓
Kotlin Spring Boot 应用
    ↓
PostgreSQL + Redis + MinIO + RabbitMQ
    ↓
扫描执行器
```

---

## 14.2 扫描执行器独立部署

扫描任务建议独立执行器，避免影响主服务。

```text
主服务
  - 页面
  - API
  - 配置
  - 报告

扫描执行器
  - 拉取代码
  - 执行扫描
  - 上报结果
```

执行器可以：

- Kubernetes Job
- Docker 容器
- Sidecar
- 独立 Worker
- CI Runner

---

## 14.3 高可用方案

后期建议：

```text
应用服务多副本
扫描执行器弹性扩缩容
数据库主备
Redis 哨兵/集群
MQ 集群
MinIO 高可用
Elasticsearch 集群
ClickHouse 集群
```

---

# 十五、安全设计

---

## 15.1 平台安全

- 用户认证
- SSO 单点登录
- RBAC 权限
- 数据权限隔离
- 操作审计
- API Token
- 请求签名
- 敏感配置加密

---

## 15.2 扫描安全

- 代码拉取凭据加密存储
- 扫描任务权限隔离
- 扫描执行器沙箱化
- 禁止执行不可信脚本
- 控制扫描资源配额
- 设置超时与重试上限

---

## 15.3 数据安全

- 原始扫描结果加密存储
- 敏感字段脱敏
- 报告下载权限控制
- 数据保留周期配置
- 审计日志不可篡改

---

# 十六、可扩展性设计

---

## 16.1 引擎可扩展

新增扫描引擎只需实现统一接口：

```text
ScanEngineAdapter
├── supports(engineType)
├── prepareScan()
├── executeScan()
├── collectResult()
├── normalizeResult()
└── cleanup()
```

---

## 16.2 规则可扩展

支持新增：

- 内置规则
- 引擎规则
- 表达式规则
- 脚本规则
- 人工检查项
- 外部系统检查结果

---

## 16.3 报告可扩展

支持：

- 报告模板管理
- 报告字段配置
- 报告封面配置
- 报告章节配置
- 多语言报告
- 自定义 Logo
- 自定义评分模型

---

# 十七、建议实施路线

建议分阶段建设。

---

## 阶段一：MVP 基础平台

周期建议：6 到 8 周

### 目标

完成平台基础能力。

### 功能范围

- 用户登录与权限
- 项目管理
- 代码仓库绑定
- 合规清单基础配置
- 规则基础管理
- 扫描任务手动触发
- 集成一个扫描引擎
- 扫描结果展示
- 基础报表

### 推荐首发引擎

建议选择：

- Semgrep：适合安全代码扫描
- Detekt：适合 Kotlin 项目
- OWASP Dependency-Check 或 Trivy：适合依赖扫描

---

## 阶段二：规则与清单动态化

周期建议：6 到 10 周

### 目标

实现合规清单可配置化。

### 功能范围

- 合规标准管理
- 合规清单模板
- 合规项版本管理
- 规则版本管理
- 规则与合规项绑定
- 判定策略配置
- 合规评分
- 合规结果计算

---

## 阶段三：多引擎集成与报告中心

周期建议：8 到 12 周

### 目标

提升平台扫描覆盖能力与报告能力。

### 功能范围

- 多扫描引擎适配
- 结果标准化
- 结果去重
- 合规报告生成
- 趋势分析
- Excel / PDF 导出
- 通知推送

---

## 阶段四：治理闭环与智能分析

周期建议：长期建设

### 目标

形成合规治理闭环。

### 功能范围

- 整改任务管理
- 豁免审批
- 审计日志
- 组织级合规看板
- 风险趋势预测
- 高频问题根因分析
- 修复建议推荐
- 与 CI/CD 质量门禁联动

---

# 十八、关键难点与应对策略

---

## 18.1 多引擎结果不统一

### 风险

不同扫描工具结果字段、严重等级、规则 ID 不一致。

### 应对

- 建立统一 Finding 模型
- 每个引擎实现 Adapter
- 建立严重等级映射表
- 保留原始结果用于审计

---

## 18.2 动态规则配置复杂

### 风险

规则配置过于灵活会导致维护困难、判定逻辑不可控。

### 应对

- 初期使用结构化配置，而非开放脚本
- 规则配置版本化
- 规则发布需要审批
- 提供测试模式
- 提供规则变更审计

---

## 18.3 扫描性能问题

### 风险

大仓库扫描慢，任务堆积。

### 应对

- 扫描任务异步化
- 扫描执行器独立部署
- 支持增量扫描
- 支持目录过滤
- 支持缓存与复用
- 支持并发控制

---

## 18.4 合规结果争议

### 风险

误报、漏报导致业务团队不认可。

### 应对

- 支持误报标记
- 支持豁免审批
- 支持人工确认项
- 保留扫描证据
- 提供规则说明与修复建议

---

## 18.5 报告可信度

### 风险

报告只是数据罗列，无法支撑合规审计。

### 应对

- 报告关联清单版本
- 报告关联规则版本
- 报告保留历史快照
- 报告包含审计日志
- 报告支持证据下载

---

# 十九、推荐架构方案总结

建议平台采用如下架构：

```text
前端：
  管理后台 + 合规看板 + 报告中心

后端：
  Kotlin + Spring Boot 3
  模块化单体架构

数据层：
  PostgreSQL 存储业务数据
  Redis 缓存与任务锁
  MinIO 存储报告和原始结果
  Elasticsearch 支持检索
  ClickHouse 支持分析统计

扫描层：
  独立扫描执行器
  多引擎 Adapter
  统一结果标准化

核心能力：
  合规标准管理
  合规清单动态配置
  规则中心
  扫描任务编排
  合规评估计算
  报表报告生成
  整改闭环治理
```

---

# 二十、建议优先建设的核心能力

如果从落地角度排优先级，建议优先建设以下能力：

## P0 必须能力

- 项目与仓库管理
- 扫描任务管理
- 扫描引擎适配
- 扫描结果标准化
- 合规清单配置
- 合规项结果判定
- 基础扫描报表

## P1 重要能力

- 规则版本管理
- 清单版本管理
- 合规评分
- 合规分析报告
- 整改状态管理
- 通知推送

## P2 增强能力

- 多引擎集成
- 质量门禁
- 豁免审批
- 组织级看板
- 智能分析
- AI 修复建议

---

# 二十一、下一步建议

接下来可以继续细化以下内容：

1. **系统模块详细设计**
   - 每个模块的职责、接口、领域模型

2. **数据库详细设计**
   - 表结构、字段、索引、关系图

3. **核心流程设计**
   - 扫描流程、合规评估流程、报告生成流程

4. **规则引擎设计**
   - 规则模型、表达式、执行方式、沙箱策略

5. **API 接口设计**
   - 对外接口、内部接口、Webhook 设计

6. **原型页面规划**
   - 合规清单配置页、规则管理页、报告页、看板页

7. **技术选型 POC**
   - Semgrep、Trivy、SonarQube、Dependency-Check 集成验证

如果需要，我可以继续输出下一部分的 **“系统模块划分与核心流程图”**，或者直接给出 **“数据库表结构设计方案”**。