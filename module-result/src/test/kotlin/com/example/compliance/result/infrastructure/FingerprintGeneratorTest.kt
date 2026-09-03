package com.example.compliance.result.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FingerprintGeneratorTest {
    private val generator = FingerprintGenerator()

    @Test
    fun `same inputs produce same fingerprint`() {
        val a = generator.generate(1L, "SEC-001", "src/A.java", 42, "String s = a;")
        val b = generator.generate(1L, "SEC-001", "src/A.java", 42, "String s = a;")
        assertEquals(a, b)
    }

    @Test
    fun `different line or snippet changes fingerprint`() {
        val base = generator.generate(1L, "SEC-001", "src/A.java", 42, "s")
        assertNotEquals(base, generator.generate(1L, "SEC-001", "src/A.java", 43, "s"))
        assertNotEquals(base, generator.generate(2L, "SEC-001", "src/A.java", 42, "s"))
    }

    @Test
    fun `generateDependency is deterministic and differs from code fingerprint`() {
        val a = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234")
        val b = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234")
        val code = generator.generate(9L, "M11TRV", "package-lock.json", null, null)
        assertEquals(a, b)                    // 确定性
        assertNotEquals(a, code)              // 与代码类指纹不冲突（uq_finding_fp 唯一索引）
    }

    @Test
    fun `generateDependency distinguishes package version and cve`() {
        val v1 = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234")
        val v2 = generator.generateDependency(9L, "lodash", "4.17.21", "CVE-2024-1234")
        val v3 = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-5678")
        assertNotEquals(v1, v2)               // 版本参与指纹
        assertNotEquals(v1, v3)               // CVE 参与指纹
    }

    @Test
    fun `generateDependency tolerates null version`() {
        val a = generator.generateDependency(9L, "lodash", null, "CVE-2024-1234")
        val b = generator.generateDependency(9L, "lodash", null, "CVE-2024-1234")
        assertEquals(a, b)
    }
}
