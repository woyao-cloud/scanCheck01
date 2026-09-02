package com.example.compliance.project.infrastructure

import com.example.compliance.project.domain.Repository
import org.springframework.data.jpa.repository.JpaRepository

interface RepoRepository : JpaRepository<Repository, Long> {
    fun findByProjectId(projectId: Long): List<Repository>
}
