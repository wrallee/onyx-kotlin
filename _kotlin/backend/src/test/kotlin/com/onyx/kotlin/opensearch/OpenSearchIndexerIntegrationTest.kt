package com.onyx.kotlin.opensearch

import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.onyx.kotlin.config.SearchProperties
import com.onyx.kotlin.search.SearchCandidate
import com.onyx.kotlin.opensearch.HybridNormalizationPipelineRegistry
import com.onyx.kotlin.opensearch.MinMaxNormalizationPipeline
import com.onyx.kotlin.opensearch.OpenSearchClientFactory
import com.onyx.kotlin.opensearch.OpenSearchVectorStoreProperties
import com.onyx.kotlin.opensearch.ZScoreNormalizationPipeline
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.apache.hc.core5.util.Timeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration
import java.time.Instant
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Testcontainers
@Tag("opensearch-integration")
class OpenSearchIndexerIntegrationTest {
    private val mapper = jacksonObjectMapper()
    private val client by lazy(::containerClient)
    private val index = "indexer-test-${UUID.randomUUID()}"
    private val baseUrl get() = "https://${openSearch.host}:${openSearch.getMappedPort(9200)}"
    private val migrationLock = ReentrantLock()
    private val externalWrites = mock(PairExternalWriteFence::class.java).also { fence ->
        doAnswer { invocation ->
            migrationLock.withLock { invocation.getArgument<() -> Unit>(1).invoke() }
        }.`when`(fence).withOpenSearchIndex(anyString(), any<() -> Unit>() ?: {})
    }

    @Test
    fun `accepts the container self-signed certificate when verification is disabled`() {
        indexer().deletePair(1)
    }

    @AfterEach
    fun deleteIndex() {
        get("/_cat/indices/$index*?format=json").forEach { row ->
            client.delete().uri("$baseUrl/${row.path("index").asString()}")
                .retrieve().toBodilessEntity()
        }
    }

    @Test
    fun exactIdsSupportAclDocumentSetAndSelectiveDeleteMaintenance() {
        val indexer = indexer()
        val urlId = "https://example.test/wiki/Engineering?id=ABC-123"
        val fileId = "FILE_CONNECTOR__file-123"
        val updatedAt = Instant.parse("2026-08-01T00:00:00Z")
        indexer.upsert(
            7,
            urlId,
            0,
            "URL",
            "url content",
            urlId,
            emptyMap(),
            vector(0.1),
            updatedAt = updatedAt,
            primaryOwners = listOf("owner@example.com"),
            secondaryOwners = listOf("reviewer@example.com"),
        )
        indexer.upsert(7, fileId, 0, "File", "file content", null, emptyMap(), vector(0.2))

        indexer.updateDocumentSets(7, setOf(urlId, fileId), listOf("Engineering"))
        indexer.deleteDocuments(7, setOf(fileId))

        val mapping = get("/$index/_mapping")
        assertThat(mapping.path(index).path("mappings").path("properties").path("source_document_id").path("type").asString())
            .isEqualTo("keyword")
        assertThat(mapping.path(index).path("mappings").path("properties").path("doc_updated_at").path("type").asString())
            .isEqualTo("date")
        assertThat(mapping.path(index).path("mappings").path("properties").path("primary_owners").path("type").asString())
            .isEqualTo("keyword")
        val urlDocument = exactDocuments(urlId).single()
        assertThat(urlDocument.path("external_user_emails").toList().map(JsonNode::asString)).isEmpty()
        assertThat(urlDocument.path("is_public").asBoolean()).isTrue()
        assertThat(urlDocument.path("document_sets").toList().map(JsonNode::asString)).containsExactly("Engineering")
        assertThat(urlDocument.path("doc_updated_at").asString()).isEqualTo(updatedAt.toString())
        assertThat(urlDocument.path("primary_owners").toList().map(JsonNode::asString)).containsExactly("owner@example.com")
        assertThat(urlDocument.path("secondary_owners").toList().map(JsonNode::asString)).containsExactly("reviewer@example.com")
        assertThat(exactDocuments(fileId)).isEmpty()
    }

    @Test
    fun reindexingShorterDocumentRemovesOldTailChunks() {
        val indexer = indexer()
        val documentId = "https://example.test/document/shorter"
        indexer.upsert(7, documentId, 0, "Old", "old head", documentId, emptyMap(), vector(0.1))
        indexer.upsert(7, documentId, 1, "Old", "stale tail", documentId, emptyMap(), vector(0.2))

        indexer.upsert(7, documentId, 0, "New", "new content", documentId, emptyMap(), vector(0.3))
        indexer.deleteStaleChunks(7, documentId, 1)

        assertThat(exactDocuments(documentId).map { it.path("content").asString() }).containsExactly("new content")
    }

    @Test
    fun contextRangeStaysWithinTheSelectedConnectorPair() {
        val indexer = indexer()
        indexer.upsert(7, "shared", 0, "Seven", "sevensignal", null, emptyMap(), vector(0.1))
        indexer.upsert(7, "shared", 1, "Seven", "pair seven context", null, emptyMap(), vector(0.1))
        indexer.upsert(9, "shared", 0, "Nine", "ninesignal", null, emptyMap(), vector(0.2))
        indexer.upsert(9, "shared", 1, "Nine", "pair nine context", null, emptyMap(), vector(0.2))

        val searchResult = indexer.keywordSearch("sevensignal", emptyList(), 1).single()
        val selected = requireNotNull(indexer.chunkById(searchResult.id))
        val chunks = indexer.chunksInRange(
            requireNotNull(selected.ccPairId),
            selected.sourceDocumentId,
            0,
            1,
        )

        assertThat(chunks.map(SearchCandidate::content))
            .containsExactly("sevensignal", "pair seven context")
    }

    @Test
    fun keywordAndVectorSearchUseTheUnionOfSelectedDocumentSets() {
        val indexer = indexer()
        indexer.upsert(7, "engineering", 0, "Guide", "deployment needle", null, emptyMap(), vector(0.1), listOf("Engineering"))
        indexer.upsert(7, "operations", 0, "Guide", "deployment needle", null, emptyMap(), vector(0.2), listOf("Operations"))
        indexer.upsert(7, "finance", 0, "Guide", "deployment needle", null, emptyMap(), vector(0.3), listOf("Finance"))

        val keyword = indexer.keywordSearch(
            "deployment needle",
            listOf("Engineering", "Operations"),
            10,
        )
        val vector = indexer.vectorSearch(
            vector(0.1),
            listOf("Engineering", "Operations"),
            10,
        )

        assertThat(keyword.map(SearchCandidate::sourceDocumentId))
            .containsExactlyInAnyOrder("engineering", "operations")
        assertThat(vector.map(SearchCandidate::sourceDocumentId))
            .containsExactlyInAnyOrder("engineering", "operations")
    }

    @Test
    fun nativeHybridSearchCreatesPipelinesAndCollapsesChunksByDocument() {
        val writer = indexer()
        writer.upsert(7, "engineering-a", 0, "Deployment Guide", "deployment needle alpha", null, emptyMap(), vector(0.1), listOf("Engineering"))
        writer.upsert(7, "engineering-a", 1, "Unrelated", "unrelated text", null, emptyMap(), vector(0.1), listOf("Engineering"))
        writer.upsert(7, "engineering-b", 0, "Deployment Guide", "deployment needle beta", null, emptyMap(), vector(0.2), listOf("Engineering"))
        writer.upsert(7, "finance", 0, "Deployment Guide", "deployment needle finance", null, emptyMap(), vector(0.3), listOf("Finance"))

        val indexer = hybridIndexer(SearchProperties(hybridCandidateMultiplier = 5))
        val results = indexer.hybridSearch(
            query = "deployment needle",
            queryEmbedding = vector(0.1),
            documentSets = listOf("Engineering"),
            limit = 3,
        )

        assertThat(results.map(SearchCandidate::sourceDocumentId))
            .containsExactlyInAnyOrder("engineering-a", "engineering-b")
        assertThat(results.single { it.sourceDocumentId == "engineering-a" }.chunkId).isZero()
        assertThat(results.mapNotNull(SearchCandidate::retrievalScore)).isSortedAccordingTo(reverseOrder())

        val minMaxId = "$index-hybrid-min-max"
        val zScoreId = "$index-hybrid-z-score"
        val minMax = get("/_search/pipeline/$minMaxId").path(minMaxId)
            .path("phase_results_processors").get(0).path("normalization-processor")
        val zScore = get("/_search/pipeline/$zScoreId").path(zScoreId)
            .path("phase_results_processors").get(0).path("normalization-processor")
        assertThat(minMax.path("normalization").path("technique").asString()).isEqualTo("min_max")
        assertThat(zScore.path("normalization").path("technique").asString()).isEqualTo("z_score")
        assertThat(minMax.path("combination").path("parameters").path("weights").toList().map { it.asDouble() })
            .isEqualTo(listOf(0.5, 0.5))

        val zScoreResults = hybridIndexer(
            SearchProperties(hybridCandidateMultiplier = 5, hybridNormalization = "z_score"),
        ).hybridSearch(
            query = "deployment needle",
            queryEmbedding = vector(0.1),
            documentSets = listOf("Engineering"),
            limit = 1,
        )
        assertThat(zScoreResults).hasSize(1)
    }

    @Test
    fun compatibleMappingUpdatePreservesExistingDocuments() {
        put("/$index", compatibleIndexDefinition())
        putRawDocument("legacy", "legacy-document", "legacy content")

        indexer().updateDocumentSets(7, setOf("legacy-document"), listOf("Engineering"))

        val mapping = get("/$index/_mapping").path(index).path("mappings")
        assertThat(mapping.path("dynamic").asString()).isEqualTo("strict")
        assertThat(mapping.path("properties").path("title").path("analyzer").asString()).isEqualTo("nori")
        assertThat(mapping.path("properties").path("content").path("analyzer").asString()).isEqualTo("nori")
        val document = exactDocuments("legacy-document").single()
        assertThat(document.path("content").asString()).isEqualTo("legacy content")
        assertThat(document.path("document_sets").toList().map(JsonNode::asString)).containsExactly("Engineering")
    }

    @Test
    fun incompatibleMappingUpdateFailsWithoutChangingExistingDocuments() {
        put("/$index", incompatibleIndexDefinition())
        putRawDocument("legacy", "legacy-document", "legacy content")

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            indexer().updateDocumentSets(7, setOf("legacy-document"), listOf("Engineering"))
        }

        assertThat(get("/$index/_doc/legacy").path("_source").path("content").asString())
            .isEqualTo("legacy content")
    }

    @Test
    fun strictMappingStoresOpaqueMetadataAndRejectsUnknownRootFields() {
        indexer().upsert(
            7,
            "metadata-document",
            0,
            "Metadata",
            "content",
            null,
            mapOf("nested" to mapOf("arbitrary" to listOf(1, "two"))),
            vector(0.1),
        )

        val metadataMapping = mappingProperties().path("metadata")
        assertThat(metadataMapping.path("enabled").asBoolean()).isFalse()
        assertThat(metadataMapping.has("properties")).isFalse()
        org.junit.jupiter.api.assertThrows<HttpClientErrorException.BadRequest> {
            client.put().uri("$baseUrl/$index/_doc/unknown-root")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("unknown" to true))
                .retrieve()
                .toBodilessEntity()
        }
        assertThat(exactDocuments("metadata-document").single().path("metadata").path("nested").path("arbitrary"))
            .isNotEmpty()
    }

    private fun indexer(url: String = baseUrl): OpenSearchIndexer = OpenSearchIndexer(
        OpenSearchVectorStoreProperties(
            uris = listOf(url),
            indexName = index,
            username = ADMIN_USERNAME,
            password = ADMIN_PASSWORD,
            ssl = OpenSearchVectorStoreProperties.Ssl(verifyCerts = false),
        ),
        null,
        mapper,
        externalWrites,
    )

    private fun hybridIndexer(searchProperties: SearchProperties): OpenSearchIndexer {
        val properties = OpenSearchVectorStoreProperties(
            uris = listOf(baseUrl),
            indexName = index,
            username = ADMIN_USERNAME,
            password = ADMIN_PASSWORD,
            ssl = OpenSearchVectorStoreProperties.Ssl(verifyCerts = false),
        )
        val openSearchClient = OpenSearchClientFactory.createClient(properties, mapper)
        val registry = HybridNormalizationPipelineRegistry(
            MinMaxNormalizationPipeline(openSearchClient, properties, searchProperties),
            ZScoreNormalizationPipeline(openSearchClient, properties, searchProperties, mapper),
            searchProperties,
        )
        return OpenSearchIndexer(
            properties,
            openSearchClient,
            mapper,
            externalWrites,
            768,
            searchProperties,
            registry,
        )
    }

    private fun exactDocuments(sourceDocumentId: String): List<JsonNode> = client.post()
        .uri("$baseUrl/$index/_search")
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("query" to mapOf("term" to mapOf("source_document_id" to sourceDocumentId))))
        .retrieve()
        .body(JsonNode::class.java)
        ?.path("hits")?.path("hits")?.toList()?.map { it.path("_source") }
        .orEmpty()

    private fun putRawDocument(documentId: String, sourceDocumentId: String, content: String) {
        putRawDocument(index, documentId, sourceDocumentId, content)
    }

    private fun putRawDocument(
        targetIndex: String,
        documentId: String,
        sourceDocumentId: String,
        content: String,
    ) {
        client.put().uri("$baseUrl/$targetIndex/_doc/$documentId?refresh=true")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "cc_pair_id" to 7,
                    "source_document_id" to sourceDocumentId,
                    "chunk_id" to 0,
                    "content" to content,
                ),
            )
            .retrieve().toBodilessEntity()
    }

    private fun put(path: String, body: Any? = null) {
        val request = client.put().uri(baseUrl + path)
        val response = if (body == null) request.retrieve() else {
            request.contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
        }
        response.toBodilessEntity()
    }

    private fun compatibleIndexDefinition(): Map<String, Any> = mapOf(
        "settings" to mapOf("index" to mapOf("knn" to true)),
        "mappings" to mapOf(
            "dynamic" to "strict",
            "properties" to mapOf(
                "cc_pair_id" to mapOf("type" to "long"),
                "source_document_id" to mapOf("type" to "keyword"),
                "chunk_id" to mapOf("type" to "integer"),
                "content" to mapOf(
                    "type" to "text",
                    "analyzer" to "nori",
                    "index_options" to "offsets",
                    "store" to true,
                ),
            ),
        ),
    )

    private fun incompatibleIndexDefinition(): Map<String, Any> = mapOf(
        "settings" to mapOf("index" to mapOf("knn" to true)),
        "mappings" to mapOf(
            "properties" to mapOf(
                "cc_pair_id" to mapOf("type" to "long"),
                "source_document_id" to mapOf("type" to "text"),
                "chunk_id" to mapOf("type" to "integer"),
                "content" to mapOf("type" to "text", "analyzer" to "nori"),
            ),
        ),
    )

    private fun vector(value: Double): List<Double> = List(768) { value }

    private fun containerClient(): RestClient {
        val certificate = openSearch.copyFileFromContainer(
            "/usr/share/opensearch/config/root-ca.pem",
        ) { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("opensearch-container", certificate)
        }
        val sslContext = SSLContextBuilder.create().loadTrustMaterial(trustStore, null).build()
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setTlsSocketStrategy(
                ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .buildClassic(),
            )
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(30))
                    .setSocketTimeout(Timeout.ofSeconds(30))
                    .build(),
            )
            .build()
        val requestFactory = HttpComponentsClientHttpRequestFactory(
            HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(
                    RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(30))
                        .build(),
                )
                .build(),
        )
        return RestClient.builder()
            .defaultHeaders { it.setBasicAuth(ADMIN_USERNAME, ADMIN_PASSWORD) }
            .requestFactory(requestFactory)
            .build()
    }

    private fun mappingProperties(): JsonNode = get("/$index/_mapping").properties().first().value
        .path("mappings").path("properties")

    private fun get(path: String): JsonNode =
        requireNotNull(client.get().uri(baseUrl + path).retrieve().body(JsonNode::class.java))

    companion object {
        private const val ADMIN_USERNAME = "admin"
        private const val ADMIN_PASSWORD = "OpenSearchTest1!"

        @Container
        @JvmStatic
        val openSearch: GenericContainer<Nothing> = GenericContainer<Nothing>(
            DockerImageName.parse("opensearchproject/opensearch:3.6.0"),
        ).apply {
            withEnv("discovery.type", "single-node")
            withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", ADMIN_PASSWORD)
            withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            withCommand(
                "sh",
                "-c",
                """
                    if ! /usr/share/opensearch/bin/opensearch-plugin list | grep -Fx analysis-nori; then
                      plugin_zip=${'$'}(mktemp)
                      trap 'rm -f "${'$'}plugin_zip"' EXIT
                      curl -fSsL -o "${'$'}plugin_zip" https://artifacts.opensearch.org/releases/plugins/analysis-nori/3.6.0/analysis-nori-3.6.0.zip
                      /usr/share/opensearch/bin/opensearch-plugin install --batch "file:${'$'}plugin_zip"
                      rm -f "${'$'}plugin_zip"
                      trap - EXIT
                    fi
                    exec /usr/share/opensearch/opensearch-docker-entrypoint.sh opensearch
                """.trimIndent(),
            )
            withCreateContainerCmdModifier { command ->
                command.withHostConfig(
                    requireNotNull(command.hostConfig)
                        .withMemory(1536L * 1024 * 1024)
                        .withMemorySwap(1536L * 1024 * 1024)
                        .withNanoCPUs(1_000_000_000L)
                        .withPidsLimit(512L),
                )
            }
            withExposedPorts(9200)
            waitingFor(
                Wait.forSuccessfulCommand(
                    "curl -fkSs -u admin:${'$'}OPENSEARCH_INITIAL_ADMIN_PASSWORD " +
                        "https://localhost:9200/_cluster/health >/dev/null",
                ),
            )
            withStartupTimeout(Duration.ofMinutes(2))
        }
    }
}
