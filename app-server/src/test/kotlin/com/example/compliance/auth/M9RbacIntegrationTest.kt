package com.example.compliance.auth

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.result.application.FindingLifecyclePort
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M9 RBAC 矩阵（spec §6.1）：@EnableMethodSecurity + 路径角色规则。
 *  Token 管理读写仅 ADMIN：匿名 401、非 ADMIN 403、ADMIN 200。
 *  整改写操作按状态转换配角色：普通 USER 被方法级 @PreAuthorize 拦在服务之外（403）。
 *  F9 (final review m10)：补 ADMIN 正向转移 / ADMIN fixed 覆写 / DEVELOPER 非受让人 fixed 403。
 *  数据前缀 FIXR-*；STUBR 的引擎规则号按测试前缀派生（stub-r-fixr / stub-r-fixr2 / stub-r-fixr3），
 *  避免共享容器内多个已发布规则绑定同一引擎规则号造成规则解析歧义（ReportApiIntegrationTest 同款处理）。 */
@AutoConfigureMockMvc
class M9RbacIntegrationTest : AbstractIntegrationTest() {

    object StubRState {
        @Volatile
        var ruleId = "stub-r-fixr"
    }

    @TestConfiguration
    class StubRAdapterConfig {
        @Bean
        fun stubRAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBR"
            override fun executeScan(context: ScanContext): ScanExecutionResult =
                ScanExecutionResult(success = true, durationMs = 5)
            override fun collectResult(context: ScanContext): List<RawFinding> =
                listOf(RawFinding(StubRState.ruleId, "RBAC SQLi", "Demo.java", 10, "HIGH", "inject", "x = id;"))
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var remediationService: RemediationService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `unauthenticated token list is 401`() {
        mockMvc.perform(get("/api/v1/openapi/tokens"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "rbac-user", roles = ["USER"])
    fun `non-admin token list is 403`() {
        mockMvc.perform(get("/api/v1/openapi/tokens"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "rbac-admin", roles = ["ADMIN"])
    fun `admin token list is 200`() {
        mockMvc.perform(get("/api/v1/openapi/tokens"))
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser(username = "rbac-user", roles = ["USER"])
    fun `plain user is denied remediation confirm by method security`() {
        mockMvc.perform(post("/api/v1/remediation/findings/1/confirm"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin can drive remediation transitions recheck and fixed override`() {
        // recheck finding：confirm → assign → fixing → fixed（ADMIN 覆写受让人）→ recheck。
        // recheck 后不再对该 finding 做同步转移 —— 异步复扫的 verifyRechecking 会并发写同一行
        // （finding 带 @Version 乐观锁），recheck 之后再碰会竞态 500。
        val recheckFinding = setupFinding("FIXR")
        driveToFixed(recheckFinding)
        mockMvc.perform(post("/api/v1/remediation/findings/$recheckFinding/recheck").with(adminAuth()))
            .andExpect(status().isOk)

        // status finding：无复扫 → 无异步竞态，ADMIN 可安全做终态转移
        val statusFinding = setupFinding("FIXR3")
        driveToFixed(statusFinding)
        mockMvc.perform(put("/api/v1/remediation/findings/$statusFinding/status")
                .with(adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"WAIVED","reason":"rbac","evidenceType":"DOC","evidenceRef":"x"}"""))
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser(username = "fixr-dev", roles = ["DEVELOPER"])
    fun `developer who is not the assignee is denied fixed`() {
        val findingId = setupFinding("FIXR2")
        // 服务端预置到 FIXING，受让人 2L（@WithMockUser 的 String principal → actorId 回落 1L）
        remediationService.confirm(findingId, 1L)
        remediationService.assign(findingId, 1L, 2L, "plan", null)
        remediationService.startFix(findingId, 1L)

        // DEVELOPER（非受让人，无 ADMIN）→ 服务端 403（F4 端到端）
        mockMvc.perform(post("/api/v1/remediation/findings/$findingId/fixed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"evidenceType":"FIX_COMMIT","evidenceRef":"abc"}"""))
            .andExpect(status().isForbidden)
    }

    /** confirm → assign（受让人 2）→ fixing → fixed（ADMIN 覆写：调用者 userId=7 ≠ 受让人 2）。 */
    private fun driveToFixed(findingId: Long) {
        mockMvc.perform(post("/api/v1/remediation/findings/$findingId/confirm").with(adminAuth()))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/remediation/findings/$findingId/assign")
                .with(adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"assigneeId":2,"plan":"fix"}"""))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/remediation/findings/$findingId/fixing").with(adminAuth()))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/remediation/findings/$findingId/fixed")
                .with(adminAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"evidenceType":"FIX_COMMIT","evidenceRef":"abc"}"""))
            .andExpect(status().isOk)
    }

    /** ADMIN 认证 postprocessor：携带真实 AuthPrincipal（userId=7 + ROLE_ADMIN）以驱动服务端受让人校验。 */
    private fun adminAuth() = SecurityMockMvcRequestPostProcessors.authentication(
        UsernamePasswordAuthenticationToken(
            AuthPrincipal(userId = 7L, username = "fix-admin", authorities = setOf("ROLE_ADMIN")),
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
    )

    /** 建项目 + 规则 + STUBR 首扫 → 返回 NEW finding（FIXR-* 数据前缀；引擎规则号按前缀派生）。 */
    private fun setupFinding(prefix: String): Long {
        val project = projectService.create(CreateProjectCommand("${prefix}P", "RBAC 项目", null, null))
        projectService.bindRepository(
            project.id!!,
            BindRepositoryCommand("${prefix.lowercase()}-repo", "https://git.example.com/fixr.git", "GITLAB", "main", "tok"),
        )
        val engineRuleId = "stub-r-${prefix.lowercase()}"
        StubRState.ruleId = engineRuleId
        val rule = ruleService.create(CreateRuleCommand("$prefix-SQLI", "RBAC 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBR", engineRuleId, null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUBR", "main")
        waitDone(task.id!!)
        return lifecyclePort.findingsForScanTask(task.id!!).first().id
    }

    private fun waitDone(taskId: Long) {
        var done = false
        repeat(50) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        kotlin.test.assertTrue(done, "scan $taskId should finish within timeout")
        kotlin.test.assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(taskId).status)
    }
}
