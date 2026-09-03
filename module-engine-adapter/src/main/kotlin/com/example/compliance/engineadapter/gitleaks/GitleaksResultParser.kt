package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** gitleaks JSON 报告解析（spec §5.2）：顶层 leak 数组 → RawFinding（保留原生 severity，映射在 normalize）。 */
@Component
class GitleaksResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(report: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(report) }.getOrNull()
            ?: return emptyList()
        if (!root.isArray) return emptyList()
        return root.mapNotNull { node ->
            val ruleId = node.path("RuleID").asText("")
            val file = node.path("File").asText("")
            if (ruleId.isEmpty() || file.isEmpty()) return@mapNotNull null
            RawFinding(
                engineRuleId = ruleId,
                ruleName = null,
                filePath = file,
                line = node.path("StartLine").takeIf { !it.isMissingNode && it.canConvertToInt() }?.asInt(),
                severity = node.path("Severity").asText("MEDIUM"),
                message = node.path("Description").takeIf { !it.isMissingNode }?.asText(),
                codeSnippet = node.path("Match").takeIf { !it.isMissingNode && it.asText().isNotBlank() }?.asText()
                    ?: node.path("Secret").takeIf { !it.isMissingNode }?.asText(),
                category = null,
            )
        }
    }
}
