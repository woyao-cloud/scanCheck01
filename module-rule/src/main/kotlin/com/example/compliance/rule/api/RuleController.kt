package com.example.compliance.rule.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.rule.api.dto.EngineBindingRequest
import com.example.compliance.rule.api.dto.MappingRequest
import com.example.compliance.rule.api.dto.PolicyRequest
import com.example.compliance.rule.api.dto.RuleRequest
import com.example.compliance.rule.api.dto.RuleResponse
import com.example.compliance.rule.api.dto.RuleVersionResponse
import com.example.compliance.rule.api.dto.UpdateRuleRequest
import com.example.compliance.rule.application.RuleService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/rules")
class RuleController(private val ruleService: RuleService) {

    @GetMapping
    fun list(): ApiResponse<List<RuleResponse>> =
        ApiResponse.ok(ruleService.list().map { RuleResponse.from(it) })

    @PostMapping
    fun create(@Valid @RequestBody req: RuleRequest): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.create(req.toCommand())))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody req: UpdateRuleRequest): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.update(id, req.toCommand())))

    @GetMapping("/{id}/versions")
    fun versions(@PathVariable id: Long): ApiResponse<List<RuleVersionResponse>> {
        val rule = ruleService.get(id)
        return ApiResponse.ok(listOf(RuleVersionResponse.from(rule)))
    }

    @PostMapping("/{id}/engine-bindings")
    fun bindEngine(@PathVariable id: Long, @Valid @RequestBody req: EngineBindingRequest): ApiResponse<Unit> {
        ruleService.addEngineBinding(id, req.toCommand())
        return ApiResponse.ok()
    }

    @PostMapping("/{id}/mappings")
    fun mapItem(@PathVariable id: Long, @Valid @RequestBody req: MappingRequest): ApiResponse<Unit> {
        ruleService.addComplianceMapping(id, req.checklistItemCode)
        return ApiResponse.ok()
    }

    @PostMapping("/{id}/policy")
    fun setPolicy(@PathVariable id: Long, @Valid @RequestBody req: PolicyRequest): ApiResponse<Unit> {
        ruleService.setEvaluationPolicy(id, req.toCommand())
        return ApiResponse.ok()
    }

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: Long): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.publish(id)))

    @PostMapping("/{id}/disable")
    fun disable(@PathVariable id: Long): ApiResponse<RuleResponse> =
        ApiResponse.ok(RuleResponse.from(ruleService.disable(id)))
}
