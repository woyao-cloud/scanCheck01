package com.example.compliance.user.infrastructure

import com.example.compliance.user.domain.UserRole
import org.springframework.data.jpa.repository.JpaRepository

interface UserRoleRepository : JpaRepository<UserRole, Long> {
    fun findByUserId(userId: Long): List<UserRole>
}
