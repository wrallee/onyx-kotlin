package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import java.util.concurrent.atomic.AtomicBoolean

class HybridNormalizationPipelineRegistry(
    private val minMax: MinMaxNormalizationPipeline,
    private val zScore: ZScoreNormalizationPipeline,
    private val searchProperties: SearchProperties,
) {
    private val ready = AtomicBoolean(false)

    fun ensureReady() {
        if (ready.get()) return
        synchronized(ready) {
            if (ready.get()) return
            minMax.ensureReady()
            zScore.ensureReady()
            ready.set(true)
        }
    }

    fun selectedPipelineId(): String = when (searchProperties.hybridNormalization) {
        minMax.technique -> minMax.pipelineId
        zScore.technique -> zScore.pipelineId
        else -> error("Unsupported hybrid normalization: ${searchProperties.hybridNormalization}")
    }
}
