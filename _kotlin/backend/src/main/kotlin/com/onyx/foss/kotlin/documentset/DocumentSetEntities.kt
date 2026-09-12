package com.onyx.foss.kotlin.documentset

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

enum class DocumentSetSyncStatus { PENDING, IN_PROGRESS, DONE }

@Entity
@Table(name = "document_sets")
class DocumentSetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var name: String = "",
    var description: String? = null,
    @Column(name = "is_public", nullable = false)
    var isPublic: Boolean = true,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(name = "document_set_cc_pairs")
@IdClass(DocumentSetPairId::class)
class DocumentSetPairEntity(
    @Id
    @Column(name = "document_set_id")
    var documentSetId: Long = 0,
    @Id
    @Column(name = "cc_pair_id")
    var ccPairId: Long = 0,
)

data class DocumentSetPairId(
    var documentSetId: Long = 0,
    var ccPairId: Long = 0,
) : Serializable

@Entity
@Table(name = "document_set_sync_claim_lock")
class DocumentSetSyncClaimLockEntity(
    @Id
    var id: Short = 1,
)

@Entity
@Table(name = "document_set_sync_outbox")
class DocumentSetSyncOutboxEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cc_pair_ids", nullable = false, columnDefinition = "varchar")
    var ccPairIds: JsonNode? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_set_ids", columnDefinition = "varchar")
    var documentSetIds: JsonNode? = null,
    @Enumerated(EnumType.STRING)
    var status: DocumentSetSyncStatus = DocumentSetSyncStatus.PENDING,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "last_error")
    var lastError: String? = null,
    @Column(name = "locked_at")
    var lockedAt: Instant? = null,
    @Column(name = "claim_token")
    var claimToken: UUID? = null,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
