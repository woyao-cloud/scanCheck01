package com.example.compliance.project

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class ProjectRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var projectRepository: ProjectRepository

    @Test
    fun `save and find project by code`() {
        projectRepository.save(Project().apply { code = "PAY"; name = "支付中心" })
        assertEquals("PAY", projectRepository.findByCode("PAY")?.code)
    }
}
