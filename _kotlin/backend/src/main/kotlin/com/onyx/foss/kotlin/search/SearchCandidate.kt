package com.onyx.foss.kotlin.search

import tools.jackson.databind.JsonNode

data class SearchCandidate(
    val id: String,
    val sourceDocumentId: String,
    val chunkId: Int,
    val title: String,
    val content: String,
    val link: String?,
    val metadata: JsonNode,
    val retrievalScore: Double,
    val ccPairId: Long? = null,
)
