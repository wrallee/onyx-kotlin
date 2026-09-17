package com.onyx.kotlin.indexing

import com.onyx.kotlin.model.ModelServerClient
import com.onyx.kotlin.opensearch.OpenSearchIndexMigrationLockRepository
import com.onyx.kotlin.support.H2IntegrationTest
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class IndexSettingsIntegrationTest : H2IntegrationTest() {
    @Autowired private lateinit var settings: IndexSettingsService
    @Autowired private lateinit var registry: LocalEmbeddingModelRegistry
    @Autowired private lateinit var searchSettings: SearchSettingsRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @MockitoBean private lateinit var modelServer: ModelServerClient
    @MockitoBean private lateinit var indexLock: OpenSearchIndexMigrationLockRepository

    @BeforeEach
    fun resetDatabase() {
        truncateTables("search_settings")
    }

    @Test
    fun `registry exposes only Granite and Harrier with fixed dimensions`() {
        assertThat(registry.all().map { it.modelName to it.dimension }).containsExactly(
            "ibm-granite/granite-embedding-311m-multilingual-r2" to 768,
            "microsoft/harrier-oss-v1-0.6b" to 1024,
        )
    }

    @Test
    fun `current setting is Granite and comes from the local registry`() {
        val current = settings.current()

        assertThat(current.modelName).isEqualTo("ibm-granite/granite-embedding-311m-multilingual-r2")
        assertThat(current.status).isEqualTo(IndexModelStatus.PRESENT)
        assertThat(searchSettings.count()).isEqualTo(1)
    }

    @Test
    fun `models API exposes only locally supported models`() {
        `when`(modelServer.modelStatus()).thenReturn(
            registryStatus(
                "ibm-granite/granite-embedding-311m-multilingual-r2" to "NOT_LOADED",
                "microsoft/harrier-oss-v1-0.6b" to "UNAVAILABLE",
            ),
        )

        mvc.perform(get("/admin/embedding/models"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].display_name").value("Granite"))
            .andExpect(jsonPath("$[0].dimension").value(768))
            .andExpect(jsonPath("$[0].available").value(true))
            .andExpect(jsonPath("$[0].status").value("NOT_LOADED"))
            .andExpect(jsonPath("$[1].display_name").value("Harrier"))
            .andExpect(jsonPath("$[1].dimension").value(1024))
            .andExpect(jsonPath("$[1].available").value(false))
            .andExpect(jsonPath("$[1].status").value("UNAVAILABLE"))
    }

    @Test
    fun `runtime reads do not take the migration lock`() {
        settings.current()
        clearInvocations(indexLock)

        settings.currentRuntime()
        settings.pending()

        verify(indexLock, never()).lock()
    }

    @Test
    fun `migration removes provider and port state and creates Granite current setting`() {
        settings.current()
        assertThat(tableExists("embedding_providers")).isFalse()
        assertThat(tableExists("reindex_port_attempts")).isFalse()
        assertThat(columnExists("connector_credential_pairs", "full_recollect_requested")).isFalse()
        assertThat(columnExists("search_settings", "provider_type")).isFalse()
        assertThat(columnExists("search_settings", "model_dim")).isFalse()
        assertThat(
            jdbc.queryForObject(
                "SELECT model_name FROM search_settings WHERE status = 'PRESENT'",
                String::class.java,
            ),
        ).isEqualTo("ibm-granite/granite-embedding-311m-multilingual-r2")
    }

    private fun tableExists(table: String): Boolean = jdbc.queryForObject(
        "SELECT COUNT(*) > 0 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
        Boolean::class.java,
        table,
    ) == true

    private fun columnExists(table: String, column: String): Boolean = jdbc.queryForObject(
        "SELECT COUNT(*) > 0 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
        Boolean::class.java,
        table,
        column,
    ) == true

    private fun registryStatus(vararg statuses: Pair<String, String>) =
        mapper.createObjectNode().apply {
            set(
                "models",
                mapper.createObjectNode().apply {
                    statuses.forEach { (model, code) ->
                        set(
                            model,
                            mapper.createObjectNode().put("code", code),
                        )
                    }
                },
            )
        }
}
