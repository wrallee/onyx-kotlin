package com.onyx.kotlin.indexing

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class SearchSettingsRequest(
    @field:NotBlank val modelName: String,
    @field:Positive val modelDim: Int,
    val normalize: Boolean = true,
    val queryPrefix: String? = null,
    val passagePrefix: String? = null,
    val providerType: EmbeddingProviderType? = null,
)

data class SearchSettingsResponse(
    val id: Long,
    val modelName: String,
    val modelDim: Int,
    val normalize: Boolean,
    val queryPrefix: String?,
    val passagePrefix: String?,
    val providerType: EmbeddingProviderType?,
    val indexName: String,
    val status: IndexModelStatus,
    val reindexStartedAt: Instant?,
    val cancelRequestedAt: Instant?,
    val usePortFlow: Boolean = true,
)

data class EmbeddingProviderRequest(
    val providerType: EmbeddingProviderType,
    @field:NotBlank val apiUrl: String,
    val apiKey: String? = null,
)

data class EmbeddingProviderResponse(
    val providerType: EmbeddingProviderType,
    val apiUrl: String,
    val apiKey: String?,
)

data class IdResponse(val id: Long)

@RestController
class IndexSettingsController(private val settings: IndexSettingsService) {
    @GetMapping("/search-settings/get-current-search-settings")
    fun current(): SearchSettingsResponse = settings.current()

    @GetMapping("/search-settings/get-secondary-search-settings")
    fun pending(): SearchSettingsResponse? = settings.pending()

    @PostMapping("/search-settings/set-new-search-settings")
    fun savePending(@Valid @RequestBody request: SearchSettingsRequest): IdResponse = settings.savePending(request)

    @GetMapping("/admin/embedding/embedding-provider")
    fun providers(): List<EmbeddingProviderResponse> = settings.providers()

    @PutMapping("/admin/embedding/embedding-provider")
    fun saveProvider(@Valid @RequestBody request: EmbeddingProviderRequest): EmbeddingProviderResponse =
        settings.saveProvider(request)

    @DeleteMapping("/admin/embedding/embedding-provider/{providerType}")
    fun deleteProvider(@PathVariable providerType: String) = settings.deleteProvider(
        EmbeddingProviderType.fromValue(providerType),
    )
}
