package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.OnyxProperties
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("spring.ai.vectorstore.opensearch")
data class OpenSearchVectorStoreProperties(
    val uris: List<String> = emptyList(),
    val indexName: String = "",
    val username: String = "",
    val password: String = "",
    val ssl: Ssl = Ssl(),
) {
    data class Ssl(
        val verifyCerts: Boolean = false,
    )

    val primaryUri: String
        get() = uris.firstOrNull()?.trimEnd('/') ?: "http://localhost:9200"

    fun fallbackWith(onyxProperties: OnyxProperties): OpenSearchVectorStoreProperties {
        val baseUri = if (
            onyxProperties.opensearch.baseUrl.isNotBlank() &&
            (uris.isEmpty() || uris == listOf("http://opensearch:9200"))
        ) {
            listOf(onyxProperties.opensearch.baseUrl)
        } else if (uris.isNotEmpty()) {
            uris
        } else {
            listOf(onyxProperties.opensearch.baseUrl)
        }

        val targetIndex = if (
            onyxProperties.opensearch.index.isNotBlank() &&
            (indexName.isBlank() || indexName == "onyx-kotlin-chunks")
        ) {
            onyxProperties.opensearch.index
        } else if (indexName.isNotBlank()) {
            indexName
        } else {
            onyxProperties.opensearch.index
        }

        val targetUsername = if (username.isNotBlank()) username else onyxProperties.opensearch.username
        val targetPassword = if (password.isNotBlank()) password else onyxProperties.opensearch.password
        val targetVerifyCerts = if (uris.isNotEmpty()) ssl.verifyCerts else onyxProperties.opensearch.verifyCerts

        return copy(
            uris = baseUri,
            indexName = targetIndex,
            username = targetUsername,
            password = targetPassword,
            ssl = Ssl(verifyCerts = targetVerifyCerts),
        )
    }
}
