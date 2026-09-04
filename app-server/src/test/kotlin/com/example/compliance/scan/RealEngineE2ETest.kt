package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M13 真实引擎 E2E（spec 4.1，门控）：本机安装 gitleaks/trivy 二进制 + 置 APP_SCAN_E2E=true 才运行；
 * CI/默认不装二进制 → 类级 @EnabledIfEnvironmentVariable 整体跳过。
 * 数据前缀 M13-*。fixture 在 @BeforeEach 建本地临时目录（gitleaks 敏感密钥 + trivy 漏洞 package-lock.json），
 * 绑定为项目 repo 本地路径 → checkout 跳过 clone（commitId=null）、adapter 直接扫目录。
 * 注意：trivy 报告的 CVE 取决于本机漏洞库（~/.cache/trivy）。fixture 用 lodash@4.17.20（经典 CVE-2021-23337，
 * fixed 4.17.21）；若本机库报告不同 CVE，用 `trivy fs <fixture目录>` 确认后调整本类的绑定与指纹常量。
 */
@EnabledIfEnvironmentVariable(named = "APP_SCAN_E2E", matches = "true")
class RealEngineE2ETest : AbstractIntegrationTest() {

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort
    @Autowired lateinit var findingRepository: FindingRepository
    @Autowired lateinit var fingerprintGenerator: FingerprintGenerator

    private lateinit var fixtureDir: Path

    @BeforeEach
    fun createFixture() {
        fixtureDir = Files.createTempDirectory("m13-e2e-")
        // gitleaks fixture：AWS 文档公开示例凭证（AKIA 访问密钥 + 对应 secret）→ gitleaks aws-access-token 规则
        Files.writeString(fixtureDir.resolve("aws-credentials.txt"),
            "aws_access_key_id = AKIAIOSFODNN7EXAMPLE\naws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n")
        // trivy fixture：lodash@4.17.20（CVE-2021-23337，fixed 4.17.21）
        Files.writeString(fixtureDir.resolve("package-lock.json"),
            """{"name":"m13-fixture","version":"1.0.0","lockfileVersion":3,"requires":true,
               |"packages":{"node_modules/lodash":{"version":"4.17.20",
               |"resolved":"https://registry.npmjs.org/lodash/-/lodash-4.17.20.tgz",
               |"integrity":"sha512-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX","dev":true}}}""".trimMargin())
    }

    @AfterEach
    fun deleteFixture() {
        runCatching {
            Files.walk(fixtureDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun pollTask(taskId: Long): ScanTaskStatus {
        var done = false
        repeat(150) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "real scan should finish within 30s")
        return scanTaskService.get(taskId).status
    }

    @Test
    fun `real gitleaks scan detects fixture secret end to end`() {
        val project = projectService.create(CreateProjectCommand("M13GE", "M13 gitleaks e2e", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m13g-repo", fixtureDir.toString(), "LOCAL", "main", null))
        val rule = ruleService.create(CreateRuleCommand("M13-GE2E", "M13 密钥", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("GITLEAKS", "aws-access-token", null))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "GITLEAKS", "main")
        assertEquals(ScanTaskStatus.SUCCESS, pollTask(task.id!!))

        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertTrue(views.isNotEmpty(), "real gitleaks should hit the fixture secret")
        assertEquals("GITLEAKS", views[0].engine)
        assertNotNull(views[0].filePath)
        assertEquals("M13-GE2E", views[0].ruleCode)
        assertTrue(scanTaskService.get(task.id!!).commitId == null, "local dir -> checkout skipped -> commitId null")
    }

    @Test
    fun `real trivy scan persists dependency finding and re-scan refreshes metadata`() {
        val project = projectService.create(CreateProjectCommand("M13TE", "M13 trivy e2e", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m13t-repo", fixtureDir.toString(), "LOCAL", "main", null))
        val rule = ruleService.create(CreateRuleCommand("M13-TE2E", "M13 依赖漏洞", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("TRIVY", "CVE-2021-23337", null))
        ruleService.publish(rule.id!!)

        // 首扫：依赖 finding 落库（5 依赖字段齐全）
        val task1 = scanTaskService.startScan(project.id!!, "TRIVY", "main")
        assertEquals(ScanTaskStatus.SUCCESS, pollTask(task1.id!!))
        val first = lifecyclePort.findingsByProject(project.id!!, null)
        assertTrue(first.isNotEmpty(), "real trivy should hit lodash 4.17.20 in fixture")
        val v = first[0]
        assertEquals("M13-TE2E", v.ruleCode)
        assertEquals("TRIVY", v.engine)
        assertEquals("lodash", v.packageName)
        assertEquals("CVE-2021-23337", v.cveId)
        assertNotNull(v.fixedVersion)
        assertNotNull(v.cvssScore)
        assertEquals(1, v.occurrenceCount)

        // 复扫（同 fixture）：REAPPEARED 路径 —— occurrenceCount 递增、元数据保持（P3-D7 真实验证；
        // fixedVersion 的「变化」由 13.1 单测钉住，真实二扫无法令本机 trivy 库在两秒内更新）
        val task2 = scanTaskService.startScan(project.id!!, "TRIVY", "main")
        assertEquals(ScanTaskStatus.SUCCESS, pollTask(task2.id!!))
        val fp = fingerprintGenerator.generateDependency(project.id!!, "lodash", "4.17.20", "CVE-2021-23337")
        val entity = findingRepository.findByProjectIdAndFingerprint(project.id!!, fp)
        assertNotNull(entity)
        assertEquals(2, entity.occurrenceCount)
        assertEquals("4.17.21", entity.fixedVersion)   // 刷新后元数据仍正确（P2-D4 不碰 status）
    }
}
