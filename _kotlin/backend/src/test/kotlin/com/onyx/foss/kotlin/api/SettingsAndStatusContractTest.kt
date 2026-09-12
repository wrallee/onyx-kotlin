package com.onyx.foss.kotlin.api

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.onyx.foss.kotlin.ingestion.IngestionQueryService
import com.onyx.foss.kotlin.ingestion.IngestionStatusController
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SettingsAndStatusContractTest {
    @Test
    fun settingsExposeAuthlessCommunityVectorDefaults() {
        val mvc = MockMvcBuilders.standaloneSetup(SettingsController()).build()

        mvc.perform(get("/settings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.application_status").value("active"))
            .andExpect(jsonPath("$.ee_features_enabled").value(false))
            .andExpect(jsonPath("$.tier").value("community"))
            .andExpect(jsonPath("$.vector_db_enabled").value(true))
            .andExpect(jsonPath("$.default_pruning_freq").value(604800))
    }

    @Test
    fun connectorStatusKeepsTheConnectorStatusContract() {
        val ingestion = mock(IngestionQueryService::class.java)
        `when`(ingestion.connectorStatuses()).thenReturn(
            listOf(
                mapOf(
                    "cc_pair_id" to 7,
                    "name" to "GitHub",
                    "connector" to mapOf("id" to 2, "source" to "github"),
                    "credential" to mapOf("id" to 3, "source" to "github"),
                    "access_type" to "public",
                    "groups" to emptyList<Long>(),
                ),
            ),
        )
        val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
            IngestionStatusController(ingestion),
        ).build()

        mvc.perform(get("/manage/admin/connector/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].cc_pair_id").value(7))
            .andExpect(jsonPath("$[0].connector.source").value("github"))
            .andExpect(jsonPath("$[0].credential.id").value(3))
            .andExpect(jsonPath("$[0].groups").isArray)
    }
}
