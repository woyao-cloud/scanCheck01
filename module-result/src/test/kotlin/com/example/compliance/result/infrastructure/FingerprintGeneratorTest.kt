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
}
