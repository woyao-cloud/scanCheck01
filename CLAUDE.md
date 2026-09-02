# AGENTS.md

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

## 技术栈

### 后端

- 语言：Kotlin 2.x
- 框架：Spring Boot 3.x
- Web：Spring MVC
- 安全：Spring Security
- ORM：Spring Data JPA，复杂查询可结合 jOOQ
- 数据库迁移：Flyway
- 数据库：PostgreSQL
- 缓存：Redis
- 消息队列：RabbitMQ 或 Kafka
- 异步处理：Kotlin Coroutines / Spring Async
- 任务调度：Quartz 或 XXL-JOB
- 对象存储：MinIO 或 S3 兼容存储
- 搜索：Elasticsearch / OpenSearch
- 分析库：ClickHouse，用于大规模扫描结果分析
- 日志：Logback + ELK
- 监控：Micrometer + Prometheus + Grafana
- API 文档：SpringDoc OpenAPI
- 测试：JUnit 5、MockK、Testcontainers

### 前端

前端可独立建设，建议技术栈：

- React / Vue 3
- TypeScript
- Ant Design / Element Plus / Arco Design
- ECharts / AntV
- Axios / React Query / Pinia

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