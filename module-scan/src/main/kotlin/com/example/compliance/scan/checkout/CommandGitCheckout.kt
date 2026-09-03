package com.example.compliance.scan.checkout

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Component
class CommandGitCheckout(
    private val processRunner: ProcessRunner,
) : GitCheckout {

    /** 本地路径（已存在目录或 file:）→ 跳过 clone；远程 → clone 到自建临时目录并回填 commitId。 */
    override fun checkout(repoUrl: String, ref: String?): CheckoutResult {
        if (isLocal(repoUrl)) return CheckoutResult(repoUrl, null)
        val target = Files.createTempDirectory("scan-checkout-").toString()
        val command = buildList {
            add("git"); add("clone"); add("--depth"); add("1")
            if (!ref.isNullOrBlank()) { add("-b"); add(ref) }
            add(repoUrl); add(target)
        }
        val clone = processRunner.run(command)
        if (clone.exitCode != 0) {
            cleanup(target)
            throw IllegalStateException("git clone failed: ${clone.stdout.take(500)}")
        }
        val rev = processRunner.run(listOf("git", "-C", target, "rev-parse", "HEAD"))
        val commitId = if (rev.exitCode == 0) rev.stdout.trim().takeIf { it.isNotBlank() } else null
        return CheckoutResult(target, commitId)
    }

    /** 只删本组件创建的 scan-checkout-* 临时目录；用户路径 no-op。 */
    override fun cleanup(workDir: String) {
        val path = Paths.get(workDir)
        if (path.fileName?.toString()?.startsWith("scan-checkout-") == true) {
            deleteRecursively(path)
        }
    }

    private fun isLocal(repoUrl: String): Boolean =
        repoUrl.startsWith("file:") || isExistingDirectory(repoUrl)

    private fun isExistingDirectory(repoUrl: String): Boolean = try {
        Files.isDirectory(Paths.get(repoUrl))
    } catch (_: java.nio.file.InvalidPathException) {
        false // 远程 URL（如 https://…）在 Windows 上无法解析为路径，按远程处理
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/** 真实进程执行器（ProcessBuilder）。 */
@Component
class SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, dir: String?): ProcessOutput {
        val pb = ProcessBuilder(command)
        if (dir != null) pb.directory(Paths.get(dir).toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        // 并发排空 stdout：先 waitFor 后读会因 64KB 管道缓冲写满而死锁；
        // 串行 readText 再 waitFor 则子进程挂起且保持管道打开时无限阻塞（R-8.2-b）。
        val stdout = StringBuilder()
        val drain = Thread {
            p.inputStream.bufferedReader().forEachLine { stdout.append(it).append('\n') }
        }
        drain.isDaemon = true
        drain.start()
        val finished = p.waitFor(120, TimeUnit.SECONDS)
        val code = if (finished) p.exitValue() else { p.destroyForcibly(); -1 }
        drain.join(5_000)
        return ProcessOutput(code, stdout.toString().trimEnd('\n'))
    }
}
