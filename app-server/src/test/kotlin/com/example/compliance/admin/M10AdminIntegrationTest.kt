package com.example.compliance.admin

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M10 I1 admin 三端点（spec §6.2）：ADMIN 可访问 / 非 ADMIN 403。
 *  数据前缀 ADM-*。响应为裸 DTO（RemediationController 惯例，无 ApiResponse 包）。 */
@AutoConfigureMockMvc
class M10AdminIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService

    // 每测试唯一 code（ADM-P<n>）—— @BeforeEach 每次执行 create，固定 code 第二次起抛
    // "project code already exists"（M9Rbac setupFinding 同款唯一前缀模式）。
    // 计数器须放 companion（JUnit5 默认 PER_METHOD：实例字段每次测试重置为 0，三个测试
    // 都会生成 ADM-P1 撞码）。brief 用实例字段 → 第 2 个测试起抛 duplicate（10.6 实证修正）。
    companion object {
        @JvmStatic
        private var seedCounter = 0
    }

    @BeforeEach
    fun seed() {
        seedCounter++
        projectService.create(CreateProjectCommand("ADM-P$seedCounter", "M10 admin project", null, null))
    }

    @Test
    @WithMockUser(username = "adm-admin", roles = ["ADMIN"])
    fun `admin can view dashboard with counts and severity distribution`() {
        mockMvc.perform(get("/api/v1/admin/dashboard"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCount").isNumber)
            .andExpect(jsonPath("$.severityDistribution").isMap)
    }

    @Test
    @WithMockUser(username = "adm-admin", roles = ["ADMIN"])
    fun `admin can list scans and findings with pagination`() {
        mockMvc.perform(get("/api/v1/admin/scans?page=0&size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
        mockMvc.perform(get("/api/v1/admin/findings?page=0&size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
    }

    @Test
    @WithMockUser(username = "adm-dev", roles = ["DEVELOPER"])
    fun `non-admin is forbidden on admin endpoints`() {
        mockMvc.perform(get("/api/v1/admin/dashboard"))
            .andExpect(status().isForbidden)
    }
}
