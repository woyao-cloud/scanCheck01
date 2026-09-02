package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SemgrepResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(stdout: String): List<RawFinding> {
        val root = objectMapper.readTree(stdout)
        val results = root.path("results")
        return results.map { node ->
            RawFinding(
                engineRuleId = node.path("check_id").asText(""),
                ruleName = null,
                filePath = node.path("path").asText(""),
                line = node.path("start").path("line").takeIf { !it.isMissingNode }?.asInt(),
                severity = node.path("extra").path("severity").asText("INFO"),
                message = node.path("extra").path("message").takeIf { !it.isMissingNode }?.asText(),
                codeSnippet = node.path("extra").path("lines").takeIf { !it.isMissingNode }?.asText(),
                category = null,
            )
        }
    }
}
