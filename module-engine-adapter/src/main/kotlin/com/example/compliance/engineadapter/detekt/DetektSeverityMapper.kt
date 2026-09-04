package com.example.compliance.engineadapter.detekt

import org.springframework.stereotype.Component

/** Detekt 原生 severity（error/warning/info/note）→ 统一等级（镜像 SemgrepSeverityMapper；spec §5.2 代码类）。 */
@Component
class DetektSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "ERROR" -> "HIGH"
        "WARNING" -> "MEDIUM"
        "INFO" -> "LOW"
        else -> "LOW"
    }
}
