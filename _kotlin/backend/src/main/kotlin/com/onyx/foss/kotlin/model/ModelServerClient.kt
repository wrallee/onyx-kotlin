package com.onyx.foss.kotlin.model

import tools.jackson.databind.JsonNode
import com.onyx.foss.kotlin.config.OnyxProperties
import com.onyx.foss.kotlin.config.buildModelServerClient
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Service
class ModelServerClient(
    private val properties: OnyxProperties,
    clientBuilder: RestClient.Builder,
) {
    private val client = clientBuilder.buildModelServerClient(properties.modelServer)

    fun embed(texts: List<String>): List<List<Double>> = embed(texts, "passage")

    fun embedQuery(query: String): List<Double> = embed(listOf(query), "query").single()

    private fun embed(texts: List<String>, textType: String): List<List<Double>> {
        require(properties.modelServer.modelName.isNotBlank()) {
            "ONYX_EMBEDDING_MODEL_NAME must be configured before file ingestion"
        }
        var attempt = 0
        var backoffMillis = properties.modelServer.embedRetryInitialBackoffMs
        var response: JsonNode
        while (true) {
            try {
                response = client.post()
                    .uri(properties.modelServer.baseUrl.trimEnd('/') + "/encoder/bi-encoder-embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        mapOf(
                            "texts" to texts,
                            "model_name" to properties.modelServer.modelName,
                            "max_context_length" to properties.modelServer.maxContextLength,
                            "normalize_embeddings" to properties.modelServer.normalizeEmbeddings,
                            "text_type" to textType,
                        ),
                    )
                    .retrieve()
                    .body(JsonNode::class.java) ?: error("Model server returned no embedding response")
                break
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
        return response.path("embeddings").toList().map { vector -> vector.toList().map { it.asDouble() } }
    }
}
