package com.example.compliance.engineadapter.gitleaks

import org.springframework.stereotype.Component

/** Gitleaks 原生 severity（HIGH/MEDIUM/LOW；旧版无 Severity 字段 → 缺省 MEDIUM）→ 统一等级。 */
@Component
class GitleaksSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "MEDIUM"
    }
}
