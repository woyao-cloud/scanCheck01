package com.example.compliance.scan.checkout

/** 检出结果：workDir 为可扫描的本地目录；commitId 为检出 commit（本地/STUB 跳过 clone 时为 null）。 */
data class CheckoutResult(val workDir: String, val commitId: String?)

/** 引擎无关的代码检出（spec §5.2）：编排器负责调用，adapter 只消费 ScanContext.workDir。 */
interface GitCheckout {
    fun checkout(repoUrl: String, ref: String?): CheckoutResult
    fun cleanup(workDir: String)
}
