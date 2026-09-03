package com.example.compliance.project.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.domain.Project
import com.example.compliance.project.domain.Repository
import com.example.compliance.project.infrastructure.CredentialCrypto
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.project.infrastructure.RepoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val repoRepository: RepoRepository,
    private val credentialCrypto: CredentialCrypto,
) : ProjectQueryPort {
    @Transactional
    fun create(command: CreateProjectCommand): Project {
        if (projectRepository.existsByCode(command.code)) {
            throw BusinessException(400, "project code already exists: ${command.code}")
        }
        return projectRepository.save(
            Project().apply {
                code = command.code
                name = command.name
                description = command.description
                ownerUserId = command.ownerUserId
            }
        )
    }

    fun get(id: Long): Project =
        projectRepository.findById(id).orElseThrow { BusinessException(404, "project not found: $id") }

    fun list(): List<Project> = projectRepository.findAll()

    @Transactional
    fun bindRepository(projectId: Long, command: BindRepositoryCommand): Repository {
        get(projectId)
        return repoRepository.save(
            Repository().apply {
                this.projectId = projectId
                name = command.name
                gitUrl = command.gitUrl
                provider = command.provider
                defaultBranch = command.defaultBranch
                credentialRef = command.credential?.let { credentialCrypto.encrypt(it) }
            }
        )
    }

    fun listRepositories(projectId: Long): List<Repository> = repoRepository.findByProjectId(projectId)

    override fun count(): Long = projectRepository.count()
}
