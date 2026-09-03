package com.example.compliance.engineadapter.trivy

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** trivy fs JSON 解析（spec §6.2）：Results[].Vulnerabilities[] → 依赖类 RawFinding（保留原生 severity）。
 *  engineRuleId = VulnerabilityID（CVE，plan.md `trivy.CVE-XXXX` 粒度）；filePath = Target（锁文件路径）。 */
@Component
class TrivyResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(stdout: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(stdout) }.getOrNull()
            ?: return emptyList()
        val results = root.path("Results")
        if (!results.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        results.forEach { result ->
            val target = result.path("Target").asText("")
            val vulns = result.path("Vulnerabilities")
            if (!vulns.isArray) return@forEach     // 非漏洞 Result（Class 非 os/library）跳过
            vulns.forEach { v ->
                val cve = v.path("VulnerabilityID").asText("")
                if (cve.isEmpty()) return@forEach
                out += RawFinding(
                    engineRuleId = cve,
                    ruleName = null,
                    filePath = target,
                    line = null,
                    severity = v.path("Severity").asText("UNKNOWN"),
                    message = v.path("Title").takeIf { !it.isMissingNode && it.asText().isNotBlank() }?.asText()
                        ?: v.path("Description").takeIf { !it.isMissingNode }?.asText(),
                    codeSnippet = null,
                    category = null,
                    packageName = v.path("PkgName").takeIf { !it.isMissingNode }?.asText(),
                    packageVersion = v.path("InstalledVersion").takeIf { !it.isMissingNode }?.asText(),
                    fixedVersion = v.path("FixedVersion").takeIf { !it.isMissingNode }?.asText(),
                    cveId = cve,
                    cvssScore = cvssScoreOf(v.path("CVSS")),
                )
            }
        }
        return out
    }

    /** 取分规则（spec §6.2）：优先 nvd.V3Score → nvd.V2Score → 各 vendor 最高分；均无 → null。 */
    private fun cvssScoreOf(cvss: com.fasterxml.jackson.databind.JsonNode): Double? {
        if (cvss.isMissingNode || !cvss.isObject) return null
        val nvd = cvss.path("nvd")
        if (nvd.isObject) {
            nvd.path("V3Score").takeIf { it.isNumber }?.let { return it.asDouble() }
            nvd.path("V2Score").takeIf { it.isNumber }?.let { return it.asDouble() }
        }
        return cvss.fields().asSequence()
            .mapNotNull { (_, v) ->
                v.path("V3Score").takeIf { it.isNumber }?.asDouble()
                    ?: v.path("V2Score").takeIf { it.isNumber }?.asDouble()
            }
            .maxOrNull()
    }
}
