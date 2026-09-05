package com.example.compliance.common.audit

import com.example.compliance.common.exception.BusinessException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification

/** M16 (R-M16-D6)：审计查询服务——负 page 400、size 钳制、空过滤 null spec。 */
class AuditQueryServiceTest {

    private val repo = mockk<AuditLogRepository>(relaxed = true)
    private val service = AuditQueryService(repo)

    @Test
    fun `negative page throws 400`() {
        assertFailsWith<BusinessException> { service.search(AuditLogFilter(), -1, 20) }
    }

    @Test
    fun `size is clamped to 100`() {
        every { repo.findAll(any<Specification<AuditLog>>(), any<Pageable>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 100), 0L)
        service.search(AuditLogFilter(module = "M"), 0, 500)
        verify { repo.findAll(any<Specification<AuditLog>>(), match<Pageable> { it.pageSize == 100 }) }
    }

    @Test
    fun `empty filter passes null specification`() {
        every { repo.findAll(null, any<Pageable>()) } returns
            PageImpl(emptyList(), PageRequest.of(0, 20), 0L)
        service.search(AuditLogFilter(), 0, 20)
        verify { repo.findAll(null, match<Pageable> { it.pageNumber == 0 }) }
    }
}
