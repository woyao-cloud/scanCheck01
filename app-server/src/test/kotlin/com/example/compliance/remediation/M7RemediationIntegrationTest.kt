package com.example.compliance.remediation

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7 复扫闭环（spec §4.3）：FIXED → recheck → 复扫 absent→CLOSED / present→回归 CONFIRMED。
 *  数据前缀 REM-*；STUBM7 用静态 StubM7.findings 控制复扫命中/缺席。
 *  五方法形态：M8 编排器直接驱动 prepare/execute/collect/normalize，本 STUB 按新契约接入（Ruling：STUB 解冻细化）。 */
class M7RemediationIntegrationTest : AbstractIntegrationTest() {

    /** 静态可控引擎输出：测试在 requestRecheck 前改写，决定复扫是否命中。 */
    object StubM7 {
        @Volatile
        var findings: List<RawFinding> = emptyList()
    }

    @TestConfiguration
    class StubM7AdapterConfig {
        @Bean
        fun stubM7Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM7"
            // M8 新契约：编排器直接驱动五阶段，STUB 按五方法形态接入（Ruling：测试 STUB 适配器按新契约更新）
            override fun executeScan(context: ScanContext): ScanExecutionResult =
                ScanExecutionResult(success = true, durationMs = 5)
            override fun collectResult(context: ScanContext): List<RawFinding> = StubM7.findings
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var scanTaskRepository: ScanTaskRepository
    @Autowired lateinit var remediationService: RemediationService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    private val raw = RawFinding("stub-m7-rule", "M7", "src/main/java/Rem.java", 10, "HIGH", "m", "x=id;")

    @Test
    fun `recheck with finding absent closes it`() {
        val findingId = setupFinding("REM")
        walkToFixed(findingId)
        StubM7.findings = emptyList()          // 复扫缺席
        remediationService.requestRecheck(findingId, 1L)
        waitResolved(findingId)
        assertEquals(FindingStatus.CLOSED, lifecyclePort.findById(findingId)!!.status)
        assertRecheckTask(findingId)
    }

    @Test
    fun `recheck with finding present regresses to confirmed`() {
        val findingId = setupFinding("REM2")
        walkToFixed(findingId)
        StubM7.findings = listOf(raw)          // 复扫命中（同指纹 → REAPPEARED）
        remediationService.requestRecheck(findingId, 1L)
        waitResolved(findingId)
        assertEquals(FindingStatus.CONFIRMED, lifecyclePort.findById(findingId)!!.status)
        assertRecheckTask(findingId)
    }

    /** 首扫产生 finding（NEW），返回其 id。
     *  prefix 决定 REM-* 数据 code（"REM" → REMP/REM-SEC/REM-BASIC/REM-001/REM-SQLI；"REM2" → REM2P/REM2-*）：
     *  brief 的固定前缀只够一套数据 —— 项目/标准/清单/规则 code 均有唯一约束，第二个 @Test 的
     *  setupFinding 复用相同 code 会撞 "already exists"（共享容器内两用例各建一套，仍属 REM-* 命名空间）。 */
    private fun setupFinding(prefix: String): Long {
        val project = projectService.create(CreateProjectCommand("${prefix}P", "M7 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("${prefix.lowercase()}-repo", "https://git.example.com/rem.git", "GITLAB", "main", "tok"))
        val standard = checklistService.createStandard("$prefix-SEC", "M7 规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "$prefix-BASIC", "M7 基线")
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "$prefix-001", name = "M7 项", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        val rule = ruleService.create(CreateRuleCommand("$prefix-SQLI", "M7 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM7", "stub-m7-rule", null))
        ruleService.addComplianceMapping(rule.id!!, "$prefix-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        StubM7.findings = listOf(raw)
        val task1 = scanTaskService.startScan(project.id!!, "STUBM7", "main")
        waitDone(task1.id!!)
        return lifecyclePort.findingsForScanTask(task1.id!!).first().id
    }

    /** 走完整改路径到 FIXED：confirm → assign → fixing → fixed(evidence)。
     *  F4 (final review I6): markFixed 新增受让人校验 —— 测试以系统/管理员身份（ROLE_ADMIN）覆写，
     *  使 actorId=1L（非受让人 3L）也能通过。 */
    private fun walkToFixed(findingId: Long) {
        remediationService.confirm(findingId, 1L)
        remediationService.assign(findingId, 1L, 3L, "fix plan", null)
        remediationService.startFix(findingId, 1L)
        remediationService.markFixed(findingId, 1L, setOf("ROLE_ADMIN"), "FIX_COMMIT", "abc123")
        assertEquals(FindingStatus.FIXED, lifecyclePort.findById(findingId)!!.status)
    }

    /** 复扫任务存在性 + 元数据断言（requestId=recheck-f<findingId> 保证跨共享容器唯一可定位）。 */
    private fun assertRecheckTask(findingId: Long) {
        val task = scanTaskRepository.findAll().first { it.requestId == "recheck-f$findingId" }
        assertEquals("MANUAL", task.triggerType)
        assertEquals(ScanTaskStatus.SUCCESS, task.status)
    }

    /** 轮询直到复扫验证决议（finding 离开 RECHECKING）。 */
    private fun waitResolved(findingId: Long) {
        var done = false
        repeat(100) {
            val s = lifecyclePort.findById(findingId)!!.status
            if (s != FindingStatus.RECHECKING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "recheck verification should resolve within timeout")
    }

    private fun waitDone(taskId: Long) {
        var done = false
        repeat(50) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.RUNNING && s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan $taskId should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(taskId).status)
    }
}
