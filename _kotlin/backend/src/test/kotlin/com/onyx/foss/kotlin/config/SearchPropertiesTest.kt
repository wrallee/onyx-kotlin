package com.onyx.foss.kotlin.config

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
            val p = context.getBean(SearchProperties::class.java)
            assertThat(p.hybridCandidates).isEqualTo(200)
            assertThat(p.hybridNormalization).isEqualTo("min_max")
            assertThat(p.keywordWeight).isEqualTo(0.5)
            assertThat(p.vectorWeight).isEqualTo(0.5)
            assertThat(p.rrfK).isEqualTo(50)
        }
    }

    @Test
    fun `z score and custom weights bind`() {
        runner.withPropertyValues(
            "onyx.search.hybrid-candidates=320",
            "onyx.search.hybrid-normalization=z_score",
            "onyx.search.keyword-weight=0.4",
            "onyx.search.vector-weight=0.6",
            "onyx.search.rrf-k=60",
        ).run { context ->
            val p = context.getBean(SearchProperties::class.java)
            assertThat(p.hybridCandidates).isEqualTo(320)
            assertThat(p.hybridNormalization).isEqualTo("z_score")
            assertThat(p.keywordWeight).isEqualTo(0.4)
            assertThat(p.vectorWeight).isEqualTo(0.6)
            assertThat(p.rrfK).isEqualTo(60)
        }
    }

    @Test
    fun `unsupported normalization fails startup`() {
        runner.withPropertyValues("onyx.search.hybrid-normalization=rank_magic")
            .run { assertThat(it).hasFailed() }
    }

    @Test
    fun `weights must be nonnegative and sum to one`() {
        runner.withPropertyValues(
            "onyx.search.keyword-weight=0.8",
            "onyx.search.vector-weight=0.8",
        ).run { assertThat(it).hasFailed() }
    }

    @Test
    fun `candidate depth and rrf k must be positive`() {
        runner.withPropertyValues(
            "onyx.search.hybrid-candidates=0",
            "onyx.search.rrf-k=0",
        ).run { assertThat(it).hasFailed() }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SearchProperties::class)
    class TestConfig
}
