package com.onyx.kotlin.ingestion

import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.ConnectorRepository
import com.onyx.kotlin.ingestion.IngestionAttemptRepository
import com.onyx.kotlin.ingestion.JobState
import com.onyx.kotlin.connector.PairStatus
import com.onyx.kotlin.indexing.IndexSettingsService
import com.onyx.kotlin.indexing.ReindexCoordinator
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class IngestionScheduler(
    private val properties: OnyxProperties,
    private val pairs: ConnectorCredentialPairRepository,
    private val connectors: ConnectorRepository,
    private val attempts: IngestionAttemptRepository,
    private val commands: IngestionCommandService,
    private val indexSettings: IndexSettingsService,
    private val reindex: ReindexCoordinator,
) {
    private val log = LoggerFactory.getLogger(IngestionScheduler::class.java)

    @Scheduled(fixedDelayString = "\${onyx.scheduler.poll-delay-ms:15000}")
    fun schedule() {
        if (!properties.worker.enabled) return
        try {
            scheduleDue(Instant.now())
        } catch (error: Exception) {
            log.error("Failed to run ingestion scheduler: {}", error.message, error)
        }
    }

    @Transactional
    fun scheduleDue(now: Instant) {
        val current = indexSettings.currentRuntime()
        if (reindex.currentSchedulingBlocked()) return
        pairs.findSchedulable(
            listOf(PairStatus.SCHEDULED, PairStatus.INITIAL_INDEXING, PairStatus.ACTIVE),
            current.settingsId,
            listOf(JobState.QUEUED, JobState.RUNNING),
        ).forEach { pair ->
            // ponytail: batch this projection if scheduler query volume becomes measurable.
            val pairId = requireNotNull(pair.id)
            val connector = connectors.findById(pair.connectorId).orElseThrow()
            val lastAttempt = attempts.findAllByCcPairIdAndSearchSettingsIdOrderByIdDesc(pairId, current.settingsId)
                .firstOrNull { !it.pruneOnly }
            val lastAttemptAt = lastAttempt?.timeUpdated ?: lastAttempt?.timeStarted ?: now
            val pruneDue = connector.pruneFreq?.let { frequency ->
                pair.lastPrunedAt?.plusSeconds(frequency)?.isAfter(now) != true
            } == true
            val refreshDue = when {
                lastAttempt == null -> true
                connector.refreshFreq == null -> false
                pair.status == PairStatus.INITIAL_INDEXING && !pair.inRepeatedErrorState -> true
                else -> !lastAttemptAt.plusSeconds(connector.refreshFreq!!).isAfter(now)
            }
            when {
                pruneDue -> {
                    log.info("Ingestion scheduler enqueuing pairId={} for prune", pairId)
                    commands.enqueuePair(pairId, fromBeginning = false, pruneOnly = true)
                }
                refreshDue -> {
                    log.info("Ingestion scheduler enqueuing pairId={} for refresh", pairId)
                    commands.enqueuePair(pairId, fromBeginning = false)
                }
            }
        }
    }
}
