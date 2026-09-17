package com.onyx.kotlin.indexing

import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

data class LocalEmbeddingModel(
    val modelName: String,
    val displayName: String,
    val dimension: Int,
    val normalize: Boolean,
    val maxContextLength: Int,
    val queryPrefix: String? = null,
    val passagePrefix: String? = null,
    val indexKey: String,
)

private data class LocalEmbeddingModelCatalog(val models: List<LocalEmbeddingModel>)

@Service
class LocalEmbeddingModelRegistry(mapper: ObjectMapper, resourceLoader: ResourceLoader) {
    private val models = resourceLoader.getResource("classpath:local-embedding-models.json").inputStream.use {
        mapper.readValue(it, LocalEmbeddingModelCatalog::class.java).models
    }.also(::validate)

    fun all(): List<LocalEmbeddingModel> = models

    fun require(modelName: String): LocalEmbeddingModel = models.firstOrNull { it.modelName == modelName }
        ?: throw IllegalArgumentException("Unsupported local embedding model: $modelName")

    fun indexNames(modelName: String, baseIndexName: String): List<String> {
        require(baseIndexName.isNotBlank()) { "OpenSearch index name must not be blank" }
        val indexKey = require(modelName).indexKey
        return when (indexKey) {
            "granite" -> listOf(baseIndexName, "$baseIndexName-granite-alt")
            else -> listOf("$baseIndexName-$indexKey", "$baseIndexName-$indexKey-alt")
        }
    }

    private fun validate(models: List<LocalEmbeddingModel>) {
        require(models.isNotEmpty()) { "At least one local embedding model is required" }
        require(models.map(LocalEmbeddingModel::modelName).all(String::isNotBlank)) { "Model names must not be blank" }
        require(models.map(LocalEmbeddingModel::displayName).all(String::isNotBlank)) { "Display names must not be blank" }
        require(models.map(LocalEmbeddingModel::indexKey).all(String::isNotBlank)) { "Index keys must not be blank" }
        require(models.map(LocalEmbeddingModel::modelName).distinct().size == models.size) { "Model names must be unique" }
        require(models.map(LocalEmbeddingModel::indexKey).distinct().size == models.size) { "Index keys must be unique" }
        require(models.all { it.dimension > 0 && it.maxContextLength > 0 }) { "Model dimensions and context lengths must be positive" }
    }
}
