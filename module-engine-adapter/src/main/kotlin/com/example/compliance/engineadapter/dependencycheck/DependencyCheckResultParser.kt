package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** dependency-check-report.json 解析（spec §5.1）：dependencies[].vulnerabilities[] → 依赖类 RawFinding。
 *  engineRuleId = cveId = vulnerability.name（DC JSON 中 CVE 位于 vulnerabilities[].name）；
 *  filePath = 依赖 filePath（缺省 target 根，保持 NOT NULL）；packageName/packageVersion 自 packages[].id
 *  （pkg:type/group:artifact@version）推断，无则 fileName 兜底（P3-D8 both-or-neither 保证）；
 *  fixedVersion 恒 null（DC 无修复版本字段）；cvssScore = CVSSv3.baseScore 或 CVSSv2.score。 */
@Component
class DependencyCheckResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(report: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(report) }.getOrNull() ?: return emptyList()
        val deps = root.path("dependencies")
        if (!deps.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        for (dep in deps) {
            val filePath = dep.path("filePath").takeIf { !it.isMissingNode }?.asText() ?: continue
            val packageName = packageNameOf(dep) ?: filePath     // 兜底保非空（P3-D8）
            val packageVersion = packageVersionOf(dep)
            val vulns = dep.path("vulnerabilities")
            if (!vulns.isArray) continue
            for (v in vulns) {
                val cve = v.path("name").asText("")
                if (cve.isEmpty()) continue
                out += RawFinding(
                    engineRuleId = cve,
                    ruleName = null,
                    filePath = filePath,
                    line = null,
                    severity = v.path("severity").asText("UNKNOWN"),
                    message = v.path("description").takeIf { !it.isMissingNode }?.asText(),
                    codeSnippet = null,
                    category = null,
                    packageName = packageName,
                    packageVersion = packageVersion,
                    fixedVersion = null,
                    cveId = cve,
                    cvssScore = cvssScoreOf(v),
                )
            }
        }
        return out
    }

    /** packages[].id（pkg:type/group:artifact@version）→ artifact；无则 fileName。 */
    private fun packageNameOf(dep: JsonNode): String? {
        dep.path("packages").takeIf { it.isArray && !it.isEmpty }?.let { pkgs ->
            val id = pkgs.first().path("id").asText("")
            if (id.isNotBlank()) {
                val artifact = id.substringAfterLast("/").substringBeforeLast("@")
                if (artifact.isNotBlank()) return artifact
            }
        }
        return dep.path("fileName").takeIf { !it.isMissingNode && it.asText().isNotBlank() }?.asText()
    }

    private fun packageVersionOf(dep: JsonNode): String? {
        dep.path("packages").takeIf { it.isArray && !it.isEmpty }?.let { pkgs ->
            val id = pkgs.first().path("id").asText("")
            if (id.isNotBlank()) return id.substringAfterLast("@").ifBlank { null }
        }
        return null
    }

    private fun cvssScoreOf(v: JsonNode): Double? {
        v.path("cvssv3").path("baseScore").takeIf { it.isNumber }?.let { return it.asDouble() }
        v.path("cvssv2").path("score").takeIf { it.isNumber }?.let { return it.asDouble() }
        return null
    }
}
