package com.onyx.kotlin.indexing

import com.onyx.kotlin.api.ApiException
import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.PairStatus
import com.onyx.kotlin.documentset.DocumentSetIndexSyncService
import com.onyx.kotlin.ingestion.AttemptStatus
import com.onyx.kotlin.ingestion.IndexedDocumentRepository
import com.onyx.kotlin.ingestion.IngestionAttemptEntity
import com.onyx.kotlin.ingestion.IngestionAttemptRepository
import com.onyx.kotlin.ingestion.IngestionCheckpointRepository
import com.onyx.kotlin.ingestion.IngestionCommandService
import com.onyx.kotlin.ingestion.IngestionJobRepository
import com.onyx.kotlin.ingestion.JobState
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

enum class ReindexMode { FULL, SYNC }

data class ReindexProgressResponse(
    val mode: ReindexMode,
    val total: Int,
    val waiting: Int,
    val inProgress: Int,
    val completed: Int,
    val failed: Int,
)

data class ReindexErrorResponse(
    val ccPairId: Long,
    val name: String,
    val status: AttemptStatus,
    val errorMessage: String?,
)

@Service
class ReindexCoordinator(
    private val properties: OnyxProperties,
    private val settings: IndexSettingsService,
    private val commands: IngestionCommandService,
    private val attempts: IngestionAttemptRepository,
    private val jobs: IngestionJobRepository,
    private val checkpoints: IngestionCheckpointRepository,
    private val documents: IndexedDocumentRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val indexer: OpenSearchIndexer,
    private val documentSetSync: DocumentSetIndexSyncService,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(ReindexCoordinator::class.java)

    @Transactional
    fun startFull(modelName: String): Long = start(modelName, ReindexMode.FULL)

    @Transactional
    fun startSync(modelName: String): Long = start(modelName, ReindexMode.SYNC)

    @Transactional
    fun cancel() {
        settings.requestCancel()
    }

    @Transactional
    fun retry(pairId: Long) {
        val future = activeFuture()
        val failed = latestAttempts(future.id).getValue(pairId)
        if (failed.status !in FAILED_STATUSES) {
            throw ApiException(HttpStatus.CONFLICT, "The connector has no failed reindex attempt")
        }
        log.info("Retrying reindex for pairId={}, searchSettingsId={}", pairId, future.id)
        commands.enqueuePair(
            pairId = pairId,
            searchSettingsId = future.id,
            fromBeginning = failed.fromBeginning,
            pruneOnly = failed.pruneOnly,
            pollRangeStart = failed.pollRangeStart,
            pollRangeEnd = failed.pollRangeEnd,
        )
    }

    @Transactional
    fun retryAll() {
        val future = activeFuture()
        latestAttempts(future.id).values.filter { it.status in FAILED_STATUSES }.forEach { retry(it.ccPairId) }
    }

    @Transactional(readOnly = true)
    fun progress(): ReindexProgressResponse? {
        val future = settings.pending()?.takeIf { it.reindexStartedAt != null } ?: return null
        val latest = latestAttempts(future.id).values
        return ReindexProgressResponse(
            mode = if (attempts.findAllBySearchSettingsIdOrderByIdAsc(future.id).firstOrNull()?.fromBeginning == true) {
                ReindexMode.FULL
            } else {
                ReindexMode.SYNC
            },
            total = latest.size,
            waiting = latest.count { it.status == AttemptStatus.NOT_STARTED },
            inProgress = latest.count { it.status == AttemptStatus.IN_PROGRESS },
            completed = latest.count { it.status == AttemptStatus.SUCCESS },
            failed = latest.count { it.status in FAILED_STATUSES },
        )
    }

    @Transactional(readOnly = true)
    fun errors(): List<ReindexErrorResponse> {
        val future = settings.pending()?.takeIf { it.reindexStartedAt != null } ?: return emptyList()
        return latestAttempts(future.id).values.filter { it.status in FAILED_STATUSES }.map {
            ReindexErrorResponse(
                it.ccPairId,
                pairs.findById(it.ccPairId).orElse(null)?.name ?: "Connector ${it.ccPairId}",
                it.status,
                it.errorMessage,
            )
        }
    }

    @Transactional(readOnly = true)
    fun currentSchedulingBlocked(): Boolean {
        val future = settings.pending()?.takeIf { it.reindexStartedAt != null } ?: return false
        if (future.cutoverAt != null) return true
        val pairIds = targetPairIds()
        val latest = latestAttempts(future.id)
        return pairIds.all { latest[it]?.status == AttemptStatus.SUCCESS }
    }

    @Scheduled(fixedDelayString = "\${onyx.scheduler.poll-delay-ms:15000}")
    fun advanceScheduled() {
        if (!properties.worker.enabled) return
        try {
            advance()
        } catch (error: Exception) {
            log.error("Unhandled error in ReindexCoordinator: {}", error.message, error)
        }
    }

    @Transactional
    fun advance() {
        val future = settings.pendingLocked()?.takeIf { it.reindexStartedAt != null } ?: return
        val activeFutureJobs = jobs.findAllBySearchSettingsIdAndStateIn(
            future.id, listOf(JobState.QUEUED, JobState.RUNNING),
        )
        if (future.cancelRequestedAt != null) {
            cancelQueued(activeFutureJobs)
            if (activeFutureJobs.any { it.state == JobState.RUNNING }) return
            val target = settings.runtime(future.id).index
            indexer.deleteIndex(target)
            settings.deleteFuture(future.id)
            log.info("Reindex searchSettingsId={} cancelled and cleaned up", future.id)
            return
        }

        val pairIds = targetPairIds()
        val latest = latestAttempts(future.id)
        if (pairIds.any { it !in latest }) return
        val selected = pairIds.map(latest::getValue)
        if (selected.any { it.status in FAILED_STATUSES }) {
            val failedPairs = selected.filter { it.status in FAILED_STATUSES }.map { "${it.ccPairId}: ${it.errorMessage}" }
            log.warn("Reindex searchSettingsId={} has failed attempts: {}", future.id, failedPairs)
            if (future.cutoverAt != null) settings.setCutover(future.id, null)
            return
        }
        if (selected.any { it.status != AttemptStatus.SUCCESS }) return

        if (future.cutoverAt == null) {
            val currentId = settings.current().id
            val currentJobs = jobs.findAllBySearchSettingsIdAndStateIn(
                currentId, listOf(JobState.QUEUED, JobState.RUNNING),
            )
            cancelQueued(currentJobs)
            if (currentJobs.any { it.state == JobState.RUNNING }) return
            val cutover = databaseNow()
            log.info("Reindex searchSettingsId={} setting cutover to {}", future.id, cutover)
            settings.setCutover(future.id, cutover)
            pairIds.forEach { commands.enqueuePair(it, future.id, false, pollRangeEnd = cutover) }
            return
        }

        if (selected.all { it.pruneOnly }) {
            val runtime = settings.runtime(future.id)
            if (pairIds.any { !documentSetSync.syncPair(it, runtime) }) return
            log.info("Reindex searchSettingsId={} activating new index", future.id)
            settings.activateFuture(future.id)
            pairIds.filterNot { pairs.findById(it).orElseThrow().status == PairStatus.PAUSED }
                .forEach { commands.enqueuePair(it, future.id, false) }
        } else {
            log.info("Reindex searchSettingsId={} enqueuing prune-only pass", future.id)
            pairIds.forEach { commands.enqueuePair(it, future.id, false, pruneOnly = true) }
        }
    }

    private fun start(modelName: String, mode: ReindexMode): Long {
        val startedAt = databaseNow()
        val future = settings.beginFuture(modelName, mode == ReindexMode.SYNC, startedAt)
        log.info("Starting reindex: mode={}, model={}, settingsId={}", mode, modelName, future.settingsId)
        if (mode == ReindexMode.FULL) {
            clearTargetState(future.settingsId)
            indexer.resetIndex(future.index)
        }
        targetPairIds().forEach {
            commands.enqueuePair(it, future.settingsId, mode == ReindexMode.FULL, pollRangeEnd = startedAt)
        }
        return future.settingsId
    }

    private fun clearTargetState(settingsId: Long) {
        jobs.deleteAllBySearchSettingsId(settingsId)
        attempts.deleteAllBySearchSettingsId(settingsId)
        checkpoints.deleteAllBySearchSettingsId(settingsId)
        documents.deleteAllBySearchSettingsId(settingsId)
    }

    private fun cancelQueued(targetJobs: List<com.onyx.kotlin.ingestion.IngestionJobEntity>) {
        targetJobs.filter { it.state == JobState.QUEUED }.forEach { job ->
            job.state = JobState.CANCELED
            job.activeMarker = null
            jobs.save(job)
            attempts.findById(job.attemptId).ifPresent { attempt ->
                attempt.status = AttemptStatus.CANCELED
                attempts.save(attempt)
            }
        }
    }

    private fun latestAttempts(settingsId: Long): Map<Long, IngestionAttemptEntity> =
        attempts.findAllBySearchSettingsIdOrderByIdAsc(settingsId).associateBy { it.ccPairId }

    private fun targetPairIds(): List<Long> {
        val currentId = settings.current().id
        return pairs.findAll().filter { pair ->
            pair.status != PairStatus.DELETING && (
                pair.status != PairStatus.PAUSED ||
                    documents.countByCcPairIdAndSearchSettingsId(requireNotNull(pair.id), currentId) > 0
            )
        }.map { requireNotNull(it.id) }
    }

    private fun activeFuture(): SearchSettingsResponse = settings.pendingLocked()?.takeIf { it.reindexStartedAt != null }
        ?: throw ApiException(HttpStatus.CONFLICT, "No embedding reindex is in progress")

    private fun databaseNow() = requireNotNull(
        jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime::class.java),
    ).toInstant()

    private companion object {
        val FAILED_STATUSES = setOf(
            AttemptStatus.FAILED,
            AttemptStatus.COMPLETED_WITH_ERRORS,
            AttemptStatus.CANCELED,
        )
    }
}
