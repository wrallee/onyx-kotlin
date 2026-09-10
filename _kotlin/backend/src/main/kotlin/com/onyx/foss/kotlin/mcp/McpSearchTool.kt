package com.onyx.foss.kotlin.mcp

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.onyx.foss.kotlin.domain.ConnectorSource
import com.onyx.foss.kotlin.service.SearchResponse
import com.onyx.foss.kotlin.service.SearchService
import com.onyx.foss.kotlin.service.SearchType
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class McpSearchTool(
    private val search: SearchService,
    private val mapper: ObjectMapper,
) {
    fun searchDefinition(): McpSchema.Tool =
        McpSchema.Tool.builder(TOOL_SEARCH_INDEXED_DOCUMENTS, SEARCH_INPUT_SCHEMA)
            .description(SEARCH_TOOL_DESCRIPTION)
            .build()

    fun contextDefinition(): McpSchema.Tool =
        McpSchema.Tool.builder(TOOL_GET_DOCUMENT_CONTEXT, CONTEXT_INPUT_SCHEMA)
            .description(CONTEXT_TOOL_DESCRIPTION)
            .build()

    fun fusionDefinition(): McpSchema.Tool =
        McpSchema.Tool.builder(TOOL_WEIGHTED_RRF, FUSION_INPUT_SCHEMA)
            .description(FUSION_TOOL_DESCRIPTION)
            .build()

    fun callSearch(
        arguments: Map<String, Any>,
        defaultDocumentSets: List<String> = emptyList(),
    ): McpSchema.CallToolResult = try {
        val query = arguments["query"] as? String ?: throw IllegalArgumentException("query must be a string")
        val requestedSets = arguments["document_set_names"]?.let { raw ->
            val values = raw as? List<*>
                ?: throw IllegalArgumentException("document_set_names must be a list")
            values.map {
                it as? String ?: throw IllegalArgumentException("document_set_names must contain strings")
            }
        }
        val documentSets = if (!requestedSets.isNullOrEmpty()) requestedSets else defaultDocumentSets
        val limit = parseLimit(arguments["limit"])
        val searchTypeStr = arguments["search_type"] as? String
        val searchType = SearchType.fromString(searchTypeStr)
        val sourceTypes = parseSourceTypes(arguments["source_types"])
        val timeCutoff = parseTimeCutoff(arguments["time_cutoff"] as? String)

        val response = search.search(query, documentSets, limit, searchType, sourceTypes, timeCutoff)
        McpSchema.CallToolResult.builder()
            .addTextContent(mapper.writeValueAsString(response))
            .structuredContent(response)
            .build()
    } catch (error: Exception) {
        McpSchema.CallToolResult.builder()
            .addTextContent(error.message ?: "Search failed")
            .isError(true)
            .build()
    }

    fun callGetDocumentContext(arguments: Map<String, Any>): McpSchema.CallToolResult = try {
        val sourceDocumentId = arguments["source_document_id"] as? String
            ?: throw IllegalArgumentException("source_document_id must be a string")
        val chunkId = (arguments["chunk_id"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("chunk_id must be an integer")
        val chunksAbove = (arguments["chunks_above"] as? Number)?.toInt() ?: SearchService.DEFAULT_CONTEXT_CHUNKS
        val chunksBelow = (arguments["chunks_below"] as? Number)?.toInt() ?: SearchService.DEFAULT_CONTEXT_CHUNKS

        val response = search.getDocumentContext(sourceDocumentId, chunkId, chunksAbove, chunksBelow)
        McpSchema.CallToolResult.builder()
            .addTextContent(mapper.writeValueAsString(response))
            .structuredContent(response)
            .build()
    } catch (error: Exception) {
        McpSchema.CallToolResult.builder()
            .addTextContent(error.message ?: "Failed to fetch document context")
            .isError(true)
            .build()
    }

    private fun parseSourceTypes(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        val values = raw as? List<*> ?: throw IllegalArgumentException("source_types must be a list")
        return values.map { entry ->
            val value = entry as? String
                ?: throw IllegalArgumentException("source_types must contain strings")
            ConnectorSource.fromValue(value).value
        }
    }

    private fun parseLimit(raw: Any?): Int = when (raw) {
        null -> SearchService.DEFAULT_RESULTS
        is Number -> runCatching { BigDecimal(raw.toString()).intValueExact() }
            .getOrElse { throw IllegalArgumentException("limit must be an integer") }
        else -> throw IllegalArgumentException("limit must be an integer")
    }

    private fun parseTimeCutoff(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw) }
            .recoverCatching { Instant.parse("${raw}Z") }
            .getOrNull()
    }

    fun callFusion(arguments: Map<String, Any>): McpSchema.CallToolResult = try {
        val rankedResultsRaw = arguments["ranked_results"] as? List<*>
            ?: throw IllegalArgumentException("ranked_results must be a list of result lists")

        val typeRef = object : TypeReference<List<Map<String, Any?>>>() {}
        val rankedResults: List<List<Map<String, Any?>>> = rankedResultsRaw.map { list ->
            mapper.convertValue(list, typeRef)
        }

        val weightsRaw = arguments["weights"] as? List<*>
        val weights: List<Double> = if (weightsRaw != null) {
            weightsRaw.map { (it as? Number)?.toDouble() ?: 1.0 }
        } else {
            List(rankedResults.size) { 1.0 }
        }

        val k = (arguments["k"] as? Number)?.toInt() ?: search.defaultRrfK()

        val fused = search.weightedReciprocalRankFusion(
            rankedResults = rankedResults,
            weights = weights,
            idExtractor = { item -> extractItemId(item) },
            k = k,
        )
        val merged = search.collapseAdjacentChunks(
            fused,
            documentIdExtractor = ::extractDocumentId,
            chunkIdExtractor = ::extractChunkId,
        )

        val response = mapOf("results" to merged)
        McpSchema.CallToolResult.builder()
            .addTextContent(mapper.writeValueAsString(response))
            .structuredContent(response)
            .build()
    } catch (error: Exception) {
        McpSchema.CallToolResult.builder()
            .addTextContent(error.message ?: "Weighted RRF failed")
            .isError(true)
            .build()
    }

    private fun extractItemId(item: Map<String, Any?>): String? {
        val documentId = extractDocumentId(item)?.takeIf(String::isNotBlank) ?: return null
        val chunkId = extractChunkId(item)?.takeIf { it >= 0 } ?: return null
        return "${documentId}_$chunkId"
    }

    private fun extractDocumentId(item: Map<String, Any?>): String? =
        (item["sourceDocumentId"] ?: item["source_document_id"]) as? String

    private fun extractChunkId(item: Map<String, Any?>): Int? = when (val value = item["chunkId"] ?: item["chunk_id"]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    companion object {
        const val TOOL_SEARCH_INDEXED_DOCUMENTS = "search_indexed_documents"
        const val TOOL_WEIGHTED_RRF = "weighted_reciprocal_rank_fusion"
        const val TOOL_GET_DOCUMENT_CONTEXT = "get_document_context"

        const val SEARCH_TOOL_DESCRIPTION = """Search the user's knowledge base indexed in Onyx (Jira, Confluence, GitHub, files, etc).

Use this tool whenever a question depends on private, internal, or organization-specific
information that a general-purpose model would not know — ticket status, internal docs,
runbooks, code in indexed repos, past decisions. Prefer calling it proactively over asking
the user for information it can supply.

This is the discovery step of a two-step retrieval flow. Each result contains metadata and
a short excerpt, not the full indexed chunk. Select relevant results, then call
`get_document_context` with each result's `source_document_id` and `chunk_id` before answering.

Search strategy (you are the agent — this tool is a retrieval primitive, not a full
pipeline):
- Exact names, IDs, error messages, code symbols -> search_type "keyword".
- Concepts, paraphrases, "how do we..." style questions -> search_type "semantic" (default
  is "hybrid", which blends both and is a safe default when unsure).
- Compound questions ("what's the status of X and what changed in Y") -> decompose into
  separate calls, one per sub-question, rather than one broad query.
- Too few or irrelevant results -> rewrite the query (broader terms, synonyms, drop
  filters) and search again rather than giving up after one call.
- Same-intent rewrites, synonyms, or alternate retrieval strategies -> combine their
  ranked results with `weighted_reciprocal_rank_fusion`.
- Independent sub-questions or facets -> keep evidence coverage for each sub-question;
  do not blindly fuse all lists into one ranking.

`document_set_names` restricts results to named Document Sets. `source_types` restricts
to connector types ("jira", "github", "confluence", or "file"); unrecognized values fail
the call. `time_cutoff` (ISO 8601) returns only documents updated on
or after that moment; naive timestamps are treated as UTC. An unparseable `time_cutoff` is
ignored (search proceeds without the filter) rather than failing the call."""

        const val CONTEXT_TOOL_DESCRIPTION = """Fetch the chunks immediately before/after a specific chunk in a document
returned by `search_indexed_documents`.

Use this when a search result's `excerpt` is truncated, cuts off mid-sentence, or you
need more surrounding text to judge relevance or answer accurately — this is how you
recover the context an internal chat agent would otherwise pre-expand for you.

`source_document_id` and `chunk_id` come from a prior search result. `chunks_above` and
`chunks_below` (default 2 each, max 10 each) control how many neighboring chunks to
retrieve on each side. Returns chunks ordered by `chunk_id`; concatenate their `content`
for continuous reading."""

        const val FUSION_TOOL_DESCRIPTION = """Merge ranked result lists using weighted Reciprocal Rank Fusion (WRRF).

Use WRRF for searches that target the same underlying information through rewrites,
synonyms, or alternate retrieval strategies. Omitted list weights are equal (1.0 each).
Use explicit weights only when one ranked list is clearly more or less trustworthy.
Do not blindly fuse independent decomposed subquestions or facets; preserve evidence
coverage for each subquestion instead."""

        val SEARCH_INPUT_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "minLength" to 1),
                "source_types" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "string",
                        "enum" to ConnectorSource.entries.map(ConnectorSource::value),
                    ),
                ),
                "document_set_names" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string", "minLength" to 1),
                    "uniqueItems" to true,
                ),
                "time_cutoff" to mapOf("type" to "string"),
                "search_type" to mapOf(
                    "type" to "string",
                    "enum" to listOf("hybrid", "keyword", "semantic"),
                    "default" to "hybrid",
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to SearchService.MAX_RESULTS,
                    "default" to SearchService.DEFAULT_RESULTS,
                ),
            ),
            "required" to listOf("query"),
            "additionalProperties" to false,
        )

        val FUSION_INPUT_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "ranked_results" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "object"),
                    ),
                    "description" to "List of ranked result lists to fuse together.",
                ),
                "weights" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "number"),
                    "description" to "Optional weights for each ranked list. Defaults to 1.0 for each list.",
                ),
                "k" to mapOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "description" to "Optional RRF constant k. Uses the server-configured default when omitted.",
                ),
            ),
            "required" to listOf("ranked_results"),
            "additionalProperties" to false,
        )

        val CONTEXT_INPUT_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "source_document_id" to mapOf("type" to "string", "minLength" to 1),
                "chunk_id" to mapOf("type" to "integer", "minimum" to 0),
                "chunks_above" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 10, "default" to 2),
                "chunks_below" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 10, "default" to 2),
            ),
            "required" to listOf("source_document_id", "chunk_id"),
            "additionalProperties" to false,
        )
    }
}
