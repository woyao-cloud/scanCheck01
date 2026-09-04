package com.example.compliance.engineadapter.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** M15 (R-M15-D3)：CliExecutor 新能力 env 需锚定 —— 子进程必须读到环境变量（token 不进 argv 的前提）。 */
class CliExecutorTest {

    @Test
    fun `env map is passed to child process`() {
        val os = System.getProperty("os.name").lowercase()
        // Windows: cmd 解析 %VAR%；Unix: sh 解析 $VAR。环境未传递时输出字面量 → 断言失败（真实回归检测）。
        val command = if (os.contains("win")) {
            listOf("cmd", "/c", "echo %M15_CLI_TEST_ENV%")
        } else {
            listOf("sh", "-c", "echo \"\$M15_CLI_TEST_ENV\"")
        }
        val out = CliExecutor(10).run(
            command = command,
            label = "env-probe",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0),
                env = mapOf("M15_CLI_TEST_ENV" to "hello"),
            ),
        )
        assertTrue(out.trim().contains("hello"), "child should see M15_CLI_TEST_ENV=hello, got: $out")
    }
}
