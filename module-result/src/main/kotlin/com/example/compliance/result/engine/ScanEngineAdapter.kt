package com.example.compliance.result.engine

/** 扫描引擎统一端口：每个引擎一个实现，不得绕过 adapter 直接调用引擎。 */
interface ScanEngineAdapter {
    val engine: String
    fun scan(context: ScanContext): ScanResult
}

data class ScanContext(
    val scanTaskId: Long,
    val projectId: Long,
    val repoUrl: String,
    val ref: String? = null,
    val configJson: String? = null,
)

/** 引擎原生结果，severity 已归一化为 LOW/MEDIUM/HIGH/CRITICAL。 */
data class RawFinding(
    val engineRuleId: String,
    val ruleName: String? = null,
    val filePath: String,
    val line: Int? = null,
    val severity: String,
    val message: String? = null,
    val codeSnippet: String? = null,
    val category: String? = null,
)

data class ScanResult(
    val findings: List<RawFinding>,
    val durationMs: Long = 0,
    val success: Boolean = true,
    val errorMessage: String? = null,
)
