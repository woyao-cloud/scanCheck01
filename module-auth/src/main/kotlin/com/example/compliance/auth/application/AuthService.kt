package com.example.compliance.auth.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.user.application.UserService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val passwordEncoder: PasswordEncoder,
) {
    fun login(username: String, password: String): String {
        val user = userService.findByUsername(username)
            ?: throw BusinessException(401, "invalid username or password")
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw BusinessException(401, "invalid username or password")
        }
        val roles = userService.findRoles(user.id!!).map { "ROLE_" + it.code }
        return jwtService.issue(user.id!!, user.username, roles)
    }
}
