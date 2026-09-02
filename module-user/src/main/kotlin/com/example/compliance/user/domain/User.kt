package com.example.compliance.user.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "sys_user")
class User : BaseEntity() {
    @Column(name = "username", nullable = false, unique = true, length = 64)
    lateinit var username: String

    @Column(name = "password_hash", nullable = false, length = 128)
    lateinit var passwordHash: String

    @Column(name = "display_name", length = 128)
    var displayName: String? = null

    @Column(name = "email", length = 128)
    var email: String? = null

    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
}
