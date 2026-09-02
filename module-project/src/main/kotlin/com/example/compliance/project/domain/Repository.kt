package com.example.compliance.project.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "repo_info")
class Repository : BaseEntity() {
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0

    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String

    @Column(name = "git_url", nullable = false, length = 512)
    lateinit var gitUrl: String

    @Column(name = "provider", nullable = false, length = 32)
    lateinit var provider: String

    @Column(name = "default_branch", length = 128)
    var defaultBranch: String = "main"

    @Column(name = "credential_ref", length = 256)
    var credentialRef: String? = null

    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
}
