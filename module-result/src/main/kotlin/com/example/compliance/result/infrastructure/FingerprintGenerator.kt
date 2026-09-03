package com.example.compliance.result.infrastructure

import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class FingerprintGenerator {
    fun generate(projectId: Long, ruleCode: String, filePath: String, lineNumber: Int?, codeSnippet: String?): String {
        val normalized = listOf(
            projectId.toString(),
            ruleCode,
            filePath,
            (lineNumber ?: -1).toString(),
            (codeSnippet ?: "").trim(),
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 依赖类指纹（plan.md §7.3 字面）：sha256(projectId|packageName|packageVersion|cveId)。
     *  M11：Trivy 依赖漏洞首次落地 —— 与代码类指纹输入不同，哈希空间不冲突。 */
    fun generateDependency(projectId: Long, packageName: String, packageVersion: String?, cveId: String): String {
        val normalized = listOf(
            projectId.toString(),
            packageName,
            packageVersion ?: "",
            cveId,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
