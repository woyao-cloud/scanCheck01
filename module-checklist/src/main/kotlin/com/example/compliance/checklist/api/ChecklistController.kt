package com.example.compliance.checklist.api

import com.example.compliance.checklist.api.dto.BindRequest
import com.example.compliance.checklist.api.dto.BindingResponse
import com.example.compliance.checklist.api.dto.ChecklistItemRequest
import com.example.compliance.checklist.api.dto.ChecklistRequest
import com.example.compliance.checklist.api.dto.ChecklistResponse
import com.example.compliance.checklist.api.dto.ItemResponse
import com.example.compliance.checklist.api.dto.StandardRequest
import com.example.compliance.checklist.api.dto.StandardResponse
import com.example.compliance.checklist.api.dto.VersionResponse
import com.example.compliance.checklist.application.AddItemCommand
import com.example.compliance.checklist.application.ChecklistQueryService
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.common.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class ChecklistController(
    private val checklistService: ChecklistService,
    private val queryService: ChecklistQueryService,
) {
    @PostMapping("/compliance/standards")
    fun createStandard(@Valid @RequestBody req: StandardRequest): ApiResponse<StandardResponse> =
        ApiResponse.ok(StandardResponse.from(checklistService.createStandard(req.code, req.name, req.description)))

    @PostMapping("/compliance/checklists")
    fun createChecklist(@Valid @RequestBody req: ChecklistRequest): ApiResponse<ChecklistResponse> =
        ApiResponse.ok(ChecklistResponse.from(checklistService.createChecklist(req.standardId, req.code, req.name)))

    /** 给当前 DRAFT 版本追加合规项（版本化编辑：若最新已发布则自动开新版本）。 */
    @PostMapping("/compliance/checklists/{id}/versions")
    fun addItem(@PathVariable id: Long, @Valid @RequestBody req: ChecklistItemRequest): ApiResponse<ItemResponse> =
        ApiResponse.ok(
            ItemResponse.from(
                checklistService.addItem(
                    id,
                    AddItemCommand(
                        req.itemCode, req.name, req.category, req.riskLevel, req.description,
                        req.basis, req.remediation, req.required, req.waivable, req.scoreWeight,
                    ),
                )
            )
        )

    @GetMapping("/compliance/checklists/{id}/versions")
    fun versions(@PathVariable id: Long): ApiResponse<List<VersionResponse>> =
        ApiResponse.ok(queryService.versions(id).map { VersionResponse.from(it) })

    @PostMapping("/compliance/checklists/{id}/publish")
    fun publish(@PathVariable id: Long): ApiResponse<VersionResponse> =
        ApiResponse.ok(VersionResponse.from(checklistService.publish(id)))

    @PostMapping("/projects/{projectId}/bind-checklist")
    fun bind(@PathVariable projectId: Long, @Valid @RequestBody req: BindRequest): ApiResponse<BindingResponse> =
        ApiResponse.ok(BindingResponse.from(checklistService.bindProject(projectId, req.checklistVersionId)))

    @GetMapping("/projects/{projectId}/checklists")
    fun projectChecklist(@PathVariable projectId: Long): ApiResponse<List<ItemResponse>> {
        val items = queryService.publishedItemsForProject(projectId)
            ?: return ApiResponse.error(404, "project has no bound published checklist")
        return ApiResponse.ok(items.map { ItemResponse.from(it) })
    }
}
