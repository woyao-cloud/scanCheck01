package com.example.compliance.engineadapter.semgrep

import org.springframework.stereotype.Component

/** Semgrep 原生 severity（ERROR/WARNING/INFO）→ 统一 LOW/MEDIUM/HIGH/CRITICAL。 */
@Component
class SemgrepSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "ERROR" -> "HIGH"
        "WARNING" -> "MEDIUM"
        "INFO" -> "LOW"
        else -> "LOW"
    }
}
