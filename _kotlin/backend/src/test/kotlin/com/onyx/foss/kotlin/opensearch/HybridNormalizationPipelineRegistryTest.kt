package com.onyx.foss.kotlin.opensearch

import com.onyx.foss.kotlin.config.SearchProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class HybridNormalizationPipelineRegistryTest {
    @Test
    fun `initializes both pipelines once and selects min max`() {
        val minMax = mock(MinMaxNormalizationPipeline::class.java)
        val zScore = mock(ZScoreNormalizationPipeline::class.java)
        `when`(minMax.pipelineId).thenReturn("documents-hybrid-min-max")
        `when`(zScore.pipelineId).thenReturn("documents-hybrid-z-score")
        val registry = HybridNormalizationPipelineRegistry(minMax, zScore, SearchProperties())

        registry.ensureReady()
        registry.ensureReady()

        verify(minMax, times(1)).ensureReady()
        verify(zScore, times(1)).ensureReady()
        assertThat(registry.selectedPipelineId()).isEqualTo("documents-hybrid-min-max")
    }

    @Test
    fun `selects z score without changing readiness behavior`() {
        val minMax = mock(MinMaxNormalizationPipeline::class.java)
        val zScore = mock(ZScoreNormalizationPipeline::class.java)
        `when`(minMax.pipelineId).thenReturn("documents-hybrid-min-max")
        `when`(zScore.pipelineId).thenReturn("documents-hybrid-z-score")
        val registry = HybridNormalizationPipelineRegistry(
            minMax,
            zScore,
            SearchProperties(hybridNormalization = "z_score"),
        )

        registry.ensureReady()

        verify(minMax).ensureReady()
        verify(zScore).ensureReady()
        assertThat(registry.selectedPipelineId()).isEqualTo("documents-hybrid-z-score")
    }
}
