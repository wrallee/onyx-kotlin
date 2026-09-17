package com.onyx.kotlin.ingestion

import tools.jackson.databind.ObjectMapper
import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.ConnectorSource
import com.onyx.kotlin.connector.PairStatus
import com.onyx.kotlin.connector.loader.ConnectorFailure
import com.onyx.kotlin.connector.loader.FailureTarget
import com.onyx.kotlin.connector.loader.FileConnectorLoader
import com.onyx.kotlin.connector.loader.RemoteConnectorLoaders
import com.onyx.kotlin.connector.loader.SourceDocument
import com.onyx.kotlin.documentset.DocumentSetRepository
import com.onyx.kotlin.indexing.IndexSettingsService
import com.onyx.kotlin.model.ModelServerClient
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import com.onyx.kotlin.search.IndexedMetadata
import com.onyx.kotlin.opensearch.PairExternalWriteFence
import com.onyx.kotlin.connector.ConnectorService
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Service
class IngestionProcessor(
    private val properties: OnyxProperties,
    private val connectorService: ConnectorService,
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
    private val indexSettings: IndexSettingsService,
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
            val runtime = attempt.searchSettingsId.takeIf { it != 0L }
                ?.let(indexSettings::runtime)
                ?: indexSettings.currentRuntime()
            val connector = connectorService.connector(pair.connectorId)
            refreshFreq = connector.refreshFreq
            if (!attempt.pruneOnly) setPollRange(attempt, connector.indexingStart)
            attempts.save(attempt)
            val checkpoint = if (attempt.fromBeginning || attempt.pruneOnly) {
                null
            } else {
                checkpoints.findById(IngestionCheckpointId(requireNotNull(pair.id), runtime.settingsId)).orElse(null)?.checkpointJson
            }
            val credentials = connectorService.credentialSecret(pair.credentialId)
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
            val unresolvedAttemptErrorsAtStart = if (attempt.fromBeginning) {
                errors.findAllByAttemptIdOrderByIdDesc(attemptId).filterNot { it.isResolved }
            } else {
                emptyList()
            }
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
                    val indexedMetadata = IndexedMetadata.from(connector.source, document.metadata)
                    val chunks = withLeaseHeartbeat(claim) {
                        embedder.chunkAndEmbed(
                            indexableContent,
                            document.title,
                            indexedMetadata.embeddingContext(connector.source),
                            runtime.embedding,
                        )
                    }
                    if (chunks.isEmpty()) {
                        enumerationSafe = false
                        return@forEach
                    }
                    renew(claim)
                    val documentSetNames = documentSets.findNamesByCcPairId(requireNotNull(pair.id))
                    chunks.forEachIndexed { index, chunk ->
                        externalWrites.withPair(requireNotNull(pair.id)) {
                            renew(claim)
                            indexer.upsert(
                                runtime.index,
                                pairId = requireNotNull(pair.id),
                                sourceDocumentId = document.id,
                                chunkId = index,
                                title = document.title,
                                content = chunk.content,
                                link = document.link,
                                metadata = document.metadata,
                                embedding = chunk.embedding,
                                documentSets = documentSetNames,
                                updatedAt = document.updatedAt,
                                primaryOwners = document.primaryOwners,
                                secondaryOwners = document.secondaryOwners,
                                sourceType = connector.source,
                                indexedMetadata = indexedMetadata,
                            )
                        }
                        renew(claim)
                    }
                    renew(claim)
                    indexer.deleteStaleChunks(runtime.index, requireNotNull(pair.id), document.id, chunks.size)
                    renew(claim)
                    val existing = documents.findByCcPairIdAndSearchSettingsIdAndSourceDocumentId(
                        requireNotNull(pair.id), runtime.settingsId, document.id,
                    )
                    if (existing == null) newDocuments += 1
                    val indexedDocument = existing ?: IndexedDocumentEntity(
                        ccPairId = requireNotNull(pair.id),
                        searchSettingsId = runtime.settingsId,
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
                            .findUnresolvedByCcPairIdAndSourceDocumentId(
                                requireNotNull(pair.id), runtime.settingsId, document.id,
                            )
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
                            searchSettingsId = runtime.settingsId,
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
                runtime.index,
                requireNotNull(pair.id),
                runtime.settingsId,
                attemptId,
                attempt.fromBeginning || attempt.pruneOnly,
                completeEnumeration,
                beforeDelete = { renew(claim) },
            )
            renew(claim)
            if (attempt.fromBeginning && completeEnumeration) {
                val errorsToResolve = (
                    errors.findPriorUnresolvedByCcPairId(requireNotNull(pair.id), runtime.settingsId, attemptId) +
                        unresolvedAttemptErrorsAtStart
                )
                    .onEach { it.isResolved = true }
                errors.saveAll(errorsToResolve)
            } else if (!attempt.fromBeginning && !hasFailures) {
                val resolvedEntityErrors = errors.findUnresolvedEntityErrorsByCcPairId(
                    requireNotNull(pair.id), runtime.settingsId,
                )
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
        if (indexSettings.pending()?.let { it.id == claim.searchSettingsId && it.cancelRequestedAt != null } == true) {
            throw ConnectorPausedException()
        }
        if (
            claim.searchSettingsId == indexSettings.currentRuntime().settingsId &&
            pairs.findById(claim.pairId).orElse(null)?.status == PairStatus.PAUSED
        ) throw ConnectorPausedException()
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
        val priorAttempts = attempts.findAllByCcPairIdAndSearchSettingsIdOrderByIdDesc(
            attempt.ccPairId,
            attempt.searchSettingsId,
        )
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
        attempt.pollRangeEnd = attempt.pollRangeEnd ?: Instant.now()
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
