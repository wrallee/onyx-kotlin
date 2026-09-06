package com.onyx.foss.kotlin.service

import com.onyx.foss.kotlin.ingestion.OpenSearchIndexer
import com.onyx.foss.kotlin.ingestion.SearchCandidate
import org.springframework.ai.document.Document
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.retrieval.join.DocumentJoiner
import org.springframework.ai.rag.retrieval.search.DocumentRetriever
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class OpenSearchHybridDocumentRetriever(
    private val indexer: OpenSearchIndexer,
    private val queryEmbeddingProvider: (String) -> List<Double>,
    private val documentSets: List<String> = emptyList(),
    private val count: Int = 50,
    private val sourceTypes: List<String>? = null,
    private val timeCutoff: String? = null,
    private val joiner: DocumentJoiner = ScoreNormalizationDocumentJoiner(),
) : DocumentRetriever {

    override fun retrieve(query: Query): List<Document> {
        val queryText = query.text()
        val embedding = queryEmbeddingProvider(queryText)
        val candidates = indexer.searchCandidates(
            query = queryText,
            queryEmbedding = embedding,
            documentSets = documentSets,
            count = count,
            sourceTypes = sourceTypes ?: emptyList(),
            updatedAfter = timeCutoff?.let { java.time.Instant.parse(it) },
        )

        val keywordDocs = candidates.keyword.map { it.toSpringAiDocument() }
        val vectorDocs = candidates.vector.map { it.toSpringAiDocument() }

        return joiner.join(mapOf(query to listOf(keywordDocs, vectorDocs)))
    }
}

fun SearchCandidate.toSpringAiDocument(): Document {
    val builder = Document.builder()
        .id(id)
        .text(content)
        .score(retrievalScore)
        .metadata("source_document_id", sourceDocumentId)
        .metadata("chunk_id", chunkId)
        .metadata("title", title)
    link?.let { builder.metadata("link", it) }
    builder.metadata("metadata", metadata)
    return builder.build()
}

fun Document.toSearchCandidate(mapper: ObjectMapper): SearchCandidate {
    val meta = metadata
    val jsonMetadata = when (val m = meta["metadata"]) {
        is JsonNode -> m
        null -> mapper.createObjectNode()
        else -> mapper.valueToTree(m)
    }
    return SearchCandidate(
        id = id,
        sourceDocumentId = meta["source_document_id"] as? String ?: "",
        chunkId = (meta["chunk_id"] as? Number)?.toInt() ?: 0,
        title = meta["title"] as? String ?: "",
        content = text ?: "",
        link = meta["link"] as? String,
        metadata = jsonMetadata,
        retrievalScore = score ?: 0.0,
    )
}
