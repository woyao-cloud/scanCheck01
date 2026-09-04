package com.example.compliance.engineadapter.sonarqube

import org.springframework.stereotype.Component

/** SonarQube 原生 severity（BLOCKER/CRITICAL/MAJOR/MINOR/INFO）→ 统一等级（R-M15-D6；镜像既有 mapper）。 */
@Component
class SonarQubeSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "BLOCKER" -> "CRITICAL"
        "CRITICAL" -> "HIGH"
        "MAJOR" -> "MEDIUM"
        "MINOR" -> "LOW"
        "INFO" -> "LOW"
        else -> "LOW"
    }
}
