package com.onyx.foss.kotlin.service

import com.onyx.foss.kotlin.config.SearchProperties
import com.onyx.foss.kotlin.domain.DocumentSetRepository
import com.onyx.foss.kotlin.ingestion.ModelServerClient
import com.onyx.foss.kotlin.ingestion.OpenSearchIndexer
import com.onyx.foss.kotlin.ingestion.SearchCandidate
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
        limit: Int = 10,
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
            results = ranked.take(limit).map(SearchCandidate::toSearchResult),
        )
    }

    fun defaultRrfK(): Int = searchProperties.rrfK

    fun <T> weightedReciprocalRankFusion(
        rankedResults: List<List<T>>,
        weights: List<Double>,
        idExtractor: (T) -> String,
        k: Int = searchProperties.rrfK,
    ): List<T> {
        require(rankedResults.size == weights.size) {
            "Number of ranked results (${rankedResults.size}) must match number of weights (${weights.size})"
        }
        require(k > 0) { "RRF k must be positive" }
        require(weights.all { it >= 0.0 }) { "RRF weights must be non-negative" }

        val rrfScores = mutableMapOf<String, Double>()
        val idToItem = mutableMapOf<String, T>()
        val idToSourceIndex = mutableMapOf<String, Int>()
        val idToSourceRank = mutableMapOf<String, Int>()

        rankedResults.forEachIndexed { sourceIdx, resultList ->
            val weight = weights[sourceIdx]
            resultList.forEachIndexed { index, item ->
                val rank = index + 1
                val itemId = idExtractor(item)
                rrfScores[itemId] = (rrfScores[itemId] ?: 0.0) + (weight / (k + rank))
                if (itemId !in idToItem) {
                    idToItem[itemId] = item
                    idToSourceIndex[itemId] = sourceIdx
                    idToSourceRank[itemId] = rank
                }
            }
        }

        return rrfScores.keys.sortedWith(
            compareByDescending<String> { rrfScores[it] ?: 0.0 }
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
        sourceDocumentId: String,
        chunkId: Int,
        chunksAbove: Int = DEFAULT_CONTEXT_CHUNKS,
        chunksBelow: Int = DEFAULT_CONTEXT_CHUNKS,
    ): DocumentContextResponse {
        require(sourceDocumentId.isNotBlank()) { "source_document_id must not be blank" }
        require(chunkId >= 0) { "chunk_id must not be negative" }
        val above = chunksAbove.coerceIn(0, MAX_CONTEXT_CHUNKS)
        val below = chunksBelow.coerceIn(0, MAX_CONTEXT_CHUNKS)
        val minChunkId = (chunkId - above).coerceAtLeast(0)
        val maxChunkId = chunkId + below
        val chunks = indexer.chunksInRange(sourceDocumentId, minChunkId, maxChunkId)
            .sortedBy { it.chunkId }
            .map { DocumentContextChunk(it.chunkId, it.content) }
        return DocumentContextResponse(sourceDocumentId, chunks)
    }

    companion object {
        const val MAX_RESULTS = 20
        const val DEFAULT_CONTEXT_CHUNKS = 2
        const val MAX_CONTEXT_CHUNKS = 10
    }
}

private fun SearchCandidate.toSearchResult(): SearchResult = SearchResult(
    sourceDocumentId = sourceDocumentId,
    chunkId = chunkId,
    title = title,
    content = content,
    link = link,
    metadata = metadata,
    retrievalScore = retrievalScore,
)

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
    val sourceDocumentId: String,
    val chunks: List<DocumentContextChunk>,
)

data class SearchResult(
    val sourceDocumentId: String,
    val chunkId: Int,
    val title: String,
    val content: String,
    val link: String?,
    val metadata: JsonNode,
    val retrievalScore: Double?,
)
