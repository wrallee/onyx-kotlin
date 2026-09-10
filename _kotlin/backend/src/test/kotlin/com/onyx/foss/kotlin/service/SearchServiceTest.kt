package com.onyx.foss.kotlin.service

import com.onyx.foss.kotlin.config.SearchProperties
import com.onyx.foss.kotlin.domain.DocumentSetEntity
import com.onyx.foss.kotlin.domain.DocumentSetRepository
import com.onyx.foss.kotlin.ingestion.ModelServerClient
import com.onyx.foss.kotlin.ingestion.OpenSearchIndexer
import com.onyx.foss.kotlin.ingestion.SearchCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    private val documentSets = mock(DocumentSetRepository::class.java)
    private val searchProperties = SearchProperties(hybridCandidateMultiplier = 5, rrfK = 50)
    private val service = SearchService(searchProperties, modelServer, indexer, documentSets)

    @Test
    fun `keyword search never calls embedding and forwards filters`() {
        val cutoff = Instant.parse("2026-01-01T00:00:00Z")
        `when`(documentSets.findAllByNameIn(listOf("Engineering")))
            .thenReturn(listOf(DocumentSetEntity(name = "Engineering")))
        `when`(
            indexer.keywordSearch(
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

        verify(indexer).keywordSearch("ABC-123", listOf("Engineering"), 10, listOf("jira"), cutoff)
        verifyNoInteractions(modelServer)
        assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-keyword")
    }

    @Test
    fun `semantic search embeds once and performs vector retrieval only`() {
        `when`(modelServer.embedQuery("deployment guide")).thenReturn(listOf(0.1, 0.2, 0.3))
        `when`(indexer.vectorSearch(listOf(0.1, 0.2, 0.3), emptyList(), 7, emptyList(), null))
            .thenReturn(listOf(candidate("semantic", 0.8)))

        val response = service.search("deployment guide", emptyList(), 7, SearchType.SEMANTIC)

        verify(modelServer).embedQuery("deployment guide")
        verify(indexer).vectorSearch(listOf(0.1, 0.2, 0.3), emptyList(), 7, emptyList(), null)
        assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-semantic")
    }

    @Test
    fun `hybrid search embeds once and delegates native fusion`() {
        `when`(modelServer.embedQuery("deployment guide")).thenReturn(listOf(0.1, 0.2, 0.3))
        `when`(indexer.hybridSearch("deployment guide", listOf(0.1, 0.2, 0.3), emptyList(), 5, emptyList(), null))
            .thenReturn(listOf(candidate("hybrid", 0.9)))

        val response = service.search("deployment guide", emptyList(), 5, SearchType.HYBRID)

        verify(modelServer).embedQuery("deployment guide")
        verify(indexer).hybridSearch("deployment guide", listOf(0.1, 0.2, 0.3), emptyList(), 5, emptyList(), null)
        assertThat(response.results.map { it.sourceDocumentId }).containsExactly("doc-hybrid")
    }

    @Test
    fun `search returns a bounded excerpt around a late keyword match`() {
        val content = "x".repeat(400) + "query match" + "y".repeat(400)
        `when`(indexer.keywordSearch("query", emptyList(), 1, emptyList(), null))
            .thenReturn(listOf(candidate("long").copy(content = content)))

        val result = service.search("query", emptyList(), 1, SearchType.KEYWORD).results.single()

        assertThat(result.excerpt).hasSize(SearchService.MAX_SEARCH_EXCERPT_CHARS)
        assertThat(result.excerpt).contains("query match")
    }

    @Test
    fun `search accepts fifty results and rejects larger requests`() {
        `when`(indexer.keywordSearch("query", emptyList(), SearchService.MAX_RESULTS, emptyList(), null))
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
        )

        assertThat(configured.defaultRrfK()).isEqualTo(73)
    }

    @Test
    fun `collapse adjacent chunks keeps best ranked member of each run`() {
        data class Item(val doc: String?, val chunk: Int?)
        val ranked = listOf(
            Item("A", 8),
            Item("B", 0),
            Item("A", 7),
            Item("A", 9),
            Item("A", 20),
            Item("B", 2),
            Item(null, null),
        )

        val collapsed = service.collapseAdjacentChunks(ranked, Item::doc, Item::chunk)

        assertThat(collapsed).containsExactly(
            Item("A", 8),
            Item("B", 0),
            Item("A", 20),
            Item("B", 2),
            Item(null, null),
        )
    }

    @Test
    fun `getDocumentContext fetches chunks around the center clamped at zero`() {
        `when`(indexer.chunksInRange("doc-1", 0, 3)).thenReturn(
            listOf(
                candidate("above").copy(sourceDocumentId = "doc-1", chunkId = 0, content = "c0"),
                candidate("center").copy(sourceDocumentId = "doc-1", chunkId = 1, content = "c1"),
                candidate("below").copy(sourceDocumentId = "doc-1", chunkId = 3, content = "c3"),
            ),
        )

        val response = service.getDocumentContext("doc-1", chunkId = 1, chunksAbove = 2, chunksBelow = 2)

        assertThat(response.chunks.map { it.chunkId to it.content })
            .containsExactly(0 to "c0", 1 to "c1", 3 to "c3")
    }

    @Test
    fun `getDocumentContext clamps chunk window to the configured maximum`() {
        service.getDocumentContext("doc-1", chunkId = 20, chunksAbove = 100, chunksBelow = 100)

        verify(indexer).chunksInRange("doc-1", 10, 30)
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
