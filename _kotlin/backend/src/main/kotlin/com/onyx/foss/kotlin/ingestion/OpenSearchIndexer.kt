package com.onyx.foss.kotlin.ingestion

import com.onyx.foss.kotlin.config.OnyxProperties
import com.onyx.foss.kotlin.config.SearchProperties
import com.onyx.foss.kotlin.domain.ConnectorSource
import com.onyx.foss.kotlin.opensearch.HybridNormalizationPipelineRegistry
import com.onyx.foss.kotlin.opensearch.OpenSearchChunkDocument
import com.onyx.foss.kotlin.opensearch.OpenSearchClientFactory
import com.onyx.foss.kotlin.opensearch.OpenSearchVectorStoreProperties
import org.opensearch.client.json.JsonData
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.Conflicts
import org.opensearch.client.opensearch._types.FieldValue
import org.opensearch.client.opensearch._types.OpenSearchException
import org.opensearch.client.opensearch._types.Refresh
import org.opensearch.client.opensearch._types.SortOrder
import org.opensearch.client.opensearch._types.query_dsl.KnnQuery
import org.opensearch.client.opensearch._types.query_dsl.Query
import org.opensearch.client.opensearch.core.SearchRequest as OpenSearchSearchRequest
import org.opensearch.client.opensearch.generic.Requests
import org.opensearch.client.opensearch.generic.Response
import org.opensearch.client.transport.httpclient5.ResponseException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

internal class OpenSearchWriteBlockedException(message: String) : RuntimeException(message)

@Service
class OpenSearchIndexer(
    val properties: OpenSearchVectorStoreProperties,
    val client: OpenSearchClient,
    private val mapper: ObjectMapper,
    private val externalWrites: PairExternalWriteFence,
    private val modelServerDimension: Int = 768,
    private val searchProperties: SearchProperties = SearchProperties(),
    private val pipelineRegistry: HybridNormalizationPipelineRegistry? = null,
) {
    constructor(
        properties: OpenSearchVectorStoreProperties,
        clientBuilder: Any?,
        mapper: ObjectMapper,
        externalWrites: PairExternalWriteFence,
        modelServerDimension: Int = 768,
        searchProperties: SearchProperties = SearchProperties(),
        pipelineRegistry: HybridNormalizationPipelineRegistry? = null,
    ) : this(
        properties,
        OpenSearchClientFactory.createClient(properties, mapper),
        mapper,
        externalWrites,
        modelServerDimension,
        searchProperties,
        pipelineRegistry,
    )

    @Autowired
    constructor(
        properties: OpenSearchVectorStoreProperties,
        openSearchClient: OpenSearchClient,
        objectMapper: ObjectMapper,
        externalWrites: PairExternalWriteFence,
        onyxProperties: OnyxProperties,
        searchProperties: SearchProperties,
        pipelineRegistry: HybridNormalizationPipelineRegistry,
    ) : this(
        properties,
        openSearchClient,
        objectMapper,
        externalWrites,
        onyxProperties.modelServer.embeddingDimension,
        searchProperties,
        pipelineRegistry,
    )

    private val indexReady = AtomicBoolean(false)

    fun keywordSearch(
        query: String,
        documentSets: List<String>,
        count: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
    ): List<SearchCandidate> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(count > 0) { "count must be positive" }
        ensureIndex()

        val filter = searchFilter(documentSets, sourceTypes, updatedAfter)
        val request = OpenSearchSearchRequest.Builder()
            .index(properties.indexName)
            .size(count)
            .query(Query.of { q ->
                q.bool { b ->
                    b.must { m ->
                        m.multiMatch { mm -> mm.query(query).fields(listOf("title^2", "content")) }
                    }
                    if (filter != null) {
                        b.filter(listOf(filter))
                    }
                    b
                }
            })
            .build()

        return client.search(request, OpenSearchChunkDocument::class.java).hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    fun vectorSearch(
        queryEmbedding: List<Double>,
        documentSets: List<String>,
        count: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
    ): List<SearchCandidate> {
        require(queryEmbedding.size == modelServerDimension) {
            "query embedding dimension must be $modelServerDimension"
        }
        require(count > 0) { "count must be positive" }
        ensureIndex()

        val knn = KnnQuery.Builder()
            .field(EMBEDDING_FIELD)
            .vector(queryEmbedding.map { it.toFloat() })
            .k(count)
        searchFilter(documentSets, sourceTypes, updatedAfter)?.let { knn.filter(it) }

        val request = OpenSearchSearchRequest.Builder()
            .index(properties.indexName)
            .size(count)
            .query(Query.of { q -> q.knn(knn.build()) })
            .build()

        return client.search(request, OpenSearchChunkDocument::class.java).hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    fun hybridSearch(
        query: String,
        queryEmbedding: List<Double>,
        documentSets: List<String>,
        limit: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
    ): List<SearchCandidate> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(queryEmbedding.size == modelServerDimension) {
            "query embedding dimension must be $modelServerDimension"
        }
        require(limit > 0) { "limit must be positive" }
        ensureIndex()

        val registry = checkNotNull(pipelineRegistry) {
            "Hybrid normalization pipeline registry is not configured"
        }
        registry.ensureReady()
        val candidateCount = Math.multiplyExact(limit, searchProperties.hybridCandidateMultiplier)

        val keywordQuery = Query.of { q ->
            q.multiMatch { mm -> mm.query(query).fields(listOf("title^2", "content")) }
        }
        val vectorQuery = Query.of { q ->
            q.knn { knn ->
                knn.field(EMBEDDING_FIELD)
                    .vector(queryEmbedding.map { it.toFloat() })
                    .k(candidateCount)
            }
        }
        val filter = searchFilter(documentSets, sourceTypes, updatedAfter)
        val hybridQuery = Query.of { q ->
            q.hybrid { hybrid ->
                hybrid.queries(listOf(keywordQuery, vectorQuery))
                    .paginationDepth(candidateCount)
                if (filter != null) {
                    hybrid.filter(filter)
                }
                hybrid
            }
        }
        val request = OpenSearchSearchRequest.Builder()
            .index(properties.indexName)
            .size(limit)
            .searchPipeline(registry.selectedPipelineId())
            .query(hybridQuery)
            .collapse { collapse -> collapse.field(EXACT_DOCUMENT_ID_FIELD) }
            .build()

        return client.search(request, OpenSearchChunkDocument::class.java).hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    private fun searchFilter(
        documentSets: List<String>,
        sourceTypes: List<String>,
        updatedAfter: Instant?,
    ): Query? {
        val clauses = mutableListOf<Query>()
        documentSets.distinct().takeIf { it.isNotEmpty() }?.let { sets ->
            clauses += Query.of { q ->
                q.terms { terms ->
                    terms.field("document_sets")
                        .terms { values -> values.value(sets.map { FieldValue.of(it) }) }
                }
            }
        }
        sourceTypes.distinct().takeIf { it.isNotEmpty() }?.let { sources ->
            clauses += Query.of { q ->
                q.terms { terms ->
                    terms.field("source_type")
                        .terms { values -> values.value(sources.map { FieldValue.of(it) }) }
                }
            }
        }
        if (updatedAfter != null) {
            clauses += Query.of { q ->
                q.range { range ->
                    range.field("doc_updated_at").gte(JsonData.of(updatedAfter.toString()))
                }
            }
        }
        return clauses.takeIf { it.isNotEmpty() }
            ?.let { Query.of { q -> q.bool { b -> b.filter(it) } } }
    }

    fun chunksInRange(sourceDocumentId: String, minChunkId: Int, maxChunkId: Int): List<SearchCandidate> {
        require(minChunkId <= maxChunkId) { "minChunkId must be <= maxChunkId" }
        ensureIndex()

        val query = Query.of { q ->
            q.bool { b ->
                b.filter(
                    listOf(
                        Query.of { q1 -> q1.term { t -> t.field("source_document_id").value(FieldValue.of(sourceDocumentId)) } },
                        Query.of { q2 -> q2.range { r -> r.field("chunk_id").gte(JsonData.of(minChunkId)).lte(JsonData.of(maxChunkId)) } },
                    ),
                )
            }
        }

        val searchRequest = OpenSearchSearchRequest.Builder()
            .index(properties.indexName)
            .size(maxChunkId - minChunkId + 1)
            .sort { s -> s.field { f -> f.field("chunk_id").order(SortOrder.Asc) } }
            .query(query)
            .build()

        val response = client.search(searchRequest, OpenSearchChunkDocument::class.java)
        return response.hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    fun deletePair(pairId: Long) {
        val query = Query.of { q ->
            q.term { t ->
                t.field("cc_pair_id").value(FieldValue.of(pairId))
            }
        }
        deleteByQuery(query, "pair deletion")
    }

    fun deleteDocuments(pairId: Long, sourceDocumentIds: Set<String>) {
        val query = Query.of { q ->
            q.bool { b ->
                b.filter(
                    listOf(
                        Query.of { q1 -> q1.term { t -> t.field("cc_pair_id").value(FieldValue.of(pairId)) } },
                        Query.of { q2 -> q2.terms { t -> t.field("source_document_id").terms { ts -> ts.value(sourceDocumentIds.map { FieldValue.of(it) }) } } },
                    ),
                )
            }
        }
        deleteByQuery(query, "document deletion")
    }

    fun deleteStaleChunks(pairId: Long, sourceDocumentId: String, newChunkCount: Int) {
        val query = Query.of { q ->
            q.bool { b ->
                b.filter(
                    listOf(
                        Query.of { q1 -> q1.term { t -> t.field("cc_pair_id").value(FieldValue.of(pairId)) } },
                        Query.of { q2 -> q2.term { t -> t.field(EXACT_DOCUMENT_ID_FIELD).value(FieldValue.of(sourceDocumentId)) } },
                        Query.of { q3 -> q3.range { r -> r.field("chunk_id").gte(JsonData.of(newChunkCount)) } },
                    ),
                )
            }
        }
        deleteByQuery(query, "stale chunk deletion")
    }

    fun updateDocumentSets(pairId: Long, sourceDocumentIds: Set<String>, documentSetNames: List<String>) {
        if (sourceDocumentIds.isEmpty()) return
        val query = Query.of { q ->
            q.bool { b ->
                b.filter(
                    listOf(
                        Query.of { q1 -> q1.term { t -> t.field("cc_pair_id").value(FieldValue.of(pairId)) } },
                        Query.of { q2 -> q2.terms { t -> t.field("source_document_id").terms { ts -> ts.value(sourceDocumentIds.map { FieldValue.of(it) }) } } },
                    ),
                )
            }
        }

        val response = withMigrationRetry {
            try {
                client.updateByQuery { u ->
                    u.index(properties.indexName)
                        .refresh(Refresh.True)
                        .conflicts(Conflicts.Proceed)
                        .script { s ->
                            s.inline { i ->
                                i.source("ctx._source.document_sets = params.document_sets")
                                    .params("document_sets", JsonData.of(documentSetNames))
                            }
                        }
                        .query(query)
                }
            } catch (e: ResponseException) {
                throw openSearchWriteError("document set update", e.status(), e.message ?: "")
            } catch (e: OpenSearchException) {
                throw openSearchWriteError("document set update", e.status(), e.message ?: "")
            }
        }

        val total = response.total() ?: -1L
        val updated = response.updated() ?: -1L
        val noops = response.noops() ?: -1L
        val failures = response.failures()
        val versionConflicts = response.versionConflicts() ?: -1L
        val timedOut = response.timedOut() ?: true

        check(
            !timedOut &&
                failures.isEmpty() &&
                versionConflicts == 0L &&
                total >= sourceDocumentIds.size && updated + noops == total,
        ) { "OpenSearch did not fully apply the document set update" }
    }

    private fun deleteByQuery(query: Query, operation: String) {
        val response = withMigrationRetry {
            try {
                client.deleteByQuery { d ->
                    d.index(properties.indexName)
                        .query(query)
                        .refresh(Refresh.True)
                }
            } catch (e: ResponseException) {
                throw openSearchWriteError(operation, e.status(), e.message ?: "")
            } catch (e: OpenSearchException) {
                throw openSearchWriteError(operation, e.status(), e.message ?: "")
            }
        }

        val total = response.total() ?: -1L
        val deleted = response.deleted() ?: -1L
        val failures = response.failures()
        val versionConflicts = response.versionConflicts() ?: -1L
        val timedOut = response.timedOut() ?: true

        check(
            !timedOut &&
                failures.isEmpty() &&
                versionConflicts == 0L && total >= 0 && deleted == total,
        ) { "OpenSearch did not fully apply the $operation" }
    }

    fun upsert(
        pairId: Long,
        sourceDocumentId: String,
        chunkId: Int,
        title: String,
        content: String,
        link: String?,
        metadata: Map<String, Any?>,
        embedding: List<Double>,
        documentSets: List<String> = emptyList(),
        updatedAt: Instant? = null,
        primaryOwners: List<String> = emptyList(),
        secondaryOwners: List<String> = emptyList(),
        sourceType: ConnectorSource? = null,
    ) {
        val documentId = Base64.getUrlEncoder().withoutPadding().encodeToString(
            (pairId.toString() + ":" + sourceDocumentId + ":" + chunkId).toByteArray(StandardCharsets.UTF_8),
        )

        val doc = OpenSearchChunkDocument(
            ccPairId = pairId,
            sourceDocumentId = sourceDocumentId,
            chunkId = chunkId,
            title = title,
            content = content,
            link = link,
            metadata = metadata,
            embedding = embedding,
            sourceType = sourceType?.value,
            documentSets = documentSets,
            docUpdatedAt = updatedAt?.toString(),
            primaryOwners = primaryOwners,
            secondaryOwners = secondaryOwners,
            externalUserEmails = emptyList(),
            externalUserGroupIds = emptyList(),
            isPublic = true,
        )

        withMigrationRetry {
            try {
                client.index<OpenSearchChunkDocument> { i ->
                    i.index(properties.indexName)
                        .id(documentId)
                        .document(doc)
                        .refresh(Refresh.True)
                }
            } catch (e: ResponseException) {
                throw openSearchWriteError("index write", e.status(), e.message ?: "")
            } catch (e: OpenSearchException) {
                throw openSearchWriteError("index write", e.status(), e.message ?: "")
            }
        }
    }

    fun ping(): Boolean = runCatching {
        client.ping().value()
    }.getOrDefault(false)

    fun clusterHealth(): String = runCatching {
        client.cluster().health().status().jsonValue()
    }.getOrDefault("red")

    private fun <T> withMigrationRetry(action: () -> T): T {
        var lastBlock: OpenSearchWriteBlockedException? = null
        repeat(2) {
            ensureIndex()
            try {
                return action()
            } catch (error: OpenSearchWriteBlockedException) {
                indexReady.set(false)
                lastBlock = error
            }
        }
        throw requireNotNull(lastBlock)
    }

    private fun openSearchWriteError(operation: String, status: Int, body: String): RuntimeException {
        val message = "OpenSearch $operation failed: $body"
        return if (status == 403 && body.contains("cluster_block_exception")) {
            OpenSearchWriteBlockedException(message)
        } else {
            IllegalStateException(message)
        }
    }

    private fun ensureIndex() {
        if (indexReady.get()) return
        synchronized(indexReady) {
            if (indexReady.get()) return
            externalWrites.withOpenSearchIndex(properties.indexName) {
                val exists = indexExists(properties.indexName)
                if (!exists) {
                    putJson("/${properties.indexName}", indexDefinition(), "index creation")
                } else {
                    val mapping = mapping(properties.indexName, "mapping check")
                    val missingFields = linkedMapOf<String, Any>()
                    var incompatibleMapping = false
                    val embedding = mapping.properties.path(EMBEDDING_FIELD)
                    val embeddingType = embedding.path("type").asString()
                    check(
                        embeddingType.isBlank() ||
                            embeddingType == "knn_vector" &&
                            embedding.path("dimension").asInt() == modelServerDimension,
                    ) {
                        "Existing embedding mapping is incompatible; delete the OpenSearch index and reindex documents"
                    }
                    EXACT_FIELDS.forEach { (field, definition) ->
                        val expectedType = (definition as Map<*, *>)["type"].toString()
                        val actualType = mapping.properties.path(field).path("type").asString()
                        if (actualType.isBlank()) {
                            missingFields[field] = definition
                        } else if (actualType != expectedType) {
                            incompatibleMapping = true
                        }
                    }
                    if (embeddingType.isBlank()) {
                        missingFields[EMBEDDING_FIELD] = vectorFieldDefinition()
                    }
                    if (incompatibleMapping) {
                        reindexWithExactMappings(mapping.concreteIndex)
                    } else if (missingFields.isNotEmpty()) {
                        putJson("/${properties.indexName}/_mapping", mapOf("properties" to missingFields), "exact mapping creation")
                    }
                }
            }
            indexReady.set(true)
        }
    }

    private fun reindexWithExactMappings(sourceIndex: String) {
        val logicalIndex = properties.indexName
        val replacementIndex = "$logicalIndex-exact-v1"
        check(sourceIndex != replacementIndex) { "OpenSearch exact mapping replacement cannot replace itself" }
        try {
            addWriteBlock(sourceIndex)
        } catch (error: Exception) {
            if (exactLogicalMapping() != null) return
            if (!isWriteBlocked(sourceIndex)) throw error
        }
        try {
            ensureReplacementIndex(replacementIndex)
            val sourceCount = getJson("/$sourceIndex/_count", "source count").path("count").asLong(-1)
            val result = postJson(
                "/_reindex?refresh=true&wait_for_completion=true",
                mapOf(
                    "source" to mapOf("index" to sourceIndex),
                    "dest" to mapOf("index" to replacementIndex),
                ),
                "exact mapping reindex",
            )
            check(
                sourceCount >= 0 && !result.path("timed_out").asBoolean(true) &&
                    result.path("failures").isArray && result.path("failures").isEmpty &&
                    result.path("version_conflicts").asLong(-1) == 0L &&
                    result.path("total").asLong(-1) == sourceCount &&
                    result.path("created").asLong(-1) + result.path("updated").asLong(-1) == sourceCount &&
                    getJson("/$replacementIndex/_count", "replacement count").path("count").asLong(-1) == sourceCount,
            ) { "OpenSearch did not fully reindex the existing index" }

            val current = mapping(logicalIndex, "pre-swap mapping check")
            if (hasExactMappings(current.properties)) return
            check(current.concreteIndex == sourceIndex) {
                "OpenSearch logical index changed to an incompatible index during migration"
            }
            val aliasResult = postJson(
                "/_aliases",
                mapOf(
                    "actions" to listOf(
                        mapOf("remove_index" to mapOf("index" to sourceIndex)),
                        mapOf("add" to mapOf("index" to replacementIndex, "alias" to logicalIndex, "is_write_index" to true)),
                    ),
                ),
                "exact mapping alias swap",
            )
            check(aliasResult.path("acknowledged").asBoolean(false)) {
                "OpenSearch did not confirm the exact mapping alias swap"
            }
        } catch (error: Exception) {
            if (exactLogicalMapping() != null) return
            throw error
        }
    }

    private fun addWriteBlock(sourceIndex: String) {
        val result = putWithoutBodyJson("/$sourceIndex/_block/write", "migration write block")
        check(
            result.path("acknowledged").asBoolean(false) &&
                result.path("shards_acknowledged").asBoolean(false),
        ) { "OpenSearch did not confirm the migration write block" }
    }

    private fun isWriteBlocked(sourceIndex: String): Boolean = runCatching {
        val settings = getJson(
            "/$sourceIndex/_settings/index.blocks.write?flat_settings=true",
            "migration write block check",
        ).path(sourceIndex).path("settings")
        settings.path("index.blocks.write").asString().equals("true", ignoreCase = true) ||
            settings.path("index").path("blocks").path("write").asBoolean(false)
    }.getOrDefault(false)

    private fun ensureReplacementIndex(replacementIndex: String) {
        if (!indexExists(replacementIndex)) {
            try {
                putJson("/$replacementIndex", indexDefinition(), "replacement index creation")
            } catch (error: Exception) {
                if (!indexExists(replacementIndex)) throw error
            }
        }
        check(hasExactMappings(mapping(replacementIndex, "replacement mapping check").properties)) {
            "OpenSearch replacement index does not have exact mappings"
        }
    }

    private fun exactLogicalMapping(): IndexMapping? = runCatching {
        mapping(properties.indexName, "migration recovery mapping check")
    }.getOrNull()?.takeIf { hasExactMappings(it.properties) }

    private fun hasExactMappings(propertiesNode: JsonNode): Boolean = EXACT_FIELDS.all { (field, definition) ->
        propertiesNode.path(field).path("type").asString() == (definition as Map<*, *>)["type"].toString()
    }

    private fun mapping(index: String, operation: String): IndexMapping {
        val response = getJson("/$index/_mapping", operation)
        check(response.size() == 1) { "OpenSearch index must resolve to one concrete index" }
        val entry = response.properties().first()
        return IndexMapping(entry.key, entry.value.path("mappings").path("properties"))
    }

    private fun indexExists(index: String): Boolean {
        return try {
            val request = Requests.builder()
                .method("HEAD")
                .endpoint("/$index")
                .build()
            val response: Response = client.generic().execute(request)
            response.use { res: Response ->
                res.status in 200..299
            }
        } catch (e: ResponseException) {
            if (e.status() == 404) false else throw e
        } catch (e: OpenSearchException) {
            if (e.status() == 404) false else throw e
        }
    }

    private fun getJson(endpoint: String, operation: String): JsonNode {
        try {
            val request = Requests.builder()
                .method("GET")
                .endpoint(endpoint)
                .build()
            val response: Response = client.generic().execute(request)
            return response.use { res: Response ->
                val body = res.body.map { it.bodyAsString() }.orElse("")
                if (res.status !in 200..299) {
                    throw openSearchWriteError(operation, res.status, body)
                }
                if (body.isBlank()) mapper.createObjectNode() else mapper.readTree(body)
            }
        } catch (e: ResponseException) {
            throw openSearchWriteError(operation, e.status(), e.message ?: "")
        }
    }

    private fun putJson(endpoint: String, body: Map<String, Any>, operation: String) {
        val jsonStr = mapper.writeValueAsString(body)
        try {
            val request = Requests.builder()
                .method("PUT")
                .endpoint(endpoint)
                .json(jsonStr)
                .build()
            val response: Response = client.generic().execute(request)
            response.use { res: Response ->
                val resBody = res.body.map { it.bodyAsString() }.orElse("")
                if (res.status !in 200..299) {
                    throw openSearchWriteError(operation, res.status, resBody)
                }
            }
        } catch (e: ResponseException) {
            throw openSearchWriteError(operation, e.status(), e.message ?: "")
        }
    }

    private fun putWithoutBodyJson(endpoint: String, operation: String): JsonNode {
        try {
            val request = Requests.builder()
                .method("PUT")
                .endpoint(endpoint)
                .build()
            val response: Response = client.generic().execute(request)
            return response.use { res: Response ->
                val body = res.body.map { it.bodyAsString() }.orElse("")
                if (res.status !in 200..299) {
                    throw openSearchWriteError(operation, res.status, body)
                }
                if (body.isBlank()) mapper.createObjectNode() else mapper.readTree(body)
            }
        } catch (e: ResponseException) {
            throw openSearchWriteError(operation, e.status(), e.message ?: "")
        }
    }

    private fun postJson(endpoint: String, body: Map<String, Any>, operation: String): JsonNode {
        val jsonStr = mapper.writeValueAsString(body)
        try {
            val request = Requests.builder()
                .method("POST")
                .endpoint(endpoint)
                .json(jsonStr)
                .build()
            val response: Response = client.generic().execute(request)
            return response.use { res: Response ->
                val resBody = res.body.map { it.bodyAsString() }.orElse("")
                if (res.status !in 200..299) {
                    throw openSearchWriteError(operation, res.status, resBody)
                }
                if (resBody.isBlank()) mapper.createObjectNode() else mapper.readTree(resBody)
            }
        } catch (e: ResponseException) {
            throw openSearchWriteError(operation, e.status(), e.message ?: "")
        }
    }

    private data class IndexMapping(
        val concreteIndex: String,
        val properties: JsonNode,
    )

    companion object {
        const val EXACT_DOCUMENT_ID_FIELD = "source_document_id"
        const val EMBEDDING_FIELD = "embedding"
        val EXACT_FIELDS: Map<String, Any> = mapOf(
            "cc_pair_id" to mapOf("type" to "long"),
            EXACT_DOCUMENT_ID_FIELD to mapOf("type" to "keyword"),
            "chunk_id" to mapOf("type" to "integer"),
            "source_type" to mapOf("type" to "keyword"),
            "external_user_emails" to mapOf("type" to "keyword"),
            "external_user_group_ids" to mapOf("type" to "keyword"),
            "is_public" to mapOf("type" to "boolean"),
            "document_sets" to mapOf("type" to "keyword"),
            "doc_updated_at" to mapOf("type" to "date"),
            "primary_owners" to mapOf("type" to "keyword"),
            "secondary_owners" to mapOf("type" to "keyword"),
        )
    }

    private fun vectorFieldDefinition(): Map<String, Any> = mapOf(
        "type" to "knn_vector",
        "dimension" to modelServerDimension,
        "method" to mapOf(
            "name" to "hnsw",
            "space_type" to "cosinesimil",
            "engine" to "lucene",
        ),
    )

    private fun indexDefinition(): Map<String, Any> = mapOf(
        "settings" to mapOf("index" to mapOf("knn" to true)),
        "mappings" to mapOf(
            "properties" to EXACT_FIELDS + (EMBEDDING_FIELD to vectorFieldDefinition()),
        ),
    )
}
