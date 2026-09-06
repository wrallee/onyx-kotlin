package com.onyx.foss.kotlin.opensearch

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("spring.ai.vectorstore.opensearch")
data class OpenSearchVectorStoreProperties(
    val uris: List<String> = listOf("http://opensearch:9200"),
    val indexName: String = "onyx-kotlin-chunks",
    val username: String = "",
    val password: String = "",
    val ssl: Ssl = Ssl(),
) {
    data class Ssl(
        val verifyCerts: Boolean = false,
    )

    val primaryUri: String
        get() = uris.firstOrNull()?.trimEnd('/') ?: "http://localhost:9200"
}
