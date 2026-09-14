package com.onyx.kotlin.connector

import com.onyx.kotlin.api.ApiExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ConnectorOAuthControllerTest {
    private val mvc = MockMvcBuilders.standaloneSetup(ConnectorOAuthController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun supportedConnectorsExposeManualCredentialsOnly() {
        ConnectorSource.entries.forEach { source ->
            mvc.perform(get("/connector/oauth/details/${source.value}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.oauth_enabled").value(false))
                .andExpect(jsonPath("$.supports_manual_credentials").value(true))
                .andExpect(jsonPath("$.additional_kwargs").isArray)
                .andExpect(jsonPath("$.additional_kwargs").isEmpty)
        }
    }

    @Test
    fun unsupportedConnectorIsRejected() {
        mvc.perform(get("/connector/oauth/details/slack"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Unsupported connector type: slack"))
    }
}
