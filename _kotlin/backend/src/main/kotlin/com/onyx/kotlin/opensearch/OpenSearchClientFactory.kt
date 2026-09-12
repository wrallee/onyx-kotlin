package com.onyx.kotlin.opensearch

import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
import org.apache.hc.client5.http.ssl.TrustAllStrategy
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.apache.hc.core5.util.Timeout
import org.opensearch.client.json.jackson3.JacksonJsonpMapper
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration

object OpenSearchClientFactory {
    val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
    val DEFAULT_SOCKET_TIMEOUT: Duration = Duration.ofSeconds(30)

    fun createClient(
        properties: OpenSearchVectorStoreProperties,
        objectMapper: ObjectMapper? = null,
    ): OpenSearchClient {
        return OpenSearchClient(createTransport(properties, objectMapper))
    }

    fun createTransport(
        properties: OpenSearchVectorStoreProperties,
        objectMapper: ObjectMapper? = null,
    ): ApacheHttpClient5Transport {
        val uri = URI.create(properties.primaryUri)
        val scheme = uri.scheme ?: "http"
        val hostName = uri.host ?: "localhost"
        val port = if (uri.port != -1) uri.port else if (scheme.equals("https", ignoreCase = true)) 443 else 80
        val httpHost = HttpHost(scheme, hostName, port)

        val builderMapper = (objectMapper as? tools.jackson.databind.json.JsonMapper)?.rebuild()
            ?: tools.jackson.databind.json.JsonMapper.builder()
        val effectiveMapper = builderMapper
            .configure(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS, false)
            .configure(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
        val mapper = JacksonJsonpMapper(effectiveMapper)
        val builder = ApacheHttpClient5TransportBuilder.builder(httpHost)
            .setMapper(mapper)

        builder.setConnectionConfigCallback { connectionConfigBuilder ->
            connectionConfigBuilder.setConnectTimeout(Timeout.ofMilliseconds(DEFAULT_CONNECT_TIMEOUT.toMillis()))
        }

        builder.setRequestConfigCallback { requestConfigBuilder ->
            requestConfigBuilder
                .setResponseTimeout(Timeout.ofMilliseconds(DEFAULT_SOCKET_TIMEOUT.toMillis()))
        }

        builder.setHttpClientConfigCallback { httpClientBuilder ->
            if (!properties.ssl.verifyCerts && scheme.equals("https", ignoreCase = true)) {
                val sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                    .build()
                val tlsStrategy = ClientTlsStrategyBuilder.create()
                    .setSslContext(sslContext)
                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .buildAsync()
                val cm = PoolingAsyncClientConnectionManagerBuilder.create()
                    .setTlsStrategy(tlsStrategy)
                    .setDefaultConnectionConfig(
                        org.apache.hc.client5.http.config.ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(DEFAULT_CONNECT_TIMEOUT.toMillis()))
                            .build()
                    )
                    .build()
                httpClientBuilder.setConnectionManager(cm)
            }

            if (properties.username.isNotBlank()) {
                val credentialsProvider = BasicCredentialsProvider().apply {
                    setCredentials(
                        AuthScope(httpHost),
                        UsernamePasswordCredentials(properties.username, properties.password.toCharArray()),
                    )
                }
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            }

            httpClientBuilder
        }

        return builder.build()
    }
}
