package com.onyx.kotlin.search

import com.onyx.kotlin.config.SearchProperties
import com.onyx.kotlin.documentset.DocumentSetEntity
import com.onyx.kotlin.documentset.DocumentSetRepository
import com.onyx.kotlin.indexing.IndexSettingsService
import com.onyx.kotlin.indexing.SearchRuntimeSettings
import com.onyx.kotlin.model.EmbeddingExecutionConfig
import com.onyx.kotlin.model.ModelServerClient
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import com.onyx.kotlin.opensearch.OpenSearchIndexTarget
import com.onyx.kotlin.search.SearchCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class SearchServiceTest {
    private val modelServer = mock(ModelServerClient::class.java)
    private val indexer = mock(OpenSearchIndexer::class.java)
    private val settings = mock(IndexSettingsService::class.java)
    private val documentSets = mock(DocumentSetRepository::class.java)
    private val searchProperties = SearchProperties(hybridCandidateMultiplier = 5, rrfK = 50)
    private val runtime = SearchRuntimeSettings(
        settingsId = 1,
        modelName = "microsoft/harrier-oss-v1-0.6b",
        embedding = EmbeddingExecutionConfig("microsoft/harrier-oss-v1-0.6b", 3, true, 512),
        index = OpenSearchIndexTarget("chunks-harrier", 3),
    )
    private val service = SearchService(searchProperties, modelServer, indexer, documentSets, settings)

    @BeforeEach
    fun useCurrentRuntime() {
        `when`(settings.currentRuntime()).thenReturn(runtime)
    }

    @Test
    fun `semantic search uses the current database model and index`() {
        `when`(modelServer.embedQuery("deployment guide", runtime.embedding)).thenReturn(listOf(0.1, 0.2, 0.3))
        `when`(indexer.vectorSearch(runtime.index, listOf(0.1, 0.2, 0.3), emptyList(), 7, emptyList(), null))
            .thenReturn(listOf(candidate("semantic", 0.8)))

        service.search("deployment guide", emptyList(), 7, SearchType.SEMANTIC)

        verify(modelServer).embedQuery("deployment guide", runtime.embedding)
        verify(indexer).vectorSearch(runtime.index, listOf(0.1, 0.2, 0.3), emptyList(), 7, emptyList(), null)
    }

    @Test
    fun `keyword search never calls embedding and forwards filters`() {
        val cutoff = Instant.parse("2026-01-01T00:00:00Z")
        `when`(documentSets.findAllByNameIn(listOf("Engineering")))
            .thenReturn(listOf(DocumentSetEntity(name = "Engineering")))
        `when`(
            indexer.keywordSearch(
                runtime.index,
                "ABC-123",
                listOf("Engineering"),
                10,
                listOf("jira"),
                cutoff,
            ),
        ).thenReturn(listOf(candidate("keyword", 10.0)))

        val response = service.search(
            "ABC-123",
            listOf("Engineering"),
            10,
            SearchType.KEYWORD,
            listOf("jira"),
            cutoff,
        )

        verify(indexer).keywordSearch(runtime.index, "ABC-123", listOf("Engineering"), 10, listOf("jira"), cutoff)
        verifyNoInteractions(modelServer)
        assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-keyword")
    }

    @Test
    fun `search normalizes metadata filters before retrieval`() {
        val normalized = SearchMetadataFilters(
            projectKeys = listOf("onyx"),
            repositories = listOf("example/repo"),
            statuses = listOf("in progress"),
            documentTypes = listOf("jira_issue"),
        )
        `when`(
            indexer.keywordSearch(
                runtime.index,
                "ABC-123",
                emptyList(),
                10,
                emptyList(),
                null,
                normalized,
            ),
        ).thenReturn(emptyList())

        service.search(
            "ABC-123",
            emptyList(),
            10,
            SearchType.KEYWORD,
            metadataFilters = SearchMetadataFilters(
                projectKeys = listOf(" ONYX ", "onyx"),
                repositories = listOf("Example/Repo"),
                statuses = listOf("In Progress"),
                documentTypes = listOf("JIRA_ISSUE"),
            ),
        )

        verify(indexer).keywordSearch(runtime.index, "ABC-123", emptyList(), 10, emptyList(), null, normalized)
    }

    @Test
    fun `semantic search embeds once and performs vector retrieval only`() {
        `when`(modelServer.embedQuery("deployment guide", runtime.embedding)).thenReturn(listOf(0.1, 0.2, 0.3))
        `when`(indexer.vectorSearch(runtime.index, listOf(0.1, 0.2, 0.3), emptyList(), 7, emptyList(), null))
            .thenReturn(listOf(candidate("semantic", 0.8)))

        val response = service.search("deployment guide", emptyList(), 7, SearchType.SEMANTIC)

        verify(modelServer).embedQuery("deployment guide", runtime.embedding)
        verify(indexer).vectorSearch(runtime.index, listOf(0.1, 0.2, 0.3), emptyList(), 7, emptyList(), null)
        assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-semantic")
    }

    @Test
    fun `hybrid search embeds once and delegates native fusion`() {
        `when`(modelServer.embedQuery("deployment guide", runtime.embedding)).thenReturn(listOf(0.1, 0.2, 0.3))
        `when`(indexer.hybridSearch(runtime.index, "deployment guide", listOf(0.1, 0.2, 0.3), emptyList(), 5, emptyList(), null))
            .thenReturn(listOf(candidate("hybrid", 0.9)))

        val response = service.search("deployment guide", emptyList(), 5, SearchType.HYBRID)

        verify(modelServer).embedQuery("deployment guide", runtime.embedding)
        verify(indexer).hybridSearch(runtime.index, "deployment guide", listOf(0.1, 0.2, 0.3), emptyList(), 5, emptyList(), null)
        assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-hybrid")
    }

    @Test
    fun `search returns a bounded excerpt around a late keyword match`() {
        val content = "x".repeat(400) + "query match" + "y".repeat(400)
        `when`(indexer.keywordSearch(runtime.index, "query", emptyList(), 1, emptyList(), null))
            .thenReturn(listOf(candidate("long").copy(content = content)))

        val result = service.search("query", emptyList(), 1, SearchType.KEYWORD).results.single()

        assertThat(result.excerpt).hasSize(SearchService.MAX_SEARCH_EXCERPT_CHARS)
        assertThat(result.excerpt).contains("query match")
    }

    @Test
    fun `search excerpt preserves supplementary Unicode characters`() {
        val content = "x".repeat(299) + "😀" + "y".repeat(300)
        `when`(indexer.keywordSearch(runtime.index, "missing", emptyList(), 1, emptyList(), null))
            .thenReturn(listOf(candidate("unicode").copy(content = content)))

        val excerpt = service.search("missing", emptyList(), 1, SearchType.KEYWORD).results.single().excerpt

        assertThat(excerpt.codePointCount(0, excerpt.length)).isEqualTo(SearchService.MAX_SEARCH_EXCERPT_CHARS)
        assertThat(excerpt.last().isHighSurrogate()).isFalse()
    }

    @Test
    fun `search accepts fifty results and rejects larger requests`() {
        `when`(indexer.keywordSearch(runtime.index, "query", emptyList(), SearchService.MAX_RESULTS, emptyList(), null))
            .thenReturn(emptyList())

        service.search("query", emptyList(), SearchService.MAX_RESULTS, SearchType.KEYWORD)

        assertThrows<IllegalArgumentException> {
            service.search("query", emptyList(), SearchService.MAX_RESULTS + 1, SearchType.KEYWORD)
        }
    }

    @Test
    fun `rejects unknown document sets before model calls`() {
        `when`(documentSets.findAllByNameIn(listOf("Missing"))).thenReturn(emptyList())

        val error = assertThrows<IllegalArgumentException> {
            service.search("query", listOf("Missing"), 10)
        }

        assertThat(error.message).contains("Missing")
        verifyNoInteractions(modelServer)
    }

    @Test
    fun `weighted reciprocal rank fusion combines lists with weights and tie breaking`() {
        val merged = service.weightedReciprocalRankFusion(
            rankedResults = listOf(listOf("doc-a", "doc-b"), listOf("doc-c", "doc-a")),
            weights = listOf(1.2, 1.0),
            idExtractor = { it },
            k = 50,
        )

        assertThat(merged).containsExactly("doc-a", "doc-b", "doc-c")
    }

    @Test
    fun `rrf default comes from search properties`() {
        val configured = SearchService(
            SearchProperties(rrfK = 73),
            modelServer,
            indexer,
            documentSets,
            settings,
        )

        assertThat(configured.defaultRrfK()).isEqualTo(73)
    }

    @Test
    fun `collapse duplicate chunks keeps the first copy and preserves adjacent chunks`() {
        data class Item(val doc: String?, val chunk: Int?)
        val ranked = listOf(
            Item("A", 8),
            Item("B", 0),
            Item("A", 7),
            Item("A", 8),
            Item("A", 9),
            Item(null, null),
            Item("B", 0),
            Item("A", -1),
        )

        val collapsed = service.collapseDuplicateChunks(ranked, Item::doc, Item::chunk)

        assertThat(collapsed).containsExactly(
            Item("A", 8),
            Item("B", 0),
            Item("A", 7),
            Item("A", 9),
            Item(null, null),
            Item("A", -1),
        )
    }

    @Test
    fun `getDocumentContext fetches chunks around the center clamped at zero`() {
        `when`(indexer.chunkById(runtime.index, "chunk-1")).thenReturn(
            candidate("chunk-1").copy(ccPairId = 7, sourceDocumentId = "doc-1", chunkId = 1),
        )
        `when`(indexer.chunksInRange(runtime.index, 7, "doc-1", 0, 3)).thenReturn(
            listOf(
                candidate("above").copy(sourceDocumentId = "doc-1", chunkId = 0, content = "c0"),
                candidate("center").copy(sourceDocumentId = "doc-1", chunkId = 1, content = "c1"),
                candidate("below").copy(sourceDocumentId = "doc-1", chunkId = 3, content = "c3"),
            ),
        )

        val response = service.getDocumentContext("chunk-1", chunksAbove = 2, chunksBelow = 2)

        assertThat(response.id).isEqualTo("chunk-1")
        assertThat(response.chunks.map { it.chunkId to it.content })
            .containsExactly(0 to "c0", 1 to "c1", 3 to "c3")
    }

    @Test
    fun `getDocumentContext clamps chunk window to the configured maximum`() {
        `when`(indexer.chunkById(runtime.index, "chunk-20")).thenReturn(
            candidate("chunk-20").copy(ccPairId = 9, sourceDocumentId = "doc-1", chunkId = 20),
        )

        service.getDocumentContext("chunk-20", chunksAbove = 100, chunksBelow = 100)

        verify(indexer).chunksInRange(runtime.index, 9, "doc-1", 10, 30)
    }

    private fun candidate(id: String, score: Double = 1.0) = SearchCandidate(
        id = id,
        sourceDocumentId = "doc-$id",
        chunkId = 0,
        title = "Title $id",
        content = "Content $id",
        link = "https://example.test/$id",
        metadata = jacksonObjectMapper().createObjectNode(),
        retrievalScore = score,
    )
}
