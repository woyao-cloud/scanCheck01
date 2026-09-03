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

    @Test
    fun `per-vendor V3 preferred over own V2 then max across vendors`() {
        // github V3=8.0 > redhat V3=7.8 > 0：正确实现 → 跨 vendor max=8.0；
        // 若实现错取同 vendor V2 → 9.9；若错取第一个 vendor → 7.8 —— 三种行为均被区分
        val stdout = """{"Results":[{"Target":"package-lock.json","Class":"lang-pkgs","Type":"java","Vulnerabilities":[{"VulnerabilityID":"CVE-2024-TESTA","PkgName":"pkg-a","InstalledVersion":"1.0.0","FixedVersion":"1.0.1","Severity":"HIGH","CVSS":{"redhat":{"V3Score":7.8,"V2Score":9.9},"github":{"V3Score":8.0}}}]}]}"""
        val finding = parser.parse(stdout).single()
        assertEquals(8.0, finding.cvssScore)
    }

    @Test
    fun `nvd without V3 or V2 falls through to vendor loop`() {
        // nvd 存在但无 V3/V2 → 落入 vendor 循环，取 redhat V3=6.1
        val stdout = """{"Results":[{"Target":"package-lock.json","Class":"lang-pkgs","Type":"java","Vulnerabilities":[{"VulnerabilityID":"CVE-2024-TESTB","PkgName":"pkg-b","InstalledVersion":"1.0.0","FixedVersion":"1.0.1","Severity":"HIGH","CVSS":{"nvd":{},"redhat":{"V3Score":6.1}}}]}]}"""
        val finding = parser.parse(stdout).single()
        assertEquals(6.1, finding.cvssScore)
    }
}
