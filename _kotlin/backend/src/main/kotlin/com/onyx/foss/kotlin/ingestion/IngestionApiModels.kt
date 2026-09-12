package com.onyx.foss.kotlin.ingestion

import com.fasterxml.jackson.annotation.JsonAlias
import com.onyx.foss.kotlin.connector.ConnectorSource

data class RunConnectorRequest(val connectorId: Long, @param:JsonAlias("credentialIds") val credentialIds: List<Long>? = null, val fromBeginning: Boolean = false)
data class IndexingStatusRequest(val source: ConnectorSource? = null, val sourceToPage: Map<String, Int> = emptyMap(), val nameFilter: String? = null)
