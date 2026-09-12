package com.onyx.kotlin.opensearch

interface HybridNormalizationPipeline {
    val technique: String
    val pipelineId: String

    fun ensureReady()
}
