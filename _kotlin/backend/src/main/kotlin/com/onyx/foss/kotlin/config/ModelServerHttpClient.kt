package com.onyx.foss.kotlin.config

import org.springframework.http.HttpHeaders
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.http.HttpClient
import java.time.Duration

internal const val MAX_REMOTE_RESPONSE_BYTES = 16 * 1024 * 1024

internal fun RestClient.Builder.buildModelServerClient(config: OnyxProperties.ModelServer): RestClient =
    withBoundedJdkTransport(
        Duration.ofMillis(config.connectTimeoutMs),
        Duration.ofMillis(config.readTimeoutMs),
    ).build()

internal fun RestClient.Builder.withBoundedJdkTransport(
    connectTimeout: Duration,
    readTimeout: Duration,
): RestClient.Builder {
    val requestFactory = JdkClientHttpRequestFactory(
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    ).also { it.setReadTimeout(readTimeout) }
    return clone()
        .requestFactory(requestFactory)
        .requestInterceptor { request, body, execution ->
            val response = execution.execute(request, body)
            try {
                val responseBody = response.body.readNBytes(MAX_REMOTE_RESPONSE_BYTES + 1)
                if (responseBody.size > MAX_REMOTE_RESPONSE_BYTES) {
                    throw RemoteResponseTooLargeException()
                }
                BufferedClientHttpResponse(response, responseBody)
            } catch (error: Exception) {
                response.close()
                throw error
            }
        }
}

internal class RemoteResponseTooLargeException :
    RestClientException("Remote response exceeds $MAX_REMOTE_RESPONSE_BYTES bytes")

private class BufferedClientHttpResponse(
    private val delegate: ClientHttpResponse,
    private val body: ByteArray,
) : ClientHttpResponse {
    override fun getStatusCode() = delegate.statusCode
    override fun getStatusText(): String = delegate.statusText
    override fun getHeaders(): HttpHeaders = delegate.headers
    override fun getBody(): InputStream = ByteArrayInputStream(body)
    override fun close() = delegate.close()
}
