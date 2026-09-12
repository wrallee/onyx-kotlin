package com.onyx.foss.kotlin.ingestion

import com.onyx.foss.kotlin.ingestion.IngestionAttemptRepository
import com.onyx.foss.kotlin.ingestion.IngestionErrorRepository
import com.onyx.foss.kotlin.connector.ConnectorCredentialPairEntity
import com.onyx.foss.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.foss.kotlin.connector.ConnectorService
import com.onyx.foss.kotlin.connector.ConnectorSource
import com.onyx.foss.kotlin.connector.PairStatus
import org.springframework.stereotype.Service

@Service
class IngestionQueryService(
    private val attempts: IngestionAttemptRepository,
    private val errors: IngestionErrorRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val documents: IndexedDocumentRepository,
    private val connectorService: ConnectorService,
) {
    fun attempts(pairId: Long, page: Int, pageSize: Int): Map<String, Any?> {
        validatePage(page, pageSize)
        val all = attempts.findAllByCcPairIdOrderByIdDesc(pairId)
        val slice = all.drop(page * pageSize).take(pageSize)
        return mapOf(
            "items" to slice.map { attempt ->
                mapOf(
                    "id" to attempt.id,
                    "status" to attempt.status.value,
                    "from_beginning" to attempt.fromBeginning,
                    "new_docs_indexed" to attempt.newDocsIndexed,
                    "total_docs_indexed" to attempt.totalDocsIndexed,
                    "docs_removed_from_index" to attempt.docsRemovedFromIndex,
                    "error_msg" to attempt.errorMessage,
                    "error_count" to errors.findAllByAttemptIdOrderByIdDesc(requireNotNull(attempt.id)).size,
                    "full_exception_trace" to attempt.fullExceptionTrace,
                    "time_started" to attempt.timeStarted,
                    "time_updated" to attempt.timeUpdated,
                    "poll_range_start" to attempt.pollRangeStart,
                    "poll_range_end" to attempt.pollRangeEnd,
                )
            },
            "total_items" to all.size,
        )
    }

    fun errors(pairId: Long, page: Int, pageSize: Int): Map<String, Any?> {
        validatePage(page, pageSize)
        val ids = attempts.findAllByCcPairIdOrderByIdDesc(pairId).mapNotNull { it.id }
        val all = if (ids.isEmpty()) {
            emptyList()
        } else {
            errors.findAllByAttemptIdInOrderByIdDesc(ids).filterNot { it.isResolved }
        }
        return mapOf(
            "items" to all.drop(page * pageSize).take(pageSize).map {
                mapOf(
                    "id" to it.id,
                    "connector_credential_pair_id" to pairId,
                    "document_id" to it.sourceDocumentId,
                    "document_link" to it.documentLink,
                    "entity_id" to it.entityId,
                    "failed_time_range_start" to it.failedTimeRangeStart,
                    "failed_time_range_end" to it.failedTimeRangeEnd,
                    "failure_message" to it.failureMessage,
                    "is_resolved" to it.isResolved,
                    "time_created" to it.createdAt,
                    "index_attempt_id" to it.attemptId,
                    "error_type" to it.errorType,
                )
            },
            "total_items" to all.size,
        )
    }

    fun indexingStatus(source: ConnectorSource?, filter: String?): List<Map<String, Any?>> =
        pairs.findAll().asSequence()
            .filter { source == null || connectorService.connector(it.connectorId).source == source }
            .filter { filter.isNullOrBlank() || it.name.contains(filter, true) }
            .groupBy { connectorService.connector(it.connectorId).source }
            .map { (kind, values) ->
                mapOf(
                    "source" to kind.value,
                    "summary" to mapOf(
                        "total_connectors" to values.size,
                        "active_connectors" to values.count { it.status == PairStatus.ACTIVE },
                        "public_connectors" to values.count { it.accessType == "public" },
                        "total_docs_indexed" to values.sumOf { documents.countByCcPairId(requireNotNull(it.id)) },
                    ),
                    "current_page" to 1,
                    "total_pages" to 1,
                    "indexing_statuses" to values.map(::indexingRow),
                )
            }

    fun connectorStatuses(): List<Map<String, Any?>> = pairs.findAll().map { pair ->
        mapOf(
            "cc_pair_id" to requireNotNull(pair.id),
            "name" to pair.name,
            "connector" to connectorService.connectorSnapshot(connectorService.connector(pair.connectorId)),
            "credential" to connectorService.credentialSnapshot(connectorService.credential(pair.credentialId)),
            "access_type" to pair.accessType,
            "groups" to emptyList<Long>(),
        )
    }

    private fun indexingRow(pair: ConnectorCredentialPairEntity): Map<String, Any?> {
        val pairId = requireNotNull(pair.id)
        val latest = attempts.findFirstByCcPairIdOrderByIdDesc(pairId)
        val lastSuccessful = attempts.findFirstByCcPairIdAndStatusInOrderByTimeStartedDescIdDesc(
            pairId,
            listOf(AttemptStatus.SUCCESS, AttemptStatus.COMPLETED_WITH_ERRORS),
        )
        return mapOf(
            "cc_pair_id" to pairId,
            "name" to pair.name,
            "source" to connectorService.connector(pair.connectorId).source.value,
            "access_type" to pair.accessType,
            "cc_pair_status" to pair.status.name,
            "in_progress" to (latest?.status == AttemptStatus.IN_PROGRESS),
            "in_repeated_error_state" to pair.inRepeatedErrorState,
            "last_finished_status" to latest?.status?.takeIf { it != AttemptStatus.IN_PROGRESS }?.value,
            "last_status" to latest?.status?.value,
            "last_success" to lastSuccessful?.timeStarted,
            "is_editable" to true,
            "permissions" to mapOf("edit" to true, "delete" to true, "manage" to true),
            "docs_indexed" to documents.countByCcPairId(pairId),
            "latest_index_attempt_docs_indexed" to latest?.totalDocsIndexed,
        )
    }


    private fun validatePage(page: Int, pageSize: Int) {
        require(page >= 0) { "page_num must be non-negative" }
        require(pageSize > 0) { "page_size must be positive" }
    }
}
