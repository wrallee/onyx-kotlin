package com.onyx.foss.kotlin.search

import com.onyx.foss.kotlin.config.SearchProperties
import com.onyx.foss.kotlin.documentset.DocumentSetRepository
import com.onyx.foss.kotlin.model.ModelServerClient
import com.onyx.foss.kotlin.opensearch.OpenSearchIndexer
import com.onyx.foss.kotlin.search.SearchCandidate
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.Instant

@Service
class SearchService(
    private val searchProperties: SearchProperties,
    private val modelServer: ModelServerClient,
    private val indexer: OpenSearchIndexer,
    private val documentSetRepository: DocumentSetRepository,
) {
    @JvmOverloads
    fun search(
        query: String,
        documentSets: List<String> = emptyList(),
        limit: Int = DEFAULT_RESULTS,
        searchType: SearchType = SearchType.HYBRID,
        sourceTypes: List<String> = emptyList(),
        timeCutoff: Instant? = null,
    ): SearchResponse {
        require(query.isNotBlank()) { "query must not be blank" }
        require(limit in 1..MAX_RESULTS) { "limit must be between 1 and $MAX_RESULTS" }
        val selectedSets = documentSets.map(String::trim).distinct()
        require(selectedSets.none(String::isBlank)) { "document set names must not be blank" }
        if (selectedSets.isNotEmpty()) {
            val known = documentSetRepository.findAllByNameIn(selectedSets).mapTo(mutableSetOf()) { it.name }
            val unknown = selectedSets.filterNot(known::contains)
            require(unknown.isEmpty()) { "Unknown document sets: ${unknown.joinToString()}" }
        }

        val ranked = when (searchType) {
            SearchType.KEYWORD -> indexer.keywordSearch(
                query,
                selectedSets,
                limit,
                sourceTypes,
                timeCutoff,
            )
            SearchType.SEMANTIC -> indexer.vectorSearch(
                modelServer.embedQuery(query),
                selectedSets,
                limit,
                sourceTypes,
                timeCutoff,
            )
            SearchType.HYBRID -> indexer.hybridSearch(
                query,
                modelServer.embedQuery(query),
                selectedSets,
                limit,
                sourceTypes,
                timeCutoff,
            )
        }

        return SearchResponse(
            results = ranked.take(limit).map { it.toSearchResult(query) },
        )
    }

    fun defaultRrfK(): Int = searchProperties.rrfK

    fun <T> weightedReciprocalRankFusion(
        rankedResults: List<List<T>>,
        weights: List<Double>,
        idExtractor: (T) -> String?,
        k: Int = searchProperties.rrfK,
    ): List<T> {
        require(rankedResults.size == weights.size) {
            "Number of ranked results (${rankedResults.size}) must match number of weights (${weights.size})"
        }
        require(k > 0) { "RRF k must be positive" }
        require(weights.all { it >= 0.0 }) { "RRF weights must be non-negative" }

        data class FusionKey(val id: String?, val sourceIndex: Int = -1, val rank: Int = -1)

        val rrfScores = mutableMapOf<FusionKey, Double>()
        val idToItem = mutableMapOf<FusionKey, T>()
        val idToSourceIndex = mutableMapOf<FusionKey, Int>()
        val idToSourceRank = mutableMapOf<FusionKey, Int>()

        rankedResults.forEachIndexed { sourceIdx, resultList ->
            val weight = weights[sourceIdx]
            resultList.forEachIndexed { index, item ->
                val rank = index + 1
                val itemId = idExtractor(item)?.let { FusionKey(it) }
                    ?: FusionKey(id = null, sourceIndex = sourceIdx, rank = rank)
                rrfScores[itemId] = (rrfScores[itemId] ?: 0.0) + (weight / (k + rank))
                if (itemId !in idToItem) {
                    idToItem[itemId] = item
                    idToSourceIndex[itemId] = sourceIdx
                    idToSourceRank[itemId] = rank
                }
            }
        }

        return rrfScores.keys.sortedWith(
            compareByDescending<FusionKey> { rrfScores[it] ?: 0.0 }
                .thenBy { idToSourceRank[it] ?: Int.MAX_VALUE }
                .thenBy { idToSourceIndex[it] ?: Int.MAX_VALUE },
        ).map { idToItem.getValue(it) }
    }

    fun <T> collapseAdjacentChunks(
        rankedResults: List<T>,
        documentIdExtractor: (T) -> String?,
        chunkIdExtractor: (T) -> Int?,
    ): List<T> {
        if (rankedResults.size < 2) return rankedResults

        data class RankedChunk<T>(val rank: Int, val item: T, val chunkId: Int)

        val identifiable = linkedMapOf<String, MutableList<RankedChunk<T>>>()
        val keepRanks = mutableSetOf<Int>()

        rankedResults.forEachIndexed { rank, item ->
            val documentId = documentIdExtractor(item)?.takeIf(String::isNotBlank)
            val chunkId = chunkIdExtractor(item)?.takeIf { it >= 0 }
            if (documentId == null || chunkId == null) {
                keepRanks += rank
            } else {
                identifiable.getOrPut(documentId) { mutableListOf() }
                    .add(RankedChunk(rank, item, chunkId))
            }
        }

        identifiable.values.forEach { chunks ->
            val sorted = chunks.sortedWith(compareBy<RankedChunk<T>> { it.chunkId }.thenBy { it.rank })
            var run = mutableListOf<RankedChunk<T>>()
            var previousChunkId: Int? = null

            fun keepRun() {
                if (run.isNotEmpty()) {
                    keepRanks += run.minOf { it.rank }
                    run = mutableListOf()
                }
            }

            sorted.forEach { chunk ->
                if (previousChunkId != null && chunk.chunkId > previousChunkId + 1) {
                    keepRun()
                }
                run += chunk
                previousChunkId = chunk.chunkId
            }
            keepRun()
        }

        return rankedResults.filterIndexed { rank, _ -> rank in keepRanks }
    }

    @JvmOverloads
    fun getDocumentContext(
        id: String,
        chunksAbove: Int = DEFAULT_CONTEXT_CHUNKS,
        chunksBelow: Int = DEFAULT_CONTEXT_CHUNKS,
    ): DocumentContextResponse {
        require(id.isNotBlank() && id.length <= MAX_RESULT_ID_CHARS) {
            "id must contain 1 to $MAX_RESULT_ID_CHARS characters"
        }
        val selected = requireNotNull(indexer.chunkById(id)) { "Search result not found: $id" }
        val ccPairId = requireNotNull(selected.ccPairId) { "Search result has no cc_pair_id: $id" }
        val above = chunksAbove.coerceIn(0, MAX_CONTEXT_CHUNKS)
        val below = chunksBelow.coerceIn(0, MAX_CONTEXT_CHUNKS)
        val minChunkId = (selected.chunkId - above).coerceAtLeast(0)
        val maxChunkId = selected.chunkId + below
        val chunks = indexer.chunksInRange(ccPairId, selected.sourceDocumentId, minChunkId, maxChunkId)
            .sortedBy { it.chunkId }
            .map { DocumentContextChunk(it.chunkId, it.content) }
        return DocumentContextResponse(id, selected.sourceDocumentId, chunks)
    }

    companion object {
        const val DEFAULT_RESULTS = 30
        const val MAX_RESULTS = 50
        const val MAX_SEARCH_EXCERPT_CHARS = 300
        const val MAX_RESULT_ID_CHARS = 8192
        const val DEFAULT_CONTEXT_CHUNKS = 2
        const val MAX_CONTEXT_CHUNKS = 10
    }
}

private fun SearchCandidate.toSearchResult(query: String): SearchResult = SearchResult(
    id = id,
    sourceDocumentId = sourceDocumentId,
    chunkId = chunkId,
    title = title,
    excerpt = content.searchExcerpt(query),
    link = link,
    metadata = metadata,
    retrievalScore = retrievalScore,
)

private fun String.searchExcerpt(query: String): String {
    val codePointCount = codePointCount(0, length)
    if (codePointCount <= SearchService.MAX_SEARCH_EXCERPT_CHARS) return this
    val match = indexOf(query, ignoreCase = true).takeIf { it >= 0 }
        ?: query.split(Regex("\\s+")).asSequence()
            .filter { it.length >= 3 }
            .map { indexOf(it, ignoreCase = true) }
            .firstOrNull { it >= 0 }
        ?: 0
    val matchCodePoint = codePointCount(0, match)
    val startCodePoint = (matchCodePoint - SearchService.MAX_SEARCH_EXCERPT_CHARS / 2)
        .coerceIn(0, codePointCount - SearchService.MAX_SEARCH_EXCERPT_CHARS)
    val start = offsetByCodePoints(0, startCodePoint)
    return substring(start, offsetByCodePoints(start, SearchService.MAX_SEARCH_EXCERPT_CHARS))
}

enum class SearchType {
    HYBRID,
    KEYWORD,
    SEMANTIC;

    companion object {
        fun fromString(value: String?): SearchType {
            if (value.isNullOrBlank()) return HYBRID
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported search_type: $value. Supported types: hybrid, keyword, semantic")
        }
    }
}

data class SearchResponse(
    val results: List<SearchResult>,
)

data class DocumentContextChunk(
    val chunkId: Int,
    val content: String,
)

data class DocumentContextResponse(
    val id: String,
    val sourceDocumentId: String,
    val chunks: List<DocumentContextChunk>,
)

data class SearchResult(
    val id: String,
    val sourceDocumentId: String,
    val chunkId: Int,
    val title: String,
    val excerpt: String,
    val link: String?,
    val metadata: JsonNode,
    val retrievalScore: Double?,
)
