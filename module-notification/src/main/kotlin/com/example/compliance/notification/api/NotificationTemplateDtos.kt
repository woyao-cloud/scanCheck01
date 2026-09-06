package com.example.compliance.notification.api

import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.constraints.NotNull

data class DraftRequest(
    val name: String? = null,
    @field:NotNull
    val content: JsonNode? = null,
)

data class TemplateVersionView(
    val templateId: Long,
    val versionNo: Int,
    val status: String,
    val content: JsonNode,
) {
    companion object {
        private val mapper = ObjectMapper()
        fun from(v: NotificationTemplateVersion) = TemplateVersionView(v.templateId, v.versionNo, v.status.name, mapper.readTree(v.content))
    }
}
