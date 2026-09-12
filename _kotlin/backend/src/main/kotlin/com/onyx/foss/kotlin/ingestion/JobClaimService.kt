package com.onyx.foss.kotlin.ingestion

import com.onyx.foss.kotlin.config.OnyxProperties
import com.onyx.foss.kotlin.connector.ConnectorCredentialPairEntity
import com.onyx.foss.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.foss.kotlin.connector.PairStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class JobClaimService(
    private val jobs: IngestionJobRepository,
    private val pairs: ConnectorCredentialPairRepository,
    private val attempts: IngestionAttemptRepository,
    private val errors: IngestionErrorRepository,
    private val properties: OnyxProperties,
) {
    @Transactional
    fun claimNext(now: Instant = Instant.now()): IngestionClaim? = jobs
        .findClaimableIds(now, PageRequest.of(0, CLAIM_CANDIDATE_LIMIT))
        .firstNotNullOfOrNull { claim(it, now) }

    @Transactional
    fun claimJob(jobId: Long, now: Instant = Instant.now()): IngestionClaim? = claim(jobId, now)

    private fun claim(jobId: Long, now: Instant): IngestionClaim? {
        val job = jobs.findById(jobId).orElse(null) ?: return null
        val pair = pairs.lockById(job.ccPairId) ?: return null
        if (pair.status == PairStatus.DELETING || pair.ingestionLeaseExpiresAt?.isAfter(now) == true) return null
        val token = UUID.randomUUID()
        val leaseExpiresAt = now.plus(INGESTION_LEASE)
        if (jobs.claim(jobId, now, "spring-worker", token, leaseExpiresAt) != 1) return null
        pair.ingestionClaimToken = token
        pair.ingestionLeaseExpiresAt = leaseExpiresAt
        pairs.saveAndFlush(pair)
        return IngestionClaim(jobId, job.ccPairId, job.attemptId, token)
    }

    @Transactional
    fun start(claim: IngestionClaim, now: Instant = Instant.now()): Boolean {
        val ownership = ownership(claim) ?: return false
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return false
        attempt.status = AttemptStatus.IN_PROGRESS
        attempt.timeStarted = attempt.timeStarted ?: now
        attempts.save(attempt)
        if (ownership.pair.status == PairStatus.SCHEDULED) {
            ownership.pair.status = PairStatus.INITIAL_INDEXING
            pairs.save(ownership.pair)
        }
        return true
    }

    @Transactional
    fun renew(claim: IngestionClaim, now: Instant = Instant.now()): Boolean {
        val ownership = ownership(claim) ?: return false
        val leaseExpiresAt = now.plus(INGESTION_LEASE)
        ownership.job.leaseExpiresAt = leaseExpiresAt
        ownership.pair.ingestionLeaseExpiresAt = leaseExpiresAt
        jobs.save(ownership.job)
        pairs.save(ownership.pair)
        return true
    }

    @Transactional
    fun complete(
        claim: IngestionClaim,
        status: AttemptStatus,
        newDocuments: Int,
        totalDocuments: Int,
        removedDocuments: Int,
        updateLastPrunedAt: Boolean,
    ): Boolean {
        val ownership = ownership(claim) ?: return false
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return false
        attempt.status = status
        attempt.newDocsIndexed = newDocuments
        attempt.totalDocsIndexed = totalDocuments
        attempt.docsRemovedFromIndex = removedDocuments
        attempts.save(attempt)
        ownership.pair.inRepeatedErrorState = false
        ownership.pair.status = PairStatus.ACTIVE
        if (updateLastPrunedAt) ownership.pair.lastPrunedAt = Instant.now()
        releasePair(ownership.pair)
        pairs.save(ownership.pair)
        ownership.job.state = JobState.SUCCEEDED
        ownership.job.activeMarker = null
        releaseJob(ownership.job)
        jobs.save(ownership.job)
        return true
    }

    @Transactional
    fun cancel(claim: IngestionClaim): Boolean {
        val ownership = ownership(claim) ?: return false
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return false
        attempt.status = AttemptStatus.CANCELED
        attempts.save(attempt)
        releasePair(ownership.pair)
        pairs.save(ownership.pair)
        ownership.job.state = JobState.SUCCEEDED
        ownership.job.activeMarker = null
        releaseJob(ownership.job)
        jobs.save(ownership.job)
        return true
    }

    @Transactional
    fun fail(
        claim: IngestionClaim,
        error: Exception,
        refreshFreq: Long?,
        now: Instant = Instant.now(),
    ): Boolean {
        val ownership = ownership(claim) ?: return false
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return false
        attempt.status = AttemptStatus.FAILED
        attempt.errorMessage = error.message?.take(1000) ?: "Ingestion failed"
        attempt.fullExceptionTrace = error.stackTraceToString().take(16000)
        attempt.timeUpdated = now
        attempts.saveAndFlush(attempt)
        errors.save(
            IngestionErrorEntity(
                attemptId = claim.attemptId,
                failureMessage = attempt.errorMessage ?: "Ingestion failed",
                errorType = error::class.simpleName,
            ),
        )
        val repeated = isRepeatedError(
            refreshFreq,
            attempts.findAllByCcPairIdOrderByIdDesc(claim.pairId),
        )
        ownership.pair.inRepeatedErrorState = repeated
        if (repeated && properties.multiTenant) {
            ownership.pair.status = PairStatus.PAUSED
        }
        releasePair(ownership.pair)
        pairs.save(ownership.pair)
        ownership.job.state = JobState.FAILED
        ownership.job.activeMarker = null
        ownership.job.lastError = attempt.errorMessage
        releaseJob(ownership.job)
        jobs.save(ownership.job)
        return true
    }

    private fun ownership(claim: IngestionClaim): IngestionOwnership? {
        val job = jobs.lockById(claim.jobId) ?: return null
        if (job.state != JobState.RUNNING || job.claimToken != claim.token || job.ccPairId != claim.pairId) return null
        val pair = pairs.lockById(claim.pairId) ?: return null
        if (pair.status == PairStatus.DELETING || pair.ingestionClaimToken != claim.token) return null
        return IngestionOwnership(job, pair)
    }

    private fun releaseJob(job: IngestionJobEntity) {
        job.claimToken = null
        job.leaseExpiresAt = null
    }

    private fun releasePair(pair: com.onyx.foss.kotlin.connector.ConnectorCredentialPairEntity) {
        pair.ingestionClaimToken = null
        pair.ingestionLeaseExpiresAt = null
    }

    private companion object {
        const val CLAIM_CANDIDATE_LIMIT = 10
    }
}

data class IngestionClaim(
    val jobId: Long,
    val pairId: Long,
    val attemptId: Long,
    val token: UUID,
)

private data class IngestionOwnership(
    val job: IngestionJobEntity,
    val pair: com.onyx.foss.kotlin.connector.ConnectorCredentialPairEntity,
)

internal val INGESTION_LEASE: Duration = Duration.ofMinutes(1)
