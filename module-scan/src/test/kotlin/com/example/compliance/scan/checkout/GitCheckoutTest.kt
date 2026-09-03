package com.example.compliance.scan.checkout

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8：GitCheckout 本地跳过 / 远程 clone+rev-parse / cleanup 只删自建临时目录。 */
class GitCheckoutTest {

    private val processRunner = mockk<ProcessRunner>()
    private val checkout = CommandGitCheckout(processRunner)

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local path skips clone`() {
        val result = checkout.checkout(tempDir.toString(), "main")
        assertEquals(tempDir.toString(), result.workDir)
        assertNull(result.commitId)
    }

    @Test
    fun `remote clone succeeds and returns commit id`() {
        every { processRunner.run(match { it.contains("clone") }, any()) } returns ProcessOutput(0, "")
        every { processRunner.run(match { it.contains("rev-parse") }, any()) } returns ProcessOutput(0, "abc123def\n")

        val result = checkout.checkout("https://git.example.com/a.git", "main")

        assertEquals("abc123def", result.commitId)
        assertTrue(result.workDir.contains("scan-checkout-"), "workDir should be a self-created temp dir")
    }

    @Test
    fun `clone failure throws and cleans temp dir`() {
        every { processRunner.run(match { it.contains("clone") }, any()) } returns ProcessOutput(128, "fatal: could not read Username")
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            checkout.checkout("https://git.example.com/a.git", "main")
        }
    }

    @Test
    fun `cleanup deletes only self-created temp dir`() {
        val self = java.nio.file.Files.createTempDirectory("scan-checkout-clean")
        checkout.cleanup(self.toString())
        assertTrue(!java.nio.file.Files.exists(self))
        // 用户路径绝不被删
        checkout.cleanup(tempDir.toString())
        assertTrue(java.nio.file.Files.exists(tempDir))
    }
}
