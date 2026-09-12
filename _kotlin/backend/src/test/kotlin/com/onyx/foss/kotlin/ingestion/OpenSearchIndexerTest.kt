package com.onyx.foss.kotlin.ingestion

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.onyx.foss.kotlin.config.SearchProperties
import com.onyx.foss.kotlin.opensearch.HybridNormalizationPipelineRegistry
import com.onyx.foss.kotlin.opensearch.MinMaxNormalizationPipeline
import com.onyx.foss.kotlin.opensearch.OpenSearchClientFactory
import com.onyx.foss.kotlin.opensearch.OpenSearchVectorStoreProperties
import com.onyx.foss.kotlin.opensearch.ZScoreNormalizationPipeline
import org.opensearch.client.opensearch.OpenSearchClient
import com.onyx.foss.kotlin.connector.ConnectorSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import java.time.Duration
import java.util.concurrent.TimeUnit

class OpenSearchIndexerTest {

    private fun testProperties(server: MockWebServer): OpenSearchVectorStoreProperties {
        return OpenSearchVectorStoreProperties(
            uris = listOf(server.url("/").toString().trimEnd('/')),
            indexName = "documents",
        )
    }
    private val mapper = jacksonObjectMapper()
    private val externalWrites = mock(PairExternalWriteFence::class.java).also { fence ->
        doAnswer { invocation -> invocation.getArgument<() -> Unit>(1).invoke() }
            .`when`(fence).withOpenSearchIndex(anyString(), any<() -> Unit>() ?: {})
    }

    @Test
    fun `keyword and vector search apply the same document set filter`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(acknowledgedResponse())
            server.enqueue(jsonResponse(searchResponse("keyword", 2.0)))
            server.enqueue(jsonResponse(searchResponse("vector", 1.5)))
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            val keywordResults = indexer.keywordSearch(
                query = "deployment guide",
                documentSets = listOf("Engineering", "Operations"),
                count = 30,
            )
            val vectorResults = indexer.vectorSearch(
                queryEmbedding = List(768) { 0.1 },
                documentSets = listOf("Engineering", "Operations"),
                count = 30,
            )

            server.takeRequest()
            server.takeRequest()
            val keyword = mapper.readTree(server.takeRequest().body.readUtf8())
            val vector = mapper.readTree(server.takeRequest().body.readUtf8())
            assertThat(keyword.path("size").asInt()).isEqualTo(30)
            assertThat(keyword.path("query").path("bool").path("filter").first()
                .path("bool").path("filter").first().path("terms").path("document_sets")
                .toList().map { it.asString() })
                .containsExactly("Engineering", "Operations")
            val vectorFilter = vector.path("query").path("knn").path("embedding").path("filter")
                .path("bool").path("filter").first().path("terms").path("document_sets")
                .toList().map { it.asString() }
            assertThat(vectorFilter).containsExactly("Engineering", "Operations")
            assertThat(keywordResults.single().id).isEqualTo("keyword")
            assertThat(vectorResults.single().id).isEqualTo("vector")
        }
    }

    @Test
    fun `keyword search applies source type and updated-after filters`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(acknowledgedResponse())
            server.enqueue(jsonResponse(searchResponse("keyword", 2.0)))
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            indexer.keywordSearch(
                query = "deployment guide",
                documentSets = emptyList(),
                count = 30,
                sourceTypes = listOf("jira", "github"),
                updatedAfter = java.time.Instant.parse("2026-01-01T00:00:00Z"),
            )

            server.takeRequest()
            server.takeRequest()
            val keyword = mapper.readTree(server.takeRequest().body.readUtf8())
            val filters = keyword.path("query").path("bool").path("filter").first()
                .path("bool").path("filter").toList()
            assertThat(filters.map { it.path("terms").path("source_type") }.filter { !it.isMissingNode }
                .single().toList().map { it.asString() }).containsExactly("jira", "github")
            assertThat(filters.map { it.path("range").path("doc_updated_at").path("gte") }
                .filter { !it.isMissingNode }.single().asString()).isEqualTo("2026-01-01T00:00:00Z")
        }
    }

    @Test
    fun `hybrid search uses configured candidate depth and returns requested size`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(acknowledgedResponse())
            server.enqueue(jsonResponse("""{"acknowledged":true}"""))
            server.enqueue(jsonResponse("{}"))
            server.enqueue(jsonResponse(searchResponse("hybrid", 1.8)))
            server.start()

            val properties = testProperties(server)
            val searchProperties = SearchProperties(hybridCandidateMultiplier = 8)
            OpenSearchClientFactory.createTransport(properties, mapper).use { transport ->
                val client = OpenSearchClient(transport)
                val registry = HybridNormalizationPipelineRegistry(
                    MinMaxNormalizationPipeline(client, properties, searchProperties),
                    ZScoreNormalizationPipeline(client, properties, searchProperties, mapper),
                    searchProperties,
                )
                val indexer = OpenSearchIndexer(
                    properties,
                    client,
                    mapper,
                    externalWrites,
                    768,
                    searchProperties,
                    registry,
                )

                val results = indexer.hybridSearch(
                    query = "deployment guide",
                    queryEmbedding = List(768) { 0.1 },
                    documentSets = listOf("Engineering"),
                    limit = 7,
                )

                server.takeRequest()
                server.takeRequest()
                server.takeRequest()
                server.takeRequest()
                val searchRequest = server.takeRequest()
                val body = mapper.readTree(searchRequest.body.readUtf8())
                assertThat(searchRequest.requestUrl?.queryParameter("search_pipeline"))
                    .isEqualTo("documents-hybrid-min-max")
                assertThat(body.path("size").asInt()).isEqualTo(7)
                val hybrid = body.path("query").path("hybrid")
                assertThat(hybrid.path("pagination_depth").asInt()).isEqualTo(56)
                assertThat(hybrid.path("queries").get(1).path("knn").path("embedding").path("k").asInt())
                    .isEqualTo(56)
                assertThat(body.path("collapse").path("field").asString()).isEqualTo("source_document_id")
                assertThat(body.has("sort")).isFalse()
                assertThat(hybrid.path("filter").path("bool").path("filter").first()
                    .path("terms").path("document_sets").toList().map { it.asString() })
                    .containsExactly("Engineering")
                assertThat(results.single().id).isEqualTo("hybrid")
            }
        }
    }

    @Test
    fun `chunksInRange fetches ordered chunks for a document`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(acknowledgedResponse())
            server.enqueue(
                jsonResponse(
                    """{"took":1,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},"hits":{"hits":[
                        {"_id":"a","_score":1.0,"_source":{"cc_pair_id":7,"source_document_id":"doc-1","chunk_id":1,"title":"T","content":"above","link":null,"metadata":{}}},
                        {"_id":"b","_score":1.0,"_source":{"cc_pair_id":7,"source_document_id":"doc-1","chunk_id":2,"title":"T","content":"center","link":null,"metadata":{}}},
                        {"_id":"c","_score":1.0,"_source":{"cc_pair_id":7,"source_document_id":"doc-1","chunk_id":3,"title":"T","content":"below","link":null,"metadata":{}}}
                    ]}}""",
                ),
            )
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            val chunks = indexer.chunksInRange(7, "doc-1", minChunkId = 1, maxChunkId = 3)

            server.takeRequest()
            server.takeRequest()
            val request = mapper.readTree(server.takeRequest().body.readUtf8())
            assertThat(request.path("size").asInt()).isEqualTo(3)
            val filters = request.path("query").path("bool").path("filter").toList()
            assertThat(filters.map { it.path("term").path("cc_pair_id") }.filter { !it.isMissingNode }
                .single().termValue()).isEqualTo(7)
            assertThat(filters.map { it.path("term").path("source_document_id") }.filter { !it.isMissingNode }
                .single().termString()).isEqualTo("doc-1")
            val range = filters.map { it.path("range").path("chunk_id") }.filter { !it.isMissingNode }.single()
            assertThat(range.path("gte").asInt()).isEqualTo(1)
            assertThat(range.path("lte").asInt()).isEqualTo(3)
            assertThat(chunks.map { it.content }).containsExactly("above", "center", "below")
        }
    }

    @Test
    fun `chunkById returns the exact indexed copy`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(acknowledgedResponse())
            server.enqueue(
                jsonResponse(
                    """{"_index":"documents","_id":"chunk-7","_version":1,"_seq_no":0,"_primary_term":1,"found":true,"_source":{"cc_pair_id":7,"source_document_id":"doc-1","chunk_id":2,"title":"T","content":"center","metadata":{}}}""",
                ),
            )
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            val chunk = indexer.chunkById("chunk-7")

            server.takeRequest()
            server.takeRequest()
            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("GET")
            assertThat(request.path).isEqualTo("/documents/_doc/chunk-7")
            assertThat(chunk?.ccPairId).isEqualTo(7)
            assertThat(chunk?.sourceDocumentId).isEqualTo("doc-1")
            assertThat(chunk?.chunkId).isEqualTo(2)
        }
    }

    @Test
    fun `new index uses the complete strict Nori mapping`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(acknowledgedResponse())
            server.enqueue(indexSuccessResponse())
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites, 768)

            indexer.upsert(7, "one", 0, "One", "content", null, emptyMap(), listOf(0.1))

            server.takeRequest()
            val create = server.takeRequest()
            val body = mapper.readTree(create.body.readUtf8())
            val mappings = body.path("mappings")
            val properties = mappings.path("properties")
            val embedding = properties.path("embedding")
            assertThat(create.path).isEqualTo("/documents")
            val knnSetting = body.path("settings").let { if (it.has("index")) it.path("index").path("knn") else it.path("knn") }
            assertThat(knnSetting.asBoolean()).isTrue()
            assertThat(mappings.path("dynamic").asString()).isEqualTo("strict")
            assertThat(properties.properties().map { it.key }).containsExactlyInAnyOrder(
                "cc_pair_id",
                "source_document_id",
                "chunk_id",
                "title",
                "content",
                "link",
                "metadata",
                "embedding",
                "source_type",
                "document_sets",
                "doc_updated_at",
                "primary_owners",
                "secondary_owners",
                "external_user_emails",
                "external_user_group_ids",
                "is_public",
            )
            assertThat(properties.path("title").path("analyzer").asString()).isEqualTo("nori")
            assertThat(properties.path("title").path("index_options").asString()).isEqualTo("offsets")
            assertThat(properties.path("title").path("fields").path("keyword").path("ignore_above").asInt())
                .isEqualTo(256)
            assertThat(properties.path("content").path("analyzer").asString()).isEqualTo("nori")
            assertThat(properties.path("content").path("store").asBoolean()).isTrue()
            assertThat(properties.path("content").path("index_options").asString()).isEqualTo("offsets")
            assertThat(properties.path("metadata").path("type").asString()).isEqualTo("object")
            assertThat(properties.path("metadata").path("enabled").asBoolean()).isFalse()
            assertThat(properties.path("link").path("index").asBoolean()).isFalse()
            assertThat(embedding.path("type").asString()).isEqualTo("knn_vector")
            assertThat(embedding.path("dimension").asInt()).isEqualTo(768)
            assertThat(embedding.path("method").path("engine").asString()).isEqualTo("lucene")
            assertThat(embedding.path("method").path("space_type").asString()).isEqualTo("cosinesimil")
        }
    }

    @Test
    fun `upsert stores the connector source type on each chunk`() {
        MockWebServer().use { server ->
            enqueueKeywordMapping(server)
            server.enqueue(indexSuccessResponse())
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            indexer.upsert(
                7, "one", 0, "One", "content", null, emptyMap(), listOf(0.1),
                sourceType = ConnectorSource.JIRA,
            )

            val request = takeOperationRequest(server)
            val body = mapper.readTree(request.body.readUtf8())
            assertThat(body.path("source_type").asString()).isEqualTo("jira")
        }
    }

    @Test
    fun `incompatible existing mapping fails before any write or migration request`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(
                MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"type":"illegal_argument_exception","reason":"mapper conflict"},"status":400}"""),
            )
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            val error = org.junit.jupiter.api.assertThrows<IllegalStateException> {
                indexer.upsert(7, "one", 0, "One", "content", null, emptyMap(), listOf(0.1))
            }

            assertThat(error.message).contains("mapping")
            assertThat(recordedRequests(server)).containsExactly(
                "HEAD /documents",
                "PUT /documents/_mapping",
            )
        }
    }

    @Test
    fun deletesOnlySelectedDocumentIds() {
        MockWebServer().use { server ->
            enqueueKeywordMapping(server)
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("""{"timed_out":false,"total":2,"deleted":2,"version_conflicts":0,"failures":[]}"""),
            )
            server.start()
            val properties = OpenSearchVectorStoreProperties(
                uris = listOf(server.url("/").toString().trimEnd('/')),
                indexName = "documents",
            )
            val indexer = OpenSearchIndexer(properties, null, mapper, externalWrites)

            indexer.deleteDocuments(7, setOf("one", "two"))

            val request = takeOperationRequest(server)
            val body = mapper.readTree(request.body.readUtf8())
            val requestUrl = request.requestUrl ?: server.url(request.path ?: "")
            assertThat(requestUrl.encodedPath).isEqualTo("/documents/_delete_by_query")
            assertThat(requestUrl.queryParameter("refresh")).isEqualTo("true")
            assertThat(body.path("query").path("bool").path("filter").first().path("term").path("cc_pair_id").termValue())
                .isEqualTo(7)
            assertThat(body.path("query").path("bool").path("filter").path(1).path("terms").path("source_document_id").toList().map{ it.asString() })
                .containsExactlyInAnyOrder("one", "two")
        }
    }

    @Test
    fun updatesDocumentSetsForOnlyOneConnectorPair() {
        MockWebServer().use { server ->
            enqueueKeywordMapping(server)
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("""{"timed_out":false,"total":2,"updated":0,"noops":2,"version_conflicts":0,"failures":[]}"""),
            )
            server.enqueue(jsonResponse(sourceDocumentIdsResponse("one", "two")))
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            indexer.updateDocumentSets(7, setOf("one", "two"), listOf("first", "second"))

            val request = takeOperationRequest(server)
            val body = mapper.readTree(request.body.readUtf8())
            val requestUrl = request.requestUrl ?: server.url(request.path ?: "")
            assertThat(requestUrl.encodedPath).isEqualTo("/documents/_update_by_query")
            assertThat(requestUrl.queryParameter("refresh")).isEqualTo("true")
            assertThat(requestUrl.queryParameter("conflicts") ?: body.path("conflicts").asString()).isEqualTo("proceed")
            assertThat(body.path("query").path("bool").path("filter").first().path("term").path("cc_pair_id").termValue())
                .isEqualTo(7)
            assertThat(body.path("query").path("bool").path("filter").path(1).path("terms").path("source_document_id").toList().map{ it.asString() })
                .containsExactlyInAnyOrder("one", "two")
            assertThat(body.path("script").path("params").path("document_sets").toList().map{ it.asString() })
                .containsExactly("first", "second")
        }
    }

    @Test
    fun documentSetUpdateRejectsMissingDocumentsHiddenByChunkCount() {
        MockWebServer().use { server ->
            enqueueKeywordMapping(server)
            server.enqueue(
                jsonResponse("""{"timed_out":false,"total":3,"updated":0,"noops":3,"version_conflicts":0,"failures":[]}"""),
            )
            server.enqueue(jsonResponse(sourceDocumentIdsResponse("one")))
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            val error = org.junit.jupiter.api.assertThrows<IllegalStateException> {
                indexer.updateDocumentSets(7, setOf("one", "two"), listOf("Engineering"))
            }

            assertThat(error.message).isEqualTo("OpenSearch did not fully apply the document set update")
        }
    }

    @Test
    fun documentSetUpdateTimeoutIsBelowTheLease() {
        assertThat(DOCUMENT_SET_UPDATE_TIMEOUT).isLessThan(Duration.ofHours(1))
    }

    @Test
    fun newChunksStartWithExplicitPrivateAccess() {
        MockWebServer().use { server ->
            enqueueKeywordMapping(server)
            server.enqueue(indexSuccessResponse())
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            indexer.upsert(7, "one", 0, "One", "content", null, emptyMap(), listOf(0.1))

            val body = mapper.readTree(takeOperationRequest(server).body.readUtf8())
            assertThat(body.has("external_user_emails")).isTrue()
            assertThat(body.has("external_user_group_ids")).isTrue()
            assertThat(body.has("is_public")).isTrue()
            assertThat(body.path("external_user_emails")).isEmpty()
            assertThat(body.path("external_user_group_ids")).isEmpty()
            assertThat(body.path("is_public").asBoolean()).isTrue()
        }
    }

    @Test
    fun existingIndexGetsTheCompleteStrictMappingBeforeTheWrite() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(acknowledgedResponse())
            server.enqueue(indexSuccessResponse())
            server.start()
            val indexer = OpenSearchIndexer(testProperties(server), null, mapper, externalWrites)

            indexer.upsert(
                7,
                "one",
                0,
                "One",
                "content",
                null,
                emptyMap(),
                listOf(0.1),
                primaryOwners = listOf("owner@example.com"),
            )

            server.takeRequest()
            val mappingRequest = server.takeRequest()
            val mapping = mapper.readTree(mappingRequest.body.readUtf8())
            assertThat(mappingRequest.path).isEqualTo("/documents/_mapping")
            assertThat(mapping.path("dynamic").asString()).isEqualTo("strict")
            assertThat(mapping.path("properties").path("title").path("analyzer").asString()).isEqualTo("nori")
            assertThat(mapping.path("properties").path("content").path("analyzer").asString()).isEqualTo("nori")
            assertThat(mapping.path("properties").path("embedding").path("dimension").asInt()).isEqualTo(768)
            assertThat(mapping.path("properties").path("doc_updated_at").path("type").asString()).isEqualTo("date")
            assertThat(mapping.path("properties").path("primary_owners").path("type").asString()).isEqualTo("keyword")
            assertThat(mapping.path("properties").path("secondary_owners").path("type").asString()).isEqualTo("keyword")
        }
    }

    private fun searchResponse(id: String, score: Double): String = mapper.writeValueAsString(
        mapOf(
            "took" to 1,
            "timed_out" to false,
            "_shards" to mapOf("total" to 1, "successful" to 1, "skipped" to 0, "failed" to 0),
            "hits" to mapOf(
                "total" to mapOf("value" to 1, "relation" to "eq"),
                "max_score" to score,
                "hits" to listOf(
                    mapOf(
                        "_index" to "documents",
                        "_id" to id,
                        "_score" to score,
                        "_source" to mapOf(
                            "source_document_id" to "doc-$id",
                            "chunk_id" to 0,
                            "title" to "Title",
                            "content" to "Content",
                            "link" to "https://example.test/$id",
                            "metadata" to mapOf("type" to "guide"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun sourceDocumentIdsResponse(vararg ids: String): String = mapper.writeValueAsString(
        mapOf(
            "took" to 1,
            "timed_out" to false,
            "_shards" to mapOf("total" to 1, "successful" to 1, "skipped" to 0, "failed" to 0),
            "hits" to mapOf(
                "hits" to ids.map { id ->
                    mapOf("_index" to "documents", "_id" to "$id-0", "_source" to mapOf("source_document_id" to id))
                },
            ),
        ),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun indexSuccessResponse(id: String = "doc-1"): MockResponse = jsonResponse(
        """{"_index":"documents","_id":"$id","_version":1,"result":"created","_shards":{"total":1,"successful":1,"failed":0},"_seq_no":0,"_primary_term":1}""",
    )

    private fun acknowledgedResponse(): MockResponse = jsonResponse(
        """{"acknowledged":true,"shards_acknowledged":true,"indices":[],"index":"documents"}""",
    )

    private fun recordedRequests(server: MockWebServer): List<String> = buildList {
        while (true) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: break
            add("${request.method} ${request.path}")
        }
    }

    private fun enqueueKeywordMapping(server: MockWebServer) {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(acknowledgedResponse())
    }

    private fun takeOperationRequest(server: MockWebServer) = server.run {
        takeRequest()
        takeRequest()
        takeRequest()
    }

    private fun JsonNode.termValue(): Long = if (this.isObject) this.path("value").asLong() else this.asLong()
    private fun JsonNode.termString(): String = if (this.isObject) this.path("value").asString() else this.asString()
}
