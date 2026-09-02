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
