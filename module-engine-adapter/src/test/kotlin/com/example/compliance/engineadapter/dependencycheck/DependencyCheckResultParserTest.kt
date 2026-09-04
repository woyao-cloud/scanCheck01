package com.example.compliance.engineadapter.dependencycheck

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DependencyCheckResultParserTest {
    private val parser = DependencyCheckResultParser()
    private val report = javaClass.getResource("/dependencycheck/basic.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses dependency vulnerabilities with package fields and null fixedVersion`() {
        val out = parser.parse(report)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("CVE-2021-23337", first.engineRuleId)
        assertEquals("CVE-2021-23337", first.cveId)
        assertEquals("/workspace/package-lock.json", first.filePath)
        assertEquals("lodash", first.packageName)          // 自 pkg:npm/lodash@4.17.20 推断
        assertEquals("4.17.20", first.packageVersion)
        assertNull(first.fixedVersion)                     // DC 无修复版本字段（spec §5.1）
        assertEquals(9.8, first.cvssScore)                 // CVSSv3.baseScore 优先
        assertEquals("High", first.severity)               // 原生透传（映射在 normalizeResult）
        assertEquals("Command Injection in lodash 4.17.20", first.message)
        val second = out[1]
        assertEquals("CVE-2022-0002", second.engineRuleId)
        assertEquals("server.js", second.packageName)      // 无 packages → fileName 兜底（P3-D8 恒非空）
        assertNull(second.packageVersion)
        assertEquals(5.3, second.cvssScore)
    }

    @Test
    fun `invalid or empty input yields empty list`() {
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("{}").isEmpty())
    }
}
