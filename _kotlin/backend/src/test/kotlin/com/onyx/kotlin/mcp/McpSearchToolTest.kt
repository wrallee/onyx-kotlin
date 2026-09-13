package com.onyx.kotlin.mcp

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.onyx.kotlin.config.SearchProperties
import com.onyx.kotlin.documentset.DocumentSetRepository
import com.onyx.kotlin.model.ModelServerClient
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import com.onyx.kotlin.search.DocumentContextResponse
import com.onyx.kotlin.search.SearchResponse
import com.onyx.kotlin.search.SearchResult
import com.onyx.kotlin.search.SearchService
import com.onyx.kotlin.search.SearchType
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class McpSearchToolTest {
    private lateinit var search: SearchService
    private lateinit var tool: McpSearchTool

    @BeforeEach
    fun setUp() {
        search = mock(SearchService::class.java)
        tool = McpSearchTool(search, jacksonObjectMapper())
    }

    @Test
    fun `search tool forwards document set names and limit`() {
        val response = SearchResponse(results = emptyList())
        `when`(search.search("deployment guide", listOf("Engineering", "Operations"), 7, SearchType.HYBRID)).thenReturn(response)

        val result = tool.callSearch(
            mapOf(
                "query" to "deployment guide",
                "document_set_names" to listOf("Engineering", "Operations"),
                "limit" to 7,
            ),
        )

        verify(search).search("deployment guide", listOf("Engineering", "Operations"), 7, SearchType.HYBRID)
        assertThat(result.isError() == true).isFalse()
        assertThat(tool.searchDefinition().name()).isEqualTo("search_indexed_documents")
    }

    @Test
    fun `search tool handles search_type and document_set_names`() {
        val response = SearchResponse(results = emptyList())
        `when`(search.search("auth error", listOf("Backend"), 5, SearchType.KEYWORD)).thenReturn(response)

        val result = tool.callSearch(
            mapOf(
                "query" to "auth error",
                "document_set_names" to listOf("Backend"),
                "search_type" to "keyword",
                "limit" to 5,
            ),
        )

        verify(search).search("auth error", listOf("Backend"), 5, SearchType.KEYWORD)
        assertThat(result.isError() == true).isFalse()
    }

    @Test
    fun `search tool uses default document sets when none provided in arguments`() {
        val response = SearchResponse(results = emptyList())
        `when`(
            search.search("deployment guide", listOf("DefaultSet"), SearchService.DEFAULT_RESULTS, SearchType.HYBRID),
        ).thenReturn(response)

        val result = tool.callSearch(
            mapOf("query" to "deployment guide"),
            listOf("DefaultSet"),
        )

        verify(search).search("deployment guide", listOf("DefaultSet"), SearchService.DEFAULT_RESULTS, SearchType.HYBRID)
        assertThat(result.isError() == true).isFalse()
    }

    @Test
    fun `search tool argument document sets override defaults`() {
        val response = SearchResponse(results = emptyList())
        `when`(
            search.search("deployment guide", listOf("CustomSet"), SearchService.DEFAULT_RESULTS, SearchType.HYBRID),
        ).thenReturn(response)

        val result = tool.callSearch(
            mapOf(
                "query" to "deployment guide",
                "document_set_names" to listOf("CustomSet"),
            ),
            listOf("DefaultSet"),
        )

        verify(search).search("deployment guide", listOf("CustomSet"), SearchService.DEFAULT_RESULTS, SearchType.HYBRID)
        assertThat(result.isError() == true).isFalse()
    }

    @Test
    fun `search tool forwards source types and time cutoff`() {
        val response = SearchResponse(results = emptyList())
        val cutoff = java.time.Instant.parse("2026-01-01T00:00:00Z")
        `when`(
            search.search(
                "deployment guide",
                emptyList(),
                SearchService.DEFAULT_RESULTS,
                SearchType.HYBRID,
                listOf("jira", "github"),
                cutoff,
            ),
        ).thenReturn(response)

        val result = tool.callSearch(
            mapOf(
                "query" to "deployment guide",
                "source_types" to listOf("jira", "github"),
                "time_cutoff" to "2026-01-01T00:00:00Z",
            ),
        )

        verify(search).search(
            "deployment guide",
            emptyList(),
            SearchService.DEFAULT_RESULTS,
            SearchType.HYBRID,
            listOf("jira", "github"),
            cutoff,
        )
        assertThat(result.isError() == true).isFalse()
    }

    @Test
    fun `search tool rejects unknown source types`() {
        val result = tool.callSearch(
            mapOf(
                "query" to "deployment guide",
                "source_types" to listOf("jira", "not-a-real-source"),
            ),
        )

        assertThat(result.isError()).isTrue()
        verifyNoInteractions(search)
    }

    @Test
    fun `search tool ignores an unparseable time cutoff instead of failing`() {
        val response = SearchResponse(results = emptyList())
        `when`(
            search.search(
                "deployment guide",
                emptyList(),
                SearchService.DEFAULT_RESULTS,
                SearchType.HYBRID,
                emptyList(),
                null,
            ),
        ).thenReturn(response)

        val result = tool.callSearch(
            mapOf(
                "query" to "deployment guide",
                "time_cutoff" to "not-a-date",
            ),
        )

        verify(search).search(
            "deployment guide",
            emptyList(),
            SearchService.DEFAULT_RESULTS,
            SearchType.HYBRID,
            emptyList(),
            null,
        )
        assertThat(result.isError() == true).isFalse()
    }

    @Test
    fun `search schema uses the metadata-first result limit`() {
        @Suppress("UNCHECKED_CAST")
        val properties = McpSearchTool.SEARCH_INPUT_SCHEMA["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val limit = properties["limit"] as Map<String, Any>

        assertThat(limit["default"]).isEqualTo(30)
        assertThat(limit["maximum"]).isEqualTo(50)
        assertThat(McpSearchTool.SEARCH_TOOL_DESCRIPTION).contains("two-step retrieval flow")
    }

    @Test
    fun `search response preserves the excerpt contract in both MCP representations`() {
        val mapper = jacksonObjectMapper()

        listOf(3, 5, 10, 20).forEach { limit ->
            val response = SearchResponse(
                results = List(limit) { index ->
                    SearchResult(
                        id = "chunk-$index",
                        sourceDocumentId = "doc-$index",
                        chunkId = index,
                        title = "Result $index",
                        excerpt = "x".repeat(SearchService.MAX_SEARCH_EXCERPT_CHARS),
                        link = null,
                        metadata = mapper.createObjectNode(),
                        retrievalScore = 1.0,
                    )
                },
            )
            `when`(search.search("query", emptyList(), limit, SearchType.HYBRID)).thenReturn(response)

            val result = tool.callSearch(mapOf("query" to "query", "limit" to limit))
            val text = (result.content().single() as McpSchema.TextContent).text()

            assertThat(mapper.readTree(text)).isEqualTo(mapper.valueToTree(result.structuredContent()))
            assertThat(text).doesNotContain("\"content\"")
        }
    }

    @Test
    fun `search rejects fractional and out-of-range limits`() {
        val realSearch = SearchService(
            SearchProperties(),
            mock(ModelServerClient::class.java),
            mock(OpenSearchIndexer::class.java),
            mock(DocumentSetRepository::class.java),
        )
        val validationTool = McpSearchTool(realSearch, jacksonObjectMapper())

        listOf(1.5, 0, 51).forEach { limit ->
            assertThat(validationTool.callSearch(mapOf("query" to "query", "limit" to limit)).isError()).isTrue()
        }
    }

    @Test
    fun `context tool forwards result id and window arguments`() {
        val response = DocumentContextResponse("chunk-5", "doc-1", emptyList())
        `when`(search.getDocumentContext("chunk-5", 1, 3)).thenReturn(response)

        val result = tool.callGetDocumentContext(
            mapOf(
                "id" to "chunk-5",
                "chunks_above" to 1,
                "chunks_below" to 3,
            ),
        )

        verify(search).getDocumentContext("chunk-5", 1, 3)
        assertThat(result.isError() == true).isFalse()
        assertThat(tool.contextDefinition().name()).isEqualTo("get_document_context")
    }

    @Test
    fun `context tool uses default window when not specified`() {
        val response = DocumentContextResponse("chunk-5", "doc-1", emptyList())
        `when`(
            search.getDocumentContext(
                "chunk-5",
                SearchService.DEFAULT_CONTEXT_CHUNKS,
                SearchService.DEFAULT_CONTEXT_CHUNKS,
            ),
        ).thenReturn(response)

        val result = tool.callGetDocumentContext(mapOf("id" to "chunk-5"))

        verify(search).getDocumentContext(
            "chunk-5",
            SearchService.DEFAULT_CONTEXT_CHUNKS,
            SearchService.DEFAULT_CONTEXT_CHUNKS,
        )
        assertThat(result.isError() == true).isFalse()
    }

    @Test
    fun `fusion tool uses server defaults and collapses adjacent chunks`() {
        val realSearch = SearchService(
            SearchProperties(rrfK = 73),
            mock(ModelServerClient::class.java),
            mock(OpenSearchIndexer::class.java),
            mock(DocumentSetRepository::class.java),
        )
        val fusionTool = McpSearchTool(realSearch, jacksonObjectMapper())
        val list1 = listOf(
            mapOf("sourceDocumentId" to "doc-1", "chunkId" to 0, "title" to "Doc 1A"),
            mapOf("sourceDocumentId" to "doc-2", "chunkId" to 0, "title" to "Doc 2"),
        )
        val list2 = listOf(
            mapOf("sourceDocumentId" to "doc-1", "chunkId" to 1, "title" to "Doc 1B"),
            mapOf("sourceDocumentId" to "doc-3", "chunkId" to 0, "title" to "Doc 3"),
        )

        val result = fusionTool.callFusion(mapOf("ranked_results" to listOf(list1, list2)))

        assertThat(result.isError() == true).isFalse()
        assertThat(result.content()).isNotEmpty()
    }

    @Test
    fun `fusion keeps results without complete chunk identity independently`() {
        val realSearch = SearchService(
            SearchProperties(),
            mock(ModelServerClient::class.java),
            mock(OpenSearchIndexer::class.java),
            mock(DocumentSetRepository::class.java),
        )
        val fusionTool = McpSearchTool(realSearch, jacksonObjectMapper())
        val results = listOf(
            mapOf("sourceDocumentId" to "doc-1", "title" to "First"),
            mapOf("sourceDocumentId" to "doc-1", "title" to "Second"),
        )

        val result = fusionTool.callFusion(mapOf("ranked_results" to listOf(results)))

        @Suppress("UNCHECKED_CAST")
        val content = result.structuredContent() as Map<String, List<Map<String, Any?>>>
        assertThat(content.getValue("results").map { it["title"] }).containsExactly("First", "Second")
    }

    @Test
    fun `fusion schema leaves k default to server configuration`() {
        @Suppress("UNCHECKED_CAST")
        val properties = McpSearchTool.FUSION_INPUT_SCHEMA["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val k = properties["k"] as Map<String, Any>

        assertThat(k).doesNotContainKey("default")
        assertThat(McpSearchTool.FUSION_TOOL_DESCRIPTION).contains("Do not blindly fuse")
    }

    @Test
    fun `search schema exposes only the canonical document set argument`() {
        @Suppress("UNCHECKED_CAST")
        val properties = McpSearchTool.SEARCH_INPUT_SCHEMA["properties"] as Map<String, Any>

        assertThat(properties).containsKey("document_set_names").doesNotContainKey("document_sets")
    }

}
