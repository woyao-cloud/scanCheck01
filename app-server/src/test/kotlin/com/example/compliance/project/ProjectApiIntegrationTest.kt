package com.example.compliance.project

import com.example.compliance.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Ruling #24: Task 2.2 runs AFTER Task 1.3, whose SecurityConfig requires authentication for
// everything except login/swagger/health. Without @WithMockUser, JwtAuthenticationFilter no-ops
// (no Bearer header) and AuthorizationFilter rejects → 401. Same pattern as Ruling #22 (Task 1.2).
// CSRF is disabled globally since Task 1.3, so .with(csrf()) is harmless belt-and-suspenders.
@AutoConfigureMockMvc
@WithMockUser
class ProjectApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `create project then bind repository`() {
        val projectJson = """{"code":"ORDER","name":"订单中心","description":"x"}"""
        val result = mockMvc.perform(
            post("/api/v1/projects").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(projectJson)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.code").value("ORDER"))
            .andReturn()
        val projectId = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.response.contentAsString)["data"]["id"].asLong()

        mockMvc.perform(
            post("/api/v1/projects/$projectId/repositories")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"order-api","gitUrl":"https://git.example.com/order.git","provider":"GITLAB","credential":"tok-123"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.gitUrl").value("https://git.example.com/order.git"))
    }
}
