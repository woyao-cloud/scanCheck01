package com.example.compliance.engineadapter.gitleaks

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GitleaksSeverityMapperTest {
    private val mapper = GitleaksSeverityMapper()

    @Test
    fun `passes through HIGH MEDIUM LOW and defaults to MEDIUM`() {
        assertEquals("HIGH", mapper.map("HIGH"))
        assertEquals("MEDIUM", mapper.map("MEDIUM"))
        assertEquals("LOW", mapper.map("LOW"))
        assertEquals("MEDIUM", mapper.map(""))       // 旧版无 Severity 字段 / 空串
        assertEquals("MEDIUM", mapper.map("CRITICAL")) // 超出等级兜底（不直接进业务层）
    }
}
