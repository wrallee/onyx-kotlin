package com.onyx.foss.kotlin.connector

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import com.onyx.foss.kotlin.api.ApiException
import com.onyx.foss.kotlin.api.ObjectCreationResponse
import com.onyx.foss.kotlin.api.StatusResponse
import com.onyx.foss.kotlin.ingestion.AttemptStatus
import com.onyx.foss.kotlin.ingestion.IngestionAttemptEntity
import com.onyx.foss.kotlin.ingestion.IngestionAttemptRepository
import com.onyx.foss.kotlin.ingestion.IngestionCommandService
import com.onyx.foss.kotlin.ingestion.IndexedDocumentRepository
import com.onyx.foss.kotlin.opensearch.OpenSearchIndexer
import com.onyx.foss.kotlin.opensearch.PairExternalWriteFence
import com.onyx.foss.kotlin.security.CredentialCipher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
class ConnectorService(
    private val mapper: ObjectMapper,
    private val cipher: CredentialCipher,
    private val credentials: CredentialRepository,
    private val connectors: ConnectorRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val attempts: IngestionAttemptRepository,
    private val documents: IndexedDocumentRepository,
    private val indexer: OpenSearchIndexer,
    private val externalWrites: PairExternalWriteFence,
    private val transactions: TransactionTemplate,
    private val commands: IngestionCommandService,
) {
    @Transactional
    fun createCredential(request: CredentialRequest): ObjectCreationResponse {
        val saved = credentials.save(
            CredentialEntity(
                source = request.source,
                name = request.name?.trim()?.ifBlank { null },
                secretJson = cipher.encrypt(request.credentialJson),
                adminPublic = request.adminPublic,
                curatorPublic = request.curatorPublic,
            ),
        )
        return ObjectCreationResponse(id(saved), credentialSnapshot(saved))
    }

    fun listCredentials(source: ConnectorSource?): List<Map<String, Any?>> =
        (source?.let(credentials::findAllBySource) ?: credentials.findAll()).map(::credentialSnapshot)

    fun credentialSnapshot(value: CredentialEntity): Map<String, Any?> = mapOf(
        "id" to id(value),
        "credential_json" to cipher.masked(cipher.decrypt(value.secretJson)),
        "admin_public" to value.adminPublic,
        "curator_public" to value.curatorPublic,
        "groups" to emptyList<Long>(),
        "source" to value.source.value,
        "name" to value.name,
        "user_id" to null,
        "user_email" to null,
        "time_created" to value.createdAt,
        "time_updated" to value.updatedAt,
    )

    @Transactional
    fun updateCredential(credentialId: Long, request: CredentialUpdateRequest): Map<String, Any?> {
        val value = credential(credentialId)
        value.name = request.name.trim()
        value.secretJson = cipher.encrypt(mergeMaskedCredential(cipher.decrypt(value.secretJson), request.credentialJson))
        return credentialSnapshot(credentials.save(value))
    }

    @Transactional
    fun deleteCredential(credentialId: Long): StatusResponse {
        if (pairs.findAllByCredentialId(credentialId).isNotEmpty()) {
            throw ApiException(HttpStatus.CONFLICT, "Credential is still associated with a connector")
        }
        credentials.delete(credential(credentialId))
        return StatusResponse(true, "Credential deleted successfully", credentialId)
    }

    @Transactional
    fun createConnector(request: ConnectorRequest): ObjectCreationResponse {
        val saved = connectors.save(
            ConnectorEntity(
                name = request.name.trim(),
                source = request.source,
                inputType = request.inputType,
                connectorSpecificConfig = request.connectorSpecificConfig,
                refreshFreq = request.refreshFreq,
                pruneFreq = request.pruneFreq,
                indexingStart = request.indexingStart,
            ),
        )
        return ObjectCreationResponse(id(saved))
    }
    @Transactional
    fun createConnectorWithMockCredential(request: ConnectorRequest): StatusResponse {
        val connector = connectors.save(
            ConnectorEntity(
                name = request.name.trim(),
                source = request.source,
                inputType = request.inputType,
                connectorSpecificConfig = request.connectorSpecificConfig,
                refreshFreq = request.refreshFreq,
                pruneFreq = request.pruneFreq,
                indexingStart = request.indexingStart,
            ),
        )
        val credential = credentials.save(
            CredentialEntity(
                source = request.source,
                name = request.name.trim(),
                secretJson = cipher.encrypt(mapper.createObjectNode()),
            ),
        )
        val pair = pairs.save(
            ConnectorCredentialPairEntity(
                connectorId = id(connector),
                credentialId = id(credential),
                name = request.name.trim(),
                accessType = "public",
            ),
        )
        commands.enqueuePair(id(pair), false)
        return StatusResponse(true, "Connector created successfully", id(pair))
    }

    fun listConnectors(credentialId: Long?): List<Map<String, Any?>> {
        val values = if (credentialId == null) connectors.findAll() else {
            pairs.findAllByCredentialId(credentialId).mapNotNull { connectors.findById(it.connectorId).orElse(null) }
        }
        return values.distinctBy { it.id }.map(::connectorSnapshot)
    }

    fun connectorSnapshot(value: ConnectorEntity): Map<String, Any?> = mapOf(
        "id" to id(value),
        "name" to value.name,
        "source" to value.source.value,
        "input_type" to value.inputType,
        "connector_specific_config" to (value.connectorSpecificConfig ?: mapper.createObjectNode()),
        "refresh_freq" to value.refreshFreq,
        "prune_freq" to value.pruneFreq,
        "indexing_start" to value.indexingStart,
        "credential_ids" to pairs.findAllByConnectorId(id(value)).map { it.credentialId },
        "time_created" to value.createdAt,
        "time_updated" to value.updatedAt,
    )

    @Transactional
    fun updateConnector(connectorId: Long, request: ConnectorRequest): Map<String, Any?> {
        val value = lockConnectorForMutation(connectorId)
        value.name = request.name.trim()
        value.source = request.source
        value.inputType = request.inputType
        value.connectorSpecificConfig = request.connectorSpecificConfig
        value.refreshFreq = request.refreshFreq
        value.pruneFreq = request.pruneFreq
        value.indexingStart = request.indexingStart
        return connectorSnapshot(connectors.save(value))
    }

    fun deleteConnector(connectorId: Long): StatusResponse {
        val pairIds = requireNotNull(
            transactions.execute {
                val connector = connectors.lockById(connectorId)
                    ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector not found")
                connector.deleting = true
                connectors.save(connector)
                val lockedPairs = pairs.findAllByConnectorId(connectorId).map { pair ->
                    pairs.lockById(id(pair)) ?: throw ApiException(HttpStatus.NOT_FOUND, "CC Pair not found")
                }
                lockedPairs.forEach(::markDeleting)
                connectors.flush()
                pairs.flush()
                lockedPairs.map(::id)
            },
        )
        externalWrites.withPairs(pairIds) {
            pairIds.forEach(indexer::deletePair)
            transactions.executeWithoutResult {
                pairIds.forEach(documents::deleteAllByCcPairId)
                connectors.lockById(connectorId)?.let(connectors::delete)
            }
        }
        return StatusResponse(true, "Connector deleted successfully", connectorId)
    }

    fun deletePair(request: DeletionAttemptRequest): StatusResponse {
        val plan = requireNotNull(
            transactions.execute {
                val connector = connectors.lockById(request.connectorId)
                    ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector not found")
                val existing = pairs.findByConnectorIdAndCredentialId(request.connectorId, request.credentialId)
                    ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector credential pair not found")
                val pair = pairs.lockById(id(existing))
                    ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector credential pair not found")
                val pairId = id(pair)
                val hasOtherPairs = pairs.findAllByConnectorId(request.connectorId).any { id(it) != pairId }
                if (connector.deleting && hasOtherPairs) {
                    throw ApiException(HttpStatus.CONFLICT, "Connector is being deleted")
                }
                if (!hasOtherPairs) {
                    connector.deleting = true
                    connectors.save(connector)
                }
                markDeleting(pair)
                connectors.flush()
                pairs.flush()
                PairDeletionPlan(pairId, hasOtherPairs)
            },
        )
        externalWrites.withPair(plan.pairId) {
            indexer.deletePair(plan.pairId)
            transactions.executeWithoutResult {
                documents.deleteAllByCcPairId(plan.pairId)
                pairs.lockById(plan.pairId)?.let(pairs::delete)
                if (!plan.hasOtherPairs) connectors.lockById(request.connectorId)?.let(connectors::delete)
            }
        }
        return StatusResponse(true, "Connector deletion completed", plan.pairId)
    }

    private fun markDeleting(pair: ConnectorCredentialPairEntity) {
        pair.status = PairStatus.DELETING
        pair.ingestionClaimToken = null
        pair.ingestionLeaseExpiresAt = null
        pairs.save(pair)
    }

    @Transactional
    fun associate(connectorId: Long, credentialId: Long, request: PairMetadataRequest): StatusResponse {
        val connector = connectors.lockById(connectorId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector not found")
        requireNotDeleting(connector)
        val credential = credential(credentialId)
        if (connector.source != credential.source) {
            throw ApiException(HttpStatus.BAD_REQUEST, "Connector and credential source do not match")
        }
        val existingPair = pairs.findByConnectorIdAndCredentialId(connectorId, credentialId)
        val pair = existingPair?.let { existing ->
            val locked = pairs.lockById(id(existing))
                ?: throw ApiException(HttpStatus.NOT_FOUND, "CC Pair not found")
            requireNotDeleting(locked)
            locked
        } ?: ConnectorCredentialPairEntity(connectorId = connectorId, credentialId = credentialId)
        pair.name = request.name.trim()
        pair.accessType = "public"
        pair.autoSyncOptions = request.autoSyncOptions
        pair.processingMode = request.processingMode
        pair.status = if (existingPair == null) PairStatus.SCHEDULED else PairStatus.ACTIVE
        val pairId = id(pairs.save(pair))
        if (existingPair == null) commands.enqueuePair(pairId, fromBeginning = true)
        return StatusResponse(true, "Credential linked successfully", pairId)
    }

    fun pairDetail(pairId: Long): Map<String, Any?> {
        val pair = pair(pairId)
        val latest = attempts.findFirstByCcPairIdOrderByIdDesc(pairId)
        val lastSuccessful = lastSuccessfulAttempt(pairId)
        return mapOf(
            "id" to pairId,
            "name" to pair.name,
            "status" to pair.status.name,
            "in_repeated_error_state" to pair.inRepeatedErrorState,
            "num_docs_indexed" to documents.countByCcPairId(pairId),
            "connector" to connectorSnapshot(connector(pair.connectorId)),
            "credential" to credentialSnapshot(credential(pair.credentialId)),
            "number_of_index_attempts" to attempts.findAllByCcPairIdOrderByIdDesc(pairId).size,
            "last_index_attempt_status" to latest?.status?.value,
            "latest_deletion_attempt" to null,
            "access_type" to "public",
            "is_editable_for_current_user" to true,
            "permissions" to mapOf("edit" to true, "delete" to true, "manage" to true),
            "deletion_failure_message" to null,
            "indexing" to (latest?.status == AttemptStatus.IN_PROGRESS),
            "creator" to null,
            "creator_email" to null,
            "last_indexed" to lastSuccessful?.timeStarted,
            "last_pruned" to pair.lastPrunedAt,
            "last_full_permission_sync" to null,
            "overall_indexing_speed" to null,
            "latest_checkpoint_description" to null,
            "last_permission_sync_attempt_status" to null,
            "permission_syncing" to false,
            "last_permission_sync_attempt_finished" to null,
            "last_permission_sync_attempt_error_message" to null,
            "supports_targeted_reindex" to false,
        )
    }

    @Transactional
    fun setPairStatus(pairId: Long, status: PairStatus): Map<String, Any?> {
        val pair = lockPairForMutation(pairId).pair
        pair.status = status
        if (status == PairStatus.ACTIVE) {
            pair.inRepeatedErrorState = false
        }
        pairs.save(pair)
        return pairDetail(pairId)
    }

    private fun lastSuccessfulAttempt(pairId: Long): IngestionAttemptEntity? =
        attempts.findFirstByCcPairIdAndStatusInOrderByTimeStartedDescIdDesc(
            pairId,
            listOf(AttemptStatus.SUCCESS, AttemptStatus.COMPLETED_WITH_ERRORS),
        )

    private fun requireNotDeleting(connector: ConnectorEntity) {
        if (connector.deleting) throw ApiException(HttpStatus.CONFLICT, "Connector is being deleted")
    }

    private fun requireNotDeleting(pair: ConnectorCredentialPairEntity) {
        if (pair.status == PairStatus.DELETING) throw ApiException(HttpStatus.CONFLICT, "CC Pair is being deleted")
    }

    private fun lockConnectorForMutation(connectorId: Long): ConnectorEntity {
        val connector = connectors.lockById(connectorId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector not found")
        requireNotDeleting(connector)
        return connector
    }

    private fun lockPairForMutation(pairId: Long): PairMutation {
        val connectorId = pairs.findConnectorIdById(pairId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "CC Pair not found")
        val connector = lockConnectorForMutation(connectorId)
        val pair = pairs.lockById(pairId) ?: throw ApiException(HttpStatus.NOT_FOUND, "CC Pair not found")
        requireNotDeleting(pair)
        return PairMutation(connector, pair)
    }

    private fun mergeMaskedCredential(current: JsonNode, update: JsonNode): JsonNode {
        if (!current.isObject || !update.isObject) return update
        val merged = update.deepCopy() as ObjectNode
        update.properties().forEach { (name, value) ->
            if (value.isString && value.asString() == "********") merged.set(name, current.path(name))
        }
        return merged
    }

    fun connector(connectorId: Long): ConnectorEntity =
        connectors.findById(connectorId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "Connector not found") }

    fun credential(credentialId: Long): CredentialEntity =
        credentials.findById(credentialId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "Credential not found") }

    fun credentialSecret(credentialId: Long) =
        cipher.decrypt(credential(credentialId).secretJson)

    fun pair(pairId: Long): ConnectorCredentialPairEntity =
        pairs.findById(pairId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "CC Pair not found") }

    @Transactional
    fun renamePair(pairId: Long, name: String): Map<String, Any?> {
        val value = lockPairForMutation(pairId).pair
        value.name = name.trim()
        pairs.save(value)
        return pairDetail(pairId)
    }

    @Transactional
    fun updatePairProperty(pairId: Long, request: CCPropertyUpdateRequest): StatusResponse {
        val mutation = lockPairForMutation(pairId)
        val connector = mutation.connector
        val value = request.value.toLongOrNull()
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "Property value must be an integer")
        val message = when (request.name) {
            "refresh_frequency" -> {
                if (value < 60) {
                    throw ApiException(HttpStatus.BAD_REQUEST, "Refresh frequency must be at least 60 seconds")
                }
                connector.refreshFreq = value
                "Refresh frequency updated successfully"
            }
            "pruning_frequency" -> {
                if (value < 300) {
                    throw ApiException(HttpStatus.BAD_REQUEST, "Pruning frequency must be at least 300 seconds")
                }
                connector.pruneFreq = value
                "Pruning frequency updated successfully"
            }
            else -> throw ApiException(HttpStatus.BAD_REQUEST, "Property name ${request.name} is not valid")
        }
        connectors.save(connector)
        return StatusResponse(true, message, pairId)
    }

    private fun id(entity: Any): Long = when (entity) {
        is CredentialEntity -> requireNotNull(entity.id)
        is ConnectorEntity -> requireNotNull(entity.id)
        is ConnectorCredentialPairEntity -> requireNotNull(entity.id)
        else -> error("Unsupported entity id")
    }
}

private data class PairDeletionPlan(val pairId: Long, val hasOtherPairs: Boolean)
private data class PairMutation(val connector: ConnectorEntity, val pair: ConnectorCredentialPairEntity)
