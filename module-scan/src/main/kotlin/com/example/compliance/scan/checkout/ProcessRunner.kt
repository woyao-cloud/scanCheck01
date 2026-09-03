package com.example.compliance.scan.checkout

data class ProcessOutput(val exitCode: Int, val stdout: String)

/** 进程执行抽象：真实实现走 ProcessBuilder；测试可 mock 模拟 git 输出。 */
interface ProcessRunner {
    fun run(command: List<String>, dir: String? = null): ProcessOutput
}
