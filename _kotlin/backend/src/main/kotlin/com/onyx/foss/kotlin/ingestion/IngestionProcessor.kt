package com.onyx.foss.kotlin.ingestion

import tools.jackson.databind.ObjectMapper
import com.onyx.foss.kotlin.config.OnyxProperties
import com.onyx.foss.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.foss.kotlin.connector.ConnectorSource
import com.onyx.foss.kotlin.connector.PairStatus
import com.onyx.foss.kotlin.connector.loader.ConnectorFailure
import com.onyx.foss.kotlin.connector.loader.FailureTarget
import com.onyx.foss.kotlin.connector.loader.FileConnectorLoader
import com.onyx.foss.kotlin.connector.loader.RemoteConnectorLoaders
import com.onyx.foss.kotlin.connector.loader.SourceDocument
import com.onyx.foss.kotlin.documentset.DocumentSetRepository
import com.onyx.foss.kotlin.model.ModelServerClient
import com.onyx.foss.kotlin.opensearch.OpenSearchIndexer
import com.onyx.foss.kotlin.opensearch.PairExternalWriteFence
import com.onyx.foss.kotlin.service.AdminService
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Service
class IngestionProcessor(
    private val properties: OnyxProperties,
    private val admin: AdminService,
    private val pairs: ConnectorCredentialPairRepository,
    private val attempts: IngestionAttemptRepository,
    private val checkpoints: IngestionCheckpointRepository,
    private val errors: IngestionErrorRepository,
    private val enumeration: IngestionEnumerationRepository,
    private val documents: IndexedDocumentRepository,
    private val documentSets: DocumentSetRepository,
    private val fileLoader: FileConnectorLoader,
    private val remoteLoaders: RemoteConnectorLoaders,
    private val embedder: ModelServerClient,
    private val indexer: OpenSearchIndexer,
    private val pruning: PruningService,
    private val mapper: ObjectMapper,
    private val claims: JobClaimService,
    private val externalWrites: PairExternalWriteFence,
) {
    fun process(jobId: Long) {
        claims.claimJob(jobId)?.let(::process)
    }

    fun process(claim: IngestionClaim) {
        if (!claims.start(claim)) return
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return
        val pair = pairs.findById(claim.pairId).orElse(null) ?: return
        var refreshFreq: Long? = null
        try {
            val connector = admin.connector(pair.connectorId)
            refreshFreq = connector.refreshFreq
            if (!attempt.pruneOnly) setPollRange(attempt, connector.indexingStart)
            attempts.save(attempt)
            val checkpoint = if (attempt.fromBeginning || attempt.pruneOnly) {
                null
            } else {
                checkpoints.findById(requireNotNull(pair.id)).orElse(null)?.checkpointJson
            }
            val credentials = admin.credentialSecret(pair.credentialId)
            val batches = if (attempt.pruneOnly) {
                when (connector.source) {
                    ConnectorSource.FILE -> fileLoader.load(connector.connectorSpecificConfig)
                    else -> remoteLoaders.loadSlim(
                        connector.source,
                        connector.connectorSpecificConfig,
                        credentials,
                    )
                }
            } else {
                when (connector.source) {
                    ConnectorSource.FILE -> fileLoader.load(connector.connectorSpecificConfig)
                    else -> remoteLoaders.load(
                        connector.source,
                        connector.connectorSpecificConfig,
                        credentials,
                        checkpoint,
                        attempt.pollRangeStart,
                        attempt.pollRangeEnd,
                    )
                }
            }
            var newDocuments = attempt.newDocsIndexed
            var totalDocuments = attempt.totalDocsIndexed
            var hasFailures = false
            var completeEnumeration = false
            var enumerationSafe = true
            val attemptId = requireNotNull(attempt.id)
            val batchIterator = batches.iterator()
            while (true) {
                stopIfStopped(claim)
                if (!batchIterator.hasNext()) break
                val batch = batchIterator.next()
                stopIfStopped(claim)
                val batchFailedDocumentIds = batch.failures.mapNotNull { failure ->
                    (failure.target as? FailureTarget.Document)?.id
                }.toSet()
                enumeration.protectFailures(attemptId, batchFailedDocumentIds)
                if (!batch.enumerationComplete || batch.failures.any { it.target !is FailureTarget.Document }) {
                    enumerationSafe = false
                }
                val newDocumentIds = enumeration.registerDocuments(attemptId, batch.documents.map(SourceDocument::id))
                val processedInBatch = mutableSetOf<String>()
                batch.documents.forEach { document ->
                    if (document.id !in newDocumentIds || !processedInBatch.add(document.id)) return@forEach
                    if (attempt.pruneOnly) return@forEach
                    if (document.title.isBlank() && document.content.isBlank()) {
                        enumerationSafe = false
                        return@forEach
                    }
                    val indexableContent = document.content.ifBlank { document.title }
                    val chunks = indexableContent.chunked(1500).filter { it.isNotBlank() }
                    if (chunks.isEmpty()) {
                        enumerationSafe = false
                        return@forEach
                    }
                    renew(claim)
                    val vectors = withLeaseHeartbeat(claim) { embedder.embed(chunks) }
                    check(vectors.size == chunks.size) {
                        "Model server returned ${vectors.size} embeddings for ${chunks.size} chunks"
                    }
                    renew(claim)
                    val documentSetNames = documentSets.findNamesByCcPairId(requireNotNull(pair.id))
                    chunks.zip(vectors).forEachIndexed { index, item ->
                        externalWrites.withPair(requireNotNull(pair.id)) {
                            renew(claim)
                            indexer.upsert(
                                pairId = requireNotNull(pair.id),
                                sourceDocumentId = document.id,
                                chunkId = index,
                                title = document.title,
                                content = item.first,
                                link = document.link,
                                metadata = document.metadata,
                                embedding = item.second,
                                documentSets = documentSetNames,
                                updatedAt = document.updatedAt,
                                primaryOwners = document.primaryOwners,
                                secondaryOwners = document.secondaryOwners,
                                sourceType = connector.source,
                            )
                        }
                        renew(claim)
                    }
                    renew(claim)
                    indexer.deleteStaleChunks(requireNotNull(pair.id), document.id, chunks.size)
                    renew(claim)
                    val existing = documents.findByCcPairIdAndSourceDocumentId(requireNotNull(pair.id), document.id)
                    if (existing == null) newDocuments += 1
                    val indexedDocument = existing ?: IndexedDocumentEntity(
                        ccPairId = requireNotNull(pair.id),
                        sourceDocumentId = document.id,
                    )
                    indexedDocument.apply {
                        title = document.title
                        link = document.link
                        contentHash = hash(document.content)
                        metadata = mapper.valueToTree(document.metadata)
                        externalAccess = document.externalAccess?.let(mapper::valueToTree)
                        lastModified = document.updatedAt
                        primaryOwners = document.primaryOwners
                        secondaryOwners = document.secondaryOwners
                        lastSynced = Instant.now()
                    }
                    documents.save(indexedDocument)
                    totalDocuments += 1
                    attempt.newDocsIndexed = newDocuments
                    attempt.totalDocsIndexed = totalDocuments
                    attempts.save(attempt)
                    if (document.id !in batchFailedDocumentIds) {
                        val resolvedErrors = errors
                            .findUnresolvedByCcPairIdAndSourceDocumentId(requireNotNull(pair.id), document.id)
                            .onEach { it.isResolved = true }
                        errors.saveAll(resolvedErrors)
                    }
                    enumeration.markProcessed(attemptId, document.id)
                }
                if (batch.failures.isNotEmpty()) {
                    hasFailures = true
                    errors.saveAll(batch.failures.map { failure -> failure.toEntity(requireNotNull(attempt.id)) })
                }
                if (!attempt.pruneOnly) {
                    checkpoints.save(
                        IngestionCheckpointEntity(
                            ccPairId = requireNotNull(pair.id),
                            checkpointJson = batch.checkpoint.value,
                        ),
                    )
                }
                completeEnumeration = enumerationSafe && !batch.checkpoint.hasMore
                attempt.enumerationComplete = completeEnumeration
                attempts.save(attempt)
            }
            stopIfStopped(claim)
            attempt.docsRemovedFromIndex = pruning.prune(
                requireNotNull(pair.id),
                attemptId,
                attempt.fromBeginning || attempt.pruneOnly,
                completeEnumeration,
                beforeDelete = { renew(claim) },
            )
            if (!hasFailures) {
                val resolvedEntityErrors = errors.findUnresolvedEntityErrorsByCcPairId(requireNotNull(pair.id))
                    .onEach { it.isResolved = true }
                errors.saveAll(resolvedEntityErrors)
            }
            renew(claim)
            claims.complete(
                claim = claim,
                status = if (hasFailures) AttemptStatus.COMPLETED_WITH_ERRORS else AttemptStatus.SUCCESS,
                newDocuments = newDocuments,
                totalDocuments = totalDocuments,
                removedDocuments = attempt.docsRemovedFromIndex,
                updateLastPrunedAt = (attempt.fromBeginning || attempt.pruneOnly) && completeEnumeration,
            )
        } catch (_: ConnectorPausedException) {
            claims.cancel(claim)
        } catch (_: StaleIngestionClaimException) {
            return
        } catch (error: Exception) {
            claims.fail(claim, error, refreshFreq)
        }
    }

    private fun stopIfStopped(claim: IngestionClaim) {
        if (pairs.findById(claim.pairId).orElse(null)?.status == PairStatus.PAUSED) throw ConnectorPausedException()
        renew(claim)
    }

    private fun renew(claim: IngestionClaim) {
        if (!claims.renew(claim)) throw StaleIngestionClaimException()
    }

    private fun <T> withLeaseHeartbeat(claim: IngestionClaim, action: () -> T): T {
        require(properties.worker.heartbeatIntervalMs > 0) { "Worker heartbeat interval must be positive" }
        val stopped = AtomicBoolean(false)
        val stale = AtomicBoolean(false)
        val heartbeat = Thread.ofVirtual().name("ingestion-heartbeat-${claim.jobId}").start {
            try {
                while (!stopped.get()) {
                    Thread.sleep(properties.worker.heartbeatIntervalMs)
                    if (!stopped.get() && !runCatching { claims.renew(claim) }.getOrDefault(false)) {
                        stale.set(true)
                        return@start
                    }
                }
            } catch (_: InterruptedException) {
                // The model request completed.
            }
        }
        val result = try {
            action()
        } finally {
            stopped.set(true)
            heartbeat.interrupt()
            heartbeat.join(5_000)
        }
        if (stale.get()) throw StaleIngestionClaimException()
        return result
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun setPollRange(attempt: IngestionAttemptEntity, indexingStart: Instant?) {
        if (attempt.pollRangeStart != null && attempt.pollRangeEnd != null) return
        val priorAttempts = attempts.findAllByCcPairIdOrderByIdDesc(attempt.ccPairId)
            .filterNot { it.id == attempt.id }
        val resumable = priorAttempts.firstOrNull()?.takeIf { it.status == AttemptStatus.FAILED }
        if (resumable?.pollRangeStart != null && resumable.pollRangeEnd != null) {
            attempt.pollRangeStart = resumable.pollRangeStart
            attempt.pollRangeEnd = resumable.pollRangeEnd
            return
        }
        val previousEnd = priorAttempts.firstOrNull {
            it.status in setOf(AttemptStatus.SUCCESS, AttemptStatus.COMPLETED_WITH_ERRORS) && it.pollRangeEnd != null
        }?.pollRangeEnd
        val earliest = indexingStart ?: Instant.EPOCH
        attempt.pollRangeStart = if (attempt.fromBeginning || previousEnd == null) {
            earliest
        } else {
            previousEnd.minusSeconds(30 * 60).coerceAtLeast(Instant.EPOCH)
        }
        attempt.pollRangeEnd = Instant.now()
    }
}

private class ConnectorPausedException : RuntimeException()
private class StaleIngestionClaimException : RuntimeException()

internal fun isRepeatedError(refreshFreq: Long?, recent: List<IngestionAttemptEntity>): Boolean {
    val required = if (refreshFreq == null) 1 else 5
    return recent.take(required).size == required && recent.take(required).all { it.status == AttemptStatus.FAILED }
}

private fun ConnectorFailure.toEntity(attemptId: Long): IngestionErrorEntity = when (val failureTarget = target) {
    is FailureTarget.Document -> IngestionErrorEntity(
        attemptId = attemptId,
        sourceDocumentId = failureTarget.id,
        documentLink = failureTarget.link,
        failureMessage = message,
        errorType = errorType,
    )
    is FailureTarget.Entity -> IngestionErrorEntity(
        attemptId = attemptId,
        entityId = failureTarget.id,
        failedTimeRangeStart = failureTarget.missedStart,
        failedTimeRangeEnd = failureTarget.missedEnd,
        failureMessage = message,
        errorType = errorType,
    )
}
