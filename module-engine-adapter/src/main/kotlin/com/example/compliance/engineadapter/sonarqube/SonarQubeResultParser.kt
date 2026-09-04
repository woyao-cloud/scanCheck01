package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** SonarQube issue JSON 解析（spec §3.2，R-M15-D10）：issues[] → 代码类 RawFinding（无依赖字段，恒 null）。
 *  engineRuleId = rule；filePath = component 去 "projectKey:" 前缀（substringAfter 首个冒号）；
 *  line = line；severity 原生透传（BLOCKER/CRITICAL/MAJOR/MINOR/INFO）；message = message；
 *  category = type（BUG/VULNERABILITY/CODE_SMELL，信息性字符串，无枚举约束）。 */
@Component
class SonarQubeResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(issuesJson: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(issuesJson) }.getOrNull() ?: return emptyList()
        val issues = root.path("issues")
        if (!issues.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        for (issue in issues) {
            val rule = issue.path("rule").asText("")
            if (rule.isEmpty()) continue
            out += RawFinding(
                engineRuleId = rule,
                ruleName = null,
                filePath = issue.path("component").asText("").substringAfter(":"),
                line = issue.path("line").takeIf { it.isNumber }?.asInt(),
                severity = issue.path("severity").asText("INFO"),
                message = issue.path("message").takeIf { !it.isMissingNode }?.asText(),
                codeSnippet = null,
                category = issue.path("type").takeIf { !it.isMissingNode }?.asText(),
            )
        }
        return out
    }
}
