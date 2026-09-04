package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M12 端到端：模板生命周期 + RBAC 三档 + TREND 快照生成/列表/详情/导出（真实 DB + Security 链）。
 *  数据前缀 M12-*：TREND 生成只需 project（新项目 trend=空列表 → payload "[]"，确定性、零扫描/评估依赖）；
 *  report_template 表仅本测试类写入（TREND/SCAN_SUMMARY 两类型，无跨类串扰）。 */
@AutoConfigureMockMvc
class M12ReportIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    private val objectMapper = ObjectMapper()

    @Test
    fun `unauthenticated snapshot access is 401`() {
        mockMvc.perform(get("/api/v1/reports/snapshots")).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "m12-cm", roles = ["COMPLIANCE_MANAGER"])
    fun `template rbac tiers admin manager auditor developer`() {
        // CM 可 draft（general 规则）→ 200，建 SCAN_SUMMARY DRAFT（单方法内先建，供 AUDITOR versions 读）
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"M12 scan","sections":{"sections":[{"title":"Summary"}]}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
        // CM 可 publish（general 规则）→ 200（R-M12-6：disable 仅停用活跃 PUBLISHED 版，先发布才有可停用对象）
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
        // AUDITOR 可看 versions（spec §3.3：ADMIN/CM/AUDITOR）→ 200
        val auditor = SecurityMockMvcRequestPostProcessors.user("m12-auditor").roles("AUDITOR")
        mockMvc.perform(get("/api/v1/reports/templates/SCAN_SUMMARY/versions").with(auditor))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
        // AUDITOR / DEVELOPER 不能 draft → 403
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/draft").with(auditor)
                .contentType(MediaType.APPLICATION_JSON).content("""{"sections":{}}"""))
            .andExpect(status().isForbidden)
        val developer = SecurityMockMvcRequestPostProcessors.user("m12-dev").roles("DEVELOPER")
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/draft").with(developer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"sections":{}}"""))
            .andExpect(status().isForbidden)
        // CM 不能 disable（disable 仅 ADMIN；*/disable 规则先于 general 命中）→ 403
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/disable").with(
            SecurityMockMvcRequestPostProcessors.user("m12-cm").roles("COMPLIANCE_MANAGER")))
            .andExpect(status().isForbidden)
        // ADMIN 可 disable → 200
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/disable").with(
            SecurityMockMvcRequestPostProcessors.user("m12-admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
    }

    @Test
    @WithMockUser(username = "m12-admin", roles = ["ADMIN"])
    fun `admin template lifecycle then trend snapshot generate list detail export`() {
        val project = projectService.create(CreateProjectCommand("M12RP", "M12 report", null, null))

        // 1. TREND 模板 DRAFT → PUBLISH（生成只取 PUBLISHED 最新版）
        mockMvc.perform(post("/api/v1/reports/templates/TREND/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"M12 trend","sections":{"sections":[{"title":"Trend"}]}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
        mockMvc.perform(post("/api/v1/reports/templates/TREND/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))

        // 2. 生成 TREND 快照（新项目 trend=空列表 → payload "[]"，确定性）
        val createResponse = mockMvc.perform(post("/api/v1/reports/TREND/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":${project.id}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.snapshotType").value("TREND"))
            .andExpect(jsonPath("$.data.templateVersionNo").value(1))
            .andExpect(jsonPath("$.data.projectId").value(project.id))
            .andReturn()
        val snapshotId = objectMapper.readTree(createResponse.response.contentAsString)["data"]["id"].asLong()

        // 3. 列表按 projectId 过滤含该快照（确定性 total=1）
        mockMvc.perform(get("/api/v1/reports/snapshots").param("projectId", project.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].snapshotType").value("TREND"))

        // 4. 详情回读 payload（不可变快照原文）
        mockMvc.perform(get("/api/v1/reports/snapshots/$snapshotId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.snapshotType").value("TREND"))
            .andExpect(jsonPath("$.data.payload").value("[]"))

        // 5. 导出 JSON 与详情 payload 一致
        mockMvc.perform(get("/api/v1/reports/snapshots/$snapshotId/export?format=json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value("[]"))
    }
}
