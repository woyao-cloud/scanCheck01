package com.example.compliance.project.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.project.api.dto.ProjectRequest
import com.example.compliance.project.api.dto.ProjectResponse
import com.example.compliance.project.api.dto.RepositoryRequest
import com.example.compliance.project.api.dto.RepositoryResponse
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController(private val projectService: ProjectService) {

    @PostMapping
    fun create(@Valid @RequestBody request: ProjectRequest): ApiResponse<ProjectResponse> =
        ApiResponse.ok(
            ProjectResponse.from(
                projectService.create(
                    CreateProjectCommand(request.code, request.name, request.description, request.ownerUserId)
                )
            )
        )

    @GetMapping
    fun list(): ApiResponse<List<ProjectResponse>> =
        ApiResponse.ok(projectService.list().map { ProjectResponse.from(it) })

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<ProjectResponse> =
        ApiResponse.ok(ProjectResponse.from(projectService.get(id)))

    @PostMapping("/{id}/repositories")
    fun bindRepo(
        @PathVariable id: Long,
        @Valid @RequestBody request: RepositoryRequest,
    ): ApiResponse<RepositoryResponse> =
        ApiResponse.ok(
            RepositoryResponse.from(
                projectService.bindRepository(
                    id,
                    BindRepositoryCommand(request.name, request.gitUrl, request.provider, request.defaultBranch, request.credential),
                )
            )
        )

    @GetMapping("/{id}/repositories")
    fun repos(@PathVariable id: Long): ApiResponse<List<RepositoryResponse>> =
        ApiResponse.ok(projectService.listRepositories(id).map { RepositoryResponse.from(it) })
}
