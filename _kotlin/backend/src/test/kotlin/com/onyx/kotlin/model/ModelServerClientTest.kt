package com.onyx.kotlin.model

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.onyx.kotlin.config.OnyxProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClient
import java.util.concurrent.TimeUnit

class ModelServerClientTest {
    @Test
    fun `query embedding uses the supplied local model config`(): Unit = MockWebServer().use { server ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[0.1,0.2]]}"""),
        )
        server.start()
        val harrier = "microsoft/harrier-oss-v1-0.6b"

        client(server).embedQuery("검색어", EmbeddingExecutionConfig(harrier, 2, true, 512))

        val body = jacksonObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertThat(body.path("model_name").asString()).isEqualTo(harrier)
        assertThat(body.has("provider_type")).isFalse()
        assertThat(EmbeddingExecutionConfig::class.java.declaredFields.map { it.name }).doesNotContain("provider")
    }

    @Test
    fun `query embedding sends query text type`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[[0.1,0.2]]}"""),
            )
            server.start()
            val client = ModelServerClient(
                OnyxProperties(
                    modelServer = OnyxProperties.ModelServer(
                        baseUrl = server.url("/").toString(),
                    ),
                ),
                RestClient.builder(),
            )

            assertThat(client.embedQuery("search terms", EmbeddingExecutionConfig("embedding-model", 2, true, 512)))
                .containsExactly(0.1, 0.2)

            val request = server.takeRequest()
            val body = jacksonObjectMapper().readTree(request.body.readUtf8())
            assertThat(request.path).isEqualTo("/encoder/bi-encoder-embed")
            assertThat(body.path("texts").toList().map{ it.asString() }).containsExactly("search terms")
            assertThat(body.path("model_name").asString()).isEqualTo("embedding-model")
            assertThat(body.path("max_context_length").asInt()).isEqualTo(512)
            assertThat(body.path("normalize_embeddings").asBoolean()).isTrue()
            assertThat(body.path("text_type").asString()).isEqualTo("query")
        }
    }

    @Test
    fun `passage embeddings preserve input order`(): Unit = MockWebServer().use { server ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[0.1],[0.2]]}"""),
        )
        server.start()
        val client = client(server)

        assertEquals(listOf(listOf(0.1), listOf(0.2)), client.embed(listOf("first", "second"), testConfig))

        val body = jacksonObjectMapper().readTree(server.takeRequest().body.readUtf8())
        assertThat(body.path("texts").toList().map { it.asString() }).containsExactly("first", "second")
        assertThat(body.path("text_type").asString()).isEqualTo("passage")
    }

    @Test
    fun `chunk and embed sends context and preserves chunk order`(): Unit = MockWebServer().use { server ->
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(
                    """{"chunks":[{"content":"first","embedding":[0.1],"token_count":10},{"content":"second","embedding":[0.2],"token_count":11}]}""",
                ),
        )
        server.start()
        val client = client(server)

        assertThat(client.chunkAndEmbed("first second", "Example", "Source: file", testConfig))
            .containsExactly(
                ModelServerClient.ChunkEmbedding("first", listOf(0.1), 10),
                ModelServerClient.ChunkEmbedding("second", listOf(0.2), 11),
            )

        val request = server.takeRequest()
        val body = jacksonObjectMapper().readTree(request.body.readUtf8())
        assertThat(request.path).isEqualTo("/encoder/chunk-and-embed")
        assertThat(body.path("text").asString()).isEqualTo("first second")
        assertThat(body.path("title").asString()).isEqualTo("Example")
        assertThat(body.path("metadata_context").asString()).isEqualTo("Source: file")
        assertThat(body.path("model_name").asString()).isEqualTo("test-model")
        assertThat(body.path("max_context_length").asInt()).isEqualTo(512)
        assertThat(body.path("normalize_embeddings").asBoolean()).isTrue()
    }

    @Test
    fun readTimeoutAppliesToEmbeddingResponse(): Unit = MockWebServer().use { server ->
        server.enqueue(
            MockResponse()
                .setHeadersDelay(150, TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[0.1]]}"""),
        )
        server.start()
        val client = client(
            server,
            connectTimeoutMs = 1_000,
            readTimeoutMs = 50,
        )

        assertThrows(ResourceAccessException::class.java) { client.embed(listOf("text"), testConfig) }
    }

    @Test
    fun `embed retries a disconnected request`() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[0.1]]}"""),
        )
        server.start()

        assertEquals(
            listOf(listOf(0.1)),
            client(server, embedMaxRetries = 1, embedRetryInitialBackoffMs = 0).embed(listOf("text"), testConfig),
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `embed retries on a 502 and succeeds once connection recovers`() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[0.1]]}"""),
        )
        server.start()

        val result = client(server, embedMaxRetries = 2, embedRetryInitialBackoffMs = 5).embed(listOf("hello"), testConfig)

        assertEquals(listOf(listOf(0.1)), result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `existing chunks are prepared once and reembedded without rechunking`() =
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"chunks":[{"content":"stored chunk","embedding_text":"restored context stored chunk","token_count":9}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[[0.5]]}"""),
            )
            server.start()
            val client = client(server)

            val chunks = client.reembedExistingChunks(
                listOf(ExistingEmbeddingChunk("stored chunk", "title", "Source: jira")),
                EmbeddingExecutionConfig("test-model", 1, true, 512),
            )

            assertThat(chunks).containsExactly(
                ModelServerClient.ChunkEmbedding("stored chunk", listOf(0.5), 9),
            )
            assertThat(server.takeRequest().path).isEqualTo("/encoder/prepare-existing-chunks")
            val embedRequest = server.takeRequest()
            assertThat(embedRequest.path).isEqualTo("/encoder/bi-encoder-embed")
            val body = jacksonObjectMapper().readTree(embedRequest.body.readUtf8())
            assertThat(body.path("texts").first().asString()).isEqualTo("restored context stored chunk")
            assertThat(body.path("manual_passage_prefix").isNull).isTrue()
        }

    @Test
    fun `embed gives up after exhausting retries`() = MockWebServer().use { server ->
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502)) }
        server.start()

        assertThrows(HttpServerErrorException::class.java) {
            client(server, embedMaxRetries = 2, embedRetryInitialBackoffMs = 5).embed(listOf("hello"), testConfig)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `embed does not retry a 4xx response`() = MockWebServer().use { server ->
        repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("invalid request")) }
        server.start()

        assertThrows(HttpClientErrorException.BadRequest::class.java) {
            client(server, embedMaxRetries = 1).embed(listOf("hello"), testConfig)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `embed does not retry an oversized response`() = MockWebServer().use { server ->
        val response = """{"embeddings":[[""" + "0,".repeat(8 * 1024 * 1024) + "0]]}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response))
        server.start()

        assertThrows(RestClientException::class.java) {
            client(server, embedMaxRetries = 1).embed(listOf("hello"), testConfig)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `embed does not follow redirects`() = MockWebServer().use { target ->
        MockWebServer().use { source ->
            target.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[[0.1]]}"""),
            )
            target.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/redirected")))
            source.start()

            assertThrows(IllegalStateException::class.java) { client(source).embed(listOf("hello"), testConfig) }
            assertEquals(0, target.requestCount)
        }
    }

    private fun client(
        server: MockWebServer,
        connectTimeoutMs: Long = 1_000,
        readTimeoutMs: Long = 30_000,
        embedMaxRetries: Int = 0,
        embedRetryInitialBackoffMs: Long = 0,
    ) = ModelServerClient(
        properties = OnyxProperties(
            modelServer = OnyxProperties.ModelServer(
                baseUrl = server.url("/").toString(),
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                embedMaxRetries = embedMaxRetries,
                embedRetryInitialBackoffMs = embedRetryInitialBackoffMs,
            ),
        ),
        clientBuilder = RestClient.builder(),
    )

    private val testConfig = EmbeddingExecutionConfig("test-model", 1, true, 512)

}
