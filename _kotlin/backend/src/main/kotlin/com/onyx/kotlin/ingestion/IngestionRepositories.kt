package com.onyx.kotlin.ingestion

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

import com.onyx.kotlin.connector.ConnectorCredentialPairEntity

interface IngestionAttemptRepository : JpaRepository<IngestionAttemptEntity, Long> {
    fun findAllByCcPairIdOrderByIdDesc(ccPairId: Long): List<IngestionAttemptEntity>
    fun findFirstByCcPairIdOrderByIdDesc(ccPairId: Long): IngestionAttemptEntity?
    fun findFirstByCcPairIdAndStatusInOrderByTimeStartedDescIdDesc(
        ccPairId: Long,
        statuses: Collection<AttemptStatus>,
    ): IngestionAttemptEntity?
    fun findFirstByCcPairIdAndPruneOnlyFalseOrderByTimeUpdatedDescIdDesc(ccPairId: Long): IngestionAttemptEntity?
}

interface IngestionCheckpointRepository : JpaRepository<IngestionCheckpointEntity, Long>

interface IngestionJobRepository : JpaRepository<IngestionJobEntity, Long> {
    fun findFirstByCcPairIdAndStateInOrderById(
        ccPairId: Long,
        states: Collection<JobState>,
    ): IngestionJobEntity?

    @Query(
        """
            SELECT job.id
            FROM IngestionJobEntity job, ConnectorCredentialPairEntity pair
            WHERE pair.id = job.ccPairId
              AND pair.status <> com.onyx.kotlin.connector.PairStatus.DELETING
              AND (pair.ingestionLeaseExpiresAt IS NULL OR pair.ingestionLeaseExpiresAt < :now)
              AND (
                  (job.state = com.onyx.kotlin.ingestion.JobState.QUEUED AND job.runAfter <= :now)
                  OR (job.state = com.onyx.kotlin.ingestion.JobState.RUNNING
                      AND (job.leaseExpiresAt IS NULL OR job.leaseExpiresAt < :now))
              )
            ORDER BY job.runAfter, job.id
        """,
    )
    fun findClaimableIds(@Param("now") now: Instant, pageable: Pageable): List<Long>

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            UPDATE IngestionJobEntity job
            SET job.state = com.onyx.kotlin.ingestion.JobState.RUNNING,
                job.lockedAt = :now,
                job.lockedBy = :worker,
                job.attempts = job.attempts + 1,
                job.claimToken = :token,
                job.leaseExpiresAt = :leaseExpiresAt
            WHERE job.id = :id
              AND (
                  (job.state = com.onyx.kotlin.ingestion.JobState.QUEUED AND job.runAfter <= :now)
                  OR (job.state = com.onyx.kotlin.ingestion.JobState.RUNNING
                      AND (job.leaseExpiresAt IS NULL OR job.leaseExpiresAt < :now))
              )
        """,
    )
    fun claim(
        @Param("id") id: Long,
        @Param("now") now: Instant,
        @Param("worker") worker: String,
        @Param("token") token: UUID,
        @Param("leaseExpiresAt") leaseExpiresAt: Instant,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM IngestionJobEntity job WHERE job.id = :id")
    fun lockById(@Param("id") id: Long): IngestionJobEntity?
}

interface IndexedDocumentRepository : JpaRepository<IndexedDocumentEntity, Long> {
    fun findByCcPairIdAndSourceDocumentId(ccPairId: Long, sourceDocumentId: String): IndexedDocumentEntity?
    fun findAllByCcPairId(ccPairId: Long): List<IndexedDocumentEntity>
    fun findAllByCcPairIdAndSourceDocumentIdIn(
        ccPairId: Long,
        sourceDocumentIds: Collection<String>,
    ): List<IndexedDocumentEntity>
    fun findAllByCcPairIdAndSourceDocumentIdGreaterThanOrderBySourceDocumentId(
        ccPairId: Long,
        afterSourceDocumentId: String,
        pageable: Pageable,
    ): List<IndexedDocumentEntity>
    fun countByCcPairId(ccPairId: Long): Long
    fun deleteAllByCcPairId(ccPairId: Long)
    @Transactional
    fun deleteByCcPairIdAndSourceDocumentIdIn(ccPairId: Long, sourceDocumentIds: Collection<String>): Long
}

interface IngestionErrorRepository : JpaRepository<IngestionErrorEntity, Long> {
    fun findAllByAttemptIdOrderByIdDesc(attemptId: Long): List<IngestionErrorEntity>
    fun findAllByAttemptIdInOrderByIdDesc(attemptIds: Collection<Long>): List<IngestionErrorEntity>
    @Query(
        """
            SELECT error FROM IngestionErrorEntity error, IngestionAttemptEntity attempt
            WHERE error.attemptId = attempt.id
              AND attempt.ccPairId = :ccPairId
              AND error.sourceDocumentId = :sourceDocumentId
              AND error.isResolved = false
            ORDER BY error.id DESC
        """,
    )
    fun findUnresolvedByCcPairIdAndSourceDocumentId(
        @Param("ccPairId") ccPairId: Long,
        @Param("sourceDocumentId") sourceDocumentId: String,
    ): List<IngestionErrorEntity>

    @Query(
        """
            SELECT error FROM IngestionErrorEntity error, IngestionAttemptEntity attempt
            WHERE error.attemptId = attempt.id
              AND attempt.ccPairId = :ccPairId
              AND error.entityId IS NOT NULL
              AND error.isResolved = false
            ORDER BY error.id DESC
        """,
    )
    fun findUnresolvedEntityErrorsByCcPairId(@Param("ccPairId") ccPairId: Long): List<IngestionErrorEntity>

    @Query(
        """
            SELECT error FROM IngestionErrorEntity error, IngestionAttemptEntity attempt
            WHERE error.attemptId = attempt.id
              AND attempt.ccPairId = :ccPairId
              AND error.attemptId <> :attemptId
              AND error.isResolved = false
            ORDER BY error.id DESC
        """,
    )
    fun findPriorUnresolvedByCcPairId(
        @Param("ccPairId") ccPairId: Long,
        @Param("attemptId") attemptId: Long,
    ): List<IngestionErrorEntity>
}


@Repository
class IngestionEnumerationRepository(
    private val rows: IngestionEnumerationJpaRepository,
) {
    @Transactional
    fun registerDocuments(attemptId: Long, sourceDocumentIds: Collection<String>): Set<String> {
        val ids = sourceDocumentIds.filter(String::isNotBlank).distinct()
        val existing = rows.findAllByAttemptIdAndSourceDocumentIdIn(attemptId, ids)
            .associateBy { it.sourceDocumentId }
        rows.saveAll(ids.filterNot(existing::containsKey).map { IngestionEnumeratedDocumentEntity(attemptId, it) })
        return ids.filterTo(mutableSetOf()) { existing[it]?.processed != true }
    }

    @Transactional
    fun markProcessed(attemptId: Long, sourceDocumentId: String) {
        val row = rows.findById(IngestionEnumeratedDocumentId(attemptId, sourceDocumentId)).orElseThrow {
            IllegalStateException("Enumerated document disappeared before processing completed")
        }
        row.processed = true
        rows.save(row)
    }

    @Transactional
    fun protectFailures(attemptId: Long, sourceDocumentIds: Collection<String>) {
        val ids = sourceDocumentIds.filter(String::isNotBlank).distinct()
        val existing = rows.findAllByAttemptIdAndSourceDocumentIdIn(attemptId, ids).mapTo(mutableSetOf()) {
            it.sourceDocumentId
        }
        rows.saveAll(ids.filterNot(existing::contains).map { IngestionEnumeratedDocumentEntity(attemptId, it) })
    }

    fun findMissingPage(pairId: Long, attemptId: Long, afterSourceDocumentId: String, limit: Int): List<String> =
        rows.findMissingSourceDocumentIds(
            pairId,
            attemptId,
            afterSourceDocumentId,
            org.springframework.data.domain.PageRequest.of(0, limit),
        )
}

interface IngestionEnumerationJpaRepository :
    JpaRepository<IngestionEnumeratedDocumentEntity, IngestionEnumeratedDocumentId> {
    fun findAllByAttemptIdAndSourceDocumentIdIn(
        attemptId: Long,
        sourceDocumentIds: Collection<String>,
    ): List<IngestionEnumeratedDocumentEntity>

    @Query(
        """
            SELECT document.sourceDocumentId
            FROM IndexedDocumentEntity document
            WHERE document.ccPairId = :pairId
              AND document.sourceDocumentId > :afterSourceDocumentId
              AND NOT EXISTS (
                  SELECT enumerated.sourceDocumentId FROM IngestionEnumeratedDocumentEntity enumerated
                  WHERE enumerated.attemptId = :attemptId
                    AND enumerated.sourceDocumentId = document.sourceDocumentId
              )
            ORDER BY document.sourceDocumentId
        """,
    )
    fun findMissingSourceDocumentIds(
        @Param("pairId") pairId: Long,
        @Param("attemptId") attemptId: Long,
        @Param("afterSourceDocumentId") afterSourceDocumentId: String,
        pageable: Pageable,
    ): List<String>
}
