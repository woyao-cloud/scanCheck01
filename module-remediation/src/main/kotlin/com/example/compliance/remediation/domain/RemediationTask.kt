package com.example.compliance.remediation.domain

import com.example.compliance.common.domain.BaseEntity
import com.example.compliance.result.domain.FindingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate

/** 整改任务：关联 finding，记录责任人/计划/期限。status 为冗余缓存列（P2-D4：权威=finding.status，同事务镜像写入，禁止第二权威）。 */
@Entity
@Table(name = "remediation_task")
class RemediationTask : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FindingStatus = FindingStatus.NEW
    @Column(name = "assignee_user_id")
    var assigneeUserId: Long? = null
    @Column(name = "plan")
    var plan: String? = null
    @Column(name = "due_date")
    var dueDate: LocalDate? = null
    @Column(name = "created_by")
    var createdBy: Long? = null
}
