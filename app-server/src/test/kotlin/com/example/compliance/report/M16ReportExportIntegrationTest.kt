package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.audit.AuditLogRepository
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M16 端到端：COMPLIANCE 快照 → xlsx/pdf 二进制导出（Content-Type/Content-Disposition/魔数可验）+ REPORT_EXPORT 审计留痕 + 未知格式 400。
 *  模板走生命周期端点 draft+publish COMPLIANCE（不触碰 M12 C6 独占的 SCAN_SUMMARY/TREND 模板写入）；
 *  快照经仓库直接播种固定 payload（生成链路需评估数据，已由 M12 覆盖）。
 *  app-server 无 POI 编译类路径（module-report implementation 不透传）→ 字节级 ZIP/%PDF 魔数验证而非 Workbook 回读。 */
@AutoConfigureMockMvc
class M16ReportExportIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var snapshotRepository: ReportSnapshotRepository
    @Autowired lateinit var auditLogRepository: AuditLogRepository

    private val objectMapper = ObjectMapper()

    private val payload = """{"projectId":5,"evaluationId":7,"score":88.5,"totalItems":2,"passed":1,"failed":1,"warning":0,"manual":0,"skipped":0,"checklistVersionId":9,"items":[{"itemCode":"M16-IT-1","result":"PASS","findingCount":0},{"itemCode":"M16-IT-2","result":"FAIL","findingCount":3}]}"""

    // 计数须放 companion（JUnit5 默认 PER_METHOD：实例字段每次测试重置 → 固定 code 第二次起抛
    // "project code already exists"）。M10AdminIntegrationTest 同款实证修正先例（brief 固定 M16RP 有缺陷）。
    companion object {
        @JvmStatic
        private var seedCounter = 0
    }

    private fun seedSnapshot(): Long {
        seedCounter++
        val project = projectService.create(CreateProjectCommand("M16RP$seedCounter", "M16 report", null, null))
        // 模板 draft/publish 需 ADMIN/COMPLIANCE_MANAGER（SecurityConfig /api/v1/reports/templates/**），
        // 而导出测试方法以 @WithMockUser(DEVELOPER) 认证——播种请求显式提权 ADMIN（M12 同款 .with 覆盖模式）。
        val admin = SecurityMockMvcRequestPostProcessors.user("m16-admin").roles("ADMIN")
        val draftResp = mockMvc.perform(post("/api/v1/reports/templates/COMPLIANCE/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"M16 compliance","sections":{"sections":[{"title":"Summary"}]}}""")
                .with(admin))
            .andExpect(status().isOk)
            .andReturn()
        val tplId = objectMapper.readTree(draftResp.response.contentAsString)["data"]["templateId"].asLong()
        mockMvc.perform(post("/api/v1/reports/templates/COMPLIANCE/publish").with(admin))
            .andExpect(status().isOk)
        // apply 隐式接收者遮蔽：RHS `templateId`/`payload` 会解析到 ReportSnapshot 自身成员
        // （templateId 静默赋 0 / payload 是未初始化 lateinit → UninitializedPropertyAccessException），
        // 必须改名或加标签限定以引用外层作用域（16.4 实证修正 brief 代码）。
        val saved = snapshotRepository.save(ReportSnapshot().apply {
            this.templateId = tplId
            templateVersionNo = 1
            this.projectId = project.id
            snapshotType = "COMPLIANCE"
            this.payload = this@M16ReportExportIntegrationTest.payload
            generatedBy = 1L
            generatedAt = Instant.now()
        })
        return saved.id!!
    }

    @Test
    @WithMockUser(username = "m16-user", roles = ["DEVELOPER"])
    fun `export xlsx and pdf return binary attachments and record audit`() {
        val id = seedSnapshot()

        mockMvc.perform(get("/api/v1/reports/snapshots/$id/export").param("format", "xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-$id-compliance.xlsx\""))
            .andReturn().response.contentAsByteArray.also { bytes ->
                assertTrue(bytes.size > 1000, "xlsx bytes should be non-trivial, was ${bytes.size}")
                assertTrue(
                    bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
                        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte(),
                    "xlsx must be a ZIP archive (PK magic)",
                )
            }

        mockMvc.perform(get("/api/v1/reports/snapshots/$id/export").param("format", "pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-$id-compliance.pdf\""))
            .andReturn().response.contentAsByteArray.also { bytes ->
                assertTrue(bytes.size > 500, "pdf bytes should be non-trivial, was ${bytes.size}")
                assertTrue(
                    bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
                        bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte(),
                    "pdf must start with %PDF",
                )
            }

        val exportAudits = auditLogRepository.findAll().filter { it.action == "REPORT_EXPORT" && it.resourceId == id }
        assertEquals(2, exportAudits.size, "xlsx + pdf 两次导出各留一条审计")
        assertTrue(exportAudits.all { it.module == "report" && it.resourceType == "report_snapshot" })
        // 实证：PG jsonb 规范化空白（"format": "xlsx" 冒号后有空格），brief 的逐字 contains("\"format\":\"xlsx\"")
        // 对活库永不命中 → 改为解析 detail JSON 断言 format 字段（16.4 实证修正，语义等价且不受 jsonb 规范化影响）。
        val formats = exportAudits.map { objectMapper.readTree(it.detail!!)["format"].asText() }
        assertTrue("xlsx" in formats, "xlsx format audit detail missing: " + exportAudits.map { it.detail })
        assertTrue("pdf" in formats, "pdf format audit detail missing: " + exportAudits.map { it.detail })
    }

    @Test
    @WithMockUser(username = "m16-user", roles = ["DEVELOPER"])
    fun `unsupported export format is 400`() {
        val id = seedSnapshot()
        mockMvc.perform(get("/api/v1/reports/snapshots/$id/export").param("format", "bad"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
