package com.example.compliance.user

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

// Ruling #22: module-common exposes spring-boot-starter-security, so Spring Security is on the
// classpath but no SecurityConfig exists until Task 1.3. Spring Boot's default chain then secures
// ALL endpoints AND leaves CSRF enabled → without @WithMockUser + csrf(), POST /api/v1/users would
// return 403/401 instead of 200/400. @WithMockUser still works after Task 1.3 (JwtAuthenticationFilter
// no-ops without a Bearer header; CSRF is disabled globally there, so .with(csrf()) is harmless).
@AutoConfigureMockMvc
@WithMockUser
class UserApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `create user returns id and validation rejects blank username`() {
        mockMvc.perform(
            post("/api/v1/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"carol","password":"secret1","displayName":"Carol"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").exists())

        mockMvc.perform(
            post("/api/v1/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"","password":"secret1"}""")
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
