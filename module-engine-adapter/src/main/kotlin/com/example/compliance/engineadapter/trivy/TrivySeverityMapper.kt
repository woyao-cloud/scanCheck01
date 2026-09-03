package com.example.compliance.engineadapter.trivy

import org.springframework.stereotype.Component

/** Trivy 原生 severity（CRITICAL/HIGH/MEDIUM/LOW/UNKNOWN）→ 统一等级；UNKNOWN 兜底 LOW（与 SemgrepMapper else->LOW 一致）。 */
@Component
class TrivySeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "CRITICAL" -> "CRITICAL"
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "LOW"
    }
}
