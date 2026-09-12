package com.onyx.kotlin.ingestion

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/manage")
class IngestionStatusController(private val ingestion: IngestionQueryService) {
    @PostMapping("/admin/connector/indexing-status")
    fun indexingStatus(@RequestBody request: IndexingStatusRequest): List<Map<String, Any?>> =
        ingestion.indexingStatus(request.source, request.nameFilter)

    @GetMapping("/admin/connector/status", "/connector-status")
    fun connectorStatus(): List<Map<String, Any?>> = ingestion.connectorStatuses()
    @GetMapping("/admin/cc-pair/{pairId}/index-attempts")
    fun attempts(
        @PathVariable pairId: Long,
        @RequestParam("page_num", defaultValue = "0") page: Int,
        @RequestParam("page_size", defaultValue = "10") pageSize: Int,
    ): Map<String, Any?> = ingestion.attempts(pairId, page, pageSize)

    @GetMapping("/admin/cc-pair/{pairId}/errors")
    fun errors(
        @PathVariable pairId: Long,
        @RequestParam("page_num", defaultValue = "0") page: Int,
        @RequestParam("page_size", defaultValue = "10") pageSize: Int,
    ): Map<String, Any?> = ingestion.errors(pairId, page, pageSize)
}
