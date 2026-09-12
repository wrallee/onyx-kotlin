package com.onyx.foss.kotlin.ingestion

import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class AttemptStatus(@get:JsonValue val value: String) {
    NOT_STARTED("not_started"),
    IN_PROGRESS("in_progress"),
    SUCCESS("success"),
    FAILED("failed"),
    COMPLETED_WITH_ERRORS("completed_with_errors"),
    CANCELED("canceled"),
}
enum class JobState { QUEUED, RUNNING, SUCCEEDED, FAILED }
@Entity
@Table(name = "ingestion_attempts", indexes = [Index(name = "idx_attempt_pair", columnList = "cc_pair_id")])
class IngestionAttemptEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "cc_pair_id", nullable = false)
    var ccPairId: Long = 0,
    @Enumerated(EnumType.STRING)
    var status: AttemptStatus = AttemptStatus.NOT_STARTED,
    @Column(name = "from_beginning", nullable = false)
    var fromBeginning: Boolean = false,
    @Column(name = "prune_only", nullable = false)
    var pruneOnly: Boolean = false,
    @Column(name = "enumeration_complete", nullable = false)
    var enumerationComplete: Boolean = false,
    @Column(name = "new_docs_indexed", nullable = false)
    var newDocsIndexed: Int = 0,
    @Column(name = "total_docs_indexed", nullable = false)
    var totalDocsIndexed: Int = 0,
    @Column(name = "docs_removed_from_index", nullable = false)
    var docsRemovedFromIndex: Int = 0,
    @Column(name = "error_msg")
    var errorMessage: String? = null,
    @Column(name = "full_exception_trace")
    var fullExceptionTrace: String? = null,
    @Column(name = "time_started")
    var timeStarted: Instant? = null,
    @UpdateTimestamp @Column(name = "time_updated")
    var timeUpdated: Instant? = null,
    @Column(name = "poll_range_start")
    var pollRangeStart: Instant? = null,
    @Column(name = "poll_range_end")
    var pollRangeEnd: Instant? = null,
)

@Entity
@Table(name = "ingestion_checkpoints")
class IngestionCheckpointEntity(
    @Id @Column(name = "cc_pair_id")
    var ccPairId: Long = 0,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checkpoint_json", nullable = false, columnDefinition = "varchar")
    var checkpointJson: JsonNode? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(name = "ingestion_jobs", indexes = [Index(name = "idx_job_state", columnList = "state,run_after")])
class IngestionJobEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "attempt_id", nullable = false)
    var attemptId: Long = 0,
    @Column(name = "cc_pair_id", nullable = false)
    var ccPairId: Long = 0,
    @Enumerated(EnumType.STRING)
    var state: JobState = JobState.QUEUED,
    @Column(name = "active_marker")
    var activeMarker: Short? = if (state == JobState.QUEUED || state == JobState.RUNNING) 1 else null,
    @Column(name = "run_after", nullable = false)
    var runAfter: Instant = Instant.now(),
    @Column(name = "locked_at")
    var lockedAt: Instant? = null,
    @Column(name = "locked_by")
    var lockedBy: String? = null,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    var attempts: Int = 0,
    @Column(name = "last_error")
    var lastError: String? = null,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(name = "indexed_documents")
class IndexedDocumentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "cc_pair_id", nullable = false)
    var ccPairId: Long = 0,
    @Column(name = "source_document_id", nullable = false)
    var sourceDocumentId: String = "",
    var title: String = "",
    var link: String? = null,
    @Column(name = "content_hash", nullable = false)
    var contentHash: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "varchar", nullable = false)
    var metadata: JsonNode? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_access", columnDefinition = "varchar")
    var externalAccess: JsonNode? = null,
    @Column(name = "last_synced", nullable = false)
    var lastSynced: Instant = Instant.now(),
    @Column(name = "last_modified")
    var lastModified: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "primary_owners", nullable = false, columnDefinition = "varchar")
    var primaryOwners: List<String> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "secondary_owners", nullable = false, columnDefinition = "varchar")
    var secondaryOwners: List<String> = emptyList(),
)

@Entity
@Table(name = "ingestion_errors")
class IngestionErrorEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "attempt_id", nullable = false)
    var attemptId: Long = 0,
    @Column(name = "source_document_id")
    var sourceDocumentId: String? = null,
    @Column(name = "document_link")
    var documentLink: String? = null,
    @Column(name = "entity_id")
    var entityId: String? = null,
    @Column(name = "failed_time_range_start")
    var failedTimeRangeStart: Instant? = null,
    @Column(name = "failed_time_range_end")
    var failedTimeRangeEnd: Instant? = null,
    @Column(name = "failure_message", nullable = false)
    var failureMessage: String = "",
    @Column(name = "error_type")
    var errorType: String? = null,
    @Column(name = "is_resolved", nullable = false)
    var isResolved: Boolean = false,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
)

@Entity
@Table(name = "ingestion_enumerated_documents")
@IdClass(IngestionEnumeratedDocumentId::class)
class IngestionEnumeratedDocumentEntity(
    @Id
    @Column(name = "attempt_id")
    var attemptId: Long = 0,
    @Id
    @Column(name = "source_document_id")
    var sourceDocumentId: String = "",
    @Column(nullable = false)
    var processed: Boolean = false,
)

data class IngestionEnumeratedDocumentId(
    var attemptId: Long = 0,
    var sourceDocumentId: String = "",
) : Serializable
