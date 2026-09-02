package com.example.compliance.project.api.dto

import com.example.compliance.project.domain.Project
import com.example.compliance.project.domain.Repository

data class ProjectResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val ownerUserId: Long?,
    val status: String,
) {
    companion object {
        fun from(p: Project) = ProjectResponse(p.id!!, p.code, p.name, p.description, p.ownerUserId, p.status)
    }
}

data class RepositoryResponse(
    val id: Long,
    val projectId: Long,
    val name: String,
    val gitUrl: String,
    val provider: String,
    val defaultBranch: String,
) {
    companion object {
        fun from(r: Repository) =
            RepositoryResponse(r.id!!, r.projectId, r.name, r.gitUrl, r.provider, r.defaultBranch)
    }
}
