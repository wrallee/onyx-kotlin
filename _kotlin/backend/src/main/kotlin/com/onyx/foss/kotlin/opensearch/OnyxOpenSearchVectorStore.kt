package com.onyx.foss.kotlin.opensearch

import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.query_dsl.KnnQuery
import org.opensearch.client.opensearch._types.query_dsl.Query
import org.opensearch.client.opensearch.core.SearchRequest as OpenSearchSearchRequest
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import java.util.Optional

class OnyxOpenSearchVectorStore(
    private val client: OpenSearchClient,
    private val properties: OpenSearchVectorStoreProperties,
    private val embeddingModel: EmbeddingModel? = null,
) : VectorStore {

    override fun getName(): String = "OnyxOpenSearchVectorStore"

    override fun add(documents: List<Document>) {
        if (documents.isEmpty()) return
        for (doc in documents) {
            val chunkDoc = OpenSearchChunkDocument.fromSpringAiDocument(doc)
            client.index { i ->
                i.index(properties.indexName)
                    .id(doc.id)
                    .document(chunkDoc)
            }
        }
    }

    override fun delete(idList: List<String>) {
        if (idList.isEmpty()) return
        client.deleteByQuery { d ->
            d.index(properties.indexName)
                .query { q -> q.ids { ids -> ids.values(idList) } }
        }
    }

    override fun delete(filterExpression: Filter.Expression) {
        // Optional delete by expression
    }

    override fun similaritySearch(request: SearchRequest): List<Document> {
        val queryEmbedding = when {
            embeddingModel != null -> embeddingModel.embed(request.query).map { it.toDouble() }
            else -> emptyList()
        }
        if (queryEmbedding.isEmpty()) return emptyList()
        return similaritySearchByEmbedding(queryEmbedding, request.topK)
    }

    fun similaritySearchByEmbedding(
        queryEmbedding: List<Double>,
        count: Int,
        filter: Query? = null,
    ): List<Document> {
        val floatVector = queryEmbedding.map { it.toFloat() }
        val knnQueryBuilder = KnnQuery.Builder()
            .field("embedding")
            .vector(floatVector)
            .k(count)

        if (filter != null) {
            knnQueryBuilder.filter(filter)
        }

        val searchRequest = OpenSearchSearchRequest.Builder()
            .index(properties.indexName)
            .size(count)
            .query(Query.of { q -> q.knn(knnQueryBuilder.build()) })
            .build()

        val response = client.search(searchRequest, OpenSearchChunkDocument::class.java)
        return response.hits().hits().mapNotNull { hit ->
            val source = hit.source() ?: return@mapNotNull null
            source.toSpringAiDocument(hit.id() ?: "", hit.score())
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getNativeClient(): Optional<T> {
        return Optional.ofNullable(client as? T)
    }
}
