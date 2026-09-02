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
}
