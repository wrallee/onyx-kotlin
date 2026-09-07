package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(OpenSearchVectorStoreProperties::class)
class OpenSearchConfiguration {

    @Bean(destroyMethod = "close")
    fun openSearchTransport(
        properties: OpenSearchVectorStoreProperties,
        objectMapper: ObjectMapper,
    ): ApacheHttpClient5Transport {
        return OpenSearchClientFactory.createTransport(properties, objectMapper)
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
        properties: OpenSearchVectorStoreProperties,
    ): OnyxOpenSearchVectorStore {
        return OnyxOpenSearchVectorStore(openSearchClient, properties)
    }

    @Bean
    fun minMaxNormalizationPipeline(
        openSearchClient: OpenSearchClient,
        properties: OpenSearchVectorStoreProperties,
        searchProperties: SearchProperties,
    ): MinMaxNormalizationPipeline {
        return MinMaxNormalizationPipeline(openSearchClient, properties, searchProperties)
    }

    @Bean
    fun zScoreNormalizationPipeline(
        openSearchClient: OpenSearchClient,
        properties: OpenSearchVectorStoreProperties,
        searchProperties: SearchProperties,
        objectMapper: ObjectMapper,
    ): ZScoreNormalizationPipeline {
        return ZScoreNormalizationPipeline(openSearchClient, properties, searchProperties, objectMapper)
    }

    @Bean
    fun hybridNormalizationPipelineRegistry(
        minMaxNormalizationPipeline: MinMaxNormalizationPipeline,
        zScoreNormalizationPipeline: ZScoreNormalizationPipeline,
        searchProperties: SearchProperties,
    ): HybridNormalizationPipelineRegistry {
        return HybridNormalizationPipelineRegistry(
            minMaxNormalizationPipeline,
            zScoreNormalizationPipeline,
            searchProperties,
        )
    }
}
