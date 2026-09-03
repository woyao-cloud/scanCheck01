package com.example.compliance.openapi.api

import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.application.ScanTriggerPort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** CI 触发扫描开放端点：X-API-Token 校验通过 → 以该 CI 触发；缺失/无效 → 401。 */
@RestController
@RequestMapping("/api/v1/openapi")
class OpenApiScanController(
    private val tokenService: ApiTokenService,
    private val triggerPort: ScanTriggerPort,
) {
    data class TriggerScanCommand(val projectId: Long, val engine: String, val ref: String? = null, val requestId: String? = null)

    @PostMapping("/scans")
    fun trigger(@RequestBody cmd: TriggerScanCommand, @RequestHeader("X-API-Token", required = false) rawToken: String?): ResponseEntity<ScanTaskView> {
        if (rawToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        val token = tokenService.verify(rawToken)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        tokenService.recordUsage(token.id!!)
        val view = triggerPort.triggerScan(cmd.projectId, cmd.engine, cmd.ref, "CI", cmd.requestId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view)
    }
}
