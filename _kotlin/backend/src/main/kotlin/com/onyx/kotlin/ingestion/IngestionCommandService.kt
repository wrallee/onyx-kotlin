package com.onyx.kotlin.ingestion

import com.onyx.kotlin.api.ApiException
import com.onyx.kotlin.api.StatusResponse
import com.onyx.kotlin.connector.ConnectorCredentialPairEntity
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.ConnectorRepository
import com.onyx.kotlin.connector.PairStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class IngestionCommandService(
    private val connectors: ConnectorRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val attempts: IngestionAttemptRepository,
    private val jobs: IngestionJobRepository,
) {
    @Transactional
    fun enqueue(request: RunConnectorRequest): StatusResponse {
        lockConnectorForMutation(request.connectorId)
        val all = pairs.findAllByConnectorId(request.connectorId)
        val selected = if (request.credentialIds.isNullOrEmpty() || request.credentialIds == listOf(0L)) {
            all
        } else {
            all.filter { request.credentialIds.contains(it.credentialId) }
        }
        if (selected.isEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "Connector has no valid credentials")
        selected.forEach { pair ->
            val pairId = id(pair)
            val lockedPair = lockPairForMutation(pairId)
            lockedPair.inRepeatedErrorState = false
            if (lockedPair.status == PairStatus.PAUSED) {
                lockedPair.status = PairStatus.ACTIVE
            }
            pairs.save(lockedPair)
            enqueuePair(pairId, request.fromBeginning)
        }
        return StatusResponse(true, "Connector indexing requested", request.connectorId)
    }

    @Transactional
    fun enqueuePair(pairId: Long, fromBeginning: Boolean, pruneOnly: Boolean = false): Long {
        lockPairForMutation(pairId)
        jobs.findFirstByCcPairIdAndStateInOrderById(pairId, listOf(JobState.QUEUED, JobState.RUNNING))
            ?.let { return id(it) }
        val attempt = attempts.save(
            IngestionAttemptEntity(
                ccPairId = pairId,
                fromBeginning = fromBeginning,
                pruneOnly = pruneOnly,
            ),
        )
        return id(
            jobs.save(
                IngestionJobEntity(attemptId = id(attempt), ccPairId = pairId, state = JobState.QUEUED),
            ),
        )
    }
    private fun lockConnectorForMutation(connectorId: Long) {
        val connector = connectors.lockById(connectorId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "Connector not found")
        if (connector.deleting) throw ApiException(HttpStatus.CONFLICT, "Connector is being deleted")
    }

    private fun lockPairForMutation(pairId: Long): ConnectorCredentialPairEntity {
        val connectorId = pairs.findConnectorIdById(pairId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "CC Pair not found")
        lockConnectorForMutation(connectorId)
        val pair = pairs.lockById(pairId) ?: throw ApiException(HttpStatus.NOT_FOUND, "CC Pair not found")
        if (pair.status == PairStatus.DELETING) throw ApiException(HttpStatus.CONFLICT, "CC Pair is being deleted")
        return pair
    }

    private fun id(entity: Any): Long = when (entity) {
        is ConnectorCredentialPairEntity -> requireNotNull(entity.id)
        is IngestionAttemptEntity -> requireNotNull(entity.id)
        is IngestionJobEntity -> requireNotNull(entity.id)
        else -> error("Unsupported entity id")
    }
}
