package com.example.compliance.user.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.user.domain.Role
import com.example.compliance.user.domain.User
import com.example.compliance.user.domain.UserRole
import com.example.compliance.user.infrastructure.RoleRepository
import com.example.compliance.user.infrastructure.UserRepository
import com.example.compliance.user.infrastructure.UserRoleRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val userRoleRepository: UserRoleRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    fun findByUsername(username: String): User? = userRepository.findByUsername(username)

    @Transactional
    fun createUser(command: CreateUserCommand): User {
        if (userRepository.existsByUsername(command.username)) {
            throw BusinessException(400, "username already exists: ${command.username}")
        }
        val user = userRepository.save(
            User().apply {
                username = command.username
                passwordHash = passwordEncoder.encode(command.password)
                displayName = command.displayName
                email = command.email
            }
        )
        command.roleCodes.forEach { code ->
            val role = roleRepository.findByCode(code)
                ?: throw BusinessException(400, "role not found: $code")
            userRoleRepository.save(UserRole().apply { userId = user.id!!; roleId = role.id!! })
        }
        return user
    }

    fun findRoles(userId: Long): List<Role> {
        val roleIds = userRoleRepository.findByUserId(userId).map { it.roleId }
        return roleRepository.findAllById(roleIds)
    }

    fun page(pageable: Pageable): Page<User> = userRepository.findAll(pageable)
}
