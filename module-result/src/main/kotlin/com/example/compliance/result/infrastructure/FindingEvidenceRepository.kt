package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingEvidence
import org.springframework.data.jpa.repository.JpaRepository

interface FindingEvidenceRepository : JpaRepository<FindingEvidence, Long>
