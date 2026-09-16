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
                        modelName = "embedding-model",
                        embeddingDimension = 2,
                    ),
                ),
                RestClient.builder(),
            )

            assertThat(client.embedQuery("search terms")).containsExactly(0.1, 0.2)

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

        assertEquals(listOf(listOf(0.1), listOf(0.2)), client.embed(listOf("first", "second")))

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

        assertThat(client.chunkAndEmbed("first second", "Example", "Source: file"))
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

        assertThrows(ResourceAccessException::class.java) { client.embed(listOf("text")) }
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
            client(server, embedMaxRetries = 1, embedRetryInitialBackoffMs = 0).embed(listOf("text")),
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

        val result = client(server, embedMaxRetries = 2, embedRetryInitialBackoffMs = 5).embed(listOf("hello"))

        assertEquals(listOf(listOf(0.1)), result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `OpenAI compatible embeddings are delegated to the model server`() =
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"embeddings":[[0.6,0.8],[0.0,1.0]]}""",
                ),
            )
            server.start()
            val config = remoteConfig(server, normalize = true)

            val embeddings = client(server).embed(listOf("first", "second"), config)

            assertThat(embeddings[0]).containsExactly(0.6, 0.8)
            assertThat(embeddings[1]).containsExactly(0.0, 1.0)
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/encoder/bi-encoder-embed")
            assertThat(request.getHeader("Authorization")).isNull()
            val body = jacksonObjectMapper().readTree(request.body.readUtf8())
            assertThat(body.path("texts").toList().map { it.asString() })
                .containsExactly("first", "second")
            assertThat(body.path("model_name").asString()).isEqualTo("remote-model")
            assertThat(body.path("provider_type").asString()).isEqualTo("openai_compatible")
            assertThat(body.path("api_url").asString()).isEqualTo(server.url("/v1/embeddings").toString())
            assertThat(body.path("api_key").asString()).isEqualTo("secret")
            assertThat(body.path("manual_passage_prefix").asString()).isEqualTo("passage: ")
        }

    @Test
    fun `OpenAI compatible embeddings reject missing wrong-sized and non-numeric vectors`() =
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[]}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[[1.0]]}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[[null,1.0]]}"""),
            )
            server.start()
            val client = client(server)
            val config = remoteConfig(server)

            assertThrows(IllegalStateException::class.java) { client.embed(listOf("text"), config) }
            assertThrows(IllegalStateException::class.java) { client.embed(listOf("text"), config) }
            assertThrows(IllegalStateException::class.java) { client.embed(listOf("text"), config) }
        }

    @Test
    fun `OpenAI compatible embeddings retry a rate limit response`() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[1.0,2.0]]}"""),
        )
        server.start()

        val result = client(server, embedMaxRetries = 1).embed(listOf("text"), remoteConfig(server))

        assertThat(result).containsExactly(listOf(1.0, 2.0))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `remote chunking is delegated to the model server`() = MockWebServer().use { modelServer ->
        modelServer.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"chunks":[{"content":"first","embedding":[1.0,2.0],"token_count":8}]}"""),
        )
        modelServer.start()

        val chunks = client(modelServer).chunkAndEmbed(
            "first",
            "title",
            "Source: file",
            remoteConfig(modelServer, normalize = false),
        )

        assertThat(chunks).containsExactly(ModelServerClient.ChunkEmbedding("first", listOf(1.0, 2.0), 8))
        val request = modelServer.takeRequest()
        assertThat(request.path).isEqualTo("/encoder/chunk-and-embed")
        val body = jacksonObjectMapper().readTree(request.body.readUtf8())
        assertThat(body.path("provider_type").asString()).isEqualTo("openai_compatible")
        assertThat(body.path("api_url").asString()).isEqualTo(modelServer.url("/v1/embeddings").toString())
        assertThat(body.path("api_key").asString()).isEqualTo("secret")
        assertThat(body.path("manual_passage_prefix").asString()).isEqualTo("passage: ")
    }

    @Test
    fun `remote reembedding uses only model server endpoints`() = MockWebServer().use { modelServer ->
        modelServer.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"chunks":[{"content":"stored chunk","embedding_text":"context stored chunk","token_count":9}]}""",
            ),
        )
        modelServer.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"embeddings":[[1.0,2.0]]}"""),
        )
        modelServer.start()

        val chunks = client(modelServer).reembedExistingChunks(
            listOf(ExistingEmbeddingChunk("stored chunk", "title", "Source: jira")),
            remoteConfig(modelServer),
        )

        assertThat(chunks).containsExactly(
            ModelServerClient.ChunkEmbedding("stored chunk", listOf(1.0, 2.0), 9),
        )
        val prepare = jacksonObjectMapper().readTree(modelServer.takeRequest().body.readUtf8())
        val embed = jacksonObjectMapper().readTree(modelServer.takeRequest().body.readUtf8())
        assertThat(prepare.path("provider_type").asString()).isEqualTo("openai_compatible")
        assertThat(embed.path("provider_type").asString()).isEqualTo("openai_compatible")
        assertThat(embed.path("texts").first().asString()).isEqualTo("context stored chunk")
        assertThat(embed.path("manual_passage_prefix").isNull).isTrue()
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
            client(server, embedMaxRetries = 2, embedRetryInitialBackoffMs = 5).embed(listOf("hello"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `embed does not retry a 4xx response`() = MockWebServer().use { server ->
        repeat(2) { server.enqueue(MockResponse().setResponseCode(400).setBody("invalid request")) }
        server.start()

        assertThrows(HttpClientErrorException.BadRequest::class.java) {
            client(server, embedMaxRetries = 1).embed(listOf("hello"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `embed does not retry an oversized response`() = MockWebServer().use { server ->
        val response = """{"embeddings":[[""" + "0,".repeat(8 * 1024 * 1024) + "0]]}"
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response))
        server.start()

        assertThrows(RestClientException::class.java) {
            client(server, embedMaxRetries = 1).embed(listOf("hello"))
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

            assertThrows(IllegalStateException::class.java) { client(source).embed(listOf("hello")) }
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
                modelName = "test-model",
                embeddingDimension = 1,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                embedMaxRetries = embedMaxRetries,
                embedRetryInitialBackoffMs = embedRetryInitialBackoffMs,
            ),
        ),
        clientBuilder = RestClient.builder(),
    )

    private fun remoteConfig(server: MockWebServer, normalize: Boolean = false) = EmbeddingExecutionConfig(
        modelName = "remote-model",
        modelDim = 2,
        normalize = normalize,
        maxContextLength = 512,
        passagePrefix = "passage: ",
        provider = OpenAiCompatibleEmbeddingProvider(server.url("/v1/embeddings").toString(), "secret"),
    )
}
