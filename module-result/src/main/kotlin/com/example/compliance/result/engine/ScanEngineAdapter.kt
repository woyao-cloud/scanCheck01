package com.example.compliance.result.engine

/** 扫描引擎统一端口（spec §5.1，P2-D8）：五方法契约全部带默认实现，现有实现零改动兼容。 */
interface ScanEngineAdapter {
    val engine: String

    fun supports(engineType: String): Boolean = engineType.equals(engine, ignoreCase = true)

    fun prepareScan(context: ScanContext) {}
    fun executeScan(context: ScanContext): ScanExecutionResult = ScanExecutionResult(success = true)
    fun collectResult(context: ScanContext): List<RawFinding> = emptyList()
    fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
    fun cleanup(context: ScanContext) {}

    /** 兼容默认方法：跑五阶段管线并聚合为旧 ScanResult（冻结 STUB 测试 override scan() 时零改动；编排器 M8 直接调用五阶段）。 */
    fun scan(context: ScanContext): ScanResult {
        prepareScan(context)
        try {
            val execution = executeScan(context)
            val raw = collectResult(context)
            val normalized = normalizeResult(context, raw)
            return ScanResult(normalized, success = execution.success, errorMessage = execution.errorMessage, durationMs = execution.durationMs ?: 0)
        } finally {
            cleanup(context)
        }
    }
}

/** 引擎执行结果；stdoutRef 指向引擎原始输出落盘位置（collectResult 读取，spec §5.1）。 */
data class ScanExecutionResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
    val stdoutRef: String? = null,
)

data class ScanContext(
    val scanTaskId: Long,
    val projectId: Long,
    val repoUrl: String,
    val ref: String? = null,
    val workDir: String? = null,        // 编排器检出的本地目录（§5.2），SemgrepAdapter 优先作为扫描目标
    val commitId: String? = null,
    val timeoutSeconds: Long? = null,
    val paramsJson: String? = null,     // rule_engine_binding.parameters
    val configJson: String? = null,     // 兼容保留
    // M15 (R-M15-D2)：编排器解密 Repository.credentialRef 注入；SonarQube 用作 SONAR_TOKEN。追加尾部默认 → 既有位置调用点零破坏。
    val credentialToken: String? = null,
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
    // M11 依赖类字段（Trivy 使用；代码类引擎恒为 null）。追加在末尾带默认值 → 既有位置调用点零破坏。
    val packageName: String? = null,
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)

data class ScanResult(
    val findings: List<RawFinding>,
    val durationMs: Long = 0,
    val success: Boolean = true,
    val errorMessage: String? = null,
)
