package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch.search_pipeline.ScoreCombinationTechnique
import org.opensearch.client.opensearch.search_pipeline.ScoreNormalizationTechnique
import java.util.concurrent.atomic.AtomicBoolean

class MinMaxNormalizationPipeline(
    private val client: OpenSearchClient,
    private val vectorStoreProperties: OpenSearchVectorStoreProperties,
    private val searchProperties: SearchProperties,
) : HybridNormalizationPipeline {
    private val ready = AtomicBoolean(false)

    override val technique: String = "min_max"
    override val pipelineId: String
        get() = "${vectorStoreProperties.indexName}-hybrid-min-max"

    override fun ensureReady() {
        if (ready.get()) return
        synchronized(ready) {
            if (ready.get()) return

            val response = client.searchPipeline().put { request ->
                request
                    .id(pipelineId)
                    .description("Onyx Kotlin min-max hybrid normalization")
                    .phaseResultsProcessors { processor ->
                        processor.normalizationProcessor { normalization ->
                            normalization
                                .normalization { score ->
                                    score.technique(ScoreNormalizationTechnique.MinMax)
                                }
                                .combination { combination ->
                                    combination
                                        .technique(ScoreCombinationTechnique.ArithmeticMean)
                                        .parameters { parameters ->
                                            parameters.weights(
                                                searchProperties.keywordWeight.toFloat(),
                                                searchProperties.vectorWeight.toFloat(),
                                            )
                                        }
                                }
                        }
                    }
            }
            check(response.acknowledged() == true) {
                "OpenSearch did not acknowledge min-max search pipeline $pipelineId"
            }
            ready.set(true)
        }
    }
}
