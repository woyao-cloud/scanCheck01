package com.example.compliance.remediation

import com.example.compliance.AbstractIntegrationTest
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

/** F5 (final review I9)：复扫任务失败 → 编排器补偿把 RECHECKING finding 回退 FIXED（不卡死）。
 *  数据前缀 FIXC-*；STUBFIX 经 StubFixState.failExecution 开关首扫成功 / 复扫失败。 */
class M9RecheckFailureCompensationTest : AbstractIntegrationTest() {

    object StubFixState {
        @Volatile
        var failExecution = false
    }

    @TestConfiguration
    class StubFixAdapterConfig {
        @Bean
        fun stubFixAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBFIX"
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                if (StubFixState.failExecution) {
                    // 先让主线程的 RECHECKING 转移落库，再抛失败 —— 避免补偿（FIXED 转移）抢先于
                    // RECHECKING 而变成 from==to 空转，导致测试时序不确定。
                    Thread.sleep(300)
                    throw IllegalStateException("stub engine failure")
                }
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> =
                listOf(RawFinding("stub-fix-rule", "FIX", "src/Fix.java", 10, "HIGH", "m", "x=id;"))
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var scanTaskRepository: ScanTaskRepository
    @Autowired lateinit var remediationService: RemediationService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `failed recheck scan compensates finding back to fixed`() {
        val findingId = setupFinding("FIXC")
        walkToFixed(findingId)
        assertEquals(FindingStatus.FIXED, lifecyclePort.findById(findingId)!!.status)

        StubFixState.failExecution = true     // 复扫必失败（引擎异常）
        remediationService.requestRecheck(findingId, 1L)

        // 若补偿缺失：finding 永远停在 RECHECKING → waitCompensated 超时失败
        waitCompensated(findingId)
        assertEquals(FindingStatus.FIXED, lifecyclePort.findById(findingId)!!.status)

        val task = scanTaskRepository.findAll().first { it.requestId == "recheck-f$findingId" }
        assertEquals(ScanTaskStatus.FAILED, task.status)
        assertEquals("stub engine failure", task.errorMessage)
    }

    private fun setupFinding(prefix: String): Long {
        val project = projectService.create(CreateProjectCommand("${prefix}P", "F5 项目", null, null))
        projectService.bindRepository(
            project.id!!,
            BindRepositoryCommand("${prefix.lowercase()}-repo", "https://git.example.com/fixc.git", "GITLAB", "main", "tok"),
        )
        val rule = ruleService.create(CreateRuleCommand("$prefix-SQLI", "F5 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBFIX", "stub-fix-rule", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        StubFixState.failExecution = false
        val task = scanTaskService.startScan(project.id!!, "STUBFIX", "main")
        waitDone(task.id!!)
        return lifecyclePort.findingsForScanTask(task.id!!).first().id
    }

    /** 走完整改路径到 FIXED（F4 后 markFixed 需 ROLE_ADMIN 覆写，actorId=1L ≠ 受让人 3L）。 */
    private fun walkToFixed(findingId: Long) {
        remediationService.confirm(findingId, 1L)
        remediationService.assign(findingId, 1L, 3L, "fix plan", null)
        remediationService.startFix(findingId, 1L)
        remediationService.markFixed(findingId, 1L, setOf("ROLE_ADMIN"), "FIX_COMMIT", "abc123")
        assertEquals(FindingStatus.FIXED, lifecyclePort.findById(findingId)!!.status)
    }

    /** 轮询直到补偿完成（finding 回到 FIXED）。 */
    private fun waitCompensated(findingId: Long) {
        var done = false
        repeat(100) {
            val s = lifecyclePort.findById(findingId)!!.status
            if (s == FindingStatus.FIXED) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "recheck failure should compensate finding back to FIXED within timeout")
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
