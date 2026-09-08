package com.onyx.foss.kotlin.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import kotlin.math.abs

@Validated
@ConfigurationProperties("onyx.search")
data class SearchProperties(
    @field:Min(1)
    @field:Max(10_000)
    val hybridCandidates: Int = 200,
    val hybridNormalization: String = "min_max",
    val keywordWeight: Double = 0.5,
    val vectorWeight: Double = 0.5,
    @field:Min(1)
    val rrfK: Int = 50,
) {
    init {
        require(hybridNormalization in SUPPORTED_NORMALIZATION) {
            "onyx.search.hybrid-normalization must be min_max or z_score"
        }
        require(keywordWeight >= 0.0 && vectorWeight >= 0.0) {
            "onyx.search keyword/vector weights must be non-negative"
        }
        require(abs(keywordWeight + vectorWeight - 1.0) <= WEIGHT_TOLERANCE) {
            "onyx.search keyword-weight + vector-weight must equal 1.0"
        }
    }

    companion object {
        private val SUPPORTED_NORMALIZATION = setOf("min_max", "z_score")
        private const val WEIGHT_TOLERANCE = 1e-9
    }
}
