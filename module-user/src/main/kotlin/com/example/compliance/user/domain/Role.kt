package com.example.compliance.user.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "sys_role")
class Role : BaseEntity() {
    @Column(name = "code", nullable = false, unique = true, length = 64)
    lateinit var code: String

    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String

    @Column(name = "description", length = 256)
    var description: String? = null
}
