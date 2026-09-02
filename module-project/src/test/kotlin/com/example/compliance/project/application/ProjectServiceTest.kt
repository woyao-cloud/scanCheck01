package com.example.compliance.project.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.domain.Project
import com.example.compliance.project.domain.Repository
import com.example.compliance.project.infrastructure.CredentialCrypto
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.project.infrastructure.RepoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class ProjectServiceTest {
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val repoRepository = mockk<RepoRepository>(relaxed = true)
    private val crypto = mockk<CredentialCrypto>()
    private val service = ProjectService(projectRepository, repoRepository, crypto)

    @Test
    fun `create rejects duplicate code`() {
        every { projectRepository.existsByCode("PAY") } returns true
        assertFailsWith<BusinessException> {
            service.create(CreateProjectCommand("PAY", "支付", null, null))
        }
    }

    @Test
    fun `create saves project`() {
        every { projectRepository.existsByCode("PAY") } returns false
        every { projectRepository.save(any()) } answers { firstArg<Project>().apply { id = 1L } }
        val project = service.create(CreateProjectCommand("PAY", "支付", null, 5L))
        assertEquals("PAY", project.code)
        verify { projectRepository.save(any()) }
    }

    @Test
    fun `bindRepository encrypts credential before persist`() {
        every { crypto.encrypt("plain-token") } returns "cipher-text"
        every { projectRepository.findById(1L) } returns java.util.Optional.of(
            Project().apply { id = 1L; code = "P"; name = "N" }
        )
        every { repoRepository.save(any()) } answers {
            firstArg<Repository>().apply { id = 2L }
        }
        val repo = service.bindRepository(
            1L,
            BindRepositoryCommand("repo-a", "https://git.example.com/a.git", "GITLAB", "main", "plain-token"),
        )
        assertEquals("cipher-text", repo.credentialRef)
    }
}
