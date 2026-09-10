package com.onyx.foss.kotlin.ingestion

import tools.jackson.databind.JsonNode
import com.onyx.foss.kotlin.config.withBoundedJdkTransport
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

data class RemoteTextResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: String,
)

data class RemoteJsonResponse(
    val body: JsonNode,
    val headers: HttpHeaders,
)

@Service
class RemoteJsonClient(
    clientBuilder: RestClient.Builder,
) {
    private val client = clientBuilder
        .withBoundedJdkTransport(REMOTE_CONNECTOR_TIMEOUT, REMOTE_CONNECTOR_TIMEOUT)
        .build()

    fun get(base: String, path: String, headers: Map<String, String>): JsonNode =
        getResponse(base, path, headers).body

    fun getResponse(base: String, path: String, headers: Map<String, String>): RemoteJsonResponse =
        client.get()
            .uri(URI.create(base.trimEnd('/') + path))
            .accept(MediaType.APPLICATION_JSON)
            .headers { httpHeaders -> headers.forEach { (name, value) -> httpHeaders.set(name, value) } }
            .retrieve()
            .toEntity(JsonNode::class.java)
            .let { response ->
                RemoteJsonResponse(
                    response.body ?: error("Remote connector returned an empty response"),
                    response.headers,
                )
            }

    fun post(base: String, path: String, headers: Map<String, String>, body: Any): JsonNode =
        client.post()
            .uri(URI.create(base.trimEnd('/') + path))
            .accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { httpHeaders -> headers.forEach { (name, value) -> httpHeaders.set(name, value) } }
            .body(body)
            .retrieve()
            .body(JsonNode::class.java) ?: error("Remote connector returned an empty response")

    fun getBytes(base: String, path: String, headers: Map<String, String>): ByteArray =
        client.get()
            .uri(URI.create(base.trimEnd('/') + path))
            .headers { httpHeaders -> headers.forEach { (name, value) -> httpHeaders.set(name, value) } }
            .retrieve()
            .body(ByteArray::class.java) ?: error("Remote connector returned an empty response")

    fun postText(base: String, path: String, headers: Map<String, String>, body: Any): RemoteTextResponse =
        client.post()
            .uri(URI.create(base.trimEnd('/') + path))
            .accept(MediaType.ALL)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { httpHeaders -> headers.forEach { (name, value) -> httpHeaders.set(name, value) } }
            .body(body)
            .exchange { _, response ->
                val charset = response.headers.contentType?.charset ?: StandardCharsets.UTF_8
                RemoteTextResponse(
                    response.statusCode.value(),
                    response.headers.contentType?.toString(),
                    response.body.readAllBytes().toString(charset),
                )
            }
}

internal val REMOTE_CONNECTOR_TIMEOUT: Duration = Duration.ofSeconds(30)
