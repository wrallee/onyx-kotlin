package com.onyx.kotlin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class SearchPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `defaults match retrieval design`() {
        runner.run { context ->
            val properties = context.getBean(SearchProperties::class.java)
            assertThat(properties.hybridCandidateMultiplier).isEqualTo(5)
            assertThat(properties.hybridNormalization).isEqualTo("min_max")
            assertThat(properties.keywordWeight).isEqualTo(0.5)
            assertThat(properties.vectorWeight).isEqualTo(0.5)
            assertThat(properties.rrfK).isEqualTo(50)
        }
    }

    @Test
    fun `custom search settings bind`() {
        runner.withPropertyValues(
            "onyx.search.hybrid-candidate-multiplier=8",
            "onyx.search.hybrid-normalization=z_score",
            "onyx.search.keyword-weight=0.4",
            "onyx.search.vector-weight=0.6",
            "onyx.search.rrf-k=60",
        ).run { context ->
            val properties = context.getBean(SearchProperties::class.java)
            assertThat(properties.hybridCandidateMultiplier).isEqualTo(8)
            assertThat(properties.hybridNormalization).isEqualTo("z_score")
            assertThat(properties.keywordWeight).isEqualTo(0.4)
            assertThat(properties.vectorWeight).isEqualTo(0.6)
            assertThat(properties.rrfK).isEqualTo(60)
        }
    }

    @Test
    fun `unsupported normalization fails startup`() {
        runner.withPropertyValues("onyx.search.hybrid-normalization=rank_magic")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `weights must be nonnegative and sum to one`() {
        runner.withPropertyValues(
            "onyx.search.keyword-weight=0.8",
            "onyx.search.vector-weight=0.8",
        ).run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `candidate multiplier and rrf k must be positive`() {
        runner.withPropertyValues(
            "onyx.search.hybrid-candidate-multiplier=0",
            "onyx.search.rrf-k=0",
        ).run { context -> assertThat(context).hasFailed() }
        runner.withPropertyValues("onyx.search.hybrid-candidate-multiplier=101")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SearchProperties::class)
    class TestConfig
}
