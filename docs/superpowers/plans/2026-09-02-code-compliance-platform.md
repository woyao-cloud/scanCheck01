# Code Compliance Platform 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建基于 Kotlin/Spring Boot 3 的代码合规扫描平台，打通「项目 → 扫描任务 → Semgrep 引擎 → 统一 Finding → 去重 → 合规判定 → 基础报表」完整链路。

**Architecture:** 模块化单体，15 个 Gradle 子模块。叶子业务模块只依赖 `module-common`（唯一例外 `auth → user`），`module-scan` 为编排层聚合 project/checklist/rule/result 的接口；扫描引擎经 `module-result` 定义的 `ScanEngineAdapter` 端口接入，`module-engine-adapter` 提供 Semgrep 实现；Flyway 迁移全部集中在 `app-server`；扫描与合规评估异步执行；测试用 Testcontainers 起真实 PostgreSQL。

**Tech Stack:** Kotlin 2.0 + JDK 21、Spring Boot 3.3、Spring Security + JWT、Spring Data JPA、Flyway、PostgreSQL 16、SpEL（合规判定）、JUnit 5 + MockK + Testcontainers + MockMvc。

**Spec:** `docs/superpowers/specs/2026-09-02-code-compliance-platform-design.md`（本计划逐任务引用该文档，执行者需两者同读）

## Global Constraints

以下约束对每个任务都生效，直接从 spec 逐字复制：

1. **模块依赖**：叶子模块（user/project/checklist/rule/result/remediation/notification/openapi/admin）只依赖 `module-common`，互不 import 实体；唯一例外 `module-auth → module-user`；`module-scan` 可依赖 project/checklist/rule/result 的接口；`app-server` 依赖全部。
2. **模块内分层**：每个业务模块用 `api/`（Controller、DTO，禁写业务逻辑、禁返回 Entity）、`application/`（编排、事务）、`domain/`（实体、枚举、领域服务）、`infrastructure/`（Repository）四层。
3. **Kotlin 风格**：优先 `data class` 做 DTO、`enum class` 做状态；优先 `val`；避免 `!!`；Controller 不返回 Entity。
4. **统一响应**：`{ "code": 0, "message": "success", "data": ... }`；分页 `data: { items, page, size, total }`；路径前缀 `/api/v1/{module}/{resource}`。
5. **数据库**：所有业务表含 `id`、`created_at`、`updated_at`；版本表含 `version`；审计表 `audit_log` 只增不改不删；原始扫描 JSON 存 `jsonb`。
6. **枚举统一**：`Severity`=CRITICAL/HIGH/MEDIUM/LOW/INFO；`TaskStatus`=PENDING/PREPARING/RUNNING/SUCCESS/FAILED/CANCELLED/PARTIAL_SUCCESS；`ItemResult`=PASS/WARNING/FAIL/MANUAL/SKIPPED；`RuleStatus`=DRAFT/TESTING/PUBLISHED/DISABLED；`VersionStatus`=DRAFT/PUBLISHED/DISABLED。
7. **安全**：除 `/api/v1/auth/login` 与 swagger 外全部要求 JWT；密码 BCrypt；仓库凭据应用级 AES 加密；敏感信息不写日志。
8. **红线**：不硬编码合规判定规则（判定走 `rule_evaluation_policy` 结构化配置 + SpEL）；不绕过 Adapter 调引擎；历史扫描结果不可修改；`audit_log` 不删改。

---

## 文件结构总览

以下为计划最终落地的关键文件（每个任务会给出该任务的精确文件清单；此图用于把握全局）：

```text
buildSrc/src/main/kotlin/com/example/compliance/ComplianceKotlinModulePlugin.kt  # 约定插件（kotlin+spring+jpa+toolchain21+test，类插件）
gradle/libs.versions.toml                                     # 版本目录
settings.gradle.kts                                           # 15 模块 include
build.gradle.kts                                              # 根构建

app-server/src/main/kotlin/com/example/compliance/Application.kt
app-server/src/main/resources/application.yml
app-server/src/main/resources/db/migration/V1..V7__*.sql      # 全部 Flyway 迁移
app-server/src/test/kotlin/com/example/compliance/AbstractIntegrationTest.kt
docker-compose.yml

module-common/src/main/kotlin/com/example/compliance/common/
    api/ApiResponse.kt  api/PageResponse.kt
    exception/BusinessException.kt  exception/GlobalExceptionHandler.kt
    domain/BaseEntity.kt
    audit/AuditLog.kt  audit/AuditService.kt  audit/AuditLogRepository.kt
    config/CommonConfig.kt

module-user/src/main/kotlin/com/example/compliance/user/
    domain/User.kt  domain/Role.kt  domain/UserRole.kt
    infrastructure/UserRepository.kt  infrastructure/RoleRepository.kt  infrastructure/UserRoleRepository.kt
    application/UserService.kt  application/CreateUserCommand.kt
    api/UserController.kt  api/dto/UserRequest.kt  api/dto/UserResponse.kt

module-auth/src/main/kotlin/com/example/compliance/auth/
    application/JwtService.kt  application/AuthService.kt
    config/SecurityConfig.kt  config/JwtAuthenticationFilter.kt
    api/AuthController.kt  api/dto/LoginRequest.kt  api/dto/LoginResponse.kt

module-project/src/main/kotlin/com/example/compliance/project/
    domain/Project.kt  domain/Repository.kt
    infrastructure/ProjectRepository.kt  infrastructure/RepoRepository.kt  infrastructure/CredentialCrypto.kt
    application/ProjectService.kt  application/commands.kt
    api/ProjectController.kt  api/dto/ProjectRequest.kt  api/dto/ProjectResponse.kt

module-checklist/src/main/kotlin/com/example/compliance/checklist/
    domain/ComplianceStandard.kt  domain/ComplianceChecklist.kt  domain/ChecklistVersion.kt
    domain/ChecklistItem.kt  domain/ChecklistItemDetail.kt  domain/ProjectChecklistBinding.kt
    domain/enums.kt  infrastructure/repos.kt
    application/ChecklistService.kt  application/ChecklistQueryService.kt
    api/ChecklistController.kt  api/dto/*.kt

module-rule/src/main/kotlin/com/example/compliance/rule/
    domain/RuleDefinition.kt  domain/RuleEngineBinding.kt  domain/RuleComplianceMapping.kt  domain/RuleEvaluationPolicy.kt
    infrastructure/repos.kt
    application/RuleService.kt  application/RuleQueryService.kt
    api/RuleController.kt  api/dto/*.kt

module-result/src/main/kotlin/com/example/compliance/result/
    domain/Finding.kt  domain/FindingTrace.kt  domain/enums.kt（FindingStatus）
    engine/ScanEngineAdapter.kt  engine/EngineAdapterRegistry.kt（含 ScanContext/RawFinding/ScanResult）
    infrastructure/FingerprintGenerator.kt  infrastructure/FindingRepository.kt  infrastructure/FindingTraceRepository.kt
    application/FindingService.kt

module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/
    SemgrepAdapter.kt  SemgrepResultParser.kt  SemgrepSeverityMapper.kt  SemgrepCli.kt
module-engine-adapter/src/test/resources/semgrep/basic.json

module-scan/src/main/kotlin/com/example/compliance/scan/
    domain/enums.kt（ScanTaskStatus）  domain/ScanTask.kt  domain/ScanJob.kt  domain/ScanExecutionLog.kt
    domain/ComplianceEvaluation.kt  domain/ChecklistItemResult.kt
    infrastructure/repos.kt
    application/ScanTaskService.kt  application/ScanOrchestrator.kt  application/ComplianceEvaluator.kt
    api/ScanController.kt  api/dto/*.kt
    （app-server 提供 config/AsyncConfig.kt：@EnableAsync + scanExecutor 线程池）

module-report/src/main/kotlin/com/example/compliance/report/
    application/ReportService.kt  api/ReportController.kt  api/dto/*.kt

module-remediation / module-notification / module-openapi / module-admin
    （骨架：build.gradle.kts + 一个包占位，无业务代码，M0 建立）
```

---

## 里程碑 M0：多模块骨架 + common + 可启动空应用

### Task 0.1: Gradle 根工程与版本目录

**Files:**
- Create: `gradle/libs.versions.toml`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`

**Interfaces:**
- Produces: 版本目录别名（`libs.spring.boot.starter.web` 等）与插件别名（`libs.plugins.kotlin.jvm` 等），后续所有模块 build 文件引用。

- [ ] **Step 1: 写版本目录 `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.0.21"
springBoot = "3.3.5"
springDependencyManagement = "1.1.6"
springdoc = "2.6.0"
jjwt = "0.12.6"
testcontainers = "1.20.4"
mockk = "1.13.13"
postgresql = "42.7.4"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-security = { module = "org.springframework.boot:spring-boot-starter-security" }
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-security-test = { module = "org.springframework.security:spring-security-test" }
springdoc-openapi-starter-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
flyway-core = { module = "org.flywaydb:flyway-core" }
flyway-database-postgresql = { module = "org.flywaydb:flyway-database-postgresql" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
jjwt-api = { module = "io.jsonwebtoken:jjwt-api", version.ref = "jjwt" }
jjwt-impl = { module = "io.jsonwebtoken:jjwt-impl", version.ref = "jjwt" }
jjwt-jackson = { module = "io.jsonwebtoken:jjwt-jackson", version.ref = "jjwt" }
testcontainers-junit-jupiter = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
kotlin-jpa = { id = "org.jetbrains.kotlin.plugin.jpa", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "springDependencyManagement" }
```

- [ ] **Step 2: 写 `settings.gradle.kts`**

```kotlin
rootProject.name = "code-compliance-platform"
include(
    "app-server",
    "module-common",
    "module-auth",
    "module-user",
    "module-project",
    "module-checklist",
    "module-rule",
    "module-scan",
    "module-engine-adapter",
    "module-result",
    "module-report",
    "module-remediation",
    "module-notification",
    "module-openapi",
    "module-admin",
)
```

- [ ] **Step 3: 写根 `build.gradle.kts` 与 `gradle.properties`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g
org.gradle.parallel=true
kotlin.code.style=official
```

- [ ] **Step 4: 验证**

Run: `./gradlew projects`
Expected: 列出 root project，`./gradlew` 可用（若本机 gradle 8.2.1 直接可用则无需 wrapper；建议执行 `gradle wrapper --gradle-version 8.8` 生成 wrapper 提交）。

- [ ] **Step 5: Commit**

```bash
git init
git add gradle/ settings.gradle.kts build.gradle.kts gradle.properties
git commit -m "chore: gradle root project with version catalog"
```

> 说明：目录当前非 git 仓库，本计划每个任务含 commit 步骤，故在 M0 初始化 git。若你不想用 git，可跳过全部 commit 步骤并在计划执行前告知。

### Task 0.2: 约定插件与 15 个模块骨架

**Files:**
- Create: `buildSrc/build.gradle.kts`（`kotlin-dsl` + 插件实现依赖）
- Create: `buildSrc/src/main/kotlin/com/example/compliance/ComplianceKotlinModulePlugin.kt`
- Create: `buildSrc/src/main/resources/META-INF/gradle-plugins/compliance-kotlin-module.properties`
- Create: 每个模块的 `build.gradle.kts`（15 个）
- Create: 每个模块的包占位（如 `module-remediation/src/main/kotlin/com/example/compliance/remediation/package-info.kt`）
- Modify: `settings.gradle.kts`（根：补 `dependencyResolutionManagement` 仓库声明）
- Modify: `build.gradle.kts`（根：仅保留 `spring-boot` apply false）

**Interfaces:**
- Produces: 约定插件 id `compliance-kotlin-module`，提供 Kotlin JVM/Spring/JPA、JDK 21 toolchain、Spring Boot BOM（无版本 starter 坐标的版本来源）、JUnit5 + MockK 测试依赖。
- Consumes: 根 `settings.gradle.kts` 的默认 `libs` 版本目录（Task 0.1 已建 `gradle/libs.versions.toml`）。

> **为什么用 Kotlin 类插件而不是预编译脚本插件**（本任务已用 Gradle 8.8 全量复刻验证）：
> 1) 预编译脚本插件的 `plugins {}` 块不允许带版本号（Gradle 硬性规则，报 `Invalid plugin request ... must not include a version number`）；
> 2) 预编译脚本插件内无法使用版本目录 `libs` 的库访问器（`libs.spring.boot.starter.test` 等无法解析）。
> 因此采用 `Plugin<Project>` 类插件：插件实现（kotlin-gradle-plugin 等）作为 `:buildSrc` 的 `implementation` 依赖提供，
> 在 `apply()` 里用 `project.plugins.apply("org.jetbrains.kotlin.jvm")` 等无版本应用，
> 依赖版本通过 `the<VersionCatalogsExtension>().named("libs").findLibrary(...)` 编程式获取。

- [ ] **Step 1: 写 buildSrc 约定插件**

`buildSrc/build.gradle.kts`:
```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Kotlin 类约定插件需要这些插件实现位于 buildSrc classpath；
    // 它们会以未知版本进入整个构建的 classpath，因此根 build.gradle.kts 不能再带版本请求（见 Step 2）。
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:2.0.21")
    implementation("org.jetbrains.kotlin.plugin.jpa:org.jetbrains.kotlin.plugin.jpa.gradle.plugin:2.0.21")
    implementation("io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:1.1.6")
}
```

`buildSrc/src/main/kotlin/com/example/compliance/ComplianceKotlinModulePlugin.kt`:
```kotlin
package com.example.compliance

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * 模块约定插件：为每个业务模块统一配置 Kotlin JVM/Spring/JPA、
 * JDK 21 toolchain、Spring Boot BOM 与 JUnit5 + MockK 测试依赖。
 *
 * 依赖版本通过 VersionCatalogsExtension 从主构建默认 libs 目录编程式解析
 * （避免预编译脚本插件无法使用目录访问器 / plugins 块不能带版本的限制）。
 */
class ComplianceKotlinModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("java-library")  // 提供 api() 配置（module-common 用 api 暴露共享技术栈）
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply("org.jetbrains.kotlin.plugin.spring")
        project.plugins.apply("org.jetbrains.kotlin.plugin.jpa")
        project.plugins.apply("io.spring.dependency-management")

        project.extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
        }
        project.extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }

        // Spring Boot 无版本坐标（starter 系列）依赖 BOM 提供版本。
        // 注意：imports 的 lambda 是接收者类型 ImportsHandler.() -> Unit，
        // 写 it.mavenBom(...) 会报 Unresolved reference: it。
        project.extensions.configure<DependencyManagementExtension> {
            imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") }
        }

        val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        project.dependencies.add("testImplementation", libs.findLibrary("spring-boot-starter-test").get())
        project.dependencies.add("testImplementation", libs.findLibrary("mockk").get())

        project.tasks.withType(Test::class.java).configureEach { useJUnitPlatform() }
    }
}
```

`buildSrc/src/main/resources/META-INF/gradle-plugins/compliance-kotlin-module.properties`:
```properties
implementation-class=com.example.compliance.ComplianceKotlinModulePlugin
```

- [ ] **Step 2: 修改根工程 build 文件（仓库 + 插件声明）**

在根 `settings.gradle.kts` 末尾追加仓库声明（Task 0.1 未声明仓库，`gradle build` 解析依赖会报 "no repositories are defined"）:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
```

根 `build.gradle.kts` 整体替换为（只保留 spring-boot）:
```kotlin
plugins {
    // kotlin.jvm/spring/jpa 与 io.spring.dependency-management 由约定插件 (buildSrc) 无版本应用——
    // buildSrc 的 implementation 依赖让这些插件以未知版本出现在整个构建 classpath 上，
    // 此处再带版本请求会报 "already on the classpath with an unknown version"。
    // 仅 spring-boot 插件（不在 buildSrc classpath 上）在此 apply false 声明版本。
    alias(libs.plugins.spring.boot) apply false
}
```

- [ ] **Step 3: 写各模块 build 文件**

`module-common/build.gradle.kts`（用 `api` 向所有下游暴露共享技术栈）:
```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.validation)
    api(libs.springdoc.openapi.starter.webmvc.ui)
}
```

其余叶子模块（user/project/checklist/rule/result/remediation/notification/openapi/admin）:
```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
}
```

`module-auth/build.gradle.kts`:
```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-user"))
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    testImplementation(libs.spring.security.test)
}
```

`module-scan/build.gradle.kts`:
```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-project"))
    implementation(project(":module-checklist"))
    implementation(project(":module-rule"))
    implementation(project(":module-result"))
}
```

`module-engine-adapter/build.gradle.kts`:
```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-result"))
}
```

`module-report/build.gradle.kts`:
```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
    implementation(project(":module-checklist"))
}
```

`app-server/build.gradle.kts`:
```kotlin
plugins {
    id("compliance-kotlin-module")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-auth"))
    implementation(project(":module-user"))
    implementation(project(":module-project"))
    implementation(project(":module-checklist"))
    implementation(project(":module-rule"))
    implementation(project(":module-scan"))
    implementation(project(":module-engine-adapter"))
    implementation(project(":module-result"))
    implementation(project(":module-report"))
    implementation(project(":module-remediation"))
    implementation(project(":module-notification"))
    implementation(project(":module-openapi"))
    implementation(project(":module-admin"))
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.security.test)
}
```

每个骨架模块建一个包占位文件（内容一致，以 module-openapi 为例）:
```kotlin
package com.example.compliance.openapi
// 骨架占位：对外 API 后续里程碑实现（spec 1.3 明确首发仅骨架）
```

- [ ] **Step 4: 验证**

Run: `./gradlew build -x test -x bootJar`
（必须用 8.8 wrapper；系统 gradle 8.2.1 内嵌 Kotlin 1.9.10 不支持 JVM target 21，buildSrc 会报 `Unknown Kotlin JVM target: 21`）
Expected: BUILD SUCCESSFUL（15 个模块全部编译通过，约定插件加载并应用成功）。
> `-x bootJar`：app-server 主类到 Task 0.5 才创建，bootJar 无法解析 mainClass 属预期。

- [ ] **Step 5: Commit**

```bash
git add buildSrc/ module-*/build.gradle.kts app-server/build.gradle.kts settings.gradle.kts build.gradle.kts
git commit -m "chore: scaffold 15 modules with convention plugin"
```

### Task 0.3: module-common 统一响应、异常与分页

**Files:**
- Create: `module-common/src/main/kotlin/com/example/compliance/common/api/ApiResponse.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/api/PageResponse.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/exception/BusinessException.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/exception/GlobalExceptionHandler.kt`
- Test: `module-common/src/test/kotlin/com/example/compliance/common/exception/GlobalExceptionHandlerTest.kt`
- Modify: `gradle/libs.versions.toml`（[libraries] 新增 `kotlin-test`，version.ref = "kotlin"）
- Modify: `buildSrc/src/main/kotlin/com/example/compliance/ComplianceKotlinModulePlugin.kt`（testImplementation 增加 kotlin-test）

**Interfaces:**
- Produces: `ApiResponse<T>(code, message, data)` + `ApiResponse.ok(data)` / `ApiResponse.ok()` / `ApiResponse.error(code, msg)`；`PageResponse<T>(items, page, size, total)`；`BusinessException(code=400, message)`；`@RestControllerAdvice GlobalExceptionHandler` 将 `BusinessException`、`MethodArgumentNotValidException`、未知异常转为统一响应。

> **预验证缺陷修复（Gradle 8.8 复刻实测，本任务已含修正）**：
> 1. 测试用 `kotlin.test.assertEquals`/`assertTrue`，但 `kotlin-test` 不在任何依赖中（spring-boot-starter-test 不含），测试编译报 `Unresolved reference 'test'`。修复：`gradle/libs.versions.toml` 新增 `kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }`，约定插件 testImplementation 追加该依赖（对所有模块生效）。
> 2. `GlobalExceptionHandler.handleBusiness` 里 `ApiResponse.error(e.code, e.message)` 编译不过——`RuntimeException.message` 是 `String?`。修复：`e.message ?: "business error"`。
> 3. 所有 Gradle 命令用 `./gradlew`（8.8 wrapper），不用系统 `gradle`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.example.compliance.common.exception

import com.example.compliance.common.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `business exception becomes 400 with code and message`() {
        val resp = handler.handleBusiness(BusinessException(422, "bad input"))
        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertEquals(422, resp.body?.code)
        assertEquals("bad input", resp.body?.message)
    }

    @Test
    fun `validation exception aggregates field errors`() {
        val ex = mockk<MethodArgumentNotValidException>()
        val fieldError = mockk<org.springframework.validation.FieldError>()
        every { ex.bindingResult.fieldErrors } returns listOf(fieldError)
        every { fieldError.field } returns "name"
        every { fieldError.defaultMessage } returns "must not be blank"
        val resp = handler.handleValidation(ex)
        assertTrue(resp.body!!.message.contains("name"))
    }

    @Test
    fun `unknown exception becomes 500 without leaking detail`() {
        val resp = handler.handleUnknown(RuntimeException("secret db password=xx"))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.statusCode)
        assertEquals("internal error", resp.body?.message)
        assertTrue(!resp.body!!.message.contains("secret"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-common:test --tests "*GlobalExceptionHandlerTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现（先补测试依赖）**

在 `gradle/libs.versions.toml` 的 `[libraries]` 段末尾追加:
```toml
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
```

在 `buildSrc/src/main/kotlin/com/example/compliance/ComplianceKotlinModulePlugin.kt` 的 `apply()` 里，`libs.findLibrary("mockk")` 一行之后追加:
```kotlin
        project.dependencies.add("testImplementation", libs.findLibrary("kotlin-test").get())
```

`ApiResponse.kt`:
```kotlin
package com.example.compliance.common.api

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(0, "success", data)
        fun ok(): ApiResponse<Unit> = ApiResponse(0, "success", null)
        fun <T> error(code: Int, message: String): ApiResponse<T> = ApiResponse(code, message, null)
    }
}
```

`PageResponse.kt`:
```kotlin
package com.example.compliance.common.api

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)
```

`BusinessException.kt`:
```kotlin
package com.example.compliance.common.exception

class BusinessException(
    val code: Int = 400,
    message: String,
) : RuntimeException(message)
```

`GlobalExceptionHandler.kt`:
```kotlin
package com.example.compliance.common.exception

import com.example.compliance.common.api.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ApiResponse<Unit>> =
        // RuntimeException.message 为 String?，需兜底
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.code, e.message ?: "business error"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Unit>> {
        val message = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, message))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        log.error("Unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(500, "internal error"))
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-common:test --tests "*GlobalExceptionHandlerTest*"`
Expected: PASS（3 个测试全绿，JUnit XML 显示 tests="3" failures="0"）。

- [ ] **Step 5: Commit**

```bash
git add module-common/src gradle/libs.versions.toml buildSrc/src/main/kotlin/com/example/compliance/ComplianceKotlinModulePlugin.kt
git commit -m "feat(common): unified response, page, exception handling"
```

### Task 0.4: BaseEntity + 审计（AuditLog/AuditService）

**Files:**
- Create: `module-common/src/main/kotlin/com/example/compliance/common/domain/BaseEntity.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLog.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditLogRepository.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditService.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/config/CommonConfig.kt`
- Test: `module-common/src/test/kotlin/com/example/compliance/common/audit/AuditServiceTest.kt`

**Interfaces:**
- Produces: `BaseEntity`（`id`/`createdAt`/`updatedAt`，JPA 审计填充）；`AuditService.record(action, module, userId?, resourceType?, resourceId?, detail?, ip?)`；`AuditLogRepository : JpaRepository<AuditLog, Long>`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.example.compliance.common.audit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class AuditServiceTest {
    @Test
    fun `record persists an audit log row`() {
        val repo = mockk<AuditLogRepository>(relaxed = true)
        // REQUIRED: relaxed `save` returns Object (generic erased) -> ClassCastException at the
        // Kotlin call site (checkcast to AuditLog). Stub returns firstArg (see Ruling #13).
        every { repo.save(any()) } answers { firstArg() }
        val service = AuditService(repo)
        service.record(action = "CREATE", module = "project", userId = 1L, resourceType = "Project", resourceId = 9L)
        verify(exactly = 1) { repo.save(any()) }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-common:test --tests "*AuditServiceTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现**

`BaseEntity.kt`:
```kotlin
package com.example.compliance.common.domain

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant? = null
}
```

`AuditLog.kt`:
```kotlin
package com.example.compliance.common.audit

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "audit_log", indexes = [Index(name = "idx_audit_resource", columnList = "resource_type, resource_id")])
class AuditLog : BaseEntity() {
    @Column(name = "user_id")
    var userId: Long? = null

    @Column(name = "action", nullable = false, length = 64)
    lateinit var action: String

    @Column(name = "module", nullable = false, length = 64)
    lateinit var module: String

    @Column(name = "resource_type", length = 64)
    var resourceType: String? = null

    @Column(name = "resource_id")
    var resourceId: Long? = null

    @JdbcTypeCode(SqlTypes.JSON)  // REQUIRED: binds String as jsonb (not varchar) on INSERT — see Ruling #13
    @Column(name = "detail", columnDefinition = "jsonb")
    var detail: String? = null

    @Column(name = "ip", length = 64)
    var ip: String? = null

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant
}
```

`AuditLogRepository.kt`:
```kotlin
package com.example.compliance.common.audit

import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLog, Long>
```

`AuditService.kt`:
```kotlin
package com.example.compliance.common.audit

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AuditService(private val repository: AuditLogRepository) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        action: String,
        module: String,
        userId: Long? = null,
        resourceType: String? = null,
        resourceId: Long? = null,
        detail: String? = null,
        ip: String? = null,
    ) {
        repository.save(
            AuditLog().apply {
                this.action = action
                this.module = module
                this.userId = userId
                this.resourceType = resourceType
                this.resourceId = resourceId
                this.detail = detail
                this.ip = ip
                this.occurredAt = Instant.now()
            }
        )
    }
}
```

`CommonConfig.kt`:
```kotlin
package com.example.compliance.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@Configuration
@EnableJpaAuditing
class CommonConfig
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-common:test --tests "*AuditServiceTest*"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add module-common/src
git commit -m "feat(common): base entity and audit logging"
```

### Task 0.5: app-server 启动类 + Docker Compose + Flyway 初始化 + 集成测试基座

**Files:**
- Create: `app-server/src/main/kotlin/com/example/compliance/Application.kt`
- Create: `app-server/src/main/resources/application.yml`
- Create: `app-server/src/main/resources/application-test.yml`
- Create: `app-server/src/main/resources/db/migration/V1__init_audit_log.sql`
- Create: `docker-compose.yml`（项目根）
- Create: `app-server/src/test/kotlin/com/example/compliance/AbstractIntegrationTest.kt`
- Create: `app-server/src/test/kotlin/com/example/compliance/SmokeIntegrationTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/SmokeIntegrationTest.kt`

**Interfaces:**
- Produces: 启动类（`@SpringBootApplication(scanBasePackages=["com.example.compliance"])` + `@EntityScan` + `@EnableJpaRepositories`）；Flyway V1 建 `audit_log`；`AbstractIntegrationTest`（Testcontainers PostgreSQL + `@ActiveProfiles("test")`），后续所有集成测试继承它。

- [ ] **Step 1: 写启动类与配置**

`Application.kt`:
```kotlin
package com.example.compliance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["com.example.compliance"])
@EntityScan(basePackages = ["com.example.compliance"])
@EnableJpaRepositories(basePackages = ["com.example.compliance"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
```

`application.yml`:
```yaml
server:
  port: 8080

spring:
  application:
    name: code-compliance-platform
  datasource:
    url: jdbc:postgresql://localhost:5432/compliance
    username: compliance
    password: compliance
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
  jackson:
    default-property-inclusion: non_null

app:
  jwt:
    secret: "change-me-please-use-a-64-char-random-secret-for-hmac-sha-256"
    expiration-minutes: 120
  semgrep:
    command: semgrep
    working-dir: /tmp/compliance-scan
    timeout-seconds: 300

springdoc:
  swagger-ui:
    path: /swagger-ui.html

logging:
  level:
    com.example.compliance: INFO
```

`application-test.yml`:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
app:
  jwt:
    secret: "test-secret-test-secret-test-secret-test-secret-test-secret!"
    expiration-minutes: 120
```

- [ ] **Step 2: 写 Flyway V1**

`V1__init_audit_log.sql`:
```sql
CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    action        VARCHAR(64)  NOT NULL,
    module        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64),
    resource_id   BIGINT,
    detail        JSONB,
    ip            VARCHAR(64),
    occurred_at   TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_resource ON audit_log (resource_type, resource_id);
```

- [ ] **Step 3: 写 docker-compose**

`docker-compose.yml`:
```yaml
services:
  postgres:
    image: postgres:16
    container_name: compliance-postgres
    environment:
      POSTGRES_DB: compliance
      POSTGRES_USER: compliance
      POSTGRES_PASSWORD: compliance
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U compliance"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  pgdata:
```

- [ ] **Step 4: 写失败测试（冒烟集成测试）**

`AbstractIntegrationTest.kt`:
```kotlin
package com.example.compliance

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {
    companion object {
        init {
            // REQUIRED: Docker Desktop 4.7x (engine 29.x, MinAPIVersion >= 1.40) rejects
            // docker-java's hardcoded default API v1.32 with 400 (empty info) — see Ruling #14.
            System.setProperty("api.version", "1.44")
        }

        // One PostgreSQL container per test JVM, started once from the companion object.
        // NOTE (Task 1.1 deviation, root cause = latent M0 harness defect, see Ruling #19):
        // Testcontainers 1.20.4 stops a static @Container at the END of each test class and
        // starts a fresh one (new random host port) for the next class; Spring's CACHED test
        // context still points at the dead port, so the second integration-test class in a JVM
        // fails with "Connection refused" / Hikari timeout. Starting the container here (once per
        // JVM) keeps the port stable; Ryuk cleans up at JVM exit. Do NOT re-add @Testcontainers.
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("compliance")
            .withUsername("compliance")
            .withPassword("compliance")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
```

`SmokeIntegrationTest.kt`:
```kotlin
package com.example.compliance

import com.example.compliance.common.audit.AuditLogRepository
import com.example.compliance.common.audit.AuditService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class SmokeIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var auditService: AuditService

    @Autowired
    lateinit var auditLogRepository: AuditLogRepository

    @Test
    fun `flyway migrates and audit persists to real postgres`() {
        auditService.record(action = "SMOKE", module = "test", userId = 1L)
        assertEquals(1L, auditLogRepository.count())
    }
}
```

- [ ] **Step 5: 运行测试确认失败**

Run: `./gradlew :app-server:test --tests "*SmokeIntegrationTest*"`
Expected: 编译失败（类不存在）。注意：测试用 Testcontainers 自管 PostgreSQL，`docker compose up -d` 可选（仅作环境自检）。

- [ ] **Step 6: 写实现（上述文件已含全部实现代码，直接落地）后运行通过**

Run: `./gradlew :app-server:test --tests "*SmokeIntegrationTest*"`
Expected: PASS（Testcontainers 起真实 PG，Flyway 执行 V1，audit 写入并 count=1）。

- [ ] **Step 7: 手工验证启动**

Run: `./gradlew :app-server:bootRun`（后台），另终端 `curl http://localhost:8080/v3/api-docs` 或访问 `http://localhost:8080/swagger-ui.html`。
Expected: 应用启动成功，无 `FlywayException`。

- [ ] **Step 8: Commit**

```bash
git add app-server/src docker-compose.yml
git commit -m "feat(app): bootable app with docker-compose, flyway, testcontainers harness"
```

**M0 完成标准**：`./gradlew build -x test` 全绿；`SmokeIntegrationTest` 通过；`docker compose up -d` + `bootRun` 可启动。

---

## 里程碑 M1：用户、认证与 RBAC

### Task 1.1: module-user 领域模型与 Repository

**Files:**
- Create: `module-user/src/main/kotlin/com/example/compliance/user/domain/User.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/domain/Role.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/domain/UserRole.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/infrastructure/UserRepository.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/infrastructure/RoleRepository.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/infrastructure/UserRoleRepository.kt`
- Create: `app-server/src/main/resources/db/migration/V2__init_user.sql`
- Test: `app-server/src/test/kotlin/com/example/compliance/user/UserRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `User` 实体（`username` 唯一、`passwordHash`、`status`）；`Role`（`code` 唯一）；`UserRole` 关联；`UserRepository`（`findByUsername`、`existsByUsername`）；`RoleRepository`（`findByCode`）；`UserRoleRepository`（`findByUserId`）。

- [ ] **Step 1: 写失败测试（集成，验证 Flyway V2 建表 + JPA 映射）**

```kotlin
package com.example.compliance.user

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.user.domain.Role
import com.example.compliance.user.domain.User
import com.example.compliance.user.domain.UserRole
import com.example.compliance.user.infrastructure.RoleRepository
import com.example.compliance.user.infrastructure.UserRepository
import com.example.compliance.user.infrastructure.UserRoleRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UserRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roleRepository: RoleRepository
    @Autowired lateinit var userRoleRepository: UserRoleRepository

    @Test
    fun `save and find user by username`() {
        val saved = userRepository.save(User().apply {
            username = "alice"; passwordHash = "hash"; displayName = "Alice"
        })
        assertNotNull(saved.id)
        assertEquals("alice", userRepository.findByUsername("alice")?.username)
    }

    @Test
    fun `role and user-role mapping persist`() {
        // Ruling #27: code must not collide with Task 1.3 DataInitializer's seeded role codes
        // (ADMIN/COMPLIANCE_MANAGER/PROJECT_OWNER/DEVELOPER/AUDITOR) — sys_role.code is UNIQUE.
        val role = roleRepository.save(Role().apply { code = "TEST_ROLE"; name = "测试角色" })
        val user = userRepository.save(User().apply { username = "bob"; passwordHash = "h" })
        userRoleRepository.save(UserRole().apply { userId = user.id!!; roleId = role.id!! })
        assertEquals(listOf(role.id), userRoleRepository.findByUserId(user.id!!).map { it.roleId })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app-server:test --tests "*UserRepositoryIntegrationTest*"`
Expected: 编译失败（类不存在）；Flyway V2 不存在。

- [ ] **Step 3: 写 Flyway V2 与实体**

`V2__init_user.sql`:
```sql
CREATE TABLE sys_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    email         VARCHAR(128),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE sys_role (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE sys_user_role (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    role_id    BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_role_user ON sys_user_role (user_id);
CREATE INDEX idx_user_role_role ON sys_user_role (role_id);
```

`User.kt`:
```kotlin
package com.example.compliance.user.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "sys_user")
class User : BaseEntity() {
    @Column(name = "username", nullable = false, unique = true, length = 64)
    lateinit var username: String

    @Column(name = "password_hash", nullable = false, length = 128)
    lateinit var passwordHash: String

    @Column(name = "display_name", length = 128)
    var displayName: String? = null

    @Column(name = "email", length = 128)
    var email: String? = null

    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
}
```

`Role.kt`:
```kotlin
package com.example.compliance.user.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "sys_role")
class Role : BaseEntity() {
    @Column(name = "code", nullable = false, unique = true, length = 64)
    lateinit var code: String

    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String

    @Column(name = "description", length = 256)
    var description: String? = null
}
```

`UserRole.kt`:
```kotlin
package com.example.compliance.user.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "sys_user_role")
class UserRole : BaseEntity() {
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0

    @Column(name = "role_id", nullable = false)
    var roleId: Long = 0
}
```

Repository（三个接口，分别放 `infrastructure/` 下，内容如下）:
```kotlin
// UserRepository.kt
package com.example.compliance.user.infrastructure

import com.example.compliance.user.domain.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?
    fun existsByUsername(username: String): Boolean
}
```
```kotlin
// RoleRepository.kt
package com.example.compliance.user.infrastructure

import com.example.compliance.user.domain.Role
import org.springframework.data.jpa.repository.JpaRepository

interface RoleRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?
}
```
```kotlin
// UserRoleRepository.kt
package com.example.compliance.user.infrastructure

import com.example.compliance.user.domain.UserRole
import org.springframework.data.jpa.repository.JpaRepository

interface UserRoleRepository : JpaRepository<UserRole, Long> {
    fun findByUserId(userId: Long): List<UserRole>
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app-server:test --tests "*UserRepositoryIntegrationTest*"`
Expected: PASS（2 个测试，真实 PG 建表验证）。

- [ ] **Step 5: Commit**

```bash
git add module-user/src app-server/src/main/resources/db/migration
git commit -m "feat(user): user/role entities with flyway migration"
```

### Task 1.2: module-user 用户服务与 Controller

**Files:**
- Create: `module-user/src/main/kotlin/com/example/compliance/user/application/CreateUserCommand.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/application/UserService.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/config/PasswordEncoderConfig.kt`  <!-- Ruling #21: PasswordEncoder bean must exist BEFORE Task 1.3 (UserService injects it at context startup); Spring Boot auto-configures none. -->
- Create: `module-user/src/main/kotlin/com/example/compliance/user/api/dto/UserRequest.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/api/dto/UserResponse.kt`
- Create: `module-user/src/main/kotlin/com/example/compliance/user/api/UserController.kt`
- Modify: `app-server/build.gradle.kts` — add `runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")` (Ruling #26: spring-boot-starter-json does NOT include Kotlin support; without it every `@RequestBody` Kotlin DTO deserialization 500s. Needed by every future module controller.)
- Test: `module-user/src/test/kotlin/com/example/compliance/user/application/UserServiceTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/user/UserApiIntegrationTest.kt`

**Interfaces:**
- Consumes: `UserRepository`、`RoleRepository`、`UserRoleRepository`（Task 1.1）。
- Produces: `UserService.findByUsername(username): User?`；`UserService.createUser(CreateUserCommand): User`；`UserService.findRoles(userId): List<Role>`；`CreateUserCommand(username, password, displayName, email, roleCodes)`；`UserController`（`GET/POST /api/v1/users`、`GET /api/v1/users/{id}/roles`）。

- [ ] **Step 1: 写失败测试（单元）**

`UserServiceTest.kt`:
```kotlin
package com.example.compliance.user.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.user.domain.Role
import com.example.compliance.user.domain.User
import com.example.compliance.user.domain.UserRole
import com.example.compliance.user.infrastructure.RoleRepository
import com.example.compliance.user.infrastructure.UserRepository
import com.example.compliance.user.infrastructure.UserRoleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserServiceTest {
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val userRoleRepository = mockk<UserRoleRepository>(relaxed = true)
    private val service = UserService(userRepository, roleRepository, userRoleRepository, BCryptPasswordEncoder())

    @Test
    fun `create user encodes password and assigns roles`() {
        every { userRepository.existsByUsername("alice") } returns false
        // Ruling #26 (Task 1.2 fixes): role needs a non-null id (UserService does role.id!!);
        // userRoleRepository.save needs an explicit stub (MockK erasure on JpaRepository.save).
        every { roleRepository.findByCode("ADMIN") } returns Role().apply { id = 7L; code = "ADMIN" }
        every { userRepository.save(any()) } answers { firstArg<User>().apply { id = 1L } }
        every { userRoleRepository.save(any()) } returnsArgument 0

        service.createUser(CreateUserCommand("alice", "secret", "Alice", "a@x.com", listOf("ADMIN")))

        verify { userRepository.save(match { it.passwordHash != "secret" }) }
        verify { userRoleRepository.save(any()) }
    }

    @Test
    fun `create user rejects duplicate username`() {
        every { userRepository.existsByUsername("alice") } returns true
        assertFailsWith<BusinessException> {
            service.createUser(CreateUserCommand("alice", "x", "A", null, emptyList()))
        }
    }

    @Test
    fun `findRoles maps user roles to role entities`() {
        val role = Role().apply { id = 7L; code = "ADMIN" }
        every { userRoleRepository.findByUserId(1L) } returns listOf(
            UserRole().apply { userId = 1L; roleId = 7L }
        )
        every { roleRepository.findAllById(listOf(7L)) } returns listOf(role)
        assertTrue(service.findRoles(1L).contains(role))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-user:test --tests "*UserServiceTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现**

`CreateUserCommand.kt`:
```kotlin
package com.example.compliance.user.application

data class CreateUserCommand(
    val username: String,
    val password: String,
    val displayName: String?,
    val email: String?,
    val roleCodes: List<String>,
)
```

`PasswordEncoderConfig.kt` (Ruling #21 — UserService injects `PasswordEncoder` at context startup, and Spring Boot auto-configures no such bean; Task 1.3's SecurityConfig must NOT redefine this bean):
```kotlin
package com.example.compliance.user.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
class PasswordEncoderConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
```

`UserService.kt`:
```kotlin
package com.example.compliance.user.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.user.domain.Role
import com.example.compliance.user.domain.User
import com.example.compliance.user.domain.UserRole
import com.example.compliance.user.infrastructure.RoleRepository
import com.example.compliance.user.infrastructure.UserRepository
import com.example.compliance.user.infrastructure.UserRoleRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val userRoleRepository: UserRoleRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun findByUsername(username: String): User? = userRepository.findByUsername(username)

    @Transactional
    fun createUser(command: CreateUserCommand): User {
        if (userRepository.existsByUsername(command.username)) {
            throw BusinessException(400, "username already exists: ${command.username}")
        }
        val user = userRepository.save(
            User().apply {
                username = command.username
                passwordHash = passwordEncoder.encode(command.password)
                displayName = command.displayName
                email = command.email
            }
        )
        command.roleCodes.forEach { code ->
            val role = roleRepository.findByCode(code)
                ?: throw BusinessException(400, "role not found: $code")
            userRoleRepository.save(UserRole().apply { userId = user.id!!; roleId = role.id!! })
        }
        return user
    }

    fun findRoles(userId: Long): List<Role> {
        val roleIds = userRoleRepository.findByUserId(userId).map { it.roleId }
        return roleRepository.findAllById(roleIds)
    }

    fun page(pageable: Pageable): Page<User> = userRepository.findAll(pageable)
}
```

`UserRequest.kt`:
```kotlin
package com.example.compliance.user.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserRequest(
    @field:NotBlank @field:Size(max = 64) val username: String,
    @field:NotBlank @field:Size(min = 6, max = 64) val password: String,
    val displayName: String? = null,
    val email: String? = null,
    val roleCodes: List<String> = emptyList(),
)
```

`UserResponse.kt`:
```kotlin
package com.example.compliance.user.api.dto

data class UserResponse(
    val id: Long,
    val username: String,
    val displayName: String?,
    val email: String?,
    val status: String,
)
```

`UserController.kt`:
```kotlin
package com.example.compliance.user.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.user.api.dto.UserRequest
import com.example.compliance.user.api.dto.UserResponse
import com.example.compliance.user.application.CreateUserCommand
import com.example.compliance.user.application.UserService
import com.example.compliance.user.domain.User
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {

    @PostMapping
    fun create(@Valid @RequestBody request: UserRequest): ApiResponse<UserResponse> {
        val user = userService.createUser(
            CreateUserCommand(request.username, request.password, request.displayName, request.email, request.roleCodes)
        )
        return ApiResponse.ok(user.toResponse())
    }

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<UserResponse>> {
        val result = userService.page(PageRequest.of((page - 1).coerceAtLeast(0), size.coerceIn(1, 100)))
        // Ruling #26 (Task 1.2 fix): Page.map returns Page, not List — use Page.getContent().
        return ApiResponse.ok(PageResponse(result.content.map { it.toResponse() }, page, size, result.totalElements))
    }

    @GetMapping("/{id}/roles")
    fun roles(@PathVariable id: Long): ApiResponse<List<String>> =
        ApiResponse.ok(userService.findRoles(id).map { it.code })

    private fun User.toResponse() = UserResponse(id!!, username, displayName, email, status)
}
```

- [ ] **Step 4: 运行单元测试确认通过**

Run: `./gradlew :module-user:test --tests "*UserServiceTest*"`
Expected: PASS。

- [ ] **Step 5: 写 API 集成测试并运行**

`UserApiIntegrationTest.kt`:
```kotlin
package com.example.compliance.user

import com.example.compliance.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Ruling #22: module-common exposes spring-boot-starter-security, so Spring Security is on the
// classpath but no SecurityConfig exists until Task 1.3. Spring Boot's default chain then secures
// ALL endpoints AND leaves CSRF enabled → without @WithMockUser + csrf(), POST /api/v1/users would
// return 403/401 instead of 200/400. @WithMockUser still works after Task 1.3 (JwtAuthenticationFilter
// no-ops without a Bearer header; CSRF is disabled globally there, so .with(csrf()) is harmless).
@AutoConfigureMockMvc
@WithMockUser
class UserApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `create user returns id and validation rejects blank username`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"carol","password":"secret1","displayName":"Carol"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").exists())

        mockMvc.perform(
            post("/api/v1/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"","password":"secret1"}""")
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
```
Run: `./gradlew :app-server:test --tests "*UserApiIntegrationTest*"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add module-user/src app-server/src/test
git commit -m "feat(user): user service and REST API"
```

### Task 1.3: module-auth JWT 认证

**Files:**
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/application/JwtService.kt`
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/application/AuthService.kt`
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt`
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/config/JwtAuthenticationFilter.kt`
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/api/AuthController.kt`
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/api/dto/LoginRequest.kt`
- Create: `module-auth/src/main/kotlin/com/example/compliance/auth/api/dto/LoginResponse.kt`
- Create: `app-server/src/main/kotlin/com/example/compliance/DataInitializer.kt`
- Test: `module-auth/src/test/kotlin/com/example/compliance/auth/application/JwtServiceTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/auth/AuthIntegrationTest.kt`

**Interfaces:**
- Consumes: `UserService.findByUsername`、`UserService.findRoles`（Task 1.2）。
- Produces: `JwtService.issue(userId, username, roles): String`、`JwtService.parse(token): Jws<Claims>`、`JwtService.expirationMinutes(): Long`；`SecurityConfig`（`PasswordEncoder` Bean + `SecurityFilterChain`，放行 login/swagger/health，其余 authenticated）；`AuthService.login(username, password): String`；`AuthController`（`POST /api/v1/auth/login`、`GET /api/v1/auth/me`、`POST /api/v1/auth/logout`）。

- [ ] **Step 1: 写失败测试（单元）**

`JwtServiceTest.kt`:
```kotlin
package com.example.compliance.auth.application

import io.jsonwebtoken.ExpiredJwtException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtServiceTest {
    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val jwtService = JwtService(secret, 120)

    @Test
    fun `issue and parse round-trip carries subject and roles`() {
        val token = jwtService.issue(1L, "alice", listOf("ROLE_ADMIN"))
        val claims = jwtService.parse(token).payload
        assertEquals("alice", claims.subject)
        // D1 (Ruling #28): brief's Long::class.java is the PRIMITIVE long.class in Kotlin; jjwt 0.12.6
        // castClaimValue only coerces numbers to the BOXED Long.class → RequiredTypeException. Use
        // javaObjectType (= java.lang.Long). Round-trip intent unchanged.
        assertEquals(1L, claims.get("uid", Long::class.javaObjectType))
        assertEquals(listOf("ROLE_ADMIN"), claims.get("roles", List::class.java))
    }

    @Test
    fun `expired token is rejected`() {
        val expired = JwtService(secret, 0)
        val token = expired.issue(1L, "alice", emptyList())
        // Ruling #23: jjwt's parseSignedClaims THROWS ExpiredJwtException on expired tokens —
        // it never returns claims, so assert the rejection instead of reading .payload.
        assertFailsWith<ExpiredJwtException> { expired.parse(token) }
    }

    @Test
    fun `expiration minutes exposed to controller`() {
        assertEquals(120, jwtService.expirationMinutes())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-auth:test --tests "*JwtServiceTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现**

`JwtService.kt`:
```kotlin
package com.example.compliance.auth.application

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jws
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.expiration-minutes:120}") private val expirationMinutesValue: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun issue(userId: Long, username: String, roles: List<String>): String =
        Jwts.builder()
            .subject(username)
            .claim("uid", userId)
            .claim("roles", roles)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMinutesValue * 60_000))
            .signWith(key)
            .compact()

    fun parse(token: String): Jws<Claims> =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token)

    fun expirationMinutes(): Long = expirationMinutesValue
}
```

`SecurityConfig.kt`:
```kotlin
package com.example.compliance.auth.config

import com.example.compliance.auth.application.JwtService
import com.example.compliance.user.application.UserService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtService: JwtService,
    private val userService: UserService,
) {
    // NO passwordEncoder() bean here — the single PasswordEncoder bean lives in
    // module-user's PasswordEncoderConfig (Task 1.2, Ruling #21). Defining a second one
    // would fail the context (Boot 3 default disallows bean-definition overriding).

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // Ruling #23: without an explicit entry point, HttpSecurity's default is
            // Http403ForbiddenEntryPoint → unauthenticated requests get 403, but a JWT API
            // must answer 401 (constraint 7). AuthIntegrationTest asserts isUnauthorized.
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/v1/auth/login",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/actuator/health",
                ).permitAll()
                auth.anyRequest().authenticated()
            }
            .addFilterBefore(
                JwtAuthenticationFilter(jwtService, userService),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }
}
```

`JwtAuthenticationFilter.kt`:
```kotlin
package com.example.compliance.auth.config

import com.example.compliance.auth.application.JwtService
import com.example.compliance.user.application.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.web.filter.OncePerRequestFilter

// Ruling #28: NOT a @Component. Spring Boot auto-registers every Filter bean as a servlet-level
// filter OUTSIDE the FilterChainProxy; SecurityConfig also constructs this class and adds it to the
// security chain via addFilterBefore — a @Component would run the JWT logic twice per Bearer request
// (double parse + 2x findByUsername/findRoles). The manual chain registration covers ordering.
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userService: UserService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            try {
                val claims = jwtService.parse(header.removePrefix("Bearer ")).payload
                val user = userService.findByUsername(claims.subject)
                if (user != null && user.status == "ACTIVE") {
                    val authorities = userService.findRoles(user.id!!)
                        .map { SimpleGrantedAuthority("ROLE_" + it.code) }
                    val authentication = UsernamePasswordAuthenticationToken(
                        user.username, null, authorities,
                    )
                    authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authentication
                }
            } catch (e: Exception) {
                SecurityContextHolder.clearContext()
            }
        }
        filterChain.doFilter(request, response)
    }
}
```

`AuthService.kt`:
```kotlin
package com.example.compliance.auth.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.user.application.UserService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
) {
    fun login(username: String, password: String): String {
        val user = userService.findByUsername(username)
            ?: throw BusinessException(401, "invalid username or password")
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw BusinessException(401, "invalid username or password")
        }
        val roles = userService.findRoles(user.id!!).map { "ROLE_" + it.code }
        return jwtService.issue(user.id!!, user.username, roles)
    }
}
```

`LoginRequest.kt`:
```kotlin
package com.example.compliance.auth.api.dto

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)
```

`LoginResponse.kt`:
```kotlin
package com.example.compliance.auth.api.dto

data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresInMinutes: Long,
)
```

`AuthController.kt`:
```kotlin
package com.example.compliance.auth.api

import com.example.compliance.auth.api.dto.LoginRequest
import com.example.compliance.auth.api.dto.LoginResponse
import com.example.compliance.auth.application.AuthService
import com.example.compliance.auth.application.JwtService
import com.example.compliance.common.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val jwtService: JwtService,
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ApiResponse<LoginResponse> {
        val token = authService.login(request.username, request.password)
        return ApiResponse.ok(LoginResponse(token, "Bearer", jwtService.expirationMinutes()))
    }

    @GetMapping("/me")
    fun me(): ApiResponse<Map<String, String?>> {
        val auth: Authentication? = SecurityContextHolder.getContext().authentication
        return ApiResponse.ok(
            mapOf("username" to auth?.name, "roles" to auth?.authorities?.joinToString(",") { it.authority })
        )
    }

    @PostMapping("/logout")
    fun logout(): ApiResponse<Unit> {
        SecurityContextHolder.clearContext()
        return ApiResponse.ok()
    }
}
```

- [ ] **Step 4: 运行单元测试确认通过**

Run: `./gradlew :module-auth:test --tests "*JwtServiceTest*"`
Expected: PASS。

- [ ] **Step 5: 写 API 集成测试并运行**

`AuthIntegrationTest.kt`:
```kotlin
package com.example.compliance.auth

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.user.application.CreateUserCommand
import com.example.compliance.user.application.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class AuthIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userService: UserService

    @BeforeEach
    fun seedUser() {
        if (userService.findByUsername("dave") == null) {
            userService.createUser(CreateUserCommand("dave", "password1", "Dave", null, emptyList()))
        }
    }

    @Test
    fun `login returns token and me requires auth`() {
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"dave","password":"password1"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.token").isNotEmpty)

        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized)
    }
}
```
Run: `./gradlew :app-server:test --tests "*AuthIntegrationTest*"`
Expected: PASS。

- [ ] **Step 6: 种子角色与管理员**

`DataInitializer.kt`（app-server，`CommandLineRunner`）:
```kotlin
package com.example.compliance

import com.example.compliance.user.application.CreateUserCommand
import com.example.compliance.user.application.UserService
import com.example.compliance.user.domain.Role
import com.example.compliance.user.infrastructure.RoleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val roleRepository: RoleRepository,
    private val userService: UserService,
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(DataInitializer::class.java)

    override fun run(vararg args: String) {
        val roles = listOf("ADMIN", "COMPLIANCE_MANAGER", "PROJECT_OWNER", "DEVELOPER", "AUDITOR")
        if (roleRepository.count() == 0L) {
            roles.forEach { roleRepository.save(Role().apply { code = it; name = it }) }
            log.info("Seeded {} roles", roles.size)
        }
        if (userService.findByUsername("admin") == null) {
            userService.createUser(CreateUserCommand("admin", "admin123", "Platform Admin", null, listOf("ADMIN")))
            log.info("Seeded admin user")
        }
    }
}
```

- [ ] **Step 7: 运行全部 M1 相关测试**

Run: `./gradlew :module-user:test :module-auth:test :app-server:test --tests "*IntegrationTest*" --tests "*ServiceTest*" --tests "*JwtServiceTest*"`
Expected: 全部 PASS。

- [ ] **Step 8: Commit**

```bash
git add module-auth/src app-server/src
git commit -m "feat(auth): jwt login, security filter chain, seed data"
```

**M1 完成标准**：登录拿 token、`/me` 需认证、BCrypt 校验、角色种子就绪，集成测试全绿。

---

## 里程碑 M2：项目与代码仓库

### Task 2.1: module-project 领域模型与 Repository

**Files:**
- Create: `module-project/src/main/kotlin/com/example/compliance/project/domain/Project.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/domain/Repository.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/domain/ProjectStatus.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/infrastructure/ProjectRepository.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/infrastructure/RepoRepository.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/infrastructure/CredentialCrypto.kt`
- Create: `app-server/src/main/resources/db/migration/V3__init_project.sql`
- Test: `module-project/src/test/kotlin/com/example/compliance/project/infrastructure/CredentialCryptoTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/project/ProjectRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: `Project`（`code` 唯一、`name`、`ownerUserId`、`status`）；`Repository`（`projectId`、`gitUrl`、`provider`、`defaultBranch`、`credentialRef`）；`ProjectStatus`（ACTIVE/ARCHIVED）；`CredentialCrypto.encrypt(plain): String` / `decrypt(cipher): String`（AES-GCM，密钥来自 `app.credential.secret`）。

- [ ] **Step 1: 写失败测试（单元 + 集成）**

`CredentialCryptoTest.kt`:
```kotlin
package com.example.compliance.project.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CredentialCryptoTest {
    private val crypto = CredentialCrypto("0123456789abcdef0123456789abcdef")

    @Test
    fun `encrypt then decrypt round-trips and ciphertext differs`() {
        val cipher = crypto.encrypt("my-git-token")
        assertNotEquals("my-git-token", cipher)
        assertEquals("my-git-token", crypto.decrypt(cipher))
    }
}
```

`ProjectRepositoryIntegrationTest.kt`:
```kotlin
package com.example.compliance.project

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class ProjectRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var projectRepository: ProjectRepository

    @Test
    fun `save and find project by code`() {
        projectRepository.save(Project().apply { code = "PAY"; name = "支付中心" })
        assertEquals("PAY", projectRepository.findByCode("PAY")?.code)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-project:test :app-server:test --tests "*CredentialCryptoTest*" --tests "*ProjectRepositoryIntegrationTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写 Flyway V3 与实现**

`V3__init_project.sql`:
```sql
CREATE TABLE org_project (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    owner_user_id BIGINT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE repo_info (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL,
    name           VARCHAR(128) NOT NULL,
    git_url        VARCHAR(512) NOT NULL,
    provider       VARCHAR(32)  NOT NULL,
    default_branch VARCHAR(128) NOT NULL DEFAULT 'main',
    credential_ref VARCHAR(256),
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_repo_project ON repo_info (project_id);
```

`Project.kt`:
```kotlin
package com.example.compliance.project.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "org_project")
class Project : BaseEntity() {
    @Column(name = "code", nullable = false, unique = true, length = 64)
    lateinit var code: String

    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String

    @Column(name = "description")
    var description: String? = null

    @Column(name = "owner_user_id")
    var ownerUserId: Long? = null

    @Column(name = "status", nullable = false, length = 16)
    var status: String = ProjectStatus.ACTIVE.name
}
```

`ProjectStatus.kt`:
```kotlin
package com.example.compliance.project.domain

enum class ProjectStatus { ACTIVE, ARCHIVED }
```

`Repository.kt`:
```kotlin
package com.example.compliance.project.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "repo_info")
class Repository : BaseEntity() {
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0

    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String

    @Column(name = "git_url", nullable = false, length = 512)
    lateinit var gitUrl: String

    @Column(name = "provider", nullable = false, length = 32)
    lateinit var provider: String

    @Column(name = "default_branch", length = 128)
    var defaultBranch: String = "main"

    @Column(name = "credential_ref", length = 256)
    var credentialRef: String? = null

    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
}
```

`ProjectRepository.kt`:
```kotlin
package com.example.compliance.project.infrastructure

import com.example.compliance.project.domain.Project
import org.springframework.data.jpa.repository.JpaRepository

interface ProjectRepository : JpaRepository<Project, Long> {
    fun findByCode(code: String): Project?
    fun existsByCode(code: String): Boolean
}
```

`RepoRepository.kt`:
```kotlin
package com.example.compliance.project.infrastructure

import com.example.compliance.project.domain.Repository
import org.springframework.data.jpa.repository.JpaRepository

interface RepoRepository : JpaRepository<Repository, Long> {
    fun findByProjectId(projectId: Long): List<Repository>
}
```

`CredentialCrypto.kt`:
```kotlin
package com.example.compliance.project.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class CredentialCrypto(
    @Value("\${app.credential.secret}") private val secret: String,
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8).copyOf(32), "AES")
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(iv + ciphertext)
    }

    fun decrypt(payload: String): String {
        val raw = decoder.decode(payload)
        val iv = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
```

`application.yml` 增加：
```yaml
app:
  credential:
    secret: "0123456789abcdef0123456789abcdef"   # 生产环境改由环境变量注入
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-project:test :app-server:test --tests "*CredentialCryptoTest*" --tests "*ProjectRepositoryIntegrationTest*"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add module-project/src app-server/src
git commit -m "feat(project): project/repo entities, credential encryption"
```

### Task 2.2: module-project 服务与 Controller

**Files:**
- Create: `module-project/src/main/kotlin/com/example/compliance/project/application/commands.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/application/ProjectService.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/api/dto/ProjectRequest.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/api/dto/ProjectResponse.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/api/ProjectController.kt`
- Test: `module-project/src/test/kotlin/com/example/compliance/project/application/ProjectServiceTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/project/ProjectApiIntegrationTest.kt`

**Interfaces:**
- Consumes: `ProjectRepository`、`RepoRepository`、`CredentialCrypto`（Task 2.1）。
- Produces: `ProjectService.create(CreateProjectCommand): Project`；`ProjectService.get(id): Project`；`ProjectService.list(): List<Project>`；`ProjectService.bindRepository(projectId, BindRepositoryCommand): Repository`；`ProjectService.listRepositories(projectId): List<Repository>`；`ProjectController`（`GET/POST /api/v1/projects`、`GET /api/v1/projects/{id}`、`POST/GET /api/v1/projects/{id}/repositories`）。

- [ ] **Step 1: 写失败测试（单元）**

`ProjectServiceTest.kt`:
```kotlin
package com.example.compliance.project.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.domain.Project
import com.example.compliance.project.domain.Repository
import com.example.compliance.project.infrastructure.CredentialCrypto
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.project.infrastructure.RepoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class ProjectServiceTest {
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val repoRepository = mockk<RepoRepository>(relaxed = true)
    private val crypto = mockk<CredentialCrypto>()
    private val service = ProjectService(projectRepository, repoRepository, crypto)

    @Test
    fun `create rejects duplicate code`() {
        every { projectRepository.existsByCode("PAY") } returns true
        assertFailsWith<BusinessException> {
            service.create(CreateProjectCommand("PAY", "支付", null, null))
        }
    }

    @Test
    fun `create saves project`() {
        every { projectRepository.existsByCode("PAY") } returns false
        every { projectRepository.save(any()) } answers { firstArg<Project>().apply { id = 1L } }
        val project = service.create(CreateProjectCommand("PAY", "支付", null, 5L))
        assertEquals("PAY", project.code)
        verify { projectRepository.save(any()) }
    }

    @Test
    fun `bindRepository encrypts credential before persist`() {
        every { crypto.encrypt("plain-token") } returns "cipher-text"
        every { projectRepository.findById(1L) } returns java.util.Optional.of(
            Project().apply { id = 1L; code = "P"; name = "N" }
        )
        every { repoRepository.save(any()) } answers {
            firstArg<Repository>().apply { id = 2L }
        }
        val repo = service.bindRepository(
            1L,
            BindRepositoryCommand("repo-a", "https://git.example.com/a.git", "GITLAB", "main", "plain-token"),
        )
        assertEquals("cipher-text", repo.credentialRef)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-project:test --tests "*ProjectServiceTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现**

`commands.kt`:
```kotlin
package com.example.compliance.project.application

data class CreateProjectCommand(
    val code: String,
    val name: String,
    val description: String?,
    val ownerUserId: Long?,
)

data class BindRepositoryCommand(
    val name: String,
    val gitUrl: String,
    val provider: String,
    val defaultBranch: String,
    val credential: String?,
)
```

`ProjectService.kt`:
```kotlin
package com.example.compliance.project.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.domain.Project
import com.example.compliance.project.domain.Repository
import com.example.compliance.project.infrastructure.CredentialCrypto
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.project.infrastructure.RepoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val repoRepository: RepoRepository,
    private val credentialCrypto: CredentialCrypto,
) {
    @Transactional
    fun create(command: CreateProjectCommand): Project {
        if (projectRepository.existsByCode(command.code)) {
            throw BusinessException(400, "project code already exists: ${command.code}")
        }
        return projectRepository.save(
            Project().apply {
                code = command.code
                name = command.name
                description = command.description
                ownerUserId = command.ownerUserId
            }
        )
    }

    fun get(id: Long): Project =
        projectRepository.findById(id).orElseThrow { BusinessException(404, "project not found: $id") }

    fun list(): List<Project> = projectRepository.findAll()

    @Transactional
    fun bindRepository(projectId: Long, command: BindRepositoryCommand): Repository {
        get(projectId)
        return repoRepository.save(
            Repository().apply {
                this.projectId = projectId
                name = command.name
                gitUrl = command.gitUrl
                provider = command.provider
                defaultBranch = command.defaultBranch
                credentialRef = command.credential?.let { credentialCrypto.encrypt(it) }
            }
        )
    }

    fun listRepositories(projectId: Long): List<Repository> = repoRepository.findByProjectId(projectId)
}
```

`ProjectRequest.kt`:
```kotlin
package com.example.compliance.project.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ProjectRequest(
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
    val description: String? = null,
    val ownerUserId: Long? = null,
)

data class RepositoryRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val gitUrl: String,
    @field:NotBlank val provider: String,
    val defaultBranch: String = "main",
    val credential: String? = null,
)
```

`ProjectResponse.kt`:
```kotlin
package com.example.compliance.project.api.dto

import com.example.compliance.project.domain.Project
import com.example.compliance.project.domain.Repository

data class ProjectResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val ownerUserId: Long?,
    val status: String,
) {
    companion object {
        fun from(p: Project) = ProjectResponse(p.id!!, p.code, p.name, p.description, p.ownerUserId, p.status)
    }
}

data class RepositoryResponse(
    val id: Long,
    val projectId: Long,
    val name: String,
    val gitUrl: String,
    val provider: String,
    val defaultBranch: String,
) {
    companion object {
        fun from(r: Repository) =
            RepositoryResponse(r.id!!, r.projectId, r.name, r.gitUrl, r.provider, r.defaultBranch)
    }
}
```

`ProjectController.kt`:
```kotlin
package com.example.compliance.project.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.project.api.dto.ProjectRequest
import com.example.compliance.project.api.dto.ProjectResponse
import com.example.compliance.project.api.dto.RepositoryRequest
import com.example.compliance.project.api.dto.RepositoryResponse
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController(private val projectService: ProjectService) {

    @PostMapping
    fun create(@Valid @RequestBody request: ProjectRequest): ApiResponse<ProjectResponse> =
        ApiResponse.ok(
            ProjectResponse.from(
                projectService.create(
                    CreateProjectCommand(request.code, request.name, request.description, request.ownerUserId)
                )
            )
        )

    @GetMapping
    fun list(): ApiResponse<List<ProjectResponse>> =
        ApiResponse.ok(projectService.list().map { ProjectResponse.from(it) })

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<ProjectResponse> =
        ApiResponse.ok(ProjectResponse.from(projectService.get(id)))

    @PostMapping("/{id}/repositories")
    fun bindRepo(
        @PathVariable id: Long,
        @Valid @RequestBody request: RepositoryRequest,
    ): ApiResponse<RepositoryResponse> =
        ApiResponse.ok(
            RepositoryResponse.from(
                projectService.bindRepository(
                    id,
                    BindRepositoryCommand(request.name, request.gitUrl, request.provider, request.defaultBranch, request.credential),
                )
            )
        )

    @GetMapping("/{id}/repositories")
    fun repos(@PathVariable id: Long): ApiResponse<List<RepositoryResponse>> =
        ApiResponse.ok(projectService.listRepositories(id).map { RepositoryResponse.from(it) })
}
```

- [ ] **Step 4: 运行单元测试确认通过**

Run: `./gradlew :module-project:test --tests "*ProjectServiceTest*"`
Expected: PASS。

- [ ] **Step 5: 写 API 集成测试并运行**

`ProjectApiIntegrationTest.kt`:
```kotlin
package com.example.compliance.project

import com.example.compliance.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Ruling #24: Task 2.2 runs AFTER Task 1.3, whose SecurityConfig requires authentication for
// everything except login/swagger/health. Without @WithMockUser, JwtAuthenticationFilter no-ops
// (no Bearer header) and AuthorizationFilter rejects → 401. Same pattern as Ruling #22 (Task 1.2).
// CSRF is disabled globally since Task 1.3, so .with(csrf()) is harmless belt-and-suspenders.
@AutoConfigureMockMvc
@WithMockUser
class ProjectApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `create project then bind repository`() {
        val projectJson = """{"code":"ORDER","name":"订单中心","description":"x"}"""
        val result = mockMvc.perform(
            post("/api/v1/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(projectJson)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.code").value("ORDER"))
            .andReturn()
        val projectId = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.response.contentAsString)["data"]["id"].asLong()

        mockMvc.perform(
            post("/api/v1/projects/$projectId/repositories")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"order-api","gitUrl":"https://git.example.com/order.git","provider":"GITLAB","credential":"tok-123"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gitUrl").value("https://git.example.com/order.git"))
    }
}
```
Run: `./gradlew :app-server:test --tests "*ProjectApiIntegrationTest*"`
Expected: PASS（通过响应读取 projectId 拼接 URL，避免硬编码 id）。

- [ ] **Step 6: Commit**

```bash
git add module-project/src app-server/src
git commit -m "feat(project): project and repository REST API"
```

**M2 完成标准**：项目创建/查询、仓库绑定/查询与凭据加密可用，单元 + 集成测试全绿。（Ruling #30：Update/Delete 端点未纳入本计划任何任务——代码块与 Produces 签名一致；如需，作为后续小任务补充。）

---

## 里程碑 M3：合规清单与规则中心

### Task 3.1: module-checklist 领域模型与版本化

**Files:**
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/enums.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/ComplianceStandard.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/ComplianceChecklist.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/ChecklistVersion.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/ChecklistItem.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/ChecklistItemDetail.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/domain/ProjectChecklistBinding.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/infrastructure/repos.kt`
- Create: `app-server/src/main/resources/db/migration/V4__init_checklist.sql`
- Test: `app-server/src/test/kotlin/com/example/compliance/checklist/ChecklistRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: 实体 `ComplianceStandard`、`ComplianceChecklist`、`ChecklistVersion`、`ChecklistItem`、`ChecklistItemDetail`、`ProjectChecklistBinding`；枚举 `VersionStatus`（DRAFT/PUBLISHED/DISABLED）、`ItemResult`（PASS/WARNING/FAIL/MANUAL/SKIPPED，供合规判定使用）；Repository：`StandardRepository`、`ChecklistRepository`、`ChecklistVersionRepository`（`findByChecklistIdOrderByVersionNoDesc`、`findFirstByChecklistIdAndStatus`）、`ChecklistItemRepository`（`findByVersionId`）、`BindingRepository`（`findFirstByProjectIdOrderByIdDesc`）。

- [ ] **Step 1: 写失败测试（集成，验证 Flyway V4 建表）**

`ChecklistRepositoryIntegrationTest.kt`:
```kotlin
package com.example.compliance.checklist

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import com.example.compliance.checklist.infrastructure.StandardRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class ChecklistRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var standardRepository: StandardRepository
    @Autowired lateinit var checklistRepository: ChecklistRepository
    @Autowired lateinit var versionRepository: ChecklistVersionRepository
    @Autowired lateinit var itemRepository: ChecklistItemRepository
    @Autowired lateinit var bindingRepository: BindingRepository

    @Test
    fun `standard checklist version item binding persist`() {
        val standard = standardRepository.save(ComplianceStandard().apply {
            code = "SEC"; name = "安全编码规范"
        })
        val checklist = checklistRepository.save(ComplianceChecklist().apply {
            standardId = standard.id!!; code = "SEC-BASIC"; name = "安全基线清单"
        })
        val version = versionRepository.save(ChecklistVersion().apply {
            checklistId = checklist.id!!; versionNo = "V1"; status = VersionStatus.DRAFT
        })
        itemRepository.save(ChecklistItem().apply {
            versionId = version.id!!; itemCode = "SEC-001"; name = "禁止SQL注入"; riskLevel = "HIGH"
        })
        bindingRepository.save(ProjectChecklistBinding().apply {
            projectId = 1L; checklistVersionId = version.id!!
        })

        assertEquals(1, itemRepository.findByVersionId(version.id!!).size)
        assertEquals(1, versionRepository.findByChecklistIdOrderByVersionNoDesc(checklist.id!!).size)
        assertEquals(version.id, bindingRepository.findFirstByProjectIdOrderByIdDesc(1L)?.checklistVersionId)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app-server:test --tests "*ChecklistRepositoryIntegrationTest*"`
Expected: 编译失败（类不存在）；Flyway V4 不存在。

- [ ] **Step 3: 写 Flyway V4 与实体**

`V4__init_checklist.sql`:
```sql
CREATE TABLE compliance_standard (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE compliance_checklist (
    id          BIGSERIAL PRIMARY KEY,
    standard_id BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE checklist_version (
    id               BIGSERIAL PRIMARY KEY,
    checklist_id     BIGINT       NOT NULL,
    version_no       VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    content_snapshot JSONB,
    published_at     TIMESTAMP,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_cv_checklist ON checklist_version (checklist_id, version_no);

CREATE TABLE checklist_item (
    id           BIGSERIAL PRIMARY KEY,
    version_id   BIGINT       NOT NULL,
    item_code    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    category     VARCHAR(64),
    risk_level   VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    description  TEXT,
    basis        TEXT,
    remediation  TEXT,
    required     BOOLEAN      NOT NULL DEFAULT TRUE,
    waivable     BOOLEAN      NOT NULL DEFAULT FALSE,
    score_weight NUMERIC(6,3) NOT NULL DEFAULT 1.0,
    effective_from TIMESTAMP,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_ci_version ON checklist_item (version_id, item_code);

CREATE TABLE checklist_item_detail (
    id          BIGSERIAL PRIMARY KEY,
    item_id     BIGINT NOT NULL,
    detail_json JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE project_checklist_binding (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL,
    checklist_version_id BIGINT NOT NULL,
    bound_at            TIMESTAMP NOT NULL DEFAULT now(),
    bound_by            BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_binding_project ON project_checklist_binding (project_id);
```

实体（继承 `BaseEntity`）:
`enums.kt`:
```kotlin
package com.example.compliance.checklist.domain

enum class VersionStatus { DRAFT, PUBLISHED, DISABLED }

enum class ItemResult { PASS, WARNING, FAIL, MANUAL, SKIPPED }
```

`ComplianceStandard.kt`:
```kotlin
package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "compliance_standard")
class ComplianceStandard : BaseEntity() {
    @Column(name = "code", nullable = false, unique = true, length = 64)
    lateinit var code: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "description")
    var description: String? = null
    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
}
```

`ComplianceChecklist.kt`:
```kotlin
package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "compliance_checklist")
class ComplianceChecklist : BaseEntity() {
    @Column(name = "standard_id", nullable = false)
    var standardId: Long = 0
    @Column(name = "code", nullable = false, unique = true, length = 64)
    lateinit var code: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "description")
    var description: String? = null
    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
}
```

`ChecklistVersion.kt`:
```kotlin
package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "checklist_version")
class ChecklistVersion : BaseEntity() {
    @Column(name = "checklist_id", nullable = false)
    var checklistId: Long = 0
    @Column(name = "version_no", nullable = false, length = 32)
    lateinit var versionNo: String
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: VersionStatus = VersionStatus.DRAFT
    // Ruling #25: String on a jsonb column binds as varchar without @JdbcTypeCode (Ruling #13
    // pattern) — INSERT fails "column is of type jsonb but expression is of type character varying".
    // Task 3.2's versioning WILL write content_snapshot, so the annotation must be here now.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_snapshot", columnDefinition = "jsonb")
    var contentSnapshot: String? = null
    @Column(name = "published_at")
    var publishedAt: Instant? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`ChecklistItem.kt`:
```kotlin
package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "checklist_item")
class ChecklistItem : BaseEntity() {
    @Column(name = "version_id", nullable = false)
    var versionId: Long = 0
    @Column(name = "item_code", nullable = false, length = 64)
    lateinit var itemCode: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "category", length = 64)
    var category: String? = null
    @Column(name = "risk_level", nullable = false, length = 16)
    var riskLevel: String = "MEDIUM"
    @Column(name = "description")
    var description: String? = null
    @Column(name = "basis")
    var basis: String? = null
    @Column(name = "remediation")
    var remediation: String? = null
    @Column(name = "required", nullable = false)
    var required: Boolean = true
    @Column(name = "waivable", nullable = false)
    var waivable: Boolean = false
    @Column(name = "score_weight", nullable = false, precision = 6, scale = 3)
    var scoreWeight: BigDecimal = BigDecimal.ONE
    @Column(name = "effective_from")
    var effectiveFrom: Instant? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`ChecklistItemDetail.kt`:
```kotlin
package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "checklist_item_detail")
class ChecklistItemDetail : BaseEntity() {
    @Column(name = "item_id", nullable = false)
    var itemId: Long = 0
    // Ruling #25: same jsonb binding requirement as ChecklistVersion.contentSnapshot.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    var detailJson: String? = null
}
```

`ProjectChecklistBinding.kt`:
```kotlin
package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "project_checklist_binding")
class ProjectChecklistBinding : BaseEntity() {
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "checklist_version_id", nullable = false)
    var checklistVersionId: Long = 0
    @Column(name = "bound_at", nullable = false)
    var boundAt: Instant = Instant.now()
    @Column(name = "bound_by")
    var boundBy: Long? = null
}
```

`repos.kt`（全部接口）:
```kotlin
package com.example.compliance.checklist.infrastructure

import com.example.compliance.checklist.domain.ComplianceStandard
import org.springframework.data.jpa.repository.JpaRepository

interface StandardRepository : JpaRepository<ComplianceStandard, Long> {
    fun findByCode(code: String): ComplianceStandard?
    fun existsByCode(code: String): Boolean
}
```
```kotlin
package com.example.compliance.checklist.infrastructure

import com.example.compliance.checklist.domain.ComplianceChecklist
import org.springframework.data.jpa.repository.JpaRepository

interface ChecklistRepository : JpaRepository<ComplianceChecklist, Long> {
    fun findByCode(code: String): ComplianceChecklist?
    fun existsByCode(code: String): Boolean
}
```
```kotlin
package com.example.compliance.checklist.infrastructure

import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.VersionStatus
import org.springframework.data.jpa.repository.JpaRepository

interface ChecklistVersionRepository : JpaRepository<ChecklistVersion, Long> {
    fun findByChecklistIdOrderByVersionNoDesc(checklistId: Long): List<ChecklistVersion>
    fun findFirstByChecklistIdAndStatusOrderByIdDesc(checklistId: Long, status: VersionStatus): ChecklistVersion?
}
```
```kotlin
package com.example.compliance.checklist.infrastructure

import com.example.compliance.checklist.domain.ChecklistItem
import org.springframework.data.jpa.repository.JpaRepository

interface ChecklistItemRepository : JpaRepository<ChecklistItem, Long> {
    fun findByVersionId(versionId: Long): List<ChecklistItem>
}
```
```kotlin
package com.example.compliance.checklist.infrastructure

import com.example.compliance.checklist.domain.ProjectChecklistBinding
import org.springframework.data.jpa.repository.JpaRepository

interface BindingRepository : JpaRepository<ProjectChecklistBinding, Long> {
    fun findFirstByProjectIdOrderByIdDesc(projectId: Long): ProjectChecklistBinding?
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app-server:test --tests "*ChecklistRepositoryIntegrationTest*"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add module-checklist/src app-server/src/main/resources/db/migration
git commit -m "feat(checklist): compliance entities with versioning"
```
### Task 3.2: module-checklist 服务与 Controller（版本化发布）

**Files:**
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/application/commands.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/application/ChecklistService.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/application/ChecklistQueryService.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/api/dto/ChecklistRequest.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/api/dto/ChecklistResponse.kt`
- Create: `module-checklist/src/main/kotlin/com/example/compliance/checklist/api/ChecklistController.kt`
- Test: `module-checklist/src/test/kotlin/com/example/compliance/checklist/application/ChecklistServiceTest.kt`

**Interfaces:**
- Consumes: Task 3.1 的 Repository（`StandardRepository`、`ChecklistRepository`、`ChecklistVersionRepository`、`ChecklistItemRepository`、`BindingRepository`）。
- Produces: `ChecklistService.createStandard(code, name, description?): ComplianceStandard`；`createChecklist(standardId, code, name): ComplianceChecklist`；`addItem(checklistId, AddItemCommand): ChecklistItem`；`publish(checklistId): ChecklistVersion`；`bindProject(projectId, checklistVersionId): ProjectChecklistBinding`；`ChecklistQueryService.versionItems(versionId): List<ChecklistItem>`；`versions(checklistId): List<ChecklistVersion>`；`publishedItemsForProject(projectId): List<ChecklistItem>?`；`ChecklistController`（`POST /api/v1/compliance/standards`、`POST /api/v1/compliance/checklists`、`POST /api/v1/compliance/checklists/{id}/versions`、`GET /api/v1/compliance/checklists/{id}/versions`、`POST /api/v1/compliance/checklists/{id}/publish`、`POST /api/v1/projects/{projectId}/bind-checklist`、`GET /api/v1/projects/{projectId}/checklists`）。

- [ ] **Step 1: 写失败测试（单元）**

`ChecklistServiceTest.kt`:
```kotlin
package com.example.compliance.checklist.application

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import com.example.compliance.checklist.infrastructure.StandardRepository
import com.example.compliance.common.audit.AuditService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChecklistServiceTest {
    private val standardRepository = mockk<StandardRepository>(relaxed = true)
    private val checklistRepository = mockk<ChecklistRepository>(relaxed = true)
    private val versionRepository = mockk<ChecklistVersionRepository>(relaxed = true)
    private val itemRepository = mockk<ChecklistItemRepository>(relaxed = true)
    private val bindingRepository = mockk<BindingRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ChecklistService(
        standardRepository, checklistRepository, versionRepository, itemRepository, bindingRepository, auditService,
    )
    private val query = ChecklistQueryService(itemRepository, bindingRepository, versionRepository)

    @Test
    fun `createChecklist creates checklist plus first draft version V1`() {
        // Ruling #32: relaxed MockK defaults Boolean to false → unstubbed existsById(1L) would
        // make createChecklist throw BusinessException(404) (standard not found) and fail this test.
        // Task 2.2's green test stubbed existsByCode explicitly for the same reason (Ruling #13/#26 pattern).
        every { standardRepository.existsById(1L) } returns true
        every { checklistRepository.save(any()) } answers {
            firstArg<ComplianceChecklist>().apply { id = 10L }
        }
        every { versionRepository.save(any()) } answers {
            firstArg<ChecklistVersion>().apply { id = 20L }
        }
        val checklist = service.createChecklist(1L, "SEC-BASIC", "安全基线")
        assertEquals("SEC-BASIC", checklist.code)
        verify { versionRepository.save(match { it.status == VersionStatus.DRAFT && it.versionNo == "V1" }) }
    }

    @Test
    fun `publish snapshots items and opens no draft until next add`() {
        every { checklistRepository.findById(10L) } returns Optional.of(
            ComplianceChecklist().apply { id = 10L; code = "C"; name = "N" }
        )
        every { versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(10L, VersionStatus.DRAFT) } returns
            ChecklistVersion().apply { id = 20L; checklistId = 10L; versionNo = "V1"; status = VersionStatus.DRAFT }
        every { versionRepository.findByChecklistIdOrderByVersionNoDesc(10L) } returns
            listOf(ChecklistVersion().apply { id = 20L; versionNo = "V1" })
        every { itemRepository.findByVersionId(20L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001"; name = "x" })
        every { versionRepository.save(any()) } answers { firstArg<ChecklistVersion>() }

        val published = service.publish(10L)
        assertEquals(VersionStatus.PUBLISHED, published.status)
        assertNotNull(published.contentSnapshot)
    }

    @Test
    fun `addItem after publish creates new draft version V2`() {
        every { checklistRepository.findById(10L) } returns Optional.of(
            ComplianceChecklist().apply { id = 10L; code = "C"; name = "N" }
        )
        every { versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(10L, VersionStatus.DRAFT) } returns null
        every { versionRepository.findByChecklistIdOrderByVersionNoDesc(10L) } returns
            listOf(ChecklistVersion().apply { id = 20L; versionNo = "V2"; status = VersionStatus.PUBLISHED })
        every { versionRepository.save(any()) } answers {
            firstArg<ChecklistVersion>().apply { id = 30L; checklistId = 10L }
        }
        every { itemRepository.save(any()) } answers { firstArg<ChecklistItem>().apply { id = 99L } }

        val item = service.addItem(10L, AddItemCommand("SEC-002", "禁止硬编码密码", "HIGH"))
        assertEquals("SEC-002", item.itemCode)
        verify { versionRepository.save(match { it.versionNo == "V3" }) }
    }

    @Test
    fun `query returns published items for bound project`() {
        every { bindingRepository.findFirstByProjectIdOrderByIdDesc(1L) } returns
            ProjectChecklistBinding().apply { projectId = 1L; checklistVersionId = 20L }
        every { versionRepository.findById(20L) } returns Optional.of(
            ChecklistVersion().apply { id = 20L; status = VersionStatus.PUBLISHED }
        )
        every { itemRepository.findByVersionId(20L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001"; name = "x" })
        val items = query.publishedItemsForProject(1L)
        assertEquals(1, items!!.size)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-checklist:test --tests "*ChecklistServiceTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现**

`commands.kt`:
```kotlin
package com.example.compliance.checklist.application

data class AddItemCommand(
    val itemCode: String,
    val name: String,
    val category: String? = null,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
    val basis: String? = null,
    val remediation: String? = null,
    val required: Boolean = true,
    val waivable: Boolean = false,
    val scoreWeight: java.math.BigDecimal = java.math.BigDecimal.ONE,
)
```

`ChecklistService.kt`:
```kotlin
package com.example.compliance.checklist.application

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import com.example.compliance.checklist.infrastructure.StandardRepository
import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ChecklistService(
    private val standardRepository: StandardRepository,
    private val checklistRepository: ChecklistRepository,
    private val versionRepository: ChecklistVersionRepository,
    private val itemRepository: ChecklistItemRepository,
    private val bindingRepository: BindingRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    fun createStandard(code: String, name: String, description: String?): ComplianceStandard {
        if (standardRepository.existsByCode(code)) {
            throw BusinessException(400, "standard code already exists: $code")
        }
        return standardRepository.save(ComplianceStandard().apply {
            this.code = code; this.name = name; this.description = description
        })
    }

    @Transactional
    fun createChecklist(standardId: Long, code: String, name: String): ComplianceChecklist {
        if (!standardRepository.existsById(standardId)) {
            throw BusinessException(404, "standard not found: $standardId")
        }
        if (checklistRepository.existsByCode(code)) {
            throw BusinessException(400, "checklist code already exists: $code")
        }
        val checklist = checklistRepository.save(ComplianceChecklist().apply {
            this.standardId = standardId; this.code = code; this.name = name
        })
        versionRepository.save(ChecklistVersion().apply {
            checklistId = checklist.id!!; versionNo = "V1"; status = VersionStatus.DRAFT
        })
        return checklist
    }

    @Transactional
    fun addItem(checklistId: Long, command: AddItemCommand): ChecklistItem {
        val version = currentDraftOrNew(checklistId)
        return itemRepository.save(ChecklistItem().apply {
            versionId = version.id!!
            itemCode = command.itemCode
            name = command.name
            category = command.category
            riskLevel = command.riskLevel
            description = command.description
            basis = command.basis
            remediation = command.remediation
            required = command.required
            waivable = command.waivable
            scoreWeight = command.scoreWeight
        })
    }

    /** 返回当前 DRAFT 版本；若最新已是 PUBLISHED 则新建下一个版本号（版本化编辑）。 */
    private fun currentDraftOrNew(checklistId: Long): ChecklistVersion {
        checklistRepository.findById(checklistId)
            .orElseThrow { BusinessException(404, "checklist not found: $checklistId") }
        versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(checklistId, VersionStatus.DRAFT)
            ?.let { return it }
        val latest = versionRepository.findByChecklistIdOrderByVersionNoDesc(checklistId).firstOrNull()
        val nextNo = latest?.versionNo?.removePrefix("V")?.toIntOrNull()?.plus(1)?.let { "V$it" } ?: "V1"
        return versionRepository.save(ChecklistVersion().apply {
            this.checklistId = checklistId; versionNo = nextNo; status = VersionStatus.DRAFT
        })
    }

    @Transactional
    fun publish(checklistId: Long): ChecklistVersion {
        val version = versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(checklistId, VersionStatus.DRAFT)
            ?: throw BusinessException(400, "no draft version to publish for checklist: $checklistId")
        val items = itemRepository.findByVersionId(version.id!!)
        val snapshot = objectMapper.writeValueAsString(
            items.map { mapOf("itemCode" to it.itemCode, "name" to it.name, "riskLevel" to it.riskLevel) }
        )
        version.status = VersionStatus.PUBLISHED
        version.contentSnapshot = snapshot
        version.publishedAt = Instant.now()
        val saved = versionRepository.save(version)
        auditService.record(
            "CHECKLIST_PUBLISH", "checklist", null, "checklist_version",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — plain-text detail fails INSERT with
            // "invalid input syntax for type json" → 500. Must be a valid JSON object.
            saved.id, """{"checklist":$checklistId,"version":"${saved.versionNo}"}""", null,
        )
        return saved
    }

    @Transactional
    fun bindProject(projectId: Long, checklistVersionId: Long): ProjectChecklistBinding {
        val version = versionRepository.findById(checklistVersionId)
            .orElseThrow { BusinessException(404, "version not found: $checklistVersionId") }
        if (version.status != VersionStatus.PUBLISHED) {
            throw BusinessException(400, "only published version can be bound")
        }
        val binding = bindingRepository.save(ProjectChecklistBinding().apply {
            this.projectId = projectId; this.checklistVersionId = checklistVersionId
        })
        auditService.record(
            "CHECKLIST_BIND", "checklist", null, "project",
            projectId, """{"checklistVersion":$checklistVersionId}""", null,
        )
        return binding
    }
}
```

`ChecklistQueryService.kt`:
```kotlin
package com.example.compliance.checklist.application

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import org.springframework.stereotype.Service

@Service
class ChecklistQueryService(
    private val itemRepository: ChecklistItemRepository,
    private val bindingRepository: BindingRepository,
    private val versionRepository: ChecklistVersionRepository,
) {
    fun versionItems(versionId: Long): List<ChecklistItem> = itemRepository.findByVersionId(versionId)

    /** 清单的全部版本（版本化配置可审计，供 GET /compliance/checklists/{id}/versions）。 */
    fun versions(checklistId: Long): List<ChecklistVersion> =
        versionRepository.findByChecklistIdOrderByVersionNoDesc(checklistId)

    /** 项目当前绑定的已发布版本的全部合规项；未绑定返回 null。 */
    fun publishedItemsForProject(projectId: Long): List<ChecklistItem>? {
        val binding = bindingRepository.findFirstByProjectIdOrderByIdDesc(projectId) ?: return null
        val version = versionRepository.findById(binding.checklistVersionId).orElse(null) ?: return null
        if (version.status != VersionStatus.PUBLISHED) return null
        return itemRepository.findByVersionId(version.id!!)
    }
}
```

`ChecklistRequest.kt`:
```kotlin
package com.example.compliance.checklist.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class StandardRequest(
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
    val description: String? = null,
)

data class ChecklistRequest(
    val standardId: Long,
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
)

data class ChecklistItemRequest(
    @field:NotBlank @field:Size(max = 64) val itemCode: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
    val category: String? = null,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
    val basis: String? = null,
    val remediation: String? = null,
    val required: Boolean = true,
    val waivable: Boolean = false,
    val scoreWeight: BigDecimal = BigDecimal.ONE,
)

data class BindRequest(val checklistVersionId: Long)
```

`ChecklistResponse.kt`:
```kotlin
package com.example.compliance.checklist.api.dto

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding

data class StandardResponse(
    val id: Long, val code: String, val name: String, val description: String?,
) { companion object { fun from(s: ComplianceStandard) = StandardResponse(s.id!!, s.code, s.name, s.description) } }

data class ChecklistResponse(
    val id: Long, val standardId: Long, val code: String, val name: String,
) { companion object { fun from(c: ComplianceChecklist) = ChecklistResponse(c.id!!, c.standardId, c.code, c.name) } }

data class VersionResponse(
    val id: Long, val checklistId: Long, val versionNo: String, val status: String, val publishedAt: java.time.Instant?,
) {
    companion object { fun from(v: ChecklistVersion) = VersionResponse(v.id!!, v.checklistId, v.versionNo, v.status.name, v.publishedAt) }
}

data class ItemResponse(
    val id: Long, val versionId: Long, val itemCode: String, val name: String,
    val category: String?, val riskLevel: String, val required: Boolean, val waivable: Boolean,
) {
    companion object {
        fun from(i: ChecklistItem) = ItemResponse(
            i.id!!, i.versionId, i.itemCode, i.name, i.category, i.riskLevel, i.required, i.waivable,
        )
    }
}

data class BindingResponse(val id: Long, val projectId: Long, val checklistVersionId: Long) {
    companion object { fun from(b: ProjectChecklistBinding) = BindingResponse(b.id!!, b.projectId, b.checklistVersionId) }
}
```

`ChecklistController.kt`:
```kotlin
package com.example.compliance.checklist.api

import com.example.compliance.checklist.api.dto.BindRequest
import com.example.compliance.checklist.api.dto.BindingResponse
import com.example.compliance.checklist.api.dto.ChecklistItemRequest
import com.example.compliance.checklist.api.dto.ChecklistRequest
import com.example.compliance.checklist.api.dto.ChecklistResponse
import com.example.compliance.checklist.api.dto.ItemResponse
import com.example.compliance.checklist.api.dto.StandardRequest
import com.example.compliance.checklist.api.dto.StandardResponse
import com.example.compliance.checklist.api.dto.VersionResponse
import com.example.compliance.checklist.application.AddItemCommand
import com.example.compliance.checklist.application.ChecklistQueryService
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.common.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class ChecklistController(
    private val checklistService: ChecklistService,
    private val queryService: ChecklistQueryService,
) {
    @PostMapping("/compliance/standards")
    fun createStandard(@Valid @RequestBody req: StandardRequest): ApiResponse<StandardResponse> =
        ApiResponse.ok(StandardResponse.from(checklistService.createStandard(req.code, req.name, req.description)))

    @PostMapping("/compliance/checklists")
    fun createChecklist(@Valid @RequestBody req: ChecklistRequest): ApiResponse<ChecklistResponse> =
        ApiResponse.ok(ChecklistResponse.from(checklistService.createChecklist(req.standardId, req.code, req.name)))

    /** 给当前 DRAFT 版本追加合规项（版本化编辑：若最新已发布则自动开新版本）。 */
    @PostMapping("/compliance/checklists/{id}/versions")
    fun addItem(@PathVariable id: Long, @Valid @RequestBody req: ChecklistItemRequest): ApiResponse<ItemResponse> =
        ApiResponse.ok(
            ItemResponse.from(
                checklistService.addItem(
                    id,
                    AddItemCommand(
                        req.itemCode, req.name, req.category, req.riskLevel, req.description,
                        req.basis, req.remediation, req.required, req.waivable, req.scoreWeight,
                    ),
                )
            )
        )

    @GetMapping("/compliance/checklists/{id}/versions")
    fun versions(@PathVariable id: Long): ApiResponse<List<VersionResponse>> =
        ApiResponse.ok(queryService.versions(id).map { VersionResponse.from(it) })

    @PostMapping("/compliance/checklists/{id}/publish")
    fun publish(@PathVariable id: Long): ApiResponse<VersionResponse> =
        ApiResponse.ok(VersionResponse.from(checklistService.publish(id)))

    @PostMapping("/projects/{projectId}/bind-checklist")
    fun bind(@PathVariable projectId: Long, @Valid @RequestBody req: BindRequest): ApiResponse<BindingResponse> =
        ApiResponse.ok(BindingResponse.from(checklistService.bindProject(projectId, req.checklistVersionId)))

    @GetMapping("/projects/{projectId}/checklists")
    fun projectChecklist(@PathVariable projectId: Long): ApiResponse<List<ItemResponse>> {
        val items = queryService.publishedItemsForProject(projectId)
            ?: return ApiResponse.error(404, "project has no bound published checklist")
        return ApiResponse.ok(items.map { ItemResponse.from(it) })
    }
}
```

- [ ] **Step 4: 运行单元测试确认通过**

Run: `./gradlew :module-checklist:test --tests "*ChecklistServiceTest*"`
Expected: PASS。

- [ ] **Step 5: 写 API 集成测试并运行**

`app-server/src/test/kotlin/com/example/compliance/checklist/ChecklistApiIntegrationTest.kt`:
```kotlin
package com.example.compliance.checklist

import com.example.compliance.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Ruling #32: Task 3.2 runs AFTER Task 1.3, whose SecurityConfig requires authentication for
// everything except login/swagger/health. Without @WithMockUser, every request here → 401 (same
// defect pattern as Ruling #22/#24 — Task 1.2/2.2's tests were patched, this one was authored
// without it). CSRF is disabled globally since Task 1.3, so .with(csrf()) is harmless belt-and-suspenders.
@AutoConfigureMockMvc
@WithMockUser
class ChecklistApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    private fun postJson(url: String, json: String): String =
        mockMvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun idOf(body: String): Long = com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(body)["data"]["id"].asLong()

    @Test
    fun `standard checklist publish bind then query items`() {
        // Ruling #34: SEC/SEC-BASIC/SEC-001 are used by the frozen Task 3.1 ChecklistRepositoryIntegrationTest
        // in the SAME :app-server:test JVM/container → duplicate unique key. These SEC2* codes are disjoint.
        val standardId = idOf(postJson("/api/v1/compliance/standards", """{"code":"SEC2","name":"安全编码规范"}"""))
        val checklistId = idOf(postJson("/api/v1/compliance/checklists", """{"standardId":$standardId,"code":"SEC2-BASIC","name":"安全基线"}"""))
        postJson("/api/v1/compliance/checklists/$checklistId/versions", """{"itemCode":"SEC2-001","name":"禁止SQL注入","riskLevel":"HIGH"}""")
        val publishBody = postJson("/api/v1/compliance/checklists/$checklistId/publish", "")
        val versionId = idOf(publishBody)

        postJson("/api/v1/projects/1/bind-checklist", """{"checklistVersionId":$versionId}""")

        mockMvc.perform(get("/api/v1/projects/1/checklists"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].itemCode").value("SEC2-001"))
    }
}
```
Run: `./gradlew :app-server:test --tests "*ChecklistApiIntegrationTest*"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add module-checklist/src app-server/src
git commit -m "feat(checklist): versioned checklist publish and binding API"
```

**M3（清单部分）完成标准**：标准→清单→版本→条目→发布→项目绑定→查询闭环可用，版本化编辑（发布后新增条目自动开新版本）有单元测试覆盖。
### Task 3.3: module-rule 领域模型与规则中心服务

**Files:**
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/domain/enums.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/domain/RuleDefinition.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/domain/RuleEngineBinding.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/domain/RuleComplianceMapping.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/domain/RuleEvaluationPolicy.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/infrastructure/repos.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/application/commands.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/application/RuleService.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/application/RuleQueryService.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/api/dto/RuleRequest.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/api/dto/RuleResponse.kt`
- Create: `module-rule/src/main/kotlin/com/example/compliance/rule/api/RuleController.kt`
- Create: `app-server/src/main/resources/db/migration/V5__init_rule.sql`
- Test: `module-rule/src/test/kotlin/com/example/compliance/rule/application/RuleServiceTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/rule/RuleRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces: 实体 `RuleDefinition`、`RuleEngineBinding`、`RuleComplianceMapping`、`RuleEvaluationPolicy`；枚举 `RuleStatus`（DRAFT/TESTING/PUBLISHED/DISABLED）；`RuleService.create(CreateRuleCommand): RuleDefinition`、`addEngineBinding(ruleId, AddEngineBindingCommand)`、`addComplianceMapping(ruleId, itemCode)`、`setEvaluationPolicy(ruleId, SetPolicyCommand)`、`list(): List<RuleDefinition>`、`get(ruleId): RuleDefinition`、`update(ruleId, UpdateRuleCommand)`、`publish(ruleId)`、`disable(ruleId)`；`RuleQueryService.publishedRuleByEngineRuleId(engine, engineRuleId): RuleDefinition?`、`policyByRuleId(ruleId): RuleEvaluationPolicy?`、`itemCodesByRuleId(ruleId): List<String>`、`findByRuleCode(ruleCode): RuleDefinition?`；`RuleController`（`GET/POST /api/v1/rules`、`PUT /api/v1/rules/{id}`、`GET /api/v1/rules/{id}/versions`、`POST /api/v1/rules/{id}/engine-bindings`、`POST /api/v1/rules/{id}/mappings`、`POST /api/v1/rules/{id}/policy`、`POST /api/v1/rules/{id}/publish`、`POST /api/v1/rules/{id}/disable`）。
- 消费方：M4 `module-scan` 的 `ComplianceEvaluator` 将依赖 `RuleQueryService` 完成「扫描结果 rule → 合规判定策略 → 清单条目」映射。

- [ ] **Step 1: 写失败测试（集成 + 单元）**

`RuleRepositoryIntegrationTest.kt`:
```kotlin
package com.example.compliance.rule

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.rule.domain.RuleComplianceMapping
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEngineBinding
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class RuleRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var ruleRepository: RuleDefinitionRepository
    @Autowired lateinit var bindingRepository: RuleEngineBindingRepository
    @Autowired lateinit var mappingRepository: RuleComplianceMappingRepository
    @Autowired lateinit var policyRepository: RuleEvaluationPolicyRepository

    @Test
    fun `rule with binding mapping policy persists and queries`() {
        val rule = ruleRepository.save(RuleDefinition().apply {
            ruleCode = "SEMGREP-JAVA-SQLI"; name = "SQL注入"; riskLevel = "HIGH"; status = RuleStatus.PUBLISHED
        })
        bindingRepository.save(RuleEngineBinding().apply {
            ruleId = rule.id!!; engine = "SEMGREP"; engineRuleId = "java.lang.security.audit.sql-injection"
        })
        mappingRepository.save(RuleComplianceMapping().apply {
            ruleId = rule.id!!; checklistItemCode = "SEC-001"
        })
        policyRepository.save(RuleEvaluationPolicy().apply {
            ruleId = rule.id!!; resultOnMatch = "FAIL"; spElExpression = "severity == 'ERROR'"
        })

        val byEngine = ruleRepository.findFirstByStatusAndEngineRuleId(RuleStatus.PUBLISHED, "java.lang.security.audit.sql-injection")
        assertEquals(rule.id, byEngine?.id)
        assertEquals(1, mappingRepository.findByRuleId(rule.id!!).size)
        assertEquals("FAIL", policyRepository.findByRuleId(rule.id!!)?.resultOnMatch)
    }
}
```

`RuleServiceTest.kt`:
```kotlin
package com.example.compliance.rule.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleServiceTest {
    private val ruleRepository = mockk<RuleDefinitionRepository>(relaxed = true)
    private val bindingRepository = mockk<RuleEngineBindingRepository>(relaxed = true)
    private val mappingRepository = mockk<RuleComplianceMappingRepository>(relaxed = true)
    private val policyRepository = mockk<RuleEvaluationPolicyRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = RuleService(ruleRepository, bindingRepository, mappingRepository, policyRepository, auditService)

    @Test
    fun `create rule starts as DRAFT`() {
        every { ruleRepository.existsByRuleCode("R1") } returns false
        every { ruleRepository.save(any()) } answers { firstArg<RuleDefinition>().apply { id = 1L } }
        val rule = service.create(CreateRuleCommand("R1", "规则一", "HIGH", "desc"))
        assertEquals(RuleStatus.DRAFT, rule.status)
    }

    @Test
    fun `publish only from DRAFT or TESTING`() {
        every { ruleRepository.findById(1L) } returns Optional.of(
            RuleDefinition().apply { id = 1L; ruleCode = "R1"; status = RuleStatus.PUBLISHED }
        )
        assertFailsWith<BusinessException> { service.publish(1L) }
    }

    @Test
    fun `setEvaluationPolicy requires spEl for FAIL mapping`() {
        every { ruleRepository.findById(1L) } returns Optional.of(
            RuleDefinition().apply { id = 1L; ruleCode = "R1"; status = RuleStatus.DRAFT }
        )
        assertFailsWith<BusinessException> {
            service.setEvaluationPolicy(1L, SetPolicyCommand("FAIL", null, null))
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-rule:test :app-server:test --tests "*Rule*Test*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写 Flyway V5 与实体**

`V5__init_rule.sql`:
```sql
CREATE TABLE rule_definition (
    id          BIGSERIAL PRIMARY KEY,
    rule_code   VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    risk_level  VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    description TEXT,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE rule_engine_binding (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          BIGINT       NOT NULL,
    engine           VARCHAR(32)  NOT NULL,
    engine_rule_id   VARCHAR(128) NOT NULL,
    engine_config_json JSONB,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_reb_engine ON rule_engine_binding (engine, engine_rule_id);

CREATE TABLE rule_compliance_mapping (
    id                 BIGSERIAL PRIMARY KEY,
    rule_id            BIGINT      NOT NULL,
    checklist_item_code VARCHAR(64) NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_rcm_rule ON rule_compliance_mapping (rule_id, checklist_item_code);

CREATE TABLE rule_evaluation_policy (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          BIGINT      NOT NULL UNIQUE,
    result_on_match  VARCHAR(16) NOT NULL DEFAULT 'FAIL',
    policy_json      JSONB,
    sp_el_expression TEXT,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);
```

`enums.kt`:
```kotlin
package com.example.compliance.rule.domain

enum class RuleStatus { DRAFT, TESTING, PUBLISHED, DISABLED }
```

`RuleDefinition.kt`:
```kotlin
package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "rule_definition")
class RuleDefinition : BaseEntity() {
    @Column(name = "rule_code", nullable = false, unique = true, length = 64)
    lateinit var ruleCode: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "risk_level", nullable = false, length = 16)
    var riskLevel: String = "MEDIUM"
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: RuleStatus = RuleStatus.DRAFT
    @Column(name = "description")
    var description: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`RuleEngineBinding.kt`:
```kotlin
package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "rule_engine_binding")
class RuleEngineBinding : BaseEntity() {
    @Column(name = "rule_id", nullable = false)
    var ruleId: Long = 0
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "engine_rule_id", nullable = false, length = 128)
    lateinit var engineRuleId: String
    // Ruling #33: String on a jsonb column binds as varchar without @JdbcTypeCode (Ruling #13/#25
    // pattern) — INSERT fails "column is of type jsonb but expression is of type character varying".
    // Not exercised by Task 3.3's tests (engineConfigJson/policyJson never written there), but M4's
    // rule-policy writes would hit it. Annotation required now.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "engine_config_json", columnDefinition = "jsonb")
    var engineConfigJson: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`RuleComplianceMapping.kt`:
```kotlin
package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "rule_compliance_mapping")
class RuleComplianceMapping : BaseEntity() {
    @Column(name = "rule_id", nullable = false)
    var ruleId: Long = 0
    @Column(name = "checklist_item_code", nullable = false, length = 64)
    lateinit var checklistItemCode: String
}
```

`RuleEvaluationPolicy.kt`:
```kotlin
package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "rule_evaluation_policy")
class RuleEvaluationPolicy : BaseEntity() {
    @Column(name = "rule_id", nullable = false, unique = true)
    var ruleId: Long = 0
    @Column(name = "result_on_match", nullable = false, length = 16)
    var resultOnMatch: String = "FAIL"
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_json", columnDefinition = "jsonb")
    var policyJson: String? = null
    @Column(name = "sp_el_expression")
    var spElExpression: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`repos.kt`:
```kotlin
package com.example.compliance.rule.infrastructure

import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleStatus
import org.springframework.data.jpa.repository.JpaRepository

interface RuleDefinitionRepository : JpaRepository<RuleDefinition, Long> {
    fun existsByRuleCode(ruleCode: String): Boolean
    fun findFirstByStatusAndEngineRuleId(status: RuleStatus, engineRuleId: String): RuleDefinition?
    fun findByStatus(status: RuleStatus): List<RuleDefinition>
}
```
```kotlin
package com.example.compliance.rule.infrastructure

import com.example.compliance.rule.domain.RuleEngineBinding
import org.springframework.data.jpa.repository.JpaRepository

interface RuleEngineBindingRepository : JpaRepository<RuleEngineBinding, Long> {
    fun findByRuleId(ruleId: Long): List<RuleEngineBinding>
}
```
```kotlin
package com.example.compliance.rule.infrastructure

import com.example.compliance.rule.domain.RuleComplianceMapping
import org.springframework.data.jpa.repository.JpaRepository

interface RuleComplianceMappingRepository : JpaRepository<RuleComplianceMapping, Long> {
    fun findByRuleId(ruleId: Long): List<RuleComplianceMapping>
    fun findByChecklistItemCodeIn(codes: Collection<String>): List<RuleComplianceMapping>
}
```
```kotlin
package com.example.compliance.rule.infrastructure

import com.example.compliance.rule.domain.RuleEvaluationPolicy
import org.springframework.data.jpa.repository.JpaRepository

interface RuleEvaluationPolicyRepository : JpaRepository<RuleEvaluationPolicy, Long> {
    fun findByRuleId(ruleId: Long): RuleEvaluationPolicy?
}
```

- [ ] **Step 4: 写服务层与 Controller**

`commands.kt`:
```kotlin
package com.example.compliance.rule.application

data class CreateRuleCommand(
    val ruleCode: String,
    val name: String,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
)

data class AddEngineBindingCommand(
    val engine: String,
    val engineRuleId: String,
    val engineConfigJson: String? = null,
)

data class SetPolicyCommand(
    val resultOnMatch: String = "FAIL",
    val policyJson: String? = null,
    val spElExpression: String? = null,
)

data class UpdateRuleCommand(
    val name: String? = null,
    val riskLevel: String? = null,
    val description: String? = null,
)
```

`RuleService.kt`:
```kotlin
package com.example.compliance.rule.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEngineBinding
import com.example.compliance.rule.domain.RuleComplianceMapping
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import com.example.compliance.common.audit.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RuleService(
    private val ruleRepository: RuleDefinitionRepository,
    private val bindingRepository: RuleEngineBindingRepository,
    private val mappingRepository: RuleComplianceMappingRepository,
    private val policyRepository: RuleEvaluationPolicyRepository,
    private val auditService: AuditService,
) {
    @Transactional
    fun create(command: CreateRuleCommand): RuleDefinition {
        if (ruleRepository.existsByRuleCode(command.ruleCode)) {
            throw BusinessException(400, "rule code already exists: ${command.ruleCode}")
        }
        return ruleRepository.save(RuleDefinition().apply {
            ruleCode = command.ruleCode
            name = command.name
            riskLevel = command.riskLevel
            description = command.description
            status = RuleStatus.DRAFT
        })
    }

    private fun require(ruleId: Long): RuleDefinition =
        ruleRepository.findById(ruleId).orElseThrow { BusinessException(404, "rule not found: $ruleId") }

    @Transactional
    fun addEngineBinding(ruleId: Long, command: AddEngineBindingCommand): RuleEngineBinding {
        require(ruleId)
        return bindingRepository.save(RuleEngineBinding().apply {
            this.ruleId = ruleId
            engine = command.engine
            engineRuleId = command.engineRuleId
            engineConfigJson = command.engineConfigJson
        })
    }

    @Transactional
    fun addComplianceMapping(ruleId: Long, checklistItemCode: String): RuleComplianceMapping {
        require(ruleId)
        return mappingRepository.save(RuleComplianceMapping().apply {
            this.ruleId = ruleId
            this.checklistItemCode = checklistItemCode
        })
    }

    @Transactional
    fun setEvaluationPolicy(ruleId: Long, command: SetPolicyCommand): RuleEvaluationPolicy {
        require(ruleId)
        val policy = policyRepository.findByRuleId(ruleId) ?: RuleEvaluationPolicy().apply { this.ruleId = ruleId }
        if (command.resultOnMatch == "FAIL" && command.spElExpression.isNullOrBlank()) {
            throw BusinessException(400, "FAIL policy requires spElExpression")
        }
        policy.resultOnMatch = command.resultOnMatch
        policy.policyJson = command.policyJson
        policy.spElExpression = command.spElExpression
        return policyRepository.save(policy)
    }

    fun list(): List<RuleDefinition> = ruleRepository.findAll()

    fun get(ruleId: Long): RuleDefinition = require(ruleId)

    /** 更新规则元信息（ruleCode 不可变，改 code 视为新建）。 */
    @Transactional
    fun update(ruleId: Long, command: UpdateRuleCommand): RuleDefinition {
        val rule = require(ruleId)
        command.name?.let { rule.name = it }
        command.riskLevel?.let { rule.riskLevel = it }
        command.description?.let { rule.description = it }
        return ruleRepository.save(rule)
    }

    @Transactional
    fun publish(ruleId: Long): RuleDefinition {
        val rule = require(ruleId)
        if (rule.status !in setOf(RuleStatus.DRAFT, RuleStatus.TESTING)) {
            throw BusinessException(400, "only DRAFT/TESTING rule can be published, current: ${rule.status}")
        }
        rule.status = RuleStatus.PUBLISHED
        val saved = ruleRepository.save(rule)
        auditService.record(
            "RULE_PUBLISH", "rule", null, "rule_definition",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — plain-text detail fails INSERT with
            // "invalid input syntax for type json" → 500. Same defect as Task 3.2's publish/bindProject.
            saved.id, """{"rule":"${saved.ruleCode}"}""", null,
        )
        return saved
    }

    @Transactional
    fun disable(ruleId: Long): RuleDefinition {
        val rule = require(ruleId)
        rule.status = RuleStatus.DISABLED
        val saved = ruleRepository.save(rule)
        auditService.record(
            "RULE_DISABLE", "rule", null, "rule_definition",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — plain-text detail fails INSERT with
            // "invalid input syntax for type json" → 500. Same defect as Task 3.2's publish/bindProject.
            saved.id, """{"rule":"${saved.ruleCode}"}""", null,
        )
        return saved
    }
}
```

`RuleQueryService.kt`:
```kotlin
package com.example.compliance.rule.application

import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import org.springframework.stereotype.Service

/** 供 module-scan 的 ComplianceEvaluator 使用的只读查询。 */
@Service
class RuleQueryService(
    private val ruleRepository: RuleDefinitionRepository,
    private val bindingRepository: RuleEngineBindingRepository,
    private val mappingRepository: RuleComplianceMappingRepository,
    private val policyRepository: RuleEvaluationPolicyRepository,
) {
    /** 按引擎 + 引擎规则号找到已发布规则；同时校验其 engine binding 匹配。 */
    fun publishedRuleByEngineRuleId(engine: String, engineRuleId: String): RuleDefinition? {
        val ruleIds = bindingRepository.findAll()
            .filter { it.engine == engine && it.engineRuleId == engineRuleId }
            .map { it.ruleId }
            .toSet()
        if (ruleIds.isEmpty()) return null
        return ruleRepository.findAllById(ruleIds).firstOrNull { it.status == com.example.compliance.rule.domain.RuleStatus.PUBLISHED }
    }

    fun policyByRuleId(ruleId: Long): RuleEvaluationPolicy? = policyRepository.findByRuleId(ruleId)

    fun itemCodesByRuleId(ruleId: Long): List<String> =
        mappingRepository.findByRuleId(ruleId).map { it.checklistItemCode }

    /** 按平台规则号查已发布规则（module-scan 合规判定使用）。 */
    fun findByRuleCode(ruleCode: String): RuleDefinition? =
        ruleRepository.findAll().firstOrNull {
            it.ruleCode == ruleCode && it.status == com.example.compliance.rule.domain.RuleStatus.PUBLISHED
        }
}
```

`RuleRequest.kt`:
```kotlin
package com.example.compliance.rule.api.dto

import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.SetPolicyCommand
import jakarta.validation.constraints.NotBlank

data class RuleRequest(
    @field:NotBlank val ruleCode: String,
    @field:NotBlank val name: String,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
) { fun toCommand() = CreateRuleCommand(ruleCode, name, riskLevel, description) }

data class EngineBindingRequest(
    @field:NotBlank val engine: String,
    @field:NotBlank val engineRuleId: String,
    val engineConfigJson: String? = null,
) { fun toCommand() = AddEngineBindingCommand(engine, engineRuleId, engineConfigJson) }

data class PolicyRequest(
    val resultOnMatch: String = "FAIL",
    val policyJson: String? = null,
    val spElExpression: String? = null,
) { fun toCommand() = SetPolicyCommand(resultOnMatch, policyJson, spElExpression) }

data class MappingRequest(@field:NotBlank val checklistItemCode: String)

data class UpdateRuleRequest(
    val name: String? = null,
    val riskLevel: String? = null,
    val description: String? = null,
) { fun toCommand() = com.example.compliance.rule.application.UpdateRuleCommand(name, riskLevel, description) }
```

`RuleResponse.kt`:
```kotlin
package com.example.compliance.rule.api.dto

import com.example.compliance.rule.domain.RuleDefinition
import java.time.Instant

data class RuleResponse(
    val id: Long, val ruleCode: String, val name: String,
    val riskLevel: String, val status: String, val description: String?,
) {
    companion object { fun from(r: RuleDefinition) = RuleResponse(r.id!!, r.ruleCode, r.name, r.riskLevel, r.status.name, r.description) }
}

/** P0：规则版本号取自 @Version 乐观锁字段（当前行即最新版本），完整版本历史留 P1。 */
data class RuleVersionResponse(
    val ruleId: Long, val version: Long, val status: String, val updatedAt: Instant,
) {
    companion object { fun from(r: RuleDefinition) = RuleVersionResponse(r.id!!, r.version, r.status.name, r.updatedAt) }
}
```

`RuleController.kt`:
```kotlin
package com.example.compliance.rule.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.rule.api.dto.EngineBindingRequest
import com.example.compliance.rule.api.dto.MappingRequest
import com.example.compliance.rule.api.dto.PolicyRequest
import com.example.compliance.rule.api.dto.RuleRequest
import com.example.compliance.rule.api.dto.RuleResponse
import com.example.compliance.rule.api.dto.RuleVersionResponse
import com.example.compliance.rule.api.dto.UpdateRuleRequest
import com.example.compliance.rule.application.RuleService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/rules")
class RuleController(private val ruleService: RuleService) {

    @GetMapping
    fun list(): ApiResponse<List<RuleResponse>> =
        ApiResponse.ok(ruleService.list().map { RuleResponse.from(it) })

    @PostMapping
    fun create(@Valid @RequestBody req: RuleRequest): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.create(req.toCommand())))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody req: UpdateRuleRequest): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.update(id, req.toCommand())))

    @GetMapping("/{id}/versions")
    fun versions(@PathVariable id: Long): ApiResponse<List<RuleVersionResponse>> {
        val rule = ruleService.get(id)
        return ApiResponse.ok(listOf(RuleVersionResponse.from(rule)))
    }

    @PostMapping("/{id}/engine-bindings")
    fun bindEngine(@PathVariable id: Long, @Valid @RequestBody req: EngineBindingRequest): ApiResponse<Unit> {
        ruleService.addEngineBinding(id, req.toCommand())
        return ApiResponse.ok()
    }

    @PostMapping("/{id}/mappings")
    fun mapItem(@PathVariable id: Long, @Valid @RequestBody req: MappingRequest): ApiResponse<Unit> {
        ruleService.addComplianceMapping(id, req.checklistItemCode)
        return ApiResponse.ok()
    }

    @PostMapping("/{id}/policy")
    fun setPolicy(@PathVariable id: Long, @Valid @RequestBody req: PolicyRequest): ApiResponse<Unit> {
        ruleService.setEvaluationPolicy(id, req.toCommand())
        return ApiResponse.ok()
    }

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: Long): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.publish(id)))

    @PostMapping("/{id}/disable")
    fun disable(@PathVariable id: Long): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.disable(id)))
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-rule:test :app-server:test --tests "*Rule*Test*"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add module-rule/src app-server/src/main/resources/db/migration app-server/src/test/kotlin/com/example/compliance/rule
git commit -m "feat(rule): rule registry with engine bindings and evaluation policy"
```

**M3 完成标准**：清单版本化发布/绑定 + 规则中心（规则→引擎绑定→清单映射→判定策略→发布/停用）均可用，测试全绿。`RuleQueryService` 为 M4 提供 `publishedRuleByEngineRuleId` / `policyByRuleId` / `itemCodesByRuleId`。
---

## 里程碑 M4：扫描结果归一化、Semgrep 适配与扫描流水线

### Task 4.1: module-result Finding 模型、指纹去重与引擎端口

**Files:**
- Create: `module-result/src/main/kotlin/com/example/compliance/result/domain/Finding.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/domain/FindingTrace.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/domain/enums.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FingerprintGenerator.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FindingRepository.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FindingTraceRepository.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/engine/EngineAdapterRegistry.kt`
- Create: `app-server/src/main/resources/db/migration/V6__init_finding.sql`
- Test: `module-result/src/test/kotlin/com/example/compliance/result/infrastructure/FingerprintGeneratorTest.kt`
- Test: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingServiceTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/result/FindingRepositoryIntegrationTest.kt`

**Interfaces:**
- Consumes: `module-common`（`BaseEntity`、`BusinessException`）。
- Produces: `Finding`（统一 finding 模型，`fingerprint` 唯一）；`FindingTrace`；`FindingStatus`（OPEN/FIXED/WAIVED/SUPPRESSED/REOPENED）；`FingerprintGenerator.generate(projectId, ruleCode, filePath, lineNumber, codeSnippet): String`；`FindingRepository.findByFingerprint`；`FindingService.upsertByFingerprint(projectId, scanTaskId, engine, List<NewFinding>): UpsertResult`；端口 `ScanEngineAdapter`（`engine: String` + `scan(ScanContext): ScanResult`）、`ScanContext`、`RawFinding`、`ScanResult`；`EngineAdapterRegistry.get(engine)` / `register` / `engines()`。
- 消费方：Task 4.2（`SemgrepAdapter` 实现端口）、Task 4.3（`ScanOrchestrator` 调用 registry + FindingService）。

- [ ] **Step 1: 写失败测试（单元 + 集成）**

`FingerprintGeneratorTest.kt`:
```kotlin
package com.example.compliance.result.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FingerprintGeneratorTest {
    private val generator = FingerprintGenerator()

    @Test
    fun `same inputs produce same fingerprint`() {
        val a = generator.generate(1L, "SEC-001", "src/A.java", 42, "String s = a;")
        val b = generator.generate(1L, "SEC-001", "src/A.java", 42, "String s = a;")
        assertEquals(a, b)
    }

    @Test
    fun `different line or snippet changes fingerprint`() {
        val base = generator.generate(1L, "SEC-001", "src/A.java", 42, "s")
        assertNotEquals(base, generator.generate(1L, "SEC-001", "src/A.java", 43, "s"))
        assertNotEquals(base, generator.generate(2L, "SEC-001", "src/A.java", 42, "s"))
    }
}
```

`FindingServiceTest.kt`:
```kotlin
package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingTrace
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingTraceRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals

class FindingServiceTest {
    private val findingRepository = mockk<FindingRepository>(relaxed = true)
    private val traceRepository = mockk<FindingTraceRepository>(relaxed = true)
    private val service = FindingService(findingRepository, traceRepository, FingerprintGenerator())

    private fun newFinding(code: String, file: String, line: Int, severity: String = "HIGH") =
        NewFinding(code, "rule", file, line, severity, "SEC", "msg", "snippet")

    @Test
    fun `new fingerprint inserts finding and records CREATED trace`() {
        every { findingRepository.findByFingerprint(any()) } returns null
        every { findingRepository.save(any()) } answers { firstArg<Finding>().apply { id = 1L } }
        every { traceRepository.save(any()) } answers { firstArg<FindingTrace>() }
        val result = service.upsertByFingerprint(1L, 100L, "SEMGREP", listOf(newFinding("SEC-001", "A.java", 1)))
        assertEquals(1, result.created)
        assertEquals(0, result.updated)
        verify { traceRepository.save(match { it.action == "CREATED" }) }
    }

    @Test
    fun `existing fingerprint increments occurrence and records UPDATED trace`() {
        every { findingRepository.findByFingerprint(any()) } returns
            Finding().apply { id = 9L; occurrenceCount = 1; status = FindingStatus.FIXED }
        every { findingRepository.save(any()) } answers { firstArg<Finding>() }
        every { traceRepository.save(any()) } answers { firstArg<FindingTrace>() }
        val result = service.upsertByFingerprint(1L, 100L, "SEMGREP", listOf(newFinding("SEC-001", "A.java", 1)))
        assertEquals(1, result.updated)
        verify { traceRepository.save(match { it.action == "UPDATED" }) }
    }
}
```

`FindingRepositoryIntegrationTest.kt`:
```kotlin
package com.example.compliance.result

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.infrastructure.FindingRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class FindingRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var findingRepository: FindingRepository

    @Test
    fun `save finding and look up by fingerprint`() {
        val fp = "f" + "a".repeat(63)
        findingRepository.save(Finding().apply {
            scanTaskId = 1L; engine = "SEMGREP"; ruleCode = "SEC-001"; filePath = "A.java"
            lineNumber = 1; severity = "HIGH"; fingerprint = fp; rawJson = """{"a":1}"""
        })
        assertEquals("SEC-001", findingRepository.findByFingerprint(fp)?.ruleCode)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-result:test :app-server:test --tests "*Fingerprint*" --tests "*Finding*Test*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写 Flyway V6 与实现**

`V6__init_finding.sql`:
```sql
CREATE TABLE finding (
    id              BIGSERIAL PRIMARY KEY,
    scan_task_id    BIGINT       NOT NULL,
    engine          VARCHAR(32)  NOT NULL,
    rule_code       VARCHAR(128) NOT NULL,
    rule_name       VARCHAR(256),
    file_path       TEXT         NOT NULL,
    line_number     INT,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'LOW',
    category        VARCHAR(64),
    message         TEXT,
    code_snippet    TEXT,
    fingerprint     VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    first_seen_at   TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMP    NOT NULL DEFAULT now(),
    occurrence_count INT         NOT NULL DEFAULT 1,
    raw_json        JSONB,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_finding_fp ON finding (fingerprint);
CREATE INDEX idx_finding_scan ON finding (scan_task_id);

CREATE TABLE finding_trace (
    id          BIGSERIAL PRIMARY KEY,
    finding_id  BIGINT       NOT NULL,
    scan_task_id BIGINT      NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_trace_finding ON finding_trace (finding_id);
```

`enums.kt`:
```kotlin
package com.example.compliance.result.domain

enum class FindingStatus { OPEN, FIXED, WAIVED, SUPPRESSED, REOPENED }
```

`Finding.kt`:
```kotlin
package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "finding")
class Finding : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "rule_code", nullable = false, length = 128)
    lateinit var ruleCode: String
    @Column(name = "rule_name", length = 256)
    var ruleName: String? = null
    @Column(name = "file_path", nullable = false)
    lateinit var filePath: String
    @Column(name = "line_number")
    var lineNumber: Int? = null
    @Column(name = "severity", nullable = false, length = 16)
    var severity: String = "LOW"
    @Column(name = "category", length = 64)
    var category: String? = null
    @Column(name = "message")
    var message: String? = null
    @Column(name = "code_snippet")
    var codeSnippet: String? = null
    @Column(name = "fingerprint", nullable = false, length = 64)
    lateinit var fingerprint: String
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FindingStatus = FindingStatus.OPEN
    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.now()
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now()
    @Column(name = "occurrence_count", nullable = false)
    var occurrenceCount: Int = 1
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb")
    var rawJson: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`FindingTrace.kt`:
```kotlin
package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "finding_trace")
class FindingTrace : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "action", nullable = false, length = 16)
    lateinit var action: String
}
```

`FingerprintGenerator.kt`:
```kotlin
package com.example.compliance.result.infrastructure

import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class FingerprintGenerator {
    fun generate(projectId: Long, ruleCode: String, filePath: String, lineNumber: Int?, codeSnippet: String?): String {
        val normalized = listOf(
            projectId.toString(),
            ruleCode,
            filePath,
            (lineNumber ?: -1).toString(),
            (codeSnippet ?: "").trim(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

`FindingRepository.kt`:
```kotlin
package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.Finding
import org.springframework.data.jpa.repository.JpaRepository

interface FindingRepository : JpaRepository<Finding, Long> {
    fun findByFingerprint(fingerprint: String): Finding?
    fun findByScanTaskId(scanTaskId: Long): List<Finding>
}
```

`FindingTraceRepository.kt`:
```kotlin
package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingTrace
import org.springframework.data.jpa.repository.JpaRepository

interface FindingTraceRepository : JpaRepository<FindingTrace, Long>
```

`FindingService.kt`:
```kotlin
package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingTrace
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingTraceRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class NewFinding(
    val ruleCode: String,
    val ruleName: String?,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val category: String?,
    val message: String?,
    val codeSnippet: String?,
)

data class UpsertResult(val created: Int, val updated: Int)

@Service
class FindingService(
    private val findingRepository: FindingRepository,
    private val traceRepository: FindingTraceRepository,
    private val fingerprintGenerator: FingerprintGenerator,
) {
    /** 按指纹去重写入：新指纹插入（CREATED），已有指纹累加出现次数并回到 OPEN（UPDATED）。 */
    @Transactional
    fun upsertByFingerprint(projectId: Long, scanTaskId: Long, engine: String, findings: List<NewFinding>): UpsertResult {
        var created = 0
        var updated = 0
        for (f in findings) {
            val fingerprint = fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
            val existing = findingRepository.findByFingerprint(fingerprint)
            if (existing == null) {
                val saved = findingRepository.save(
                    Finding().apply {
                        this.scanTaskId = scanTaskId
                        this.engine = engine
                        ruleCode = f.ruleCode
                        ruleName = f.ruleName
                        filePath = f.filePath
                        lineNumber = f.lineNumber
                        severity = f.severity
                        category = f.category
                        message = f.message
                        codeSnippet = f.codeSnippet
                        this.fingerprint = fingerprint
                    }
                )
                traceRepository.save(FindingTrace().apply {
                    findingId = saved.id!!; this.scanTaskId = scanTaskId; action = "CREATED"
                })
                created++
            } else {
                existing.occurrenceCount += 1
                existing.lastSeenAt = Instant.now()
                existing.status = FindingStatus.OPEN
                findingRepository.save(existing)
                traceRepository.save(FindingTrace().apply {
                    findingId = existing.id!!; this.scanTaskId = scanTaskId; action = "UPDATED"
                })
                updated++
            }
        }
        return UpsertResult(created, updated)
    }
}
```

`engine/ScanEngineAdapter.kt`:
```kotlin
package com.example.compliance.result.engine

/** 扫描引擎统一端口：每个引擎一个实现，不得绕过 adapter 直接调用引擎。 */
interface ScanEngineAdapter {
    val engine: String
    fun scan(context: ScanContext): ScanResult
}

data class ScanContext(
    val scanTaskId: Long,
    val projectId: Long,
    val repoUrl: String,
    val ref: String? = null,
    val configJson: String? = null,
)

/** 引擎原生结果，severity 已归一化为 LOW/MEDIUM/HIGH/CRITICAL。 */
data class RawFinding(
    val engineRuleId: String,
    val ruleName: String? = null,
    val filePath: String,
    val line: Int? = null,
    val severity: String,
    val message: String? = null,
    val codeSnippet: String? = null,
    val category: String? = null,
)

data class ScanResult(
    val findings: List<RawFinding>,
    val durationMs: Long = 0,
    val success: Boolean = true,
    val errorMessage: String? = null,
)
```

`engine/EngineAdapterRegistry.kt`:
```kotlin
package com.example.compliance.result.engine

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

interface EngineAdapterRegistry {
    fun register(adapter: ScanEngineAdapter)
    fun get(engine: String): ScanEngineAdapter?
    fun engines(): Set<String>
}

@Component
class DefaultEngineAdapterRegistry(
    adapters: List<ScanEngineAdapter>,
) : EngineAdapterRegistry {
    private val registry = ConcurrentHashMap<String, ScanEngineAdapter>()

    init {
        adapters.forEach { registry[it.engine.uppercase()] = it }
    }

    override fun register(adapter: ScanEngineAdapter) {
        registry[adapter.engine.uppercase()] = adapter
    }

    override fun get(engine: String): ScanEngineAdapter? = registry[engine.uppercase()]

    override fun engines(): Set<String> = registry.keys
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-result:test :app-server:test --tests "*Fingerprint*" --tests "*Finding*Test*"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add module-result/src app-server/src/main/resources/db/migration
git commit -m "feat(result): unified finding model, fingerprint dedup, engine port"
```
### Task 4.2: module-engine-adapter SemgrepAdapter

**Files:**
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepSeverityMapper.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepResultParser.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepCli.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapter.kt`
- Create: `module-engine-adapter/src/test/resources/semgrep/basic.json`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepSeverityMapperTest.kt`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepResultParserTest.kt`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapterTest.kt`

**Interfaces:**
- Consumes: `module-result` 的 `ScanEngineAdapter`/`ScanContext`/`ScanResult`/`RawFinding`。
- Produces: `SemgrepSeverityMapper.map(engineSeverity): String`（ERROR→HIGH、WARNING→MEDIUM、INFO→LOW，其余→LOW）；`SemgrepResultParser.parse(stdout): List<RawFinding>`（解析 `semgrep --json` 输出，`results[].check_id/path/start.line/extra.severity/extra.message/extra.lines`）；`SemgrepCli.run(targetPath, ref?): String`；`SemgrepAdapter(engine="SEMGREP")`，在 `scan` 中执行 `semgrep --json` 并归一化 severity。

- [ ] **Step 1: 写 fixture 与失败测试**

`basic.json`（与真实 `semgrep --json` 输出一致的样例，两条结果）:
```json
{
  "errors": [],
  "results": [
    {
      "check_id": "java.lang.security.audit.sql-injection",
      "path": "src/main/java/com/demo/OrderDao.java",
      "start": { "line": 42, "col": 9 },
      "end": { "line": 42, "col": 80 },
      "extra": {
        "message": "Detected potential SQL injection.",
        "severity": "ERROR",
        "lines": "String sql = \"select * from t where id=\" + id;"
      }
    },
    {
      "check_id": "java.lang.security.audit.cookie-http-only",
      "path": "src/main/java/com/demo/CookieUtil.java",
      "start": { "line": 17, "col": 5 },
      "end": { "line": 17, "col": 40 },
      "extra": {
        "message": "Cookie not marked HttpOnly.",
        "severity": "WARNING",
        "lines": "response.addCookie(cookie);"
      }
    }
  ]
}
```

`SemgrepSeverityMapperTest.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SemgrepSeverityMapperTest {
    private val mapper = SemgrepSeverityMapper()

    @Test
    fun `maps semgrep severities to unified levels`() {
        assertEquals("HIGH", mapper.map("ERROR"))
        assertEquals("MEDIUM", mapper.map("WARNING"))
        assertEquals("LOW", mapper.map("INFO"))
        assertEquals("LOW", mapper.map("UNKNOWN"))
    }
}
```

`SemgrepResultParserTest.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class SemgrepResultParserTest {
    private val parser = SemgrepResultParser()

    @Test
    fun `parses two findings from fixture json`() {
        val json = javaClass.getResource("/semgrep/basic.json")
            .readText(StandardCharsets.UTF_8)
        val findings = parser.parse(json)
        assertEquals(2, findings.size)
        assertEquals("java.lang.security.audit.sql-injection", findings[0].engineRuleId)
        assertEquals("src/main/java/com/demo/OrderDao.java", findings[0].filePath)
        assertEquals(42, findings[0].line)
        assertEquals("ERROR", findings[0].severity)
        assertEquals("Detected potential SQL injection.", findings[0].message)
    }
}
```

`SemgrepAdapterTest.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemgrepAdapterTest {
    private val cli = mockk<SemgrepCli>()
    private val adapter = SemgrepAdapter(cli, SemgrepResultParser(), SemgrepSeverityMapper())

    @Test
    fun `scan returns normalized severities`() {
        val json = javaClass.getResource("/semgrep/basic.json").readText(StandardCharsets.UTF_8)
        every { cli.run(any(), any()) } returns json
        val result = adapter.scan(
            ScanContext(1L, 1L, "https://git.example.com/repo.git", "main", """{"localPath":"/tmp/repo"}""")
        )
        assertTrue(result.success)
        assertEquals(2, result.findings.size)
        assertEquals("HIGH", result.findings[0].severity)
        assertEquals("MEDIUM", result.findings[1].severity)
    }

    @Test
    fun `engine name is SEMGREP`() {
        assertEquals("SEMGREP", adapter.engine)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-engine-adapter:test`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写实现**

`SemgrepSeverityMapper.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import org.springframework.stereotype.Component

/** Semgrep 原生 severity（ERROR/WARNING/INFO）→ 统一 LOW/MEDIUM/HIGH/CRITICAL。 */
@Component
class SemgrepSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "ERROR" -> "HIGH"
        "WARNING" -> "MEDIUM"
        "INFO" -> "LOW"
        else -> "LOW"
    }
}
```

`SemgrepResultParser.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SemgrepResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(stdout: String): List<RawFinding> {
        val root = objectMapper.readTree(stdout)
        val results = root.path("results")
        return results.map { node ->
            RawFinding(
                engineRuleId = node.path("check_id").asText(""),
                ruleName = null,
                filePath = node.path("path").asText(""),
                line = node.path("start").path("line").takeIf { !it.isMissingNode }?.asInt(),
                severity = node.path("extra").path("severity").asText("INFO"),
                message = node.path("extra").path("message").takeIf { !it.isMissingNode }?.asText(),
                codeSnippet = node.path("extra").path("lines").takeIf { !it.isMissingNode }?.asText(),
                category = null,
            )
        }
    }
}
```

`SemgrepCli.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import org.springframework.stereotype.Component

interface SemgrepCli {
    fun run(targetPath: String, ref: String?): String
}

@Component
class ProcessSemgrepCli : SemgrepCli {
    override fun run(targetPath: String, ref: String?): String {
        val cmd = mutableListOf("semgrep", "--json", "--no-rewrite-rule-ids")
        ref?.let { cmd += listOf("--baseline-commit", it) }
        cmd += targetPath
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        // P0 说明：semgrep 在发现违规时返回非 0 退出码，这里不抛错，交由 parser 解析 stdout；
        // 进程本身启动失败（引擎未安装）时 readBytes 会抛 IOException，由 Orchestrator 捕获记为 FAILED。
        return output
    }
}
```

`SemgrepAdapter.kt`:
```kotlin
package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SemgrepAdapter(
    private val cli: SemgrepCli,
    private val parser: SemgrepResultParser,
    private val severityMapper: SemgrepSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "SEMGREP"

    override fun scan(context: ScanContext): ScanResult {
        val target = localPathOf(context) ?: context.repoUrl
        val stdout = cli.run(target, context.ref)
        val raw = parser.parse(stdout)
        val normalized: List<RawFinding> = raw.map { it.copy(severity = severityMapper.map(it.severity)) }
        return ScanResult(findings = normalized)
    }

    /** P0：允许通过 configJson 提供本地检出目录 localPath，便于本地与测试运行。 */
    private fun localPathOf(context: ScanContext): String? =
        context.configJson?.let { json ->
            runCatching { ObjectMapper().readTree(json).path("localPath").takeIf { !it.isMissingNode }?.asText() }
                .getOrNull()
        }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-engine-adapter:test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add module-engine-adapter/src
git commit -m "feat(engine-adapter): semgrep adapter with json parser and severity mapping"
```
### Task 4.3: module-scan 扫描流水线（编排 + 合规判定）

**Files:**
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/enums.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/ScanTask.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/ScanJob.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/ScanExecutionLog.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/ComplianceEvaluation.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/ChecklistItemResult.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/infrastructure/repos.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskService.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ComplianceEvaluator.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/api/dto/ScanRequest.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/api/dto/ScanResponse.kt`
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/api/ScanController.kt`
- Modify: `app-server/src/main/kotlin/com/example/compliance/config/AsyncConfig.kt`（`@EnableAsync` + `scanExecutor` 线程池）
- Create: `app-server/src/main/resources/db/migration/V7__init_scan.sql`
- Test: `module-scan/src/test/kotlin/com/example/compliance/scan/application/ComplianceEvaluatorTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/scan/ScanPipelineIntegrationTest.kt`

**Interfaces:**
- Consumes: `module-project`（`ProjectService.get`、`RepoRepository.findByProjectId`）；`module-rule`（`RuleQueryService.publishedRuleByEngineRuleId` / `policyByRuleId` / `itemCodesByRuleId`、`findByRuleCode`）；`module-checklist`（`ChecklistQueryService.publishedItemsForProject`、`ItemResult`）；`module-result`（`ScanEngineAdapter`/`ScanContext`/`ScanResult`、`EngineAdapterRegistry`、`FindingService`/`NewFinding`/`UpsertResult`、`FindingRepository.findByScanTaskId`）。
- Produces: `ScanTask`（PENDING/PREPARING/RUNNING/SUCCESS/FAILED/CANCELLED/PARTIAL_SUCCESS，Ruling #46 —— 对齐 spec §TaskStatus 枚举值集）；`ScanTaskService.startScan(projectId, engine, ref): ScanTask`、`cancel(id): ScanTask`（仅 PENDING 可取消）、`complianceResults(scanTaskId): ComplianceResultView`；`ScanOrchestrator.executeAsync(scanTaskId)`（`@Async("scanExecutor")` 全流水线）；`ComplianceEvaluator.evaluate(projectId, List<Finding>): List<ItemEvaluation>`（SpEL 判定）；`ScanController`（`POST /api/v1/projects/{projectId}/scan-tasks`、`GET /api/v1/scan-tasks/{id}`、`POST /api/v1/scan-tasks/{id}/cancel`、`GET /api/v1/scan-tasks/{id}/findings`、`GET /api/v1/scan-tasks/{id}/compliance-results`）。

- [ ] **Step 1: 写失败测试（单元）**

`ComplianceEvaluatorTest.kt`:
```kotlin
package com.example.compliance.scan.application

import com.example.compliance.checklist.application.ChecklistQueryService
import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ItemResult
import com.example.compliance.result.domain.Finding
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ComplianceEvaluatorTest {
    private val ruleQuery = mockk<RuleQueryService>(relaxed = true)
    private val checklistQuery = mockk<ChecklistQueryService>(relaxed = true)
    private val evaluator = ComplianceEvaluator(ruleQuery, checklistQuery)

    private fun finding(code: String, severity: String) = Finding().apply {
        id = 1L; ruleCode = code; filePath = "A.java"; lineNumber = 1; this.severity = severity
    }

    @Test
    fun `HIGH finding mapped to FAIL policy produces FAIL item result`() {
        val rule = RuleDefinition().apply { id = 1L; ruleCode = "SEMGREP-SQLI"; status = RuleStatus.PUBLISHED }
        every { checklistQuery.publishedItemsForProject(1L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001" })
        every { ruleQuery.findByRuleCode("SEMGREP-SQLI") } returns rule
        every { ruleQuery.policyByRuleId(1L) } returns
            RuleEvaluationPolicy().apply { resultOnMatch = "FAIL"; spElExpression = "severity == 'HIGH'" }
        every { ruleQuery.itemCodesByRuleId(1L) } returns listOf("SEC-001")

        val result = evaluator.evaluate(1L, listOf(finding("SEMGREP-SQLI", "HIGH")))
        assertEquals(1, result.size)
        assertEquals("SEC-001", result[0].itemCode)
        assertEquals(ItemResult.FAIL, result[0].result)
        assertEquals(1, result[0].findingCount)
    }

    @Test
    fun `MEDIUM finding does not match HIGH-only policy and passes`() {
        val rule = RuleDefinition().apply { id = 1L; ruleCode = "SEMGREP-SQLI"; status = RuleStatus.PUBLISHED }
        every { checklistQuery.publishedItemsForProject(1L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001" })
        every { ruleQuery.findByRuleCode("SEMGREP-SQLI") } returns rule
        every { ruleQuery.policyByRuleId(1L) } returns
            RuleEvaluationPolicy().apply { resultOnMatch = "FAIL"; spElExpression = "severity == 'HIGH'" }
        every { ruleQuery.itemCodesByRuleId(1L) } returns listOf("SEC-001")

        val result = evaluator.evaluate(1L, listOf(finding("SEMGREP-SQLI", "MEDIUM")))
        assertEquals(ItemResult.PASS, result[0].result)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :module-scan:test --tests "*ComplianceEvaluatorTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 写 Flyway V7 与实现（实体、repos、判定、编排、API）**

`V7__init_scan.sql`:
```sql
CREATE TABLE scan_task (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT      NOT NULL,
    repo_id       BIGINT,
    engine        VARCHAR(32) NOT NULL,
    ref           VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    trigger_type  VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_by    BIGINT,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    error_message VARCHAR(512),
    finding_count INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_scan_project ON scan_task (project_id, created_at);

CREATE TABLE scan_job (
    id            BIGSERIAL PRIMARY KEY,
    scan_task_id  BIGINT      NOT NULL,
    engine        VARCHAR(32) NOT NULL,
    job_status    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    finding_count INT         NOT NULL DEFAULT 0,
    error_message VARCHAR(512),
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_task ON scan_job (scan_task_id);

CREATE TABLE scan_execution_log (
    id           BIGSERIAL PRIMARY KEY,
    scan_task_id BIGINT      NOT NULL,
    stage        VARCHAR(32) NOT NULL,
    level        VARCHAR(8)  NOT NULL DEFAULT 'INFO',
    message      TEXT,
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_task ON scan_execution_log (scan_task_id);

CREATE TABLE compliance_evaluation (
    id           BIGSERIAL PRIMARY KEY,
    scan_task_id BIGINT      NOT NULL,
    project_id   BIGINT      NOT NULL,
    total_items  INT         NOT NULL DEFAULT 0,
    passed       INT         NOT NULL DEFAULT 0,
    failed       INT         NOT NULL DEFAULT 0,
    warning      INT         NOT NULL DEFAULT 0,
    manual       INT         NOT NULL DEFAULT 0,
    skipped      INT         NOT NULL DEFAULT 0,
    score        NUMERIC(5,2),
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_eval_task ON compliance_evaluation (scan_task_id);

CREATE TABLE checklist_item_result (
    id                  BIGSERIAL PRIMARY KEY,
    evaluation_id       BIGINT      NOT NULL,
    item_code           VARCHAR(64) NOT NULL,
    result              VARCHAR(16) NOT NULL,
    finding_count       INT         NOT NULL DEFAULT 0,
    matched_finding_ids JSONB,
    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_cir_eval ON checklist_item_result (evaluation_id);
```

`enums.kt`:
```kotlin
package com.example.compliance.scan.domain

enum class ScanTaskStatus { PENDING, PREPARING, RUNNING, SUCCESS, FAILED, CANCELLED, PARTIAL_SUCCESS }
```

`ScanTask.kt`:
```kotlin
package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "scan_task")
class ScanTask : BaseEntity() {
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "repo_id")
    var repoId: Long? = null
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "ref", length = 128)
    var ref: String? = null
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ScanTaskStatus = ScanTaskStatus.PENDING
    @Column(name = "trigger_type", nullable = false, length = 16)
    var triggerType: String = "MANUAL"
    @Column(name = "created_by")
    var createdBy: Long? = null
    @Column(name = "started_at")
    var startedAt: Instant? = null
    @Column(name = "finished_at")
    var finishedAt: Instant? = null
    @Column(name = "error_message", length = 512)
    var errorMessage: String? = null
    @Column(name = "finding_count", nullable = false)
    var findingCount: Int = 0
}
```

`ScanJob.kt`:
```kotlin
package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "scan_job")
class ScanJob : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "job_status", nullable = false, length = 16)
    var jobStatus: String = "PENDING"
    @Column(name = "started_at")
    var startedAt: Instant? = null
    @Column(name = "finished_at")
    var finishedAt: Instant? = null
    @Column(name = "duration_ms", nullable = false)
    var durationMs: Long = 0
    @Column(name = "finding_count", nullable = false)
    var findingCount: Int = 0
    @Column(name = "error_message", length = 512)
    var errorMessage: String? = null
}
```

`ScanExecutionLog.kt`:
```kotlin
package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "scan_execution_log")
class ScanExecutionLog : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "stage", nullable = false, length = 32)
    lateinit var stage: String
    @Column(name = "level", nullable = false, length = 8)
    var level: String = "INFO"
    @Column(name = "message")
    var message: String? = null
}
```

`ComplianceEvaluation.kt`:
```kotlin
package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "compliance_evaluation")
class ComplianceEvaluation : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "total_items", nullable = false)
    var totalItems: Int = 0
    @Column(name = "passed", nullable = false)
    var passed: Int = 0
    @Column(name = "failed", nullable = false)
    var failed: Int = 0
    @Column(name = "warning", nullable = false)
    var warning: Int = 0
    @Column(name = "manual", nullable = false)
    var manual: Int = 0
    @Column(name = "skipped", nullable = false)
    var skipped: Int = 0
    @Column(name = "score", precision = 5, scale = 2)
    var score: BigDecimal? = null
}
```

`ChecklistItemResult.kt`:
```kotlin
package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "checklist_item_result")
class ChecklistItemResult : BaseEntity() {
    @Column(name = "evaluation_id", nullable = false)
    var evaluationId: Long = 0
    @Column(name = "item_code", nullable = false, length = 64)
    lateinit var itemCode: String
    @Column(name = "result", nullable = false, length = 16)
    lateinit var result: String
    @Column(name = "finding_count", nullable = false)
    var findingCount: Int = 0
    // Ruling #44: String 存 jsonb 列必须 @JdbcTypeCode(SqlTypes.JSON)（orchestrator 会写入 matchedFindingIds）
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_finding_ids", columnDefinition = "jsonb")
    var matchedFindingIds: String? = null
}
```

`repos.kt`:
```kotlin
package com.example.compliance.scan.infrastructure

import com.example.compliance.scan.domain.ScanTask
import org.springframework.data.jpa.repository.JpaRepository

interface ScanTaskRepository : JpaRepository<ScanTask, Long> {
    fun findByProjectIdOrderByIdDesc(projectId: Long): List<ScanTask>
}
```
```kotlin
package com.example.compliance.scan.infrastructure

import com.example.compliance.scan.domain.ScanJob
import org.springframework.data.jpa.repository.JpaRepository

interface ScanJobRepository : JpaRepository<ScanJob, Long>
```
```kotlin
package com.example.compliance.scan.infrastructure

import com.example.compliance.scan.domain.ScanExecutionLog
import org.springframework.data.jpa.repository.JpaRepository

interface ScanExecutionLogRepository : JpaRepository<ScanExecutionLog, Long> {
    fun findByScanTaskId(scanTaskId: Long): List<ScanExecutionLog>
}
```
```kotlin
package com.example.compliance.scan.infrastructure

import com.example.compliance.scan.domain.ComplianceEvaluation
import org.springframework.data.jpa.repository.JpaRepository

interface ComplianceEvaluationRepository : JpaRepository<ComplianceEvaluation, Long> {
    fun findByScanTaskId(scanTaskId: Long): ComplianceEvaluation?
    fun findFirstByProjectIdOrderByIdDesc(projectId: Long): ComplianceEvaluation?
}
```
```kotlin
package com.example.compliance.scan.infrastructure

import com.example.compliance.scan.domain.ChecklistItemResult
import org.springframework.data.jpa.repository.JpaRepository

interface ChecklistItemResultRepository : JpaRepository<ChecklistItemResult, Long> {
    fun findByEvaluationId(evaluationId: Long): List<ChecklistItemResult>
}
```

`ComplianceEvaluator.kt`:
```kotlin
package com.example.compliance.scan.application

import com.example.compliance.checklist.application.ChecklistQueryService
import com.example.compliance.checklist.domain.ItemResult
import com.example.compliance.result.domain.Finding
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.stereotype.Component

@Component
class ComplianceEvaluator(
    private val ruleQueryService: RuleQueryService,
    private val checklistQueryService: ChecklistQueryService,
) {
    private val parser = SpelExpressionParser()

    data class ItemEvaluation(
        val itemCode: String,
        val result: ItemResult,
        val findingCount: Int,
        val matchedFindingIds: List<Long>,
    )

    /** 对一次扫描的 findings 做合规判定：按规则映射到清单条目，用 SpEL 策略判定结果。 */
    fun evaluate(projectId: Long, findings: List<Finding>): List<ItemEvaluation> {
        val items = checklistQueryService.publishedItemsForProject(projectId) ?: return emptyList()
        val itemCodes = items.map { it.itemCode }.toSet()
        val evaluations = mutableListOf<ItemEvaluation>()

        for ((ruleCode, ruleFindings) in findings.groupBy { it.ruleCode }) {
            val rule = ruleQueryService.findByRuleCode(ruleCode) ?: continue
            val policy = ruleQueryService.policyByRuleId(rule.id!!) ?: continue
            val mappedItems = ruleQueryService.itemCodesByRuleId(rule.id!!).filter { it in itemCodes }
            if (mappedItems.isEmpty()) continue

            val matched = ruleFindings.filter { evaluatePolicy(policy, it) }
            val result = if (matched.isNotEmpty()) ItemResult.valueOf(policy.resultOnMatch) else ItemResult.PASS
            val findingIds = matched.map { it.id!! }
            mappedItems.forEach { itemCode ->
                evaluations += ItemEvaluation(itemCode, result, findingIds.size, findingIds)
            }
        }
        return evaluations
    }

    private fun evaluatePolicy(policy: RuleEvaluationPolicy, finding: Finding): Boolean {
        val expr = policy.spElExpression
        if (expr.isNullOrBlank()) return false
        return runCatching {
            parser.parseExpression(expr).getValue(finding, Boolean::class.java) ?: false
        }.getOrDefault(false)
    }
}
```

> 说明：`ComplianceEvaluator` 依赖 `RuleQueryService.findByRuleCode`，该方法已在 Task 3.3 的 `RuleQueryService` 中定义。

`ScanTaskService.kt`:
```kotlin
package com.example.compliance.scan.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanTaskService(
    private val scanTaskRepository: ScanTaskRepository,
    private val projectService: ProjectService,
    private val registry: EngineAdapterRegistry,
    private val findingRepository: FindingRepository,
    private val evaluationRepository: ComplianceEvaluationRepository,
    private val itemResultRepository: ChecklistItemResultRepository,
    private val orchestrator: ScanOrchestrator,
) {
    /** 创建 PENDING 扫描任务并异步启动，立即返回任务。
     *  Ruling #45: 刻意不加 @Transactional —— save 自带事务立即提交，异步线程的
     *  findById 才能看到 PENDING 行；若在未提交事务内 dispatch，@Async 线程可能
     *  先于提交执行 → 任务 404 卡死在 PENDING（executeAsync 的 orElseThrow 在 try 外）。 */
    fun startScan(projectId: Long, engine: String, ref: String?): ScanTask {
        if (registry.get(engine) == null) {
            throw BusinessException(400, "unsupported engine: $engine")
        }
        projectService.get(projectId)
        val task = scanTaskRepository.save(ScanTask().apply {
            this.projectId = projectId
            this.engine = engine
            this.ref = ref
        })
        orchestrator.executeAsync(task.id!!)
        return task
    }

    fun get(id: Long): ScanTask =
        scanTaskRepository.findById(id).orElseThrow { BusinessException(404, "scan task not found: $id") }

    /** P0：仅在 PENDING（尚未被异步线程接管）时可取消；RUNNING 后由执行器独占，取消留 P1。 */
    @Transactional
    fun cancel(id: Long): ScanTask {
        val task = get(id)
        if (task.status != ScanTaskStatus.PENDING) {
            throw BusinessException(400, "only PENDING scan can be cancelled, current: ${task.status}")
        }
        task.status = ScanTaskStatus.CANCELLED
        return scanTaskRepository.save(task)
    }

    fun findings(scanTaskId: Long) = findingRepository.findByScanTaskId(scanTaskId)

    /** 扫描的合规评估结果（评估 + 逐条结果）；无评估返回空视图。 */
    fun complianceResults(scanTaskId: Long): ComplianceResultView {
        val task = get(scanTaskId)
        val evaluation = evaluationRepository.findByScanTaskId(scanTaskId)
        val items = evaluation?.let { itemResultRepository.findByEvaluationId(it.id!!) } ?: emptyList()
        return ComplianceResultView(task, evaluation, items)
    }

    data class ComplianceResultView(
        val scanTask: ScanTask,
        val evaluation: ComplianceEvaluation?,
        val items: List<ChecklistItemResult>,
    )
}
```

`ScanOrchestrator.kt`:
```kotlin
package com.example.compliance.scan.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.application.ProjectService
import com.example.compliance.project.infrastructure.RepoRepository
import com.example.compliance.result.application.FindingService
import com.example.compliance.result.application.NewFinding
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ScanExecutionLog
import com.example.compliance.scan.domain.ScanJob
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanExecutionLogRepository
import com.example.compliance.scan.infrastructure.ScanJobRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Component
class ScanOrchestrator(
    private val scanTaskRepository: ScanTaskRepository,
    private val scanJobRepository: ScanJobRepository,
    private val scanLogRepository: ScanExecutionLogRepository,
    private val evaluationRepository: ComplianceEvaluationRepository,
    private val itemResultRepository: ChecklistItemResultRepository,
    private val projectService: ProjectService,
    private val repoRepository: RepoRepository,
    private val registry: EngineAdapterRegistry,
    private val findingService: FindingService,
    private val findingRepository: FindingRepository,
    private val ruleQueryService: RuleQueryService,
    private val complianceEvaluator: ComplianceEvaluator,
) {
    private val objectMapper = ObjectMapper()

    /** 全流水线：RUNNING → adapter 扫描 → 归一化 → 指纹去重入库 → 合规判定 → 汇总评估。 */
    // Ruling #52: 刻意不加 @Transactional（同 Ruling #45）—— 每个 repo save 自带事务立即提交，
    // catch 里的 FAILED 写入必然落库。若加外层 @Transactional，来自事务性协作方（如
    // FindingService.upsertByFingerprint）的异常会把共享事务标记 rollback-only，commit 时报
    // UnexpectedRollbackException，FAILED 写入被回滚 → 任务永远卡在 PENDING。
    @Async("scanExecutor")
    fun executeAsync(scanTaskId: Long) {
        val task = scanTaskRepository.findById(scanTaskId)
            .orElseThrow { BusinessException(404, "scan task not found: $scanTaskId") }
        task.status = ScanTaskStatus.RUNNING
        task.startedAt = Instant.now()
        scanTaskRepository.save(task)
        log(scanTaskId, "SCAN", "INFO", "start engine=${task.engine} project=${task.projectId}")
        try {
            val repo = repoRepository.findByProjectId(task.projectId).firstOrNull()
                ?: throw BusinessException(400, "project has no repository bound")
            val adapter = registry.get(task.engine)
                ?: throw BusinessException(400, "unsupported engine: ${task.engine}")
            val context = ScanContext(task.id!!, task.projectId, repo.gitUrl, task.ref)
            val start = System.currentTimeMillis()
            val result = adapter.scan(context)
            val duration = System.currentTimeMillis() - start

            if (!result.success) {
                throw BusinessException(500, result.errorMessage ?: "engine scan failed")
            }

            val normalized = ArrayList<NewFinding>()
            var skipped = 0
            for (raw in result.findings) {
                val rule = ruleQueryService.publishedRuleByEngineRuleId(task.engine, raw.engineRuleId)
                if (rule == null) { skipped++; continue }
                normalized += NewFinding(
                    rule.ruleCode, rule.name, raw.filePath, raw.line,
                    raw.severity, raw.category, raw.message, raw.codeSnippet,
                )
            }
            log(scanTaskId, "NORMALIZE", "INFO", "raw=${result.findings.size} mapped=${normalized.size} skipped=$skipped")

            val upsert = findingService.upsertByFingerprint(task.projectId, scanTaskId, task.engine, normalized)
            val findings = findingRepository.findByScanTaskId(scanTaskId)

            scanJobRepository.save(ScanJob().apply {
                this.scanTaskId = scanTaskId
                engine = task.engine
                jobStatus = "SUCCESS"
                startedAt = task.startedAt
                finishedAt = Instant.now()
                durationMs = duration
                findingCount = findings.size
            })

            val evaluations = complianceEvaluator.evaluate(task.projectId, findings)
            if (evaluations.isNotEmpty()) {
                val evaluation = evaluationRepository.save(ComplianceEvaluation().apply {
                    this.scanTaskId = scanTaskId
                    this.projectId = task.projectId
                    totalItems = evaluations.size
                    passed = evaluations.count { it.result.name == "PASS" }
                    failed = evaluations.count { it.result.name == "FAIL" }
                    warning = evaluations.count { it.result.name == "WARNING" }
                    score = BigDecimal(100.0 * passed / evaluations.size)
                        .setScale(2, RoundingMode.HALF_UP)
                })
                evaluations.forEach { ev ->
                    itemResultRepository.save(ChecklistItemResult().apply {
                        evaluationId = evaluation.id!!
                        itemCode = ev.itemCode
                        result = ev.result.name
                        findingCount = ev.findingCount
                        matchedFindingIds = objectMapper.writeValueAsString(ev.matchedFindingIds)
                    })
                }
            }

            task.status = ScanTaskStatus.SUCCESS
            task.findingCount = findings.size
            task.finishedAt = Instant.now()
            scanTaskRepository.save(task)
            log(scanTaskId, "SCAN", "INFO", "done findings=${findings.size} created=${upsert.created} updated=${upsert.updated} evaluated=${evaluations.size}")
        } catch (e: Exception) {
            task.status = ScanTaskStatus.FAILED
            task.errorMessage = e.message?.take(500)
            task.finishedAt = Instant.now()
            scanTaskRepository.save(task)
            log(scanTaskId, "SCAN", "ERROR", e.message ?: "unknown failure")
        }
    }

    private fun log(scanTaskId: Long, stage: String, level: String, message: String) {
        scanLogRepository.save(ScanExecutionLog().apply {
            this.scanTaskId = scanTaskId
            this.stage = stage
            this.level = level
            this.message = message
        })
    }
}
```

`AsyncConfig.kt`（app-server）:
```kotlin
package com.example.compliance.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfig {
    @Bean(name = ["scanExecutor"])
    fun scanExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 8
            queueCapacity = 100
            setThreadNamePrefix("scan-")
        }
}
```

`ScanRequest.kt`:
```kotlin
package com.example.compliance.scan.api.dto

import jakarta.validation.constraints.NotBlank

data class ScanRequest(
    @field:NotBlank val engine: String,
    val ref: String? = null,
)
```

`ScanResponse.kt`:
```kotlin
package com.example.compliance.scan.api.dto

import com.example.compliance.result.domain.Finding
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanTask
import java.math.BigDecimal

data class ScanResponse(
    val id: Long,
    val projectId: Long,
    val engine: String,
    val ref: String?,
    val status: String,
    val findingCount: Int,
    val errorMessage: String?,
) {
    companion object {
        fun from(t: ScanTask) =
            ScanResponse(t.id!!, t.projectId, t.engine, t.ref, t.status.name, t.findingCount, t.errorMessage)
    }
}

data class FindingResponse(
    val id: Long,
    val ruleCode: String,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val message: String?,
    val status: String,
) {
    companion object {
        fun from(f: Finding) = FindingResponse(
            f.id!!, f.ruleCode, f.filePath, f.lineNumber, f.severity, f.message, f.status.name,
        )
    }
}

data class ItemResultResponse(
    val itemCode: String,
    val result: String,
    val findingCount: Int,
    val matchedFindingIds: List<Long>,
) {
    companion object {
        private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        fun from(r: ChecklistItemResult): ItemResultResponse {
            val ids: List<Long> = r.matchedFindingIds
                ?.let { json -> runCatching { mapper.readValue(json, Array<Long>::class.java) }.getOrNull() }
                ?.toList() ?: emptyList()
            return ItemResultResponse(r.itemCode, r.result, r.findingCount, ids)
        }
    }
}

data class ComplianceResultResponse(
    val scanTaskId: Long,
    val projectId: Long,
    val evaluationId: Long?,
    val score: BigDecimal?,
    val totalItems: Int,
    val passed: Int,
    val failed: Int,
    val warning: Int,
    val manual: Int,
    val skipped: Int,
    val items: List<ItemResultResponse>,
) {
    companion object {
        fun from(scanTask: ScanTask, evaluation: ComplianceEvaluation?, items: List<ChecklistItemResult>) =
            ComplianceResultResponse(
                scanTaskId = scanTask.id!!,
                projectId = scanTask.projectId,
                evaluationId = evaluation?.id,
                score = evaluation?.score,
                totalItems = evaluation?.totalItems ?: 0,
                passed = evaluation?.passed ?: 0,
                failed = evaluation?.failed ?: 0,
                warning = evaluation?.warning ?: 0,
                manual = evaluation?.manual ?: 0,
                skipped = evaluation?.skipped ?: 0,
                items = items.map { ItemResultResponse.from(it) },
            )
    }
}
```

`ScanController.kt`:
```kotlin
package com.example.compliance.scan.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.scan.api.dto.ComplianceResultResponse
import com.example.compliance.scan.api.dto.FindingResponse
import com.example.compliance.scan.api.dto.ScanRequest
import com.example.compliance.scan.api.dto.ScanResponse
import com.example.compliance.scan.application.ScanTaskService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
class ScanController(private val scanTaskService: ScanTaskService) {

    @PostMapping("/api/v1/projects/{projectId}/scan-tasks")
    fun start(@PathVariable projectId: Long, @Valid @RequestBody req: ScanRequest): ApiResponse<ScanResponse> =
        ApiResponse.ok(ScanResponse.from(scanTaskService.startScan(projectId, req.engine, req.ref)))

    @GetMapping("/api/v1/scan-tasks/{id}")
    fun get(@PathVariable id: Long): ApiResponse<ScanResponse> =
        ApiResponse.ok(ScanResponse.from(scanTaskService.get(id)))

    @PostMapping("/api/v1/scan-tasks/{id}/cancel")
    fun cancel(@PathVariable id: Long): ApiResponse<ScanResponse> =
        ApiResponse.ok(ScanResponse.from(scanTaskService.cancel(id)))

    @GetMapping("/api/v1/scan-tasks/{id}/findings")
    fun findings(@PathVariable id: Long): ApiResponse<List<FindingResponse>> =
        ApiResponse.ok(scanTaskService.findings(id).map { FindingResponse.from(it) })

    @GetMapping("/api/v1/scan-tasks/{id}/compliance-results")
    fun complianceResults(@PathVariable id: Long): ApiResponse<ComplianceResultResponse> {
        val view = scanTaskService.complianceResults(id)
        return ApiResponse.ok(ComplianceResultResponse.from(view.scanTask, view.evaluation, view.items))
    }
}
```

- [ ] **Step 4: 运行单元测试确认通过**

Run: `./gradlew :module-scan:test --tests "*ComplianceEvaluatorTest*"`
Expected: PASS。

- [ ] **Step 5: 写端到端集成测试**

`ScanPipelineIntegrationTest.kt`（app-server；注册 STUB 适配器 + 完整业务数据 + 触发扫描 + 轮询等待）:
```kotlin
package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

class ScanPipelineIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubAdapterConfig {
        @Bean
        fun stubAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUB"
            override fun scan(context: ScanContext): ScanResult = ScanResult(
                findings = listOf(
                    RawFinding("stub-rule-sqli", "Stub SQLi", "src/main/java/Demo.java", 10, "HIGH", "inject", "x = id;"),
                )
            )
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService

    @Test
    fun `full pipeline produces finding and evaluation`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("PIPE", "流水线项目", null, null))
        projectService.bindRepository(
            project.id!!,
            BindRepositoryCommand("repo-a", "https://git.example.com/a.git", "GITLAB", "main", "tok"),
        )
        // 2. 标准 → 清单 → 条目 → 发布 → 绑定（PIPE-* 系列：与冻结 Task 3.1 的 SEC-* / Task 3.2 的 SEC2-* 在同一共享容器中必须 disjoint，Ruling #43）
        val standard = checklistService.createStandard("PIPE-SEC", "安全规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "PIPE-BASIC", "安全基线")
        // Ruling #47: 用命名参数，riskLevel=HIGH（第 3 个位置形参是 category 不是 riskLevel）
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "PIPE-001", name = "防注入", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // 3. 规则：引擎绑定 + 清单映射 + FAIL 策略（severity==HIGH）
        val rule = ruleService.create(CreateRuleCommand("STUB-SQLI", "Stub注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUB", "stub-rule-sqli", null))
        ruleService.addComplianceMapping(rule.id!!, "PIPE-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 4. 触发扫描并轮询等待完成
        val task = scanTaskService.startScan(project.id!!, "STUB", "main")
        var done = false
        repeat(50) {
            if (scanTaskService.get(task.id!!).status != ScanTaskStatus.RUNNING &&
                scanTaskService.get(task.id!!).status != ScanTaskStatus.PENDING
            ) { done = true; return@repeat }
            Thread.sleep(200)
        }
        kotlin.test.assertTrue(done, "scan should finish within timeout")
        val finished = scanTaskService.get(task.id!!)
        kotlin.test.assertEquals(ScanTaskStatus.SUCCESS, finished.status)
        kotlin.test.assertEquals(1, scanTaskService.findings(task.id!!).size)
    }
}
```

Run: `./gradlew :app-server:test --tests "*ScanPipelineIntegrationTest*"`
Expected: PASS。若 `app.credential.secret` 未出现在测试资源，把 Task 2.1 加的配置复制到 `app-server/src/test/resources/application.yml`（或 test 用 profile）。

- [ ] **Step 6: 运行 M4 全部相关测试**

Run: `./gradlew :module-result:test :module-engine-adapter:test :module-scan:test :app-server:test`
Expected: 全部 PASS。

- [ ] **Step 7: Commit**

```bash
git add module-scan/src app-server/src
git commit -m "feat(scan): async scan orchestration with compliance evaluation"
```

**M4 完成标准**：`POST /api/v1/projects/{projectId}/scan-tasks` 触发异步扫描，finding 归一化 + 指纹去重入库，SpEL 合规判定写入 compliance_evaluation / checklist_item_result，端到端集成测试全绿。
---

## 里程碑 M5：报表报告

### Task 5.1: module-report 报表服务与 API

**Files:**
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportService.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/dto.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/api/ReportController.kt`
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/infrastructure/repos.kt`（给 `ComplianceEvaluationRepository` 增加趋势查询方法）
- Test: `module-report/src/test/kotlin/com/example/compliance/report/application/ReportServiceTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/report/ReportApiIntegrationTest.kt`

**Interfaces:**
- Consumes: `module-scan`（`ScanTaskRepository`、`ComplianceEvaluationRepository`、`ChecklistItemResultRepository`）、`module-result`（`FindingRepository.findByScanTaskId`）。
- Produces: `ReportService.scanSummary(scanTaskId): ScanSummary`；`complianceSummary(projectId): ComplianceSummary`；`trend(projectId, days): List<TrendPoint>`；`ReportController`（`GET /api/v1/reports/scan-summary?taskId={id}`、`GET /api/v1/reports/compliance-summary?projectId={id}`、`GET /api/v1/reports/trend?projectId={id}&days=30`）。

- [ ] **Step 1: 给 ComplianceEvaluationRepository 增加趋势方法**

在 `module-scan/.../infrastructure/repos.kt` 的 `ComplianceEvaluationRepository` 中追加：
```kotlin
interface ComplianceEvaluationRepository : JpaRepository<ComplianceEvaluation, Long> {
    fun findByScanTaskId(scanTaskId: Long): ComplianceEvaluation?
    fun findFirstByProjectIdOrderByIdDesc(projectId: Long): ComplianceEvaluation?

    /** M5 趋势分析使用：按时间升序返回项目指定时间之后的评估。 */
    fun findAllByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
        projectId: Long,
        since: java.time.Instant,
    ): List<ComplianceEvaluation>
}
```

- [ ] **Step 2: 写失败测试（单元）**

`ReportServiceTest.kt`:
```kotlin
package com.example.compliance.report.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportServiceTest {
    private val scanTaskRepository = mockk<ScanTaskRepository>(relaxed = true)
    private val findingRepository = mockk<FindingRepository>(relaxed = true)
    private val evaluationRepository = mockk<ComplianceEvaluationRepository>(relaxed = true)
    private val itemResultRepository = mockk<ChecklistItemResultRepository>(relaxed = true)
    private val service = ReportService(scanTaskRepository, findingRepository, evaluationRepository, itemResultRepository)

    @Test
    fun `scanSummary groups findings by severity`() {
        every { scanTaskRepository.findById(1L) } returns Optional.of(
            ScanTask().apply { id = 1L; projectId = 1L; engine = "SEMGREP"; status = ScanTaskStatus.SUCCESS }
        )
        every { findingRepository.findByScanTaskId(1L) } returns listOf(
            Finding().apply { severity = "HIGH" },
            Finding().apply { severity = "HIGH" },
            Finding().apply { severity = "MEDIUM" },
        )
        val summary = service.scanSummary(1L)
        assertEquals(3, summary.findingCount)
        assertEquals(2, summary.bySeverity["HIGH"])
        assertEquals(1, summary.bySeverity["MEDIUM"])
    }

    @Test
    fun `complianceSummary returns latest evaluation with item results`() {
        every { evaluationRepository.findFirstByProjectIdOrderByIdDesc(1L) } returns
            ComplianceEvaluation().apply {
                id = 5L; projectId = 1L; totalItems = 2; passed = 1; failed = 1; score = BigDecimal("50.00")
            }
        every { itemResultRepository.findByEvaluationId(5L) } returns listOf(
            ChecklistItemResult().apply { itemCode = "SEC-001"; result = "FAIL"; findingCount = 2 },
            ChecklistItemResult().apply { itemCode = "SEC-002"; result = "PASS"; findingCount = 0 },
        )
        val summary = service.complianceSummary(1L)
        assertEquals(5L, summary.evaluationId)
        assertEquals(2, summary.items.size)
        assertEquals("FAIL", summary.items[0].result)
    }

    @Test
    fun `complianceSummary throws when no evaluation`() {
        every { evaluationRepository.findFirstByProjectIdOrderByIdDesc(1L) } returns null
        assertFailsWith<BusinessException> { service.complianceSummary(1L) }
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :module-report:test --tests "*ReportServiceTest*"`
Expected: 编译失败（类不存在）。

- [ ] **Step 4: 写实现**

`dto.kt`:
```kotlin
package com.example.compliance.report.application

import java.math.BigDecimal

data class ScanSummary(
    val scanTaskId: Long,
    val engine: String,
    val status: String,
    val findingCount: Int,
    val bySeverity: Map<String, Int>,
)

data class ItemSummary(val itemCode: String, val result: String, val findingCount: Int)

data class ComplianceSummary(
    val projectId: Long,
    val evaluationId: Long,
    val score: BigDecimal?,
    val totalItems: Int,
    val passed: Int,
    val failed: Int,
    val warning: Int,
    val manual: Int,
    val skipped: Int,
    val items: List<ItemSummary>,
)

data class TrendPoint(val evaluatedAt: String, val score: BigDecimal?, val failed: Int)
```

`ReportService.kt`:
```kotlin
package com.example.compliance.report.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class ReportService(
    private val scanTaskRepository: ScanTaskRepository,
    private val findingRepository: FindingRepository,
    private val evaluationRepository: ComplianceEvaluationRepository,
    private val itemResultRepository: ChecklistItemResultRepository,
) {
    fun scanSummary(scanTaskId: Long): ScanSummary {
        val task = scanTaskRepository.findById(scanTaskId)
            .orElseThrow { BusinessException(404, "scan task not found: $scanTaskId") }
        val findings = findingRepository.findByScanTaskId(scanTaskId)
        return ScanSummary(
            scanTaskId = task.id!!,
            engine = task.engine,
            status = task.status.name,
            findingCount = findings.size,
            bySeverity = findings.groupingBy { it.severity }.eachCount(),
        )
    }

    fun complianceSummary(projectId: Long): ComplianceSummary {
        val evaluation = evaluationRepository.findFirstByProjectIdOrderByIdDesc(projectId)
            ?: throw BusinessException(404, "no compliance evaluation for project: $projectId")
        val items = itemResultRepository.findByEvaluationId(evaluation.id!!)
        return ComplianceSummary(
            projectId = projectId,
            evaluationId = evaluation.id!!,
            score = evaluation.score,
            totalItems = evaluation.totalItems,
            passed = evaluation.passed,
            failed = evaluation.failed,
            warning = evaluation.warning,
            manual = evaluation.manual,
            skipped = evaluation.skipped,
            items = items.map { ItemSummary(it.itemCode, it.result, it.findingCount) },
        )
    }

    fun trend(projectId: Long, days: Int): List<TrendPoint> {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        return evaluationRepository
            .findAllByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(projectId, since)
            .map { TrendPoint(it.createdAt.toString(), it.score, it.failed) }
    }
}
```

`ReportController.kt`:
```kotlin
package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.report.application.ComplianceSummary
import com.example.compliance.report.application.ReportService
import com.example.compliance.report.application.ScanSummary
import com.example.compliance.report.application.TrendPoint
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reports")
class ReportController(private val reportService: ReportService) {

    @GetMapping("/scan-summary")
    fun scanSummary(@RequestParam taskId: Long): ApiResponse<ScanSummary> =
        ApiResponse.ok(reportService.scanSummary(taskId))

    @GetMapping("/compliance-summary")
    fun complianceSummary(@RequestParam projectId: Long): ApiResponse<ComplianceSummary> =
        ApiResponse.ok(reportService.complianceSummary(projectId))

    @GetMapping("/trend")
    fun trend(
        @RequestParam projectId: Long,
        @RequestParam(defaultValue = "30") days: Int,
    ): ApiResponse<List<TrendPoint>> = ApiResponse.ok(reportService.trend(projectId, days))
}
```

- [ ] **Step 5: 运行单元测试确认通过**

Run: `./gradlew :module-report:test --tests "*ReportServiceTest*"`
Expected: PASS。

- [ ] **Step 6: 写 API 集成测试并运行**

`ReportApiIntegrationTest.kt`（复用 ScanPipelineIntegrationTest 的 STUB 适配器配置与完整数据链路，仅新增报表断言）:
```kotlin
package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.AddItemCommand
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Ruling #49: MockMvc hits the live Task 1.3 security chain (everything authenticated) — @WithMockUser required.
@AutoConfigureMockMvc
@WithMockUser
class ReportApiIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubAdapterConfig {
        @Bean
        fun stubAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUB"
            override fun scan(context: ScanContext): ScanResult = ScanResult(
                findings = listOf(RawFinding("stub-rule-sqli", "Stub SQLi", "Demo.java", 10, "HIGH", "inject", "x = id;"))
            )
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService

    @Test
    fun `reports return summary and compliance data after pipeline scan`() {
        val project = projectService.create(CreateProjectCommand("RPT", "报表项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("r", "https://git.example.com/r.git", "GITLAB", "main", "t"))
        // Ruling #48: RPT-* codes disjoint from frozen Task 3.1 (SEC) and Task 4.3 (PIPE) in the shared :app-server:test container.
        val standard = checklistService.createStandard("RPT-SEC", "规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "RPT-BASIC", "基线")
        checklistService.addItem(checklist.id!!, AddItemCommand(itemCode = "RPT-001", name = "防注入", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // Ruling #48: RPT-SQLI rule code disjoint from Task 4.3's STUB-SQLI (rule_definition.rule_code is UNIQUE, V5 DDL).
        val rule = ruleService.create(CreateRuleCommand("RPT-SQLI", "注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUB", "stub-rule-sqli", null))
        ruleService.addComplianceMapping(rule.id!!, "RPT-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUB", "main")
        repeat(50) {
            if (scanTaskService.get(task.id!!).status !in setOf(ScanTaskStatus.PENDING, ScanTaskStatus.RUNNING)) return@repeat
            Thread.sleep(200)
        }

        mockMvc.perform(get("/api/v1/reports/scan-summary").param("taskId", task.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.findingCount").value(1))
            .andExpect(jsonPath("$.data.bySeverity.HIGH").value(1))

        mockMvc.perform(get("/api/v1/reports/compliance-summary").param("projectId", project.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.failed").value(1))
            .andExpect(jsonPath("$.data.items[0].itemCode").value("RPT-001"))
            .andExpect(jsonPath("$.data.items[0].result").value("FAIL"))

        mockMvc.perform(get("/api/v1/reports/trend").param("projectId", project.id.toString()).param("days", "30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].failed").value(1))
    }
}
```
Run: `./gradlew :app-server:test --tests "*ReportApiIntegrationTest*"`
Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add module-report/src module-scan/src
git commit -m "feat(report): scan summary, compliance summary, trend reports"
```

**M5 完成标准**：三类报表 API 可用且有数据，端到端报表集成测试全绿。
---

## 整体验收与运行手册

### 全量验证

实现完成后（M0–M5 全部任务通过），在项目根目录执行并确认全绿：

```bash
./gradlew build            # 编译全部 15 模块 + 全部单元/集成测试（Testcontainers 起 PG）
```

重点回归路径（每加一个模块后都跑一次）：
- `./gradlew :app-server:test`：集成测试覆盖 auth / project / checklist / rule / result / scan / report。
- `./gradlew :module-scan:test --tests "*ComplianceEvaluatorTest*"`：SpEL 判定策略。

### 本地启动

```bash
docker compose up -d postgres    # 起 PostgreSQL 16（M0 已提供 docker-compose.yml）
./gradlew :app-server:bootRun       # 启动应用，Flyway 自动执行 V1..V7
```

启动后：
- API 文档：`http://localhost:8080/swagger-ui.html`
- 健康检查：`http://localhost:8080/actuator/health`
- 种子账号：`admin / admin123`（DataInitializer 写入，角色 ADMIN）
- 首跑一条链路（curl 示意）：
  1. `POST /api/v1/auth/login` 拿 token
  2. `POST /api/v1/projects` 建项目 → `POST /api/v1/projects/{id}/repositories` 绑仓库
  3. `POST /api/v1/compliance/standards` → `POST /api/v1/compliance/checklists` → `POST /api/v1/compliance/checklists/{id}/versions`（添加清单项）→ `POST /api/v1/compliance/checklists/{id}/publish` → `POST /api/v1/projects/{id}/bind-checklist`
  4. `POST /api/v1/rules` → `POST /api/v1/rules/{id}/engine-bindings` → `POST /api/v1/rules/{id}/mappings` → `POST /api/v1/rules/{id}/policy` → `POST /api/v1/rules/{id}/publish`
  5. `POST /api/v1/projects/{id}/scan-tasks`（body: `{"engine":"SEMGREP","ref":"main"}`；需机器装 semgrep；configJson 可给 `{"localPath":"/tmp/repo"}` 指向本地检出目录以便离线演示）→ 轮询 `GET /api/v1/scan-tasks/{id}` 至 SUCCESS
  6. `GET /api/v1/reports/scan-summary?taskId={id}`、`GET /api/v1/reports/compliance-summary?projectId={id}`、`GET /api/v1/reports/trend?projectId={id}&days=30`

### 里程碑验收标准汇总

| 里程碑 | 完成标准 |
|---|---|
| M0 | 15 模块可编译，应用可启动，Flyway V1 建表，Testcontainers 冒烟测试绿 |
| M1 | 登录拿 JWT、`/me` 需认证、BCrypt 校验、角色种子就绪 |
| M2 | 项目/仓库 CRUD + 凭据 AES-GCM 加密，单元 + 集成测试绿 |
| M3 | 清单版本化发布/绑定 + 规则中心（绑定/映射/策略/发布/停用）可用 |
| M4 | 异步扫描流水线：Semgrep 归一化 → 指纹去重 → SpEL 合规判定 → 评估落库，端到端测试绿 |
| M5 | 三类报表 API 可用且有数据，报表集成测试绿 |

## 本计划范围之外（P1/P2，遵循 spec「非目标」）

以下明确**不在本计划**实现（避免过度设计，保持 M0–M5 可独立交付）：

- 更多扫描引擎（SonarQube/Trivy/Dependency-Check/Detekt/Gitleaks）：仅需新增 `ScanEngineAdapter` 实现 + `rule_engine_binding` 记录，端口已就绪。
- 整改闭环 `module-remediation`、通知 `module-notification`、对外 API `module-openapi`、管理后台 `module-admin`：本轮仅 M0 建骨架。
- Redis/MQ/MinIO/ClickHouse/Elasticsearch：P1 引入，当前 JPA + jsonb 足够。
- 多租户、豁免流、趋势报告的更细粒度统计（按引擎/按规则聚合）。
- 前端（React/Vue3）：独立建设，按 spec 建议技术栈。

## 决策日志（计划级）

| 编号 | 决策 | 理由 |
|---|---|---|
| P1 | 合规判定用 `rule_evaluation_policy`（policyJson 结构化配置 + SpEL），不开放任意脚本 | spec D 系列：判定可审计、可解释，红线「不硬编码规则」 |
| P2 | 判定结果只对「命中规则且映射到清单」的条目落 `checklist_item_result`；无 finding 的条目本轮不计分 | P0 报表基于已记录结果；完整计分留 P1 趋势统计 |
| P3 | Flyway 迁移全在 `app-server`（V1..V7），业务模块不挂迁移 | 单一升级入口，避免多数据源迁移冲突 |
| P4 | 扫描 async 用 `@Async("scanExecutor")`，不加外层 @Transactional（Ruling #52）：每阶段 repo save 自带事务立即提交，FAILED 状态必然落库；避免事务性协作方异常导致 rollback-only 卡死任务 | P0 单引擎、结果量小；大结果集分片落库留 P1 |
| P5 | Semgrep 退出码非 0 视为正常（违规即非 0），以 stdout JSON 解析为准 | 引擎契约如此，避免把「发现违规」误判为「扫描失败」 |
