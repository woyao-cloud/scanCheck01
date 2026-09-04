package com.example.compliance.engineadapter.detekt

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** Detekt SARIF 解析（spec §5.2）：runs[].results[] → 代码类 RawFinding（无依赖字段，恒 null）。
 *  engineRuleId = ruleId；filePath = locations[0].physicalLocation.artifactLocation.uri；
 *  line = region.startLine；severity 原生透传（error/warning/note）；message = message.text；
 *  category = ruleId 点前缀段。 */
@Component
class DetektResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(sarif: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(sarif) }.getOrNull() ?: return emptyList()
        val runs = root.path("runs")
        if (!runs.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        for (run in runs) {
            val results = run.path("results")
            if (!results.isArray) continue
            for (r in results) {
                val ruleId = r.path("ruleId").asText("")
                if (ruleId.isEmpty()) continue
                val loc = r.path("locations").takeIf { it.isArray && !it.isEmpty }?.get(0)
                    ?.path("physicalLocation")
                out += RawFinding(
                    engineRuleId = ruleId,
                    ruleName = null,
                    filePath = loc?.path("artifactLocation")?.path("uri")?.asText("") ?: "",
                    line = loc?.path("region")?.path("startLine")?.takeIf { it.isNumber }?.asInt(),
                    severity = r.path("level").asText("warning"),
                    message = r.path("message").path("text").takeIf { !it.isMissingNode }?.asText(),
                    codeSnippet = null,
                    category = ruleId.substringBefore(".").ifBlank { null },
                )
            }
        }
        return out
    }
}
