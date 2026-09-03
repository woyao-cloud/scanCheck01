package com.example.compliance.engineadapter.trivy

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrivyResultParserTest {
    private val parser = TrivyResultParser()
    private val json = javaClass.getResource("/trivy/basic.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses vulnerabilities into dependency raw findings`() {
        val findings = parser.parse(json)
        assertEquals(2, findings.size)
        val f0 = findings[0]
        assertEquals("CVE-2024-1234", f0.engineRuleId)          // engineRuleId = CVE（spec §6.2）
        assertEquals("CVE-2024-1234", f0.cveId)
        assertEquals("lodash", f0.packageName)
        assertEquals("4.17.20", f0.packageVersion)
        assertEquals("4.17.21", f0.fixedVersion)
        assertEquals(9.8, f0.cvssScore)                          // nvd.V3Score 优先
        assertEquals("package-lock.json", f0.filePath)           // Target → filePath
        assertEquals("CRITICAL", f0.severity)                    // 保留原生 severity
        assertNull(f0.line)
    }

    @Test
    fun `unknown severity preserved and empty cvss yields null score`() {
        val f1 = parser.parse(json)[1]
        assertEquals("UNKNOWN", f1.severity)                     // normalize 才映射 → LOW
        assertNull(f1.cvssScore)                                 // 空 CVSS → null
        assertEquals("CVE-2024-5678", f1.cveId)
    }

    @Test
    fun `skips results without vulnerabilities and empty report yields empty list`() {
        assertTrue(parser.parse(json).size == 2)                 // go.mod Result 无 Vulnerabilities → 跳过
        assertTrue(parser.parse("""{"Results":[]}""").isEmpty())
        assertTrue(parser.parse("not json").isEmpty())
    }
}
