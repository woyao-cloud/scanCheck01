package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingTrace
import org.springframework.data.jpa.repository.JpaRepository

interface FindingTraceRepository : JpaRepository<FindingTrace, Long>
