package com.example.compliance.common.audit

import com.example.compliance.common.exception.BusinessException
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 审计日志查询（spec R-M16-D5/D6）：多可选 AND 过滤 + 分页；空过滤 → null Specification（全量）。
 *  负 page 400、size 钳制 [1,100]、固定 id 倒序——镜像 ReportGenerationService.list C2 硬化。 */
@Service
class AuditQueryService(private val repository: AuditLogRepository) {

    @Transactional(readOnly = true)
    fun search(filter: AuditLogFilter, page: Int, size: Int): Page<AuditLog> {
        if (page < 0) throw BusinessException(400, "page must be non-negative")
        val pageable = PageRequest.of(page, size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "id"))
        return repository.findAll(filter.toSpecification(), pageable)
    }

    private fun AuditLogFilter.toSpecification(): Specification<AuditLog>? {
        val anyFilter = module != null || action != null || userId != null ||
            resourceType != null || resourceId != null || from != null || to != null
        if (!anyFilter) return null
        return Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            module?.let { predicates += cb.equal(root.get<String>("module"), it) }
            action?.let { predicates += cb.equal(root.get<String>("action"), it) }
            userId?.let { predicates += cb.equal(root.get<Long>("userId"), it) }
            resourceType?.let { predicates += cb.equal(root.get<String>("resourceType"), it) }
            resourceId?.let { predicates += cb.equal(root.get<Long>("resourceId"), it) }
            from?.let { predicates += cb.greaterThanOrEqualTo(root.get<Instant>("occurredAt"), it) }
            to?.let { predicates += cb.lessThanOrEqualTo(root.get<Instant>("occurredAt"), it) }
            cb.and(*predicates.toTypedArray())
        }
    }
}
