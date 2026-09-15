package com.onyx.kotlin.indexing

import com.onyx.kotlin.api.ApiException
import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.model.EmbeddingExecutionConfig
import com.onyx.kotlin.model.DEFAULT_LOCAL_EMBEDDING_MODEL
import com.onyx.kotlin.model.OpenAiCompatibleEmbeddingProvider
import com.onyx.kotlin.model.requireValidEmbeddingProviderUrl
import com.onyx.kotlin.opensearch.OpenSearchIndexMigrationLockRepository
import com.onyx.kotlin.security.CredentialCipher
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class IndexSettingsService(
    private val searchSettings: SearchSettingsRepository,
    private val providers: EmbeddingProviderRepository,
    private val indexLock: OpenSearchIndexMigrationLockRepository,
    private val properties: OnyxProperties,
    private val cipher: CredentialCipher,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun current(): SearchSettingsResponse {
        indexLock.lock()
        return currentEntity().response()
    }

    @Transactional
    fun pending(): SearchSettingsResponse? {
        indexLock.lock()
        currentEntity()
        return searchSettings.findByStatus(IndexModelStatus.FUTURE)?.response()
    }

    @Transactional(readOnly = true)
    fun needsReindexing(): Boolean = searchSettings.findByStatus(IndexModelStatus.FUTURE) != null

    @Transactional
    fun savePending(request: SearchSettingsRequest): IdResponse {
        indexLock.lock()
        currentEntity()
        request.providerType?.let {
            if (!providers.existsById(it)) {
                throw ApiException(HttpStatus.BAD_REQUEST, "Embedding provider is not configured")
            }
        }
        val pending = searchSettings.findByStatus(IndexModelStatus.FUTURE)
        if (pending?.reindexStartedAt != null) {
            throw ApiException(HttpStatus.CONFLICT, "Embedding reindex is already in progress")
        }
        val target = pending ?: SearchSettingsEntity(
            status = IndexModelStatus.FUTURE,
            singletonMarker = 1,
            indexName = "${properties.opensearch.index}-${UUID.randomUUID().toString().replace("-", "").take(12)}",
        )
        target.modelName = request.modelName.trim()
        target.modelDim = request.modelDim
        target.normalize = request.normalize
        target.queryPrefix = request.queryPrefix
        target.passagePrefix = request.passagePrefix
        target.providerType = request.providerType
        return IdResponse(requireNotNull(searchSettings.save(target).id))
    }

    @Transactional(readOnly = true)
    fun providers(): List<EmbeddingProviderResponse> = providers.findAll().map { it.response() }

    @Transactional
    fun saveProvider(request: EmbeddingProviderRequest): EmbeddingProviderResponse {
        indexLock.lock()
        val existing = providers.findById(request.providerType).orElse(null)
        val running = searchSettings.findByStatus(IndexModelStatus.FUTURE)?.reindexStartedAt != null
        val apiUrl = requireValidEmbeddingProviderUrl(request.apiUrl)
        if (running && existing?.apiUrl != apiUrl) {
            throw ApiException(HttpStatus.CONFLICT, "Embedding provider URL cannot change during reindex")
        }
        val provider = existing ?: EmbeddingProviderEntity(providerType = request.providerType)
        provider.apiUrl = apiUrl
        if (request.apiKey != null && request.apiKey != MASK) {
            provider.apiKeyEncrypted = cipher.encrypt(mapper.createObjectNode().put("api_key", request.apiKey))
        }
        return providers.save(provider).response()
    }

    @Transactional
    fun deleteProvider(providerType: EmbeddingProviderType) {
        indexLock.lock()
        val usedByLiveSettings = searchSettings.existsByProviderTypeAndStatusIn(
            providerType,
            listOf(IndexModelStatus.PRESENT, IndexModelStatus.FUTURE),
        )
        if (usedByLiveSettings) {
            throw ApiException(HttpStatus.CONFLICT, "Embedding provider is used by search settings")
        }
        providers.deleteById(providerType)
    }

    @Transactional(readOnly = true)
    fun executionConfig(settingsId: Long): EmbeddingExecutionConfig =
        searchSettings.findById(settingsId).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "Search settings not found")
        }.executionConfig()

    @Transactional(readOnly = true)
    fun executionConfig(request: TestEmbeddingRequest): EmbeddingExecutionConfig {
        val provider = request.providerType?.let { providerType ->
            val stored = providers.findById(providerType).orElse(null)
            val apiUrl = request.apiUrl?.takeIf(String::isNotBlank) ?: stored?.apiUrl
                ?: throw ApiException(HttpStatus.BAD_REQUEST, "Embedding provider URL is required")
            val apiKey = when (request.apiKey) {
                null, MASK -> stored?.decryptedApiKey()
                else -> request.apiKey
            }
            OpenAiCompatibleEmbeddingProvider(requireValidEmbeddingProviderUrl(apiUrl), apiKey)
        }
        return EmbeddingExecutionConfig(
            modelName = request.modelName.trim(),
            modelDim = request.modelDim,
            normalize = request.normalize,
            maxContextLength = properties.modelServer.maxContextLength,
            queryPrefix = request.queryPrefix,
            passagePrefix = request.passagePrefix,
            provider = provider,
        )
    }

    private fun currentEntity(): SearchSettingsEntity = searchSettings.findByStatus(IndexModelStatus.PRESENT)
        ?: searchSettings.save(
            SearchSettingsEntity(
                modelName = properties.modelServer.modelName.ifBlank { DEFAULT_LOCAL_EMBEDDING_MODEL },
                modelDim = properties.modelServer.embeddingDimension,
                normalize = properties.modelServer.normalizeEmbeddings,
                indexName = properties.opensearch.index,
            ),
        )

    private fun SearchSettingsEntity.response() = SearchSettingsResponse(
        id = requireNotNull(id),
        modelName = modelName,
        modelDim = modelDim,
        normalize = normalize,
        queryPrefix = queryPrefix,
        passagePrefix = passagePrefix,
        providerType = providerType,
        indexName = indexName,
        status = status,
        reindexStartedAt = reindexStartedAt,
        cancelRequestedAt = cancelRequestedAt,
    )

    private fun SearchSettingsEntity.executionConfig(): EmbeddingExecutionConfig {
        val provider = providerType?.let { type ->
            val stored = providers.findById(type).orElseThrow {
                ApiException(HttpStatus.BAD_REQUEST, "Embedding provider is not configured")
            }
            OpenAiCompatibleEmbeddingProvider(stored.apiUrl, stored.decryptedApiKey())
        }
        return EmbeddingExecutionConfig(
            modelName = modelName,
            modelDim = modelDim,
            normalize = normalize,
            maxContextLength = properties.modelServer.maxContextLength,
            queryPrefix = queryPrefix,
            passagePrefix = passagePrefix,
            provider = provider,
        )
    }

    private fun EmbeddingProviderEntity.decryptedApiKey(): String? = apiKeyEncrypted?.let {
        cipher.decrypt(it).path("api_key").asString().takeIf(String::isNotBlank)
    }

    private fun EmbeddingProviderEntity.response() = EmbeddingProviderResponse(
        providerType = providerType,
        apiUrl = apiUrl,
        apiKey = if (apiKeyEncrypted == null) null else MASK,
    )

    private companion object {
        const val MASK = "********"
    }
}

@Service
class IndexSettingsInitializer(private val settings: IndexSettingsService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        settings.current()
    }
}
