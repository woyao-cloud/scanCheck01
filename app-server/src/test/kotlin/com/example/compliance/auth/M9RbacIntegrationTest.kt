package com.example.compliance.auth

import com.example.compliance.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M9 RBAC 矩阵（spec §6.1）：@EnableMethodSecurity + 路径角色规则。
 *  Token 管理读写仅 ADMIN：匿名 401、非 ADMIN 403、ADMIN 200。
 *  整改写操作按状态转换配角色：普通 USER 被方法级 @PreAuthorize 拦在服务之外（403）。
 *  无 DB 数据（token 表在共享容器内为空；GET list 为读操作；@PreAuthorize 403 在服务调用前触发）。 */
@AutoConfigureMockMvc
class M9RbacIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

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
}
