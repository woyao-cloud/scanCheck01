package com.example.compliance.project.infrastructure

import com.example.compliance.project.domain.Project
import org.springframework.data.jpa.repository.JpaRepository

interface ProjectRepository : JpaRepository<Project, Long> {
    fun findByCode(code: String): Project?
    fun existsByCode(code: String): Boolean
}
