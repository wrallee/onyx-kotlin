package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.opensearch.client.opensearch.OpenSearchClient
import tools.jackson.module.kotlin.jacksonObjectMapper

class HybridNormalizationPipelineRegistryTest {
    @Test
    fun `initializes both pipelines once and selects configured pipeline`() {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{"acknowledged":true}"""))
            server.enqueue(jsonResponse("{}"))
            server.start()

            val mapper = jacksonObjectMapper()
            val properties = OpenSearchVectorStoreProperties(
                uris = listOf(server.url("/").toString()),
                indexName = "documents",
            )
            OpenSearchClientFactory.createTransport(properties, mapper).use { transport ->
                val client = OpenSearchClient(transport)
                val searchProperties = SearchProperties(hybridNormalization = "z_score")
                val registry = HybridNormalizationPipelineRegistry(
                    MinMaxNormalizationPipeline(client, properties, searchProperties),
                    ZScoreNormalizationPipeline(client, properties, searchProperties, mapper),
                    searchProperties,
                )

                registry.ensureReady()
                registry.ensureReady()

                assertThat(server.requestCount).isEqualTo(2)
                assertThat(registry.selectedPipelineId()).isEqualTo("documents-hybrid-z-score")
            }
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
