package com.onyx.kotlin.indexing

import com.onyx.kotlin.api.ApiException
import com.onyx.kotlin.support.H2IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class IndexSettingsIntegrationTest : H2IntegrationTest() {
    @Autowired private lateinit var settings: IndexSettingsService
    @Autowired private lateinit var searchSettings: SearchSettingsRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mvc: MockMvc

    @BeforeEach
    fun resetDatabase() {
        truncateTables(
            "reindex_port_attempts", "search_settings", "embedding_providers",
            "ingestion_jobs", "ingestion_attempts", "ingestion_checkpoints",
            "connector_credential_pairs", "connectors", "credentials",
        )
    }

    @Test
    fun currentAndPendingSettingsAreSingletons() {
        val current = settings.current()
        val first = settings.savePending(request("first"))
        val second = settings.savePending(request("second"))

        assertThat(settings.current().id).isEqualTo(current.id)
        assertThat(first.id).isEqualTo(second.id)
        assertThat(settings.pending()?.modelName).isEqualTo("second")
        assertThat(searchSettings.count()).isEqualTo(2)
        assertThat(settings.needsReindexing()).isTrue()
    }

    @Test
    fun apiExposesCurrentPendingAndReindexRequiredState() {
        mvc.perform(get("/search-settings/get-secondary-search-settings"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string("null"))

        mvc.perform(get("/search-settings/get-current-search-settings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.model_name").value("ibm-granite/granite-embedding-311m-multilingual-r2"))
            .andExpect(jsonPath("$.status").value("PRESENT"))

        mvc.perform(
            post("/search-settings/set-new-search-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"model_name":"future","model_dim":1024,"normalize":true}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isNumber)
        mvc.perform(get("/settings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.needs_reindexing").value(true))
    }

    @Test
    fun concurrentPendingSavesCannotCreateDuplicateFutureSettings() {
        settings.current()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("first", "second").map { model ->
                executor.submit<Long> {
                    start.await()
                    settings.savePending(request(model)).id
                }
            }
            start.countDown()

            assertThat(results.map { it.get(10, TimeUnit.SECONDS) }.distinct()).hasSize(1)
            assertThat(searchSettings.findAll().count { it.status == IndexModelStatus.FUTURE }).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun providerSecretIsEncryptedAndMaskedInputPreservesIt() {
        val saved = settings.saveProvider(
            EmbeddingProviderRequest(
                EmbeddingProviderType.OPENAI_COMPATIBLE,
                "http://embedding/v1/embeddings",
                "secret",
            ),
        )
        val encrypted = storedApiKey()

        assertThat(saved.apiKey).isEqualTo("********")
        assertThat(encrypted).doesNotContain("secret")

        settings.saveProvider(
            EmbeddingProviderRequest(
                EmbeddingProviderType.OPENAI_COMPATIBLE,
                "http://embedding/v1/embeddings",
                "********",
            ),
        )
        assertThat(storedApiKey()).isEqualTo(encrypted)
    }

    @Test
    fun runningReindexBlocksSettingAndProviderUrlChangesButAllowsKeyRotation() {
        settings.saveProvider(
            EmbeddingProviderRequest(EmbeddingProviderType.OPENAI_COMPATIBLE, "http://first/v1/embeddings", "old"),
        )
        settings.savePending(request("remote", EmbeddingProviderType.OPENAI_COMPATIBLE))
        jdbc.update("UPDATE search_settings SET reindex_started_at = CURRENT_TIMESTAMP WHERE status = 'FUTURE'")

        assertThatThrownBy { settings.savePending(request("replacement")) }
            .isInstanceOf(ApiException::class.java)
        assertThatThrownBy {
            settings.saveProvider(
                EmbeddingProviderRequest(EmbeddingProviderType.OPENAI_COMPATIBLE, "http://second/v1/embeddings", "new"),
            )
        }.isInstanceOf(ApiException::class.java)

        val rotated = settings.saveProvider(
            EmbeddingProviderRequest(EmbeddingProviderType.OPENAI_COMPATIBLE, "http://first/v1/embeddings", "new"),
        )
        assertThat(rotated.apiKey).isEqualTo("********")
    }

    @Test
    fun portUnitAndFullRecollectStateAreDatabaseEnforced() {
        val futureId = settings.savePending(request("future")).id
        jdbc.update("INSERT INTO credentials(source, secret_json) VALUES ('FILE', 'secret')")
        jdbc.update(
            "INSERT INTO connectors(name, source, input_type, connector_specific_config) " +
                "VALUES ('files', 'FILE', 'load_state', '{}')",
        )
        jdbc.update(
            "INSERT INTO connector_credential_pairs(connector_id, credential_id, name) VALUES (1, 1, 'files')",
        )

        assertThat(
            jdbc.queryForObject(
                "SELECT full_recollect_requested FROM connector_credential_pairs WHERE id = 1",
                Boolean::class.java,
            ),
        ).isFalse()
        jdbc.update(
            "INSERT INTO reindex_port_attempts(search_settings_id, cc_pair_id) VALUES (?, 1)",
            futureId,
        )
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO reindex_port_attempts(search_settings_id, cc_pair_id) VALUES (?, 1)",
                futureId,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun request(
        model: String,
        provider: EmbeddingProviderType? = null,
    ) = SearchSettingsRequest(
        modelName = model,
        modelDim = 768,
        providerType = provider,
    )

    private fun storedApiKey(): String = requireNotNull(
        jdbc.queryForObject(
            "SELECT api_key_encrypted FROM embedding_providers WHERE provider_type = 'OPENAI_COMPATIBLE'",
            String::class.java,
        ),
    )
}
