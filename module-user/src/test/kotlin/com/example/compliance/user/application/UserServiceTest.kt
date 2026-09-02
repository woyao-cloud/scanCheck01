package com.example.compliance.user.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.user.domain.Role
import com.example.compliance.user.domain.User
import com.example.compliance.user.domain.UserRole
import com.example.compliance.user.infrastructure.RoleRepository
import com.example.compliance.user.infrastructure.UserRepository
import com.example.compliance.user.infrastructure.UserRoleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserServiceTest {
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val roleRepository = mockk<RoleRepository>(relaxed = true)
    private val userRoleRepository = mockk<UserRoleRepository>(relaxed = true)
    private val service = UserService(userRepository, roleRepository, userRoleRepository, BCryptPasswordEncoder())

    @Test
    fun `create user encodes password and assigns roles`() {
        every { userRepository.existsByUsername("alice") } returns false
        every { roleRepository.findByCode("ADMIN") } returns Role().apply { id = 7L; code = "ADMIN" }
        every { userRepository.save(any()) } answers { firstArg<User>().apply { id = 1L } }
        every { userRoleRepository.save(any()) } returnsArgument 0

        service.createUser(CreateUserCommand("alice", "secret", "Alice", "a@x.com", listOf("ADMIN")))

        verify { userRepository.save(match { it.passwordHash != "secret" }) }
        verify { userRoleRepository.save(any()) }
    }

    @Test
    fun `create user rejects duplicate username`() {
        every { userRepository.existsByUsername("alice") } returns true
        assertFailsWith<BusinessException> {
            service.createUser(CreateUserCommand("alice", "x", "A", null, emptyList()))
        }
    }

    @Test
    fun `findRoles maps user roles to role entities`() {
        val role = Role().apply { id = 7L; code = "ADMIN" }
        every { userRoleRepository.findByUserId(1L) } returns listOf(
            UserRole().apply { userId = 1L; roleId = 7L }
        )
        every { roleRepository.findAllById(listOf(7L)) } returns listOf(role)
        assertTrue(service.findRoles(1L).contains(role))
    }
}
