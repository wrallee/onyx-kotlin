package com.onyx.kotlin.connector

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

data class OAuthDetailsResponse(
    @get:JsonProperty("oauth_enabled")
    val oauthEnabled: Boolean,
    @get:JsonProperty("supports_manual_credentials")
    val supportsManualCredentials: Boolean,
    @get:JsonProperty("additional_kwargs")
    val additionalKwargs: List<Any> = emptyList(),
)

@RestController
class ConnectorOAuthController {
    @GetMapping("/connector/oauth/details/{source}")
    fun details(@PathVariable source: String): OAuthDetailsResponse {
        ConnectorSource.fromValue(source)
        return OAuthDetailsResponse(
            oauthEnabled = false,
            supportsManualCredentials = true,
        )
    }
}
