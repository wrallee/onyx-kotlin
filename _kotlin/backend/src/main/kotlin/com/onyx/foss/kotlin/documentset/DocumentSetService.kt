package com.onyx.foss.kotlin.documentset

import tools.jackson.databind.ObjectMapper
import com.onyx.foss.kotlin.api.ApiException
import com.onyx.foss.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.foss.kotlin.connector.ConnectorService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DocumentSetService(
    private val mapper: ObjectMapper,
    private val sets: DocumentSetRepository,
    private val setPairs: DocumentSetPairRepository,
    private val documentSetSyncOutbox: DocumentSetSyncOutboxRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val connectorService: ConnectorService,
) {
    @Transactional
    fun createSet(request: DocumentSetRequest): Long {
        validatePairs(request.ccPairIds)
        if (sets.existsByName(request.name.trim())) {
            throw ApiException(HttpStatus.CONFLICT, "Document set name already exists")
        }
        val set = sets.saveAndFlush(DocumentSetEntity(name = request.name.trim(), description = request.description, isPublic = true))
        replaceSetPairs(id(set), request.ccPairIds)
        enqueueDocumentSetSync(request.ccPairIds, id(set))
        return id(set)
    }

    @Transactional
    fun updateSet(request: DocumentSetRequest) {
        val setId = request.id ?: throw ApiException(HttpStatus.BAD_REQUEST, "Document set id is required")
        val set = sets.findById(setId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "Document set not found") }
        validatePairs(request.ccPairIds)
        if (sets.existsByNameAndIdNot(request.name.trim(), setId)) {
            throw ApiException(HttpStatus.CONFLICT, "Document set name already exists")
        }
        val previousPairIds = setPairIds(setId)
        set.name = request.name.trim()
        set.description = request.description
        set.isPublic = true
        sets.saveAndFlush(set)
        replaceSetPairs(setId, request.ccPairIds)
        enqueueDocumentSetSync(previousPairIds + request.ccPairIds, setId)
    }

    @Transactional
    fun deleteSet(setId: Long) {
        if (!sets.existsById(setId)) throw ApiException(HttpStatus.NOT_FOUND, "Document set not found")
        val pairIds = setPairIds(setId)
        setPairs.deleteAllByDocumentSetId(setId)
        sets.deleteById(setId)
        sets.flush()
        enqueueDocumentSetSync(pairIds, setId)
    }

    fun listSets(): List<Map<String, Any?>> = sets.findAll().map(::setSnapshot)

    fun setSnapshot(set: DocumentSetEntity): Map<String, Any?> {
        val setId = id(set)
        val pairIds = setPairIds(setId)
        return mapOf(
            "id" to setId,
            "name" to set.name,
            "description" to set.description,
            "cc_pair_summaries" to pairIds.map(::pairSummary),
            "cc_pair_descriptors" to pairIds.map(::pairDescriptor),
            "is_up_to_date" to !hasActiveDocumentSetSync(setId),
            "is_public" to true,
            "users" to emptyList<String>(),
            "groups" to emptyList<Long>(),
            "permissions" to mapOf("edit" to true, "delete" to true),
            "federated_connector_summaries" to emptyList<Any>(),
            "federated_connectors" to emptyList<Any>(),
        )
    }

    private fun pairSummary(pairId: Long): Map<String, Any?> {
        val pair = connectorService.pair(pairId)
        return mapOf("id" to pairId, "name" to pair.name, "source" to connectorService.connector(pair.connectorId).source.value, "access_type" to pair.accessType)
    }

    private fun pairDescriptor(pairId: Long): Map<String, Any?> {
        val pair = connectorService.pair(pairId)
        return mapOf(
            "id" to pairId,
            "name" to pair.name,
            "connector" to connectorService.connectorSnapshot(connectorService.connector(pair.connectorId)),
            "credential" to connectorService.credentialSnapshot(connectorService.credential(pair.credentialId)),
            "access_type" to pair.accessType,
        )
    }

    private fun replaceSetPairs(setId: Long, pairIds: List<Long>) {
        setPairs.deleteAllByDocumentSetId(setId)
        setPairs.saveAll(pairIds.distinct().map { DocumentSetPairEntity(setId, it) })
    }

    private fun setPairIds(setId: Long): List<Long> =
        setPairs.findAllByDocumentSetIdOrderByCcPairId(setId).map { it.ccPairId }

    private fun hasActiveDocumentSetSync(setId: Long): Boolean = documentSetSyncOutbox.findAllByStatusIn(
        listOf(DocumentSetSyncStatus.PENDING, DocumentSetSyncStatus.IN_PROGRESS),
    ).any { row -> row.documentSetIds?.let { ids -> ids.any { it.asLong() == setId } } ?: true }

    private fun enqueueDocumentSetSync(pairIds: Collection<Long>, documentSetId: Long) {
        val distinctPairIds = pairIds.distinct()
        if (distinctPairIds.isNotEmpty()) {
            documentSetSyncOutbox.save(
                DocumentSetSyncOutboxEntity(
                    ccPairIds = mapper.valueToTree(distinctPairIds),
                    documentSetIds = mapper.valueToTree(listOf(documentSetId)),
                ),
            )
        }
    }

    private fun validatePairs(pairIds: List<Long>) {
        if (pairIds.any { !pairs.existsById(it) }) throw ApiException(HttpStatus.BAD_REQUEST, "Document set references a missing connector")
    }

    fun documentSet(setId: Long): Map<String, Any?> = setSnapshot(
        sets.findById(setId).orElseThrow { ApiException(HttpStatus.NOT_FOUND, "Document set not found") },
    )

    private fun id(set: DocumentSetEntity): Long = requireNotNull(set.id)
}
