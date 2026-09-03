package com.example.compliance.remediation.infrastructure

import com.example.compliance.remediation.domain.RemediationTask
import org.springframework.data.jpa.repository.JpaRepository

interface RemediationTaskRepository : JpaRepository<RemediationTask, Long> {
    fun findByFindingId(findingId: Long): RemediationTask?
    fun findByProjectId(projectId: Long): List<RemediationTask>
    fun findByAssigneeUserId(assigneeUserId: Long): List<RemediationTask>
}
