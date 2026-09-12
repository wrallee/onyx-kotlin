package com.onyx.foss.kotlin.connector

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.onyx.foss.kotlin.api.ObjectCreationResponse
import com.onyx.foss.kotlin.api.StatusResponse
import com.onyx.foss.kotlin.ingestion.IngestionCommandService
import com.onyx.foss.kotlin.ingestion.RunConnectorRequest
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/manage")
class ConnectorController(
    private val connector: ConnectorService,
    private val files: FileStorageService,
    private val commands: IngestionCommandService,
    private val mapper: ObjectMapper,
) {
    @PostMapping("/credential")
    fun createCredential(@Valid @RequestBody request: CredentialRequest): ObjectCreationResponse =
        connector.createCredential(request)

    @GetMapping("/credential")
    fun credentials(): List<Map<String, Any?>> = connector.listCredentials(null)

    @GetMapping("/credential/{credentialId}")
    fun credential(@PathVariable credentialId: Long): Map<String, Any?> =
        connector.credentialSnapshot(connector.credential(credentialId))

    @GetMapping("/admin/credential")
    fun adminCredentials(): List<Map<String, Any?>> = connector.listCredentials(null)

    @GetMapping("/admin/similar-credentials/{source}")
    fun similarCredentials(@PathVariable source: String): List<Map<String, Any?>> =
        connector.listCredentials(ConnectorSource.fromValue(source))

    @PutMapping("/admin/credential/{credentialId}")
    fun updateCredential(
        @PathVariable credentialId: Long,
        @Valid @RequestBody request: CredentialUpdateRequest,
    ): Map<String, Any?> = connector.updateCredential(credentialId, request)

    @DeleteMapping("/credential/{credentialId}", "/admin/credential/{credentialId}")
    fun deleteCredential(@PathVariable credentialId: Long): StatusResponse = connector.deleteCredential(credentialId)

    @GetMapping("/connector")
    fun connectors(): List<Map<String, Any?>> = connector.listConnectors(null)

    @GetMapping("/connector/{connectorId}")
    fun connector(@PathVariable connectorId: Long): Map<String, Any?> =
        connector.connectorSnapshot(connector.connector(connectorId))

    @GetMapping("/admin/connector")
    fun adminConnectors(@RequestParam("credential", required = false) credentialId: Long?): List<Map<String, Any?>> =
        connector.listConnectors(credentialId)

    @PostMapping("/admin/connector")
    fun createConnector(@Valid @RequestBody request: ConnectorRequest): ObjectCreationResponse =
        connector.createConnector(request)
    @PostMapping("/admin/connector-with-mock-credential")
    fun createConnectorWithMockCredential(@Valid @RequestBody request: ConnectorRequest): StatusResponse =
        connector.createConnectorWithMockCredential(request)

    @PatchMapping("/admin/connector/{connectorId}")
    fun updateConnector(
        @PathVariable connectorId: Long,
        @Valid @RequestBody request: ConnectorRequest,
    ): Map<String, Any?> = connector.updateConnector(connectorId, request)

    @DeleteMapping("/admin/connector/{connectorId}")
    fun deleteConnector(@PathVariable connectorId: Long): StatusResponse = connector.deleteConnector(connectorId)

    @PostMapping("/admin/deletion-attempt")
    fun deletePair(@RequestBody request: DeletionAttemptRequest): StatusResponse = connector.deletePair(request)

    @PutMapping("/connector/{connectorId}/credential/{credentialId}")
    fun associateCredential(
        @PathVariable connectorId: Long,
        @PathVariable credentialId: Long,
        @Valid @RequestBody request: PairMetadataRequest,
    ): StatusResponse = connector.associate(connectorId, credentialId, request)

    @PostMapping("/admin/connector/run-once")
    fun runConnector(@RequestBody request: RunConnectorRequest): StatusResponse = commands.enqueue(request)
    @PostMapping("/admin/connector/file/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadFiles(@RequestParam("files") uploaded: List<MultipartFile>): Map<String, Any?> = files.upload(uploaded)

    @GetMapping("/admin/connector/{connectorId}/files")
    fun connectorFiles(@PathVariable connectorId: Long): Map<String, Any?> = files.listConnectorFiles(connectorId)

    @PostMapping("/admin/connector/{connectorId}/files/update", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateConnectorFiles(
        @PathVariable connectorId: Long,
        @RequestParam("files", required = false) uploaded: List<MultipartFile>?,
        @RequestParam("file_ids_to_remove", defaultValue = "[]") idsToRemove: String,
    ): Map<String, Any?> = files.updateConnectorFiles(
        connectorId,
        uploaded ?: emptyList(),
        mapper.readValue(idsToRemove, object : TypeReference<List<String>>() {}),
    )

    @GetMapping("/admin/cc-pair/{pairId}")
    fun ccPair(@PathVariable pairId: Long): Map<String, Any?> = connector.pairDetail(pairId)

    @PutMapping("/admin/cc-pair/{pairId}/status")
    fun updatePairStatus(
        @PathVariable pairId: Long,
        @RequestBody request: PairStatusRequest,
    ): Map<String, Any?> = connector.setPairStatus(pairId, request.status)

    @PutMapping("/admin/cc-pair/{pairId}/name")
    fun updatePairName(
        @PathVariable pairId: Long,
        @RequestParam("new_name") name: String,
    ): Map<String, Any?> = connector.renamePair(pairId, name)

    @PutMapping("/admin/cc-pair/{pairId}/property")
    fun updatePairProperty(
        @PathVariable pairId: Long,
        @Valid @RequestBody request: CCPropertyUpdateRequest,
    ): StatusResponse = connector.updatePairProperty(pairId, request)
}
