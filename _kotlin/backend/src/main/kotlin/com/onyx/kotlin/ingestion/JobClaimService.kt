package com.onyx.kotlin.ingestion

import com.onyx.kotlin.config.OnyxProperties
import com.onyx.kotlin.connector.ConnectorCredentialPairEntity
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.PairStatus
import com.onyx.kotlin.indexing.IndexSettingsService
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
    private val indexSettings: IndexSettingsService,
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
        if (pair.status == PairStatus.DELETING) return null
        val token = UUID.randomUUID()
        val leaseExpiresAt = now.plus(INGESTION_LEASE)
        if (jobs.claim(jobId, now, "spring-worker", token, leaseExpiresAt) != 1) return null
        return IngestionClaim(jobId, job.ccPairId, job.searchSettingsId, job.attemptId, token)
    }

    @Transactional
    fun start(claim: IngestionClaim, now: Instant = Instant.now()): Boolean {
        val ownership = ownership(claim) ?: return false
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return false
        attempt.status = AttemptStatus.IN_PROGRESS
        attempt.timeStarted = attempt.timeStarted ?: now
        attempts.save(attempt)
        if (isCurrent(claim) && ownership.pair.status == PairStatus.SCHEDULED) {
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
        jobs.save(ownership.job)
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
        if (isCurrent(claim)) {
            ownership.pair.inRepeatedErrorState = false
            ownership.pair.status = PairStatus.ACTIVE
            if (updateLastPrunedAt) ownership.pair.lastPrunedAt = Instant.now()
            pairs.save(ownership.pair)
        }
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
        ownership.job.state = JobState.CANCELED
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
        if (isCurrent(claim)) {
            val repeated = isRepeatedError(
                refreshFreq,
                attempts.findAllByCcPairIdAndSearchSettingsIdOrderByIdDesc(claim.pairId, claim.searchSettingsId),
            )
            ownership.pair.inRepeatedErrorState = repeated
            if (repeated && properties.multiTenant) {
                ownership.pair.status = PairStatus.PAUSED
            }
            pairs.save(ownership.pair)
        }
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
        if (pair.status == PairStatus.DELETING) return null
        return IngestionOwnership(job, pair)
    }

    private fun releaseJob(job: IngestionJobEntity) {
        job.claimToken = null
        job.leaseExpiresAt = null
    }

    private fun isCurrent(claim: IngestionClaim): Boolean =
        claim.searchSettingsId == indexSettings.currentRuntime().settingsId

    private companion object {
        const val CLAIM_CANDIDATE_LIMIT = 10
    }
}

data class IngestionClaim(
    val jobId: Long,
    val pairId: Long,
    val searchSettingsId: Long,
    val attemptId: Long,
    val token: UUID,
)

private data class IngestionOwnership(
    val job: IngestionJobEntity,
    val pair: com.onyx.kotlin.connector.ConnectorCredentialPairEntity,
)

internal val INGESTION_LEASE: Duration = Duration.ofMinutes(1)
