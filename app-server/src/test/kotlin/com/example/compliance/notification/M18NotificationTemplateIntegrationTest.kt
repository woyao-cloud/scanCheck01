package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M18 模板 e2e：完整 SecurityConfig + 真实模板服务/渲染器 + 事件触发。数据前缀 M18TMP-*、M18NTF-*（跨类全局唯一）。
 *  共享容器安全（PL-M18-3）：自定义 SCAN_COMPLETED 模板只在「custom template」方法内 PUBLISHED，
 *  方法结束时 disable → 回落内置（内置文本 == V15 播种文本，M17Event 等对默认标题的断言在任何顺序下都成立）。 */
@AutoConfigureMockMvc
class M18NotificationTemplateIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var templateRepository: NotificationTemplateRepository
    @Autowired lateinit var versionRepository: NotificationTemplateVersionRepository
    @Autowired lateinit var projectRepository: ProjectRepository
    @Autowired lateinit var userRepository: UserRepository

    private fun ownerUser(username: String): Long =
        userRepository.save(User().apply { this.username = username; passwordHash = "x" }).id!!

    private fun project(code: String, ownerId: Long): Long =
        projectRepository.save(Project().apply { this.code = code; name = "m18"; ownerUserId = ownerId }).id!!

    @Test
    @WithMockUser(username = "m18tmp-admin", roles = ["ADMIN"])
    fun `published custom template drives rendering and disable falls back to built-in`() {
        val owner = ownerUser("m18tmp-owner1")
        val projectId = project("M18TMP1", owner)

        // 发布自定义 SCAN_COMPLETED 模板（draft → publish，v2）
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"m18 custom","content":{"title":"M18 custom","body":"任务 {scanTaskId} 完成状态 {status}"}}""")
        ).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(2))

        // 触发扫描完成事件 → IN_APP 行按自定义模板渲染
        publisher.publishEvent(ScanCompletedEvent(1001L, projectId, "SUCCESS"))
        val custom = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }.maxByOrNull { it.id!! }!!
        assertEquals("M18 custom", custom.title)
        assertEquals("任务 1001 完成状态 SUCCESS", custom.content)

        // disable → 再触发 → 内置回落（title/body = M17 逐字默认）
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/disable"))
            .andExpect(status().isOk)
        publisher.publishEvent(ScanCompletedEvent(1002L, projectId, "SUCCESS"))
        val fallback = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }.maxByOrNull { it.id!! }!!
        assertEquals("scan completed", fallback.title)
        assertEquals("扫描 1002 完成：SUCCESS", fallback.content)

        // 共享容器安全：结束时 SCAN_COMPLETED 无活跃 PUBLISHED（禁用态 → 后续测试走内置回落，默认文本不变）
        val templateId = templateRepository.findByTemplateType("SCAN_COMPLETED")!!.id!!
        assertTrue(versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId).none { it.status == TemplateStatus.PUBLISHED })
    }

    @Test
    fun `default content renders before any customization`() {
        val owner = ownerUser("m18tmp-owner2")
        val projectId = project("M18TMP2", owner)
        publisher.publishEvent(ScanCompletedEvent(1003L, projectId, "SUCCESS"))
        val row = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }.maxByOrNull { it.id!! }!!
        // 播种 PUBLISHED 或禁用回落 → 文本同为默认（内置 == 播种文本，本断言对两种状态都成立）
        assertEquals("scan completed", row.title)
        assertEquals("扫描 1003 完成：SUCCESS", row.content)
    }

    @Test
    fun `unauthenticated draft is 401`() {
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":{"title":"t","body":"b"}}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "m18tmp-dev", roles = ["DEVELOPER"])
    fun `non privileged role draft is 403`() {
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":{"title":"t","body":"b"}}""")
        ).andExpect(status().isForbidden)
    }
}
