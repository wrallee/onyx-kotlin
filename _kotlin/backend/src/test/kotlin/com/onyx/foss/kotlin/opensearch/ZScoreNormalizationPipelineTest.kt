package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.opensearch.client.opensearch.OpenSearchClient
import tools.jackson.module.kotlin.jacksonObjectMapper

class ZScoreNormalizationPipelineTest {
    @Test
    fun `creates isolated z score pipeline once with configured weights`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.start()

            val mapper = jacksonObjectMapper()
            val properties = OpenSearchVectorStoreProperties(
                uris = listOf(server.url("/").toString()),
                indexName = "documents",
            )
            OpenSearchClientFactory.createTransport(properties, mapper).use { transport ->
                val pipeline = ZScoreNormalizationPipeline(
                    OpenSearchClient(transport),
                    properties,
                    SearchProperties(keywordWeight = 0.4, vectorWeight = 0.6),
                    mapper,
                )

                pipeline.ensureReady()
                pipeline.ensureReady()

                assertThat(server.requestCount).isEqualTo(1)
                val request = server.takeRequest()
                assertThat(request.method).isEqualTo("PUT")
                assertThat(request.path).isEqualTo("/_search/pipeline/documents-hybrid-z-score")

                val processor = mapper.readTree(request.body.readUtf8())
                    .path("phase_results_processors").get(0).path("normalization-processor")
                assertThat(processor.path("normalization").path("technique").asText()).isEqualTo("z_score")
                assertThat(processor.path("combination").path("technique").asText()).isEqualTo("arithmetic_mean")
                assertThat(
                    processor.path("combination").path("parameters").path("weights")
                        .toList().map { it.asDouble() },
                ).isEqualTo(listOf(0.4, 0.6))
            }
        }
    }
}
