package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.OnyxProperties
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(OpenSearchVectorStoreProperties::class, OnyxProperties::class)
class OpenSearchConfiguration {

    @Bean
    fun effectiveOpenSearchVectorStoreProperties(
        properties: OpenSearchVectorStoreProperties,
        onyxProperties: OnyxProperties,
    ): OpenSearchVectorStoreProperties {
        return properties.fallbackWith(onyxProperties)
    }

    @Bean(destroyMethod = "close")
    fun openSearchTransport(
        effectiveOpenSearchVectorStoreProperties: OpenSearchVectorStoreProperties,
        objectMapper: ObjectMapper,
    ): ApacheHttpClient5Transport {
        return OpenSearchClientFactory.createTransport(effectiveOpenSearchVectorStoreProperties, objectMapper)
    }

    @Bean(destroyMethod = "")
    fun openSearchClient(
        openSearchTransport: ApacheHttpClient5Transport,
    ): OpenSearchClient {
        return OpenSearchClient(openSearchTransport)
    }

    @Bean
    fun onyxOpenSearchVectorStore(
        openSearchClient: OpenSearchClient,
        effectiveOpenSearchVectorStoreProperties: OpenSearchVectorStoreProperties,
    ): OnyxOpenSearchVectorStore {
        return OnyxOpenSearchVectorStore(openSearchClient, effectiveOpenSearchVectorStoreProperties)
    }
}
