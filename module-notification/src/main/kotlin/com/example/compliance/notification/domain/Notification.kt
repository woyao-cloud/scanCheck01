package com.example.compliance.notification.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/** 通知记录：channel EMAIL/WEBHOOK，status PENDING/SENT/FAILED。M9 先落库并标记 SENT（桩）。 */
@Entity
@Table(name = "notification")
class Notification : BaseEntity() {
    @Column(name = "channel", nullable = false, length = 16)
    var channel: String = ""
    @Column(name = "recipient", nullable = false, length = 128)
    var recipient: String = ""
    @Column(name = "type", nullable = false, length = 32)
    var type: String = ""
    @Column(name = "title", nullable = false, length = 200)
    var title: String = ""
    @Column(name = "content")
    var content: String? = null
    @Column(name = "status", nullable = false, length = 16)
    var status: String = "PENDING"
    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0
    @Column(name = "sent_at")
    var sentAt: Instant? = null
}
