package com.example.compliance.user.infrastructure

import com.example.compliance.user.domain.Role
import org.springframework.data.jpa.repository.JpaRepository

interface RoleRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?
}
