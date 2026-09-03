package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingHistory
import org.springframework.data.jpa.repository.JpaRepository

interface FindingHistoryRepository : JpaRepository<FindingHistory, Long>
