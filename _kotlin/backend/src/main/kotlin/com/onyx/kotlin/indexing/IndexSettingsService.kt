package com.onyx.kotlin.indexing

import com.onyx.kotlin.api.ApiException
import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.model.DEFAULT_LOCAL_EMBEDDING_MODEL
import com.onyx.kotlin.model.EmbeddingExecutionConfig
import com.onyx.kotlin.opensearch.OpenSearchIndexMigrationLockRepository
import com.onyx.kotlin.opensearch.OpenSearchIndexTarget
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import tools.jackson.databind.JsonNode

data class SearchRuntimeSettings(
    val settingsId: Long,
    val modelName: String,
    val embedding: EmbeddingExecutionConfig,
    val index: OpenSearchIndexTarget,
)

@Service
class IndexSettingsService(
    private val searchSettings: SearchSettingsRepository,
    private val indexLock: OpenSearchIndexMigrationLockRepository,
    private val properties: OnyxProperties,
    private val models: LocalEmbeddingModelRegistry,
) {
    @Transactional
    fun current(): SearchSettingsResponse {
        indexLock.lock()
        return currentEntity().response()
    }

    @Transactional(readOnly = true)
    fun pending(): SearchSettingsResponse? = searchSettings.findByStatus(IndexModelStatus.FUTURE)?.response()

    @Transactional
    fun pendingLocked(): SearchSettingsResponse? {
        indexLock.lock()
        currentEntity()
        return searchSettings.findByStatus(IndexModelStatus.FUTURE)?.response()
    }

    @Transactional(readOnly = true)
    fun needsReindexing(): Boolean = searchSettings.findByStatus(IndexModelStatus.FUTURE) != null

    @Transactional(readOnly = true)
    fun currentRuntime(): SearchRuntimeSettings = checkNotNull(searchSettings.findByStatus(IndexModelStatus.PRESENT)) {
        "Current search settings are not initialized"
    }.runtime()

    @Transactional(readOnly = true)
    fun runtime(settingsId: Long): SearchRuntimeSettings = searchSettings.findById(settingsId).orElseThrow {
        ApiException(HttpStatus.NOT_FOUND, "Search settings not found")
    }.runtime()

    @Transactional(readOnly = true)
    fun retainedRuntimes(): List<SearchRuntimeSettings> = searchSettings.findAll().map { it.runtime() }

    @Transactional
    fun savePending(request: SearchSettingsRequest): IdResponse {
        indexLock.lock()
        currentEntity()
        val model = models.require(request.modelName.trim())
        val pending = searchSettings.findByStatus(IndexModelStatus.FUTURE)
        if (pending?.reindexStartedAt != null) {
            throw ApiException(HttpStatus.CONFLICT, "Embedding reindex is already in progress")
        }
        val target = pending ?: SearchSettingsEntity(
            status = IndexModelStatus.FUTURE,
            singletonMarker = 1,
            indexName = nextIndexName(model.modelName),
        )
        if (pending != null && pending.modelName != model.modelName) {
            target.indexName = nextIndexName(model.modelName)
        }
        target.modelName = model.modelName
        return IdResponse(requireNotNull(searchSettings.save(target).id))
    }

    @Transactional
    fun beginFuture(modelName: String, requirePast: Boolean, startedAt: Instant): SearchRuntimeSettings {
        indexLock.lock()
        currentEntity()
        val model = models.require(modelName.trim())
        val existingFuture = searchSettings.findByStatus(IndexModelStatus.FUTURE)
        if (existingFuture?.reindexStartedAt != null) {
            throw ApiException(HttpStatus.CONFLICT, "Embedding reindex is already in progress")
        }
        val past = searchSettings.findFirstByModelNameAndStatusOrderByIdAsc(model.modelName, IndexModelStatus.PAST)
        if (requirePast && past == null) {
            throw ApiException(HttpStatus.BAD_REQUEST, "No previous index is available for this model")
        }
        val target = when {
            requirePast -> requireNotNull(past)
            existingFuture?.modelName == model.modelName -> existingFuture
            past != null -> past
            else -> existingFuture ?: SearchSettingsEntity(indexName = nextIndexName(model.modelName))
        }
        if (existingFuture != null && existingFuture.id != target.id) {
            searchSettings.delete(existingFuture)
            searchSettings.flush()
        }
        if (target.modelName.isNotBlank() && target.modelName != model.modelName && target.status == IndexModelStatus.FUTURE) {
            target.indexName = nextIndexName(model.modelName)
        }
        target.modelName = model.modelName
        target.status = IndexModelStatus.FUTURE
        target.singletonMarker = 1
        target.reindexStartedAt = startedAt
        target.cancelRequestedAt = null
        target.cutoverAt = null
        return searchSettings.saveAndFlush(target).runtime()
    }

    @Transactional
    fun requestCancel(): SearchSettingsResponse {
        indexLock.lock()
        val future = searchSettings.findByStatus(IndexModelStatus.FUTURE)
            ?: throw ApiException(HttpStatus.CONFLICT, "No embedding reindex is in progress")
        future.cancelRequestedAt = Instant.now()
        return searchSettings.save(future).response()
    }

    @Transactional
    fun setCutover(settingsId: Long, value: Instant?) {
        indexLock.lock()
        val future = searchSettings.findById(settingsId).orElseThrow()
        check(future.status == IndexModelStatus.FUTURE)
        future.cutoverAt = value
        searchSettings.save(future)
    }

    @Transactional
    fun activateFuture(settingsId: Long): SearchRuntimeSettings {
        indexLock.lock()
        val current = currentEntity()
        val future = searchSettings.findById(settingsId).orElseThrow()
        check(future.status == IndexModelStatus.FUTURE)
        current.status = IndexModelStatus.PAST
        current.singletonMarker = null
        searchSettings.saveAndFlush(current)
        future.status = IndexModelStatus.PRESENT
        future.singletonMarker = 1
        future.cancelRequestedAt = null
        searchSettings.saveAndFlush(future)
        return future.runtime()
    }

    @Transactional
    fun deleteFuture(settingsId: Long) {
        indexLock.lock()
        searchSettings.findById(settingsId).orElse(null)?.takeIf { it.status == IndexModelStatus.FUTURE }
            ?.let(searchSettings::delete)
    }

    @Transactional(readOnly = true)
    fun executionConfig(settingsId: Long): EmbeddingExecutionConfig = runtime(settingsId).embedding

    @Transactional(readOnly = true)
    fun executionConfig(request: TestEmbeddingRequest): EmbeddingExecutionConfig = models.require(request.modelName.trim())
        .executionConfig()

    @Transactional(readOnly = true)
    fun localModels(statuses: JsonNode): List<LocalEmbeddingModelResponse> = models.all().map { model ->
        val status = statuses.path("models").path(model.modelName).path("code").asString("UNAVAILABLE")
        LocalEmbeddingModelResponse(
            modelName = model.modelName,
            displayName = model.displayName,
            dimension = model.dimension,
            available = status in AVAILABLE_MODEL_STATUSES,
            status = status,
            compatiblePastSettingsId = searchSettings
                .findFirstByModelNameAndStatusOrderByIdAsc(model.modelName, IndexModelStatus.PAST)
                ?.id,
        )
    }

    private fun currentEntity(): SearchSettingsEntity = searchSettings.findByStatus(IndexModelStatus.PRESENT)
        ?: searchSettings.save(
            SearchSettingsEntity(
                modelName = DEFAULT_LOCAL_EMBEDDING_MODEL,
                indexName = properties.opensearch.index,
            ),
        )

    private fun nextIndexName(modelName: String): String = models.indexNames(modelName, properties.opensearch.index)
        .firstOrNull { candidate -> searchSettings.findAll().none { it.indexName == candidate } }
        ?: throw ApiException(HttpStatus.CONFLICT, "No inactive index slot is available")

    private fun SearchSettingsEntity.response() = SearchSettingsResponse(
        id = requireNotNull(id),
        modelName = modelName,
        indexName = indexName,
        status = status,
        reindexStartedAt = reindexStartedAt,
        cancelRequestedAt = cancelRequestedAt,
        cutoverAt = cutoverAt,
    )

    private fun SearchSettingsEntity.runtime(): SearchRuntimeSettings {
        val model = models.require(modelName)
        return SearchRuntimeSettings(
            settingsId = requireNotNull(id),
            modelName = model.modelName,
            embedding = model.executionConfig(),
            index = OpenSearchIndexTarget(indexName, model.dimension),
        )
    }

    private fun LocalEmbeddingModel.executionConfig() = EmbeddingExecutionConfig(
        modelName = modelName,
        modelDim = dimension,
        normalize = normalize,
        maxContextLength = maxContextLength,
        queryPrefix = queryPrefix,
        passagePrefix = passagePrefix,
    )

    private companion object {
        val AVAILABLE_MODEL_STATUSES = setOf("NOT_LOADED", "AVAILABLE", "READY")
    }
}

@Service
class IndexSettingsInitializer(private val settings: IndexSettingsService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        settings.current()
    }
}
