package com.example.compliance.user

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.user.domain.Role
import com.example.compliance.user.domain.User
import com.example.compliance.user.domain.UserRole
import com.example.compliance.user.infrastructure.RoleRepository
import com.example.compliance.user.infrastructure.UserRepository
import com.example.compliance.user.infrastructure.UserRoleRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UserRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roleRepository: RoleRepository
    @Autowired lateinit var userRoleRepository: UserRoleRepository

    @Test
    fun `save and find user by username`() {
        val saved = userRepository.save(User().apply {
            username = "alice"; passwordHash = "hash"; displayName = "Alice"
        })
        assertNotNull(saved.id)
        assertEquals("alice", userRepository.findByUsername("alice")?.username)
    }

    @Test
    fun `role and user-role mapping persist`() {
        val role = roleRepository.save(Role().apply { code = "ADMIN"; name = "管理员" })
        val user = userRepository.save(User().apply { username = "bob"; passwordHash = "h" })
        userRoleRepository.save(UserRole().apply { userId = user.id!!; roleId = role.id!! })
        assertEquals(listOf(role.id), userRoleRepository.findByUserId(user.id!!).map { it.roleId })
    }
}
