package com.example.compliance.project.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "org_project")
class Project : BaseEntity() {
    @Column(name = "code", nullable = false, unique = true, length = 64)
    lateinit var code: String

    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String

    @Column(name = "description")
    var description: String? = null

    @Column(name = "owner_user_id")
    var ownerUserId: Long? = null

    @Column(name = "status", nullable = false, length = 16)
    var status: String = ProjectStatus.ACTIVE.name
}
