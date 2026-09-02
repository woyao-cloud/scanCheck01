package com.example.compliance.engineadapter.semgrep

import org.springframework.stereotype.Component

interface SemgrepCli {
    fun run(targetPath: String, ref: String?): String
}

@Component
class ProcessSemgrepCli : SemgrepCli {
    override fun run(targetPath: String, ref: String?): String {
        val cmd = mutableListOf("semgrep", "--json", "--no-rewrite-rule-ids")
        ref?.let { cmd += listOf("--baseline-commit", it) }
        cmd += targetPath
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        // P0 说明：semgrep 在发现违规时返回非 0 退出码，这里不抛错，交由 parser 解析 stdout；
        // 进程本身启动失败（引擎未安装）时 readBytes 会抛 IOException，由 Orchestrator 捕获记为 FAILED。
        return output
    }
}
