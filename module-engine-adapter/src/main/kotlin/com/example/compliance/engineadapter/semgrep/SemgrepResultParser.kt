package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SemgrepResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(stdout: String): List<RawFinding> {
        val root = objectMapper.readTree(stdout)
        // F1 (final review C1) 兜底：semgrep 配置/规则错误输出 {"errors":[...],"results":[]}，
        // 若不拦截会被解析成 0 finding 的「干净扫描」（exit>=2 已由 SemgrepCli 主检，此处双保险）。
        val errors = root.path("errors")
        if (errors.isArray && !errors.isEmpty) {
            throw IllegalArgumentException("semgrep reported errors: $errors")
        }
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
