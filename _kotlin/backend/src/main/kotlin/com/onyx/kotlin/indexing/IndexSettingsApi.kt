package com.onyx.kotlin.indexing

import com.onyx.kotlin.model.ModelServerClient
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.node.NullNode
import java.time.Instant

data class SearchSettingsRequest(@field:NotBlank val modelName: String)

data class SearchSettingsResponse(
    val id: Long,
    val modelName: String,
    val indexName: String,
    val status: IndexModelStatus,
    val reindexStartedAt: Instant?,
    val cancelRequestedAt: Instant?,
    val cutoverAt: Instant?,
)

data class LocalEmbeddingModelResponse(
    val modelName: String,
    val displayName: String,
    val dimension: Int,
    val available: Boolean,
    val status: String,
    val compatiblePastSettingsId: Long?,
)

data class TestEmbeddingRequest(@field:NotBlank val modelName: String)
data class ReindexRequest(@field:NotBlank val modelName: String)

data class IdResponse(val id: Long)

@RestController
class IndexSettingsController(
    private val settings: IndexSettingsService,
    private val modelServer: ModelServerClient,
    private val reindex: ReindexCoordinator,
) {
    @GetMapping("/search-settings/get-current-search-settings")
    fun current(): SearchSettingsResponse = settings.current()

    @GetMapping("/search-settings/get-secondary-search-settings")
    fun pending(): Any = settings.pending() ?: NullNode.getInstance()

    @PostMapping("/search-settings/set-new-search-settings")
    fun savePending(@Valid @RequestBody request: SearchSettingsRequest): IdResponse = settings.savePending(request)

    @GetMapping("/admin/embedding/models")
    fun models(): List<LocalEmbeddingModelResponse> = settings.localModels(modelServer.modelStatus())

    @PostMapping("/admin/embedding/test-embedding")
    fun testEmbedding(@Valid @RequestBody request: TestEmbeddingRequest) =
        modelServer.test(settings.executionConfig(request))

    @GetMapping("/admin/embedding/model-status")
    fun modelStatus() = modelServer.modelStatus()

    @PostMapping("/search-settings/reindex/full")
    fun full(@Valid @RequestBody request: ReindexRequest) = IdResponse(reindex.startFull(request.modelName))

    @PostMapping("/search-settings/reindex/sync-and-switch")
    fun sync(@Valid @RequestBody request: ReindexRequest) = IdResponse(reindex.startSync(request.modelName))

    @PostMapping("/search-settings/cancel-new-embedding")
    fun cancel() = reindex.cancel()

    @PostMapping("/search-settings/reindex/{pairId}/retry")
    fun retry(@PathVariable pairId: Long) = reindex.retry(pairId)

    @PostMapping("/search-settings/reindex/retry-all")
    fun retryAll() = reindex.retryAll()

    @GetMapping("/search-settings/reindex-progress")
    fun progress() = reindex.progress()

    @GetMapping("/search-settings/reindex-errors")
    fun errors() = reindex.errors()
}
