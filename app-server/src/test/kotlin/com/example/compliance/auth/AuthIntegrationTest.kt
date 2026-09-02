package com.example.compliance.auth

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.user.application.CreateUserCommand
import com.example.compliance.user.application.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class AuthIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userService: UserService

    @BeforeEach
    fun seedUser() {
        if (userService.findByUsername("dave") == null) {
            userService.createUser(CreateUserCommand("dave", "password1", "Dave", null, emptyList()))
        }
    }

    @Test
    fun `login returns token and me requires auth`() {
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"dave","password":"password1"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.token").isNotEmpty)

        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized)
    }
}
