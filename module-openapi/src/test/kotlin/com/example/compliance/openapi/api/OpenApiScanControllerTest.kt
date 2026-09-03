package com.example.compliance.openapi.api

import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.openapi.domain.ApiToken
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.application.ScanTriggerPort
import com.example.compliance.scan.domain.ScanTaskStatus
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M9：开放扫描端点——合法 token 触发、无效 token 401（切片，服务 mock）。 */
@WebMvcTest(OpenApiScanController::class)
@AutoConfigureMockMvc(addFilters = false)   // Security 过滤链关闭：未认证请求直接进控制器
class OpenApiScanControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var tokenService: ApiTokenService
    @Autowired lateinit var triggerPort: ScanTriggerPort

    /** 控制器依赖经 MockK mock 注入（@MockBean 是 Mockito mock，与 io.mockk.every 不兼容）。 */
    @TestConfiguration
    class OpenApiConfig {
        @Bean
        fun tokenService(): ApiTokenService = mockk()

        @Bean
        fun triggerPort(): ScanTriggerPort = mockk()
    }

    @Test
    fun `valid token triggers scan`() {
        every { tokenService.verify("cop-ci-a-xyz") } returns ApiToken().apply { id = 5L; name = "ci-a" }
        every { tokenService.recordUsage(5L) } just Runs
        every { triggerPort.triggerScan(9L, "SEMGREP", "main", "CI", "req-1") } returns
            ScanTaskView(1L, 9L, "SEMGREP", ScanTaskStatus.PENDING, "req-1")

        mockMvc.perform(
            post("/api/v1/openapi/scans")
                .header("X-API-Token", "cop-ci-a-xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":9,"engine":"SEMGREP","ref":"main","requestId":"req-1"}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `invalid token rejected with 401`() {
        every { tokenService.verify("bad") } returns null
        mockMvc.perform(
            post("/api/v1/openapi/scans")
                .header("X-API-Token", "bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":9,"engine":"SEMGREP"}""")
        )
            .andExpect(status().isUnauthorized)
    }
}
