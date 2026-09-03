package com.example.compliance.openapi

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** M9-* 数据前缀；开放 API CI 触发（Task 9.2 控制器）+ token 鉴权 + 异常语义（401）端到端。
 *  R-9.8-a：ScanTaskService 无 listByProject——任务存在性改为从 202 响应体解析 task id 断言；
 *  不断言 status（异步编排器可能已推进）。STUBM9 经嵌套 @TestConfiguration 注册（镜像 M8）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M9OpenApiIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubM9AdapterConfig {
        @Bean
        fun stubM9Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM9"
            override fun executeScan(context: ScanContext): ScanExecutionResult =
                ScanExecutionResult(success = true, durationMs = 5)
            override fun collectResult(context: ScanContext): List<RawFinding> =
                listOf(RawFinding("stub-m9-rule", "M9", "src/main/java/M9.java", 10, "HIGH", "m", "x=id;"))
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
            // prepareScan / cleanup 使用接口默认空实现
        }
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var tokenService: ApiTokenService
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService

    @Test
    fun `open api trigger with valid token creates scan task`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("M9P", "M9 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m9-repo", "https://git.example.com/m9.git", "GITLAB", "main", "tok"))
        // 2. 规则 + STUBM9 引擎绑定（镜像 M8——引擎入 registry 且绑定有意义）
        val rule = ruleService.create(CreateRuleCommand("M9-SQLI", "M9 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM9", "stub-m9-rule", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 3. 创建 CI token（服务直调，避开 ADMIN 登录）
        val token = tokenService.create("m9-ci", null, 9L).token
        // 4. 经开放端点触发（真实安全链 + 控制器；permitAll，token 在控制器内校验）
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-API-Token", token)
        }
        val body = """{"projectId":${project.id},"engine":"STUBM9","ref":"main","requestId":"m9-req-1"}"""
        val response = rest.exchange("/api/v1/openapi/scans", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        // 5. 任务存在 + 引擎为 STUBM9（R-9.8-a：解析响应体 id；不断言 status——异步编排器可能已推进）
        // 注：json-path 默认 provider 把小整数解析为 Integer，read<Long> 原始 cast 会 ClassCastException
        //（实测 RED）；改按 Number 读取再 .toLong()，语义与 ruling 一致。
        val taskId = JsonPath.parse(response.body!!).read<Number>("$.id").toLong()
        assertNotNull(scanTaskService.get(taskId))
        assertEquals("STUBM9", scanTaskService.get(taskId)!!.engine)
    }

    @Test
    fun `open api trigger without token is rejected`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"projectId":1,"engine":"STUB","ref":"main"}"""
        val response = rest.exchange("/api/v1/openapi/scans", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
