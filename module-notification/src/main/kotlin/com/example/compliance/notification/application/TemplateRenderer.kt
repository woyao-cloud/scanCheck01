package com.example.compliance.notification.application

import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class TemplateRenderer(
    private val templateRepository: NotificationTemplateRepository,
    private val versionRepository: NotificationTemplateVersionRepository,
) {
    private val objectMapper = ObjectMapper()

    /** 解析 type 的 PUBLISHED 模板渲染 title/body；无 PUBLISHED/解析失败 → 内置回落。绝不抛出（R-M18-7）。 */
    fun render(notificationType: String, variables: Map<String, Any?>): Pair<String, String> {
        // 一次性解析 PUBLISHED 版本（title/body 同一版），避免双次仓库查询
        val published = templateRepository.findByTemplateType(notificationType)
            ?.let { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(it.id!!, TemplateStatus.PUBLISHED) }
        val resolved: Pair<String, String>? = published?.let { v ->
            runCatching {
                val node = objectMapper.readTree(v.content)
                node["title"].asText() to node["body"].asText()
            }.getOrNull()
        }
        val (title, body) = resolved
            ?: (DefaultNotificationTemplates.TITLES[notificationType] ?: notificationType) to
               (DefaultNotificationTemplates.BODIES[notificationType] ?: notificationType)
        return replace(title, variables) to replace(body, variables)
    }

    private fun replace(text: String, variables: Map<String, Any?>): String =
        variables.entries.fold(text) { acc, (k, v) -> acc.replace("{$k}", v?.toString() ?: "") }
}
