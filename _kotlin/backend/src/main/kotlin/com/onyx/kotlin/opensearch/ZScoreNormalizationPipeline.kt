package com.onyx.kotlin.opensearch

import com.onyx.kotlin.config.SearchProperties
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.generic.Requests
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicBoolean

class ZScoreNormalizationPipeline(
    private val client: OpenSearchClient,
    private val vectorStoreProperties: OpenSearchVectorStoreProperties,
    private val searchProperties: SearchProperties,
    private val mapper: ObjectMapper,
) : HybridNormalizationPipeline {
    private val ready = AtomicBoolean(false)

    override val technique: String = "z_score"
    override val pipelineId: String
        get() = "${vectorStoreProperties.indexName}-hybrid-z-score"

    override fun ensureReady() {
        if (ready.get()) return
        synchronized(ready) {
            if (ready.get()) return

            val body = mapOf(
                "description" to "Onyx Kotlin z-score hybrid normalization",
                "phase_results_processors" to listOf(
                    mapOf(
                        "normalization-processor" to mapOf(
                            "normalization" to mapOf("technique" to "z_score"),
                            "combination" to mapOf(
                                "technique" to "arithmetic_mean",
                                "parameters" to mapOf(
                                    "weights" to listOf(
                                        searchProperties.keywordWeight,
                                        searchProperties.vectorWeight,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val request = Requests.builder()
                .method("PUT")
                .endpoint("/_search/pipeline/$pipelineId")
                .json(mapper.writeValueAsString(body))
                .build()

            client.generic().execute(request).use { response ->
                val responseBody = response.body.map { it.bodyAsString() }.orElse("")
                check(response.status in 200..299) {
                    "OpenSearch z-score search pipeline update failed: HTTP ${response.status} $responseBody"
                }
            }
            ready.set(true)
        }
    }
}
