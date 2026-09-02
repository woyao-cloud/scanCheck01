package com.example.compliance.result

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.infrastructure.FindingRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class FindingRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var findingRepository: FindingRepository

    @Test
    fun `save finding and look up by fingerprint`() {
        val fp = "f" + "a".repeat(63)
        findingRepository.save(Finding().apply {
            scanTaskId = 1L; engine = "SEMGREP"; ruleCode = "SEC-001"; filePath = "A.java"
            lineNumber = 1; severity = "HIGH"; fingerprint = fp; rawJson = """{"a":1}"""
        })
        assertEquals("SEC-001", findingRepository.findByFingerprint(fp)?.ruleCode)
    }
}
