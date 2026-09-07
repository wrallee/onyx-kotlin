package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.opensearch.client.opensearch.OpenSearchClient
import tools.jackson.module.kotlin.jacksonObjectMapper

class MinMaxNormalizationPipelineTest {
    @Test
    fun `creates typed min max pipeline once with configured weights`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"acknowledged":true}"""),
        )
        server.start()

        val mapper = jacksonObjectMapper()
        val properties = OpenSearchVectorStoreProperties(
            uris = listOf(server.url("/").toString()),
            indexName = "documents",
        )
        val transport = OpenSearchClientFactory.createTransport(properties, mapper)
        val client = OpenSearchClient(transport)

        try {
            val pipeline = MinMaxNormalizationPipeline(
                client,
                properties,
                SearchProperties(keywordWeight = 0.4, vectorWeight = 0.6),
            )

            pipeline.ensureReady()
            pipeline.ensureReady()

            assertThat(pipeline.pipelineId).isEqualTo("documents-hybrid-min-max")
            assertThat(pipeline.technique).isEqualTo("min_max")
            assertThat(server.requestCount).isEqualTo(1)

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("PUT")
            assertThat(request.path).isEqualTo("/_search/pipeline/documents-hybrid-min-max")

            val processor = mapper.readTree(request.body.readUtf8())
                .path("phase_results_processors")
                .get(0)
                .path("normalization-processor")
            assertThat(processor.path("normalization").path("technique").asText()).isEqualTo("min_max")
            assertThat(processor.path("combination").path("technique").asText()).isEqualTo("arithmetic_mean")
            assertThat(
                processor.path("combination").path("parameters").path("weights").map { it.asDouble() },
            ).isEqualTo(listOf(0.4, 0.6))
        } finally {
            transport.close()
            server.shutdown()
        }
    }
}
