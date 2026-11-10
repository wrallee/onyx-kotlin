package com.onyx.kotlin.opensearch

import com.onyx.kotlin.config.SearchProperties
import com.onyx.kotlin.connector.ConnectorSource
import com.onyx.kotlin.search.SearchCandidate
import com.onyx.kotlin.search.IndexedMetadata
import com.onyx.kotlin.search.SearchMetadataFilters
import com.onyx.kotlin.opensearch.HybridNormalizationPipelineRegistry
import com.onyx.kotlin.opensearch.OpenSearchChunkDocument
import com.onyx.kotlin.opensearch.OpenSearchClientFactory
import com.onyx.kotlin.opensearch.OpenSearchVectorStoreProperties
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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

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
    private val log = LoggerFactory.getLogger(OpenSearchIndexer::class.java)
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
        searchProperties: SearchProperties,
        pipelineRegistry: HybridNormalizationPipelineRegistry,
    ) : this(
        properties,
        openSearchClient,
        objectMapper,
        externalWrites,
        768,
        searchProperties,
        pipelineRegistry,
    )

    private val indexReady = ConcurrentHashMap.newKeySet<String>()

    fun keywordSearch(
        target: OpenSearchIndexTarget,
        query: String,
        documentSets: List<String>,
        count: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
        metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
    ): List<SearchCandidate> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(count > 0) { "count must be positive" }
        ensureIndex(target)

        val filter = searchFilter(documentSets, sourceTypes, updatedAfter, metadataFilters)
        val request = OpenSearchSearchRequest.Builder()
            .index(target.name)
            .size(count)
            .query(Query.of { q ->
                q.bool { b ->
                    b.must { m ->
                        m.multiMatch { mm ->
                            mm.query(query).fields(listOf("title^2", "content", "search_context^0.5"))
                        }
                    }
                    if (filter != null) {
                        b.filter(listOf(filter))
                    }
                    b
                }
            })
            .collapse { collapse -> collapse.field(SOURCE_CHUNK_ID_FIELD) }
            .build()

        return client.search(request, OpenSearchChunkDocument::class.java).hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    fun vectorSearch(
        target: OpenSearchIndexTarget,
        queryEmbedding: List<Double>,
        documentSets: List<String>,
        count: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
        metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
    ): List<SearchCandidate> {
        require(queryEmbedding.size == target.dimension) {
            "query embedding dimension must be ${target.dimension}"
        }
        require(count > 0) { "count must be positive" }
        ensureIndex(target)
        val candidateCount = Math.multiplyExact(count, searchProperties.hybridCandidateMultiplier)

        val knn = KnnQuery.Builder()
            .field(EMBEDDING_FIELD)
            .vector(queryEmbedding.map { it.toFloat() })
            .k(candidateCount)
        searchFilter(documentSets, sourceTypes, updatedAfter, metadataFilters)?.let { knn.filter(it) }

        val request = OpenSearchSearchRequest.Builder()
            .index(target.name)
            .size(count)
            .query(Query.of { q -> q.knn(knn.build()) })
            .collapse { collapse -> collapse.field(SOURCE_CHUNK_ID_FIELD) }
            .build()

        return client.search(request, OpenSearchChunkDocument::class.java).hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    fun hybridSearch(
        target: OpenSearchIndexTarget,
        query: String,
        queryEmbedding: List<Double>,
        documentSets: List<String>,
        limit: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
        metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
    ): List<SearchCandidate> {
        require(query.isNotBlank()) { "query must not be blank" }
        require(queryEmbedding.size == target.dimension) {
            "query embedding dimension must be ${target.dimension}"
        }
        require(limit > 0) { "limit must be positive" }
        ensureIndex(target)

        val registry = checkNotNull(pipelineRegistry) {
            "Hybrid normalization pipeline registry is not configured"
        }
        registry.ensureReady()
        val candidateCount = Math.multiplyExact(limit, searchProperties.hybridCandidateMultiplier)

        val keywordQuery = Query.of { q ->
            q.multiMatch { mm ->
                mm.query(query).fields(listOf("title^2", "content", "search_context^0.5"))
            }
        }
        val vectorQuery = Query.of { q ->
            q.knn { knn ->
                knn.field(EMBEDDING_FIELD)
                    .vector(queryEmbedding.map { it.toFloat() })
                    .k(candidateCount)
            }
        }
        val filter = searchFilter(documentSets, sourceTypes, updatedAfter, metadataFilters)
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
            .index(target.name)
            .size(limit)
            .searchPipeline(registry.selectedPipelineId())
            .query(hybridQuery)
            .collapse { collapse -> collapse.field(SOURCE_CHUNK_ID_FIELD) }
            .build()

        return client.search(request, OpenSearchChunkDocument::class.java).hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSearchCandidate(hit.id() ?: "", hit.score() ?: 0.0, mapper)
        }
    }

    fun keywordSearch(
        query: String,
        documentSets: List<String>,
        count: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
        metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
    ) = keywordSearch(defaultTarget(), query, documentSets, count, sourceTypes, updatedAfter, metadataFilters)

    fun vectorSearch(
        queryEmbedding: List<Double>,
        documentSets: List<String>,
        count: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
        metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
    ) = vectorSearch(defaultTarget(), queryEmbedding, documentSets, count, sourceTypes, updatedAfter, metadataFilters)

    fun hybridSearch(
        query: String,
        queryEmbedding: List<Double>,
        documentSets: List<String>,
        limit: Int,
        sourceTypes: List<String> = emptyList(),
        updatedAfter: Instant? = null,
        metadataFilters: SearchMetadataFilters = SearchMetadataFilters(),
    ) = hybridSearch(defaultTarget(), query, queryEmbedding, documentSets, limit, sourceTypes, updatedAfter, metadataFilters)

    private fun searchFilter(
        documentSets: List<String>,
        sourceTypes: List<String>,
        updatedAfter: Instant?,
        metadataFilters: SearchMetadataFilters,
    ): Query? {
        val clauses = mutableListOf<Query>()
        fun addTerms(field: String, values: List<String>) {
            values.distinct().takeIf { it.isNotEmpty() }?.let { selected ->
                clauses += Query.of { q ->
                    q.terms { terms ->
                        terms.field(field)
                            .terms { termsValues -> termsValues.value(selected.map { FieldValue.of(it) }) }
                    }
                }
            }
        }
        addTerms("document_sets", documentSets)
        addTerms("source_type", sourceTypes)
        addTerms("project_key", metadataFilters.projectKeys)
        addTerms("repository", metadataFilters.repositories)
        addTerms("space", metadataFilters.spaces)
        addTerms("status", metadataFilters.statuses)
        addTerms("document_type", metadataFilters.documentTypes)
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

    fun chunkById(id: String): SearchCandidate? = chunkById(defaultTarget(), id)

    fun chunkById(target: OpenSearchIndexTarget, id: String): SearchCandidate? {
        require(id.isNotBlank()) { "id must not be blank" }
        ensureIndex(target)
        val response = client.get(
            { get -> get.index(target.name).id(id) },
            OpenSearchChunkDocument::class.java,
        )
        val source = response.source() ?: return null
        return source.toSearchCandidate(response.id(), 0.0, mapper)
    }

    fun chunksInRange(
        ccPairId: Long,
        sourceDocumentId: String,
        minChunkId: Int,
        maxChunkId: Int,
    ): List<SearchCandidate> = chunksInRange(defaultTarget(), ccPairId, sourceDocumentId, minChunkId, maxChunkId)

    fun chunksInRange(
        target: OpenSearchIndexTarget,
        ccPairId: Long,
        sourceDocumentId: String,
        minChunkId: Int,
        maxChunkId: Int,
    ): List<SearchCandidate> {
        require(minChunkId <= maxChunkId) { "minChunkId must be <= maxChunkId" }
        ensureIndex(target)

        val query = Query.of { q ->
            q.bool { b ->
                b.filter(
                    listOf(
                        Query.of { q1 -> q1.term { t -> t.field("cc_pair_id").value(FieldValue.of(ccPairId)) } },
                        Query.of { q2 -> q2.term { t -> t.field("source_document_id").value(FieldValue.of(sourceDocumentId)) } },
                        Query.of { q3 -> q3.range { r -> r.field("chunk_id").gte(JsonData.of(minChunkId)).lte(JsonData.of(maxChunkId)) } },
                    ),
                )
            }
        }

        val searchRequest = OpenSearchSearchRequest.Builder()
            .index(target.name)
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
        deletePair(defaultTarget(), pairId)
    }

    fun deletePair(target: OpenSearchIndexTarget, pairId: Long) {
        val query = Query.of { q ->
            q.term { t ->
                t.field("cc_pair_id").value(FieldValue.of(pairId))
            }
        }
        deleteByQuery(target, query, "pair deletion")
    }

    fun deleteDocuments(pairId: Long, sourceDocumentIds: Set<String>) {
        deleteDocuments(defaultTarget(), pairId, sourceDocumentIds)
    }

    fun deleteDocuments(target: OpenSearchIndexTarget, pairId: Long, sourceDocumentIds: Set<String>) {
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
        deleteByQuery(target, query, "document deletion")
    }

    fun deleteStaleChunks(pairId: Long, sourceDocumentId: String, newChunkCount: Int) =
        deleteStaleChunks(defaultTarget(), pairId, sourceDocumentId, newChunkCount)

    fun deleteStaleChunks(
        target: OpenSearchIndexTarget,
        pairId: Long,
        sourceDocumentId: String,
        newChunkCount: Int,
    ) {
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
        deleteByQuery(target, query, "stale chunk deletion")
    }

    fun updateDocumentSets(pairId: Long, sourceDocumentIds: Set<String>, documentSetNames: List<String>) {
        updateDocumentSets(defaultTarget(), pairId, sourceDocumentIds, documentSetNames)
    }

    fun updateDocumentSets(
        target: OpenSearchIndexTarget,
        pairId: Long,
        sourceDocumentIds: Set<String>,
        documentSetNames: List<String>,
    ) {
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

        ensureIndex(target)
        val response = try {
            client.updateByQuery { u ->
                u.index(target.name)
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
                total >= 0 && updated + noops == total,
        ) { "OpenSearch did not fully apply the document set update" }
        if (sourceDocumentIds.size == 1) {
            check(total > 0) { "OpenSearch did not fully apply the document set update" }
            return
        }

        val matchedSourceDocumentIds = client.search(
            OpenSearchSearchRequest.Builder()
                .index(target.name)
                .size(sourceDocumentIds.size)
                .query(query)
                .collapse { it.field(EXACT_DOCUMENT_ID_FIELD) }
                .source { it.filter { filter -> filter.includes(EXACT_DOCUMENT_ID_FIELD) } }
                .build(),
            OpenSearchChunkDocument::class.java,
        ).hits().hits().mapNotNull { it.source()?.sourceDocumentId }.toSet()
        check(matchedSourceDocumentIds == sourceDocumentIds) {
            "OpenSearch did not fully apply the document set update"
        }
    }

    private fun deleteByQuery(query: Query, operation: String) =
        deleteByQuery(defaultTarget(), query, operation)

    private fun deleteByQuery(target: OpenSearchIndexTarget, query: Query, operation: String) {
        ensureIndex(target)
        val response = try {
            client.deleteByQuery { d ->
                d.index(target.name)
                    .query(query)
                    .refresh(Refresh.True)
            }
        } catch (e: ResponseException) {
            throw openSearchWriteError(operation, e.status(), e.message ?: "")
        } catch (e: OpenSearchException) {
            throw openSearchWriteError(operation, e.status(), e.message ?: "")
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
        indexedMetadata: IndexedMetadata = IndexedMetadata(),
    ) = upsert(
        defaultTarget(),
        pairId,
        sourceDocumentId,
        chunkId,
        title,
        content,
        link,
        metadata,
        embedding,
        documentSets,
        updatedAt,
        primaryOwners,
        secondaryOwners,
        sourceType,
        indexedMetadata,
    )

    fun upsert(
        target: OpenSearchIndexTarget,
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
        indexedMetadata: IndexedMetadata = IndexedMetadata(),
    ) {
        require(embedding.size == target.dimension) {
            "Embedding dimension ${embedding.size} does not match index ${target.name} dimension ${target.dimension}"
        }
        val documentId = Base64.getUrlEncoder().withoutPadding().encodeToString(
            (pairId.toString() + ":" + sourceDocumentId + ":" + chunkId).toByteArray(StandardCharsets.UTF_8),
        )

        val doc = OpenSearchChunkDocument(
            ccPairId = pairId,
            sourceDocumentId = sourceDocumentId,
            sourceChunkId = "$sourceDocumentId:$chunkId",
            chunkId = chunkId,
            title = title,
            content = content,
            link = link,
            metadata = metadata,
            embedding = embedding,
            sourceType = sourceType?.value,
            projectKey = indexedMetadata.projectKey,
            repository = indexedMetadata.repository,
            space = indexedMetadata.space,
            status = indexedMetadata.status,
            documentType = indexedMetadata.documentType,
            searchContext = indexedMetadata.searchContext(sourceType),
            documentSets = documentSets,
            docUpdatedAt = updatedAt?.toString(),
            primaryOwners = primaryOwners,
            secondaryOwners = secondaryOwners,
            externalUserEmails = emptyList(),
            externalUserGroupIds = emptyList(),
            isPublic = true,
        )

        ensureIndex(target)
        try {
            client.index<OpenSearchChunkDocument> { i ->
                i.index(target.name)
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

    fun ping(): Boolean = runCatching {
        client.ping().value()
    }.getOrDefault(false)

    fun clusterHealth(): String = runCatching {
        client.cluster().health().status().jsonValue()
    }.getOrDefault("red")

    fun resetIndex(target: OpenSearchIndexTarget) {
        deleteIndex(target)
        ensureIndex(target)
    }

    fun deleteIndex(target: OpenSearchIndexTarget) {
        externalWrites.withOpenSearchIndex(target.name) {
            try {
                val response = client.generic().execute(
                    Requests.builder().method("DELETE").endpoint("/${target.name}").build(),
                )
                response.use { res ->
                    if (res.status !in 200..299 && res.status != 404) {
                        throw openSearchWriteError("index deletion", res.status, res.body.map { it.bodyAsString() }.orElse(""))
                    }
                }
            } catch (e: ResponseException) {
                if (e.status() != 404) throw openSearchWriteError("index deletion", e.status(), e.message ?: "")
            } catch (e: OpenSearchException) {
                if (e.status() != 404) throw openSearchWriteError("index deletion", e.status(), e.message ?: "")
            }
            indexReady -= target.name
        }
    }

    private fun openSearchWriteError(operation: String, status: Int, body: String): IllegalStateException {
        log.error("OpenSearch {} failed with status {}: {}", operation, status, body)
        return IllegalStateException("OpenSearch $operation failed with status $status: $body")
    }

    private fun ensureIndex() = ensureIndex(defaultTarget())

    private fun ensureIndex(target: OpenSearchIndexTarget) {
        if (target.name in indexReady) return
        synchronized(indexReady) {
            if (target.name in indexReady) return
            externalWrites.withOpenSearchIndex(target.name) {
                val exists = indexExists(target.name)
                if (!exists) {
                    putJson("/${target.name}", indexDefinition(target.dimension), "index creation")
                } else {
                    putJson("/${target.name}/_mapping", documentMapping(target.dimension), "mapping update")
                }
            }
            indexReady += target.name
        }
    }

    private fun defaultTarget() = OpenSearchIndexTarget(properties.indexName, modelServerDimension)

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

    companion object {
        const val EXACT_DOCUMENT_ID_FIELD = "source_document_id"
        const val SOURCE_CHUNK_ID_FIELD = "source_chunk_id"
        const val EMBEDDING_FIELD = "embedding"
    }

    private fun vectorFieldDefinition(dimension: Int): Map<String, Any> = mapOf(
        "type" to "knn_vector",
        "dimension" to dimension,
        "method" to mapOf(
            "name" to "hnsw",
            "space_type" to "cosinesimil",
            "engine" to "lucene",
        ),
    )

    private fun indexDefinition(dimension: Int): Map<String, Any> = mapOf(
        "settings" to mapOf("index" to mapOf("knn" to true)),
        "mappings" to documentMapping(dimension),
    )

    private fun documentMapping(dimension: Int): Map<String, Any> = mapOf(
        "dynamic" to "strict",
        "properties" to mapOf(
            "cc_pair_id" to mapOf("type" to "long"),
            EXACT_DOCUMENT_ID_FIELD to mapOf("type" to "keyword"),
            SOURCE_CHUNK_ID_FIELD to mapOf("type" to "keyword"),
            "chunk_id" to mapOf("type" to "integer"),
            "title" to mapOf(
                "type" to "text",
                "analyzer" to "nori",
                "index_options" to "offsets",
                "fields" to mapOf(
                    "keyword" to mapOf("type" to "keyword", "ignore_above" to 256),
                ),
            ),
            "content" to mapOf(
                "type" to "text",
                "analyzer" to "nori",
                "index_options" to "offsets",
                "store" to true,
            ),
            "link" to mapOf(
                "type" to "keyword",
                "index" to false,
                "doc_values" to false,
                "store" to false,
            ),
            "metadata" to mapOf("type" to "object", "enabled" to false),
            EMBEDDING_FIELD to vectorFieldDefinition(dimension),
            "source_type" to mapOf("type" to "keyword"),
            "project_key" to mapOf("type" to "keyword"),
            "repository" to mapOf("type" to "keyword"),
            "space" to mapOf("type" to "keyword"),
            "status" to mapOf("type" to "keyword"),
            "document_type" to mapOf("type" to "keyword"),
            "search_context" to mapOf("type" to "text", "analyzer" to "nori"),
            "document_sets" to mapOf("type" to "keyword"),
            "doc_updated_at" to mapOf("type" to "date"),
            "primary_owners" to mapOf("type" to "keyword"),
            "secondary_owners" to mapOf("type" to "keyword"),
            "external_user_emails" to mapOf("type" to "keyword"),
            "external_user_group_ids" to mapOf("type" to "keyword"),
            "is_public" to mapOf("type" to "boolean"),
        ),
    )
}

internal val DOCUMENT_SET_UPDATE_TIMEOUT: Duration = Duration.ofSeconds(30)
internal val OPENSEARCH_TIMEOUT: Duration = Duration.ofSeconds(30)
internal val OPENSEARCH_MIGRATION_TIMEOUT: Duration = Duration.ofMinutes(10)
