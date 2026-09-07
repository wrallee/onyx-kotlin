package com.onyx.foss.kotlin.opensearch

interface HybridNormalizationPipeline {
    val technique: String
    val pipelineId: String

    fun ensureReady()
}
