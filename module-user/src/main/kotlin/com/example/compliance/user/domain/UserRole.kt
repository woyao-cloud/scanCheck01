package com.example.compliance.user.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "sys_user_role")
class UserRole : BaseEntity() {
    @Column(name = "user_id", nullable = false)
    var userId: Long = 0

    @Column(name = "role_id", nullable = false)
    var roleId: Long = 0
}
