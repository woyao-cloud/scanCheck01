package com.example.compliance.engineadapter.dependencycheck

import org.springframework.stereotype.Component

/** Dependency-Check 原生 severity（spec §5.1：HIGH/MEDIUM/LOW 直通，else→MEDIUM）。
 *  注意：DC 的 CRITICAL（若本机库报告）落入 else→MEDIUM——spec 明文仅三档直通（R-M14-1 携终审留意）。 */
@Component
class DependencyCheckSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "MEDIUM"
    }
}
