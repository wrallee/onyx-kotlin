package com.onyx.kotlin.connector

import tools.jackson.databind.JsonNode
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class CredentialRequest(val credentialJson: JsonNode, val adminPublic: Boolean = true, val source: ConnectorSource, val name: String? = null, val curatorPublic: Boolean = true, val groups: List<Long> = emptyList())
data class CredentialUpdateRequest(@field:NotBlank val name: String, val credentialJson: JsonNode)
data class ConnectorRequest(@field:NotBlank val name: String, val source: ConnectorSource, val inputType: String = "load_state", val connectorSpecificConfig: JsonNode, val refreshFreq: Long? = null, val pruneFreq: Long? = null, val indexingStart: Instant? = null, val accessType: String = "public", val groups: List<Long> = emptyList())
data class PairMetadataRequest(@field:NotBlank val name: String, val accessType: String = "public", val autoSyncOptions: JsonNode? = null, val groups: List<Long>? = null, val processingMode: String = "REGULAR")
data class PairStatusRequest(val status: PairStatus)
data class CCPropertyUpdateRequest(@field:NotBlank val name: String, @field:NotBlank val value: String)
data class DeletionAttemptRequest(val connectorId: Long, val credentialId: Long)
