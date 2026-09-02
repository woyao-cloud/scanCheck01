package com.example.compliance.user.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.user.api.dto.UserRequest
import com.example.compliance.user.api.dto.UserResponse
import com.example.compliance.user.application.CreateUserCommand
import com.example.compliance.user.application.UserService
import com.example.compliance.user.domain.User
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/users")
class UserController(private val userService: UserService) {

    @PostMapping
    fun create(@Valid @RequestBody request: UserRequest): ApiResponse<UserResponse> {
        val user = userService.createUser(
            CreateUserCommand(request.username, request.password, request.displayName, request.email, request.roleCodes)
        )
        return ApiResponse.ok(user.toResponse())
    }

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<UserResponse>> {
        val result = userService.page(PageRequest.of((page - 1).coerceAtLeast(0), size.coerceIn(1, 100)))
        return ApiResponse.ok(PageResponse(result.content.map { it.toResponse() }, page, size, result.totalElements))
    }

    @GetMapping("/{id}/roles")
    fun roles(@PathVariable id: Long): ApiResponse<List<String>> =
        ApiResponse.ok(userService.findRoles(id).map { it.code })

    private fun User.toResponse() = UserResponse(id!!, username, displayName, email, status)
}
