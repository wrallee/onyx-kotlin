package com.onyx.kotlin.model

import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.config.buildModelServerClient
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import java.net.URI
import kotlin.math.sqrt

internal const val DEFAULT_LOCAL_EMBEDDING_MODEL = "ibm-granite/granite-embedding-311m-multilingual-r2"

data class EmbeddingExecutionConfig(
    val modelName: String,
    val modelDim: Int,
    val normalize: Boolean,
    val maxContextLength: Int,
    val queryPrefix: String? = null,
    val passagePrefix: String? = null,
    val provider: OpenAiCompatibleEmbeddingProvider? = null,
)

data class OpenAiCompatibleEmbeddingProvider(
    val apiUrl: String,
    val apiKey: String? = null,
)

data class ExistingEmbeddingChunk(
    val content: String,
    val title: String,
    val searchContext: String,
)

@Service
class ModelServerClient(
    private val properties: OnyxProperties,
    clientBuilder: RestClient.Builder,
) {
    data class ChunkEmbedding(
        val content: String,
        val embedding: List<Double>,
        val tokenCount: Int,
    )

    private data class PreparedChunk(
        val content: String,
        val embeddingText: String,
        val tokenCount: Int,
    )

    private val client = clientBuilder.buildModelServerClient(properties.modelServer)

    fun embed(texts: List<String>): List<List<Double>> = embed(texts, "passage", defaultConfig())

    fun embed(texts: List<String>, config: EmbeddingExecutionConfig): List<List<Double>> =
        embed(texts, "passage", config)

    fun embedQuery(query: String): List<Double> = embed(listOf(query), "query", defaultConfig()).single()

    fun embedQuery(query: String, config: EmbeddingExecutionConfig): List<Double> =
        embed(listOf(query), "query", config).single()

    fun chunkAndEmbed(text: String, title: String, metadataContext: String): List<ChunkEmbedding> =
        chunkAndEmbed(text, title, metadataContext, defaultConfig())

    fun chunkAndEmbed(
        text: String,
        title: String,
        metadataContext: String,
        config: EmbeddingExecutionConfig,
    ): List<ChunkEmbedding> {
        validate(config)
        if (config.provider == null) {
            return localChunkAndEmbed(text, title, metadataContext, config)
        }
        val prepared = prepareChunks(text, title, metadataContext, config)
        val embeddings = remoteEmbed(prepared.map(PreparedChunk::embeddingText), config)
        return prepared.zip(embeddings).map { (chunk, embedding) ->
            ChunkEmbedding(chunk.content, embedding, chunk.tokenCount)
        }
    }

    fun reembedExistingChunks(
        chunks: List<ExistingEmbeddingChunk>,
        config: EmbeddingExecutionConfig,
    ): List<ChunkEmbedding> {
        require(chunks.isNotEmpty()) { "chunks must not be empty" }
        require(chunks.none { it.content.isBlank() }) { "chunk content must not be blank" }
        validate(config)
        val modelName = if (config.provider == null) config.modelName else defaultConfig().modelName
        val response = postModelServer(
            "/encoder/prepare-existing-chunks",
            mapOf(
                "chunks" to chunks.map {
                    mapOf(
                        "content" to it.content,
                        "title" to it.title,
                        "search_context" to it.searchContext,
                    )
                },
                "model_name" to modelName,
                "max_context_length" to config.maxContextLength,
                "manual_passage_prefix" to config.passagePrefix,
            ),
        ).preparedChunks()
        val embeddings = if (config.provider == null) {
            localEmbed(response.map(PreparedChunk::embeddingText), "passage", config, includePrefix = false)
        } else {
            remoteEmbed(response.map(PreparedChunk::embeddingText), config)
        }
        return response.zip(embeddings).map { (chunk, embedding) ->
            ChunkEmbedding(chunk.content, embedding, chunk.tokenCount)
        }
    }

    fun test(config: EmbeddingExecutionConfig) {
        embedQuery("Testing Embedding", config)
    }

    fun modelStatus(): JsonNode = client.get()
        .uri(properties.modelServer.baseUrl.trimEnd('/') + "/api/model-status")
        .retrieve()
        .body(JsonNode::class.java) ?: error("Model server returned no status response")

    private fun localChunkAndEmbed(
        text: String,
        title: String,
        metadataContext: String,
        config: EmbeddingExecutionConfig,
    ): List<ChunkEmbedding> {
        val chunks = postModelServer(
            "/encoder/chunk-and-embed",
            mapOf(
                "text" to text,
                "title" to title,
                "metadata_context" to metadataContext,
                "model_name" to config.modelName,
                "max_context_length" to config.maxContextLength,
                "normalize_embeddings" to config.normalize,
                "manual_passage_prefix" to config.passagePrefix,
            ),
        ).path("chunks")
        check(chunks.isArray && chunks.size() > 0) { "Model server returned no chunks" }
        return chunks.toList().map { chunk ->
            val result = ChunkEmbedding(
                content = chunk.path("content").asString(),
                embedding = chunk.path("embedding").toList().map { it.asDouble() },
                tokenCount = chunk.path("token_count").asInt(),
            )
            check(result.content.isNotBlank()) { "Model server returned a blank chunk" }
            validateEmbeddings(listOf(result.embedding), 1, config.modelDim)
            check(result.tokenCount in 1..config.maxContextLength) {
                "Model server returned a chunk with ${result.tokenCount} tokens"
            }
            result
        }
    }

    private fun prepareChunks(
        text: String,
        title: String,
        metadataContext: String,
        config: EmbeddingExecutionConfig,
    ): List<PreparedChunk> = postModelServer(
        "/encoder/chunk",
        mapOf(
            "text" to text,
            "title" to title,
            "metadata_context" to metadataContext,
            "model_name" to defaultConfig().modelName,
            "max_context_length" to config.maxContextLength,
            "normalize_embeddings" to config.normalize,
            "manual_passage_prefix" to config.passagePrefix,
        ),
    ).preparedChunks()

    private fun JsonNode.preparedChunks(): List<PreparedChunk> {
        val chunks = path("chunks")
        check(chunks.isArray && chunks.size() > 0) { "Model server returned no prepared chunks" }
        return chunks.toList().map { chunk ->
            PreparedChunk(
                content = chunk.path("content").asString(),
                embeddingText = chunk.path("embedding_text").asString(),
                tokenCount = chunk.path("token_count").asInt(),
            ).also {
                check(it.content.isNotBlank()) { "Model server returned a blank chunk" }
                check(it.embeddingText.isNotBlank()) { "Model server returned blank embedding text" }
                check(it.tokenCount > 0) { "Model server returned an invalid token count" }
            }
        }
    }

    private fun embed(texts: List<String>, textType: String, config: EmbeddingExecutionConfig): List<List<Double>> {
        require(texts.isNotEmpty() && texts.none(String::isBlank)) { "texts must contain non-blank values" }
        validate(config)
        return if (config.provider == null) {
            localEmbed(texts, textType, config, includePrefix = true)
        } else {
            val prefix = if (textType == "query") config.queryPrefix else config.passagePrefix
            remoteEmbed(texts.map { if (prefix.isNullOrEmpty()) it else prefix + it }, config)
        }
    }

    private fun localEmbed(
        texts: List<String>,
        textType: String,
        config: EmbeddingExecutionConfig,
        includePrefix: Boolean,
    ): List<List<Double>> {
        val response = postModelServer(
            "/encoder/bi-encoder-embed",
            mapOf(
                "texts" to texts,
                "model_name" to config.modelName,
                "max_context_length" to config.maxContextLength,
                "normalize_embeddings" to config.normalize,
                "text_type" to textType,
                "manual_query_prefix" to config.queryPrefix.takeIf { includePrefix },
                "manual_passage_prefix" to config.passagePrefix.takeIf { includePrefix },
            ),
        )
        return response.path("embeddings").toList().map { vector ->
            vector.toList().map { it.asDouble() }
        }.also { validateEmbeddings(it, texts.size, config.modelDim) }
    }

    private fun remoteEmbed(texts: List<String>, config: EmbeddingExecutionConfig): List<List<Double>> {
        val provider = requireNotNull(config.provider)
        val apiUrl = requireValidEmbeddingProviderUrl(provider.apiUrl)
        val response = post(
            apiUrl,
            mapOf("input" to texts, "model" to config.modelName, "encoding_format" to "float"),
            provider.apiKey,
        )
        val data = response.path("data")
        check(data.isArray) { "Embedding provider returned no data array" }
        check(data.size() == texts.size) { "Embedding provider returned the wrong number of vectors" }
        val indexed = data.toList().associateBy { it.path("index").asInt(-1) }
        check(indexed.keys == texts.indices.toSet()) { "Embedding provider returned invalid indexes" }
        val embeddings = texts.indices.map { index ->
            indexed.getValue(index).path("embedding").toList().map { it.asDouble() }
        }
        validateEmbeddings(embeddings, texts.size, config.modelDim)
        return if (config.normalize) embeddings.map(::normalize) else embeddings
    }

    private fun postModelServer(path: String, body: Map<String, Any?>): JsonNode =
        post(properties.modelServer.baseUrl.trimEnd('/') + path, body, null)

    private fun post(url: String, body: Map<String, Any?>, apiKey: String?): JsonNode {
        var attempt = 0
        var backoffMillis = properties.modelServer.embedRetryInitialBackoffMs
        while (true) {
            try {
                return client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers { headers ->
                        apiKey?.takeIf(String::isNotBlank)?.let(headers::setBearerAuth)
                    }
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java) ?: error("Embedding service returned no response")
            } catch (error: RuntimeException) {
                val retryable = error is ResourceAccessException ||
                    (error is RestClientResponseException && error.statusCode.is5xxServerError)
                if (!retryable || attempt >= properties.modelServer.embedMaxRetries) throw error
                try {
                    Thread.sleep(backoffMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
                attempt++
                backoffMillis = backoffMillis.coerceAtMost(Long.MAX_VALUE / 2) * 2
            }
        }
    }

    private fun defaultConfig() = EmbeddingExecutionConfig(
        modelName = properties.modelServer.modelName.ifBlank { DEFAULT_LOCAL_EMBEDDING_MODEL },
        modelDim = properties.modelServer.embeddingDimension,
        normalize = properties.modelServer.normalizeEmbeddings,
        maxContextLength = properties.modelServer.maxContextLength,
    )

    private fun validate(config: EmbeddingExecutionConfig) {
        require(config.modelName.isNotBlank()) { "Embedding model name must not be blank" }
        require(config.modelDim > 0) { "Embedding model dimension must be positive" }
        require(config.maxContextLength > 0) { "Embedding context length must be positive" }
        config.provider?.let { requireValidEmbeddingProviderUrl(it.apiUrl) }
    }

    private fun validateEmbeddings(embeddings: List<List<Double>>, expectedCount: Int, expectedDimension: Int) {
        check(embeddings.size == expectedCount) {
            "Embedding service returned ${embeddings.size} vectors; expected $expectedCount"
        }
        check(embeddings.all { vector -> vector.size == expectedDimension && vector.all(Double::isFinite) }) {
            "Embedding service returned a vector with an invalid dimension or value"
        }
    }

    private fun normalize(vector: List<Double>): List<Double> {
        val norm = sqrt(vector.sumOf { it * it })
        check(norm > 0.0 && norm.isFinite()) { "Embedding provider returned a zero vector" }
        return vector.map { it / norm }
    }
}

internal fun requireValidEmbeddingProviderUrl(value: String): String {
    val normalized = value.trim()
    val uri = runCatching { URI(normalized) }.getOrNull()
    require(
        uri != null && uri.isAbsolute && uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null,
    ) { "Embedding provider URL must be an absolute HTTP(S) URL without user info or a fragment" }
    return normalized
}
