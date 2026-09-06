package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M17 站内信中心 e2e：真实 SecurityConfig + @PreAuthorize + 当前用户过滤。
 *  数据：recipient "1"（m17-user String principal → userId 1L），标题 NTF-M17-*（module 标记，全局唯一）。 */
@AutoConfigureMockMvc
class M17NotificationCenterIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var repository: NotificationRepository

    private fun seed(recipient: String, title: String, readAt: Instant? = null) =
        repository.save(Notification().apply {
            channel = Channel.IN_APP.name; this.recipient = recipient; type = "SCAN_COMPLETED"
            this.title = title; content = "body"; status = "SENT"; sentAt = Instant.now(); this.readAt = readAt
        })

    // 每测试方法隔离（数据污染实证修正）：本类 3 个写测试共用 recipient "1"（m17-user → 1L）且 total/count
    // 为绝对断言 —— 共享容器内前序测试残留行会让 total==2 / read-all count==2 失真（实证：total 2→3）。
    // 仅清本类自有收件人（"1"/"2"），不触碰 delivery/event/M10 等他类行。
    @BeforeEach
    fun cleanSlate() {
        repository.findAll().filter { it.recipient == "1" || it.recipient == "2" }.forEach { repository.delete(it) }
    }

    @Test
    fun `current user lists own notifications and counts unread`() {
        seed("1", "NTF-M17-LIST-READ", Instant.now())
        seed("1", "NTF-M17-LIST-UNREAD")
        seed("2", "NTF-M17-LIST-OTHER")   // 他人行不可见

        mockMvc.perform(get("/api/v1/notifications").with(user("m17-user")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].title").value("NTF-M17-LIST-UNREAD"))   // id DESC 最新在前

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user("m17-user")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(1))
    }

    @Test
    fun `read marks own notification read and foreign read is 404`() {
        val mine = seed("1", "NTF-M17-READ-MINE")
        val theirs = seed("2", "NTF-M17-READ-THEIRS")

        mockMvc.perform(post("/api/v1/notifications/${theirs.id}/read").with(user("m17-user")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))

        mockMvc.perform(post("/api/v1/notifications/${mine.id}/read").with(user("m17-user")))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/notifications/${mine.id}/read").with(user("m17-user")))   // 幂等
            .andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user("m17-user")))
            .andExpect(jsonPath("$.data.count").value(0))
    }

    @Test
    fun `read-all marks all own unread read`() {
        seed("1", "NTF-M17-RA-A")
        seed("1", "NTF-M17-RA-B")
        mockMvc.perform(post("/api/v1/notifications/read-all").with(user("m17-user")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(2))
        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user("m17-user")))
            .andExpect(jsonPath("$.data.count").value(0))
    }

    @Test
    fun `unauthenticated access is 401`() {
        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized)
    }
}
