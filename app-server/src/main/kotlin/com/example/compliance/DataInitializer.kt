package com.example.compliance

import com.example.compliance.user.application.CreateUserCommand
import com.example.compliance.user.application.UserService
import com.example.compliance.user.domain.Role
import com.example.compliance.user.infrastructure.RoleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class DataInitializer(
    private val roleRepository: RoleRepository,
    private val userService: UserService,
) : CommandLineRunner {
    private val log = LoggerFactory.getLogger(DataInitializer::class.java)

    override fun run(vararg args: String) {
        val roles = listOf("ADMIN", "COMPLIANCE_MANAGER", "PROJECT_OWNER", "DEVELOPER", "AUDITOR")
        if (roleRepository.count() == 0L) {
            roles.forEach { roleRepository.save(Role().apply { code = it; name = it }) }
            log.info("Seeded {} roles", roles.size)
        }
        if (userService.findByUsername("admin") == null) {
            userService.createUser(CreateUserCommand("admin", "admin123", "Platform Admin", null, listOf("ADMIN")))
            log.info("Seeded admin user")
        }
    }
}
