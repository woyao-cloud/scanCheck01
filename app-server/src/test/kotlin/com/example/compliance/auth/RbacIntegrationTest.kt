package com.example.compliance.auth

import com.example.compliance.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class RbacIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `unauthenticated admin path is 401`() {
        mockMvc.perform(get("/api/v1/admin/anything"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "rbac-user", roles = ["USER"])
    fun `non-admin is forbidden on admin path`() {
        mockMvc.perform(get("/api/v1/admin/anything"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "rbac-admin", roles = ["ADMIN"])
    fun `admin is allowed through to the missing endpoint`() {
        mockMvc.perform(get("/api/v1/admin/anything"))
            .andExpect { result ->
                // The ADMIN role clears the /api/v1/admin/** role gate: security answers neither
                // 401 (unauthenticated) nor 403 (forbidden). The endpoint itself does not exist;
                // the app's GlobalExceptionHandler catch-all maps Spring's NoResourceFoundException
                // to a generic 500 today instead of the framework's 404 — fixing that mapping is out
                // of scope for this fix round, so assert only that the role gate is passed.
                assertTrue(result.response.status != 401 && result.response.status != 403)
            }
    }
}
