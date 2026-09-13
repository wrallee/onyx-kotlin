package com.onyx.kotlin.documentset

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

interface DocumentSetRepository : JpaRepository<DocumentSetEntity, Long> {
    fun existsByName(name: String): Boolean
    fun existsByNameAndIdNot(name: String, id: Long): Boolean
    fun findAllByNameIn(names: Collection<String>): List<DocumentSetEntity>

    @Query(
        """
            SELECT documentSet.name
            FROM DocumentSetEntity documentSet, DocumentSetPairEntity membership
            WHERE membership.documentSetId = documentSet.id
              AND membership.ccPairId = :ccPairId
            ORDER BY documentSet.name
        """,
    )
    fun findNamesByCcPairId(@Param("ccPairId") ccPairId: Long): List<String>
}

interface DocumentSetPairRepository : JpaRepository<DocumentSetPairEntity, DocumentSetPairId> {
    fun findAllByDocumentSetIdOrderByCcPairId(documentSetId: Long): List<DocumentSetPairEntity>
    fun deleteAllByDocumentSetId(documentSetId: Long)
    fun deleteAllByCcPairId(ccPairId: Long)
}

interface DocumentSetSyncOutboxRepository : JpaRepository<DocumentSetSyncOutboxEntity, Long> {
    fun findAllByStatusIn(statuses: Collection<DocumentSetSyncStatus>): List<DocumentSetSyncOutboxEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findFirstByStatusOrderById(status: DocumentSetSyncStatus): DocumentSetSyncOutboxEntity?

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            UPDATE DocumentSetSyncOutboxEntity row
            SET row.lockedAt = :now
            WHERE row.id = :id
              AND row.claimToken = :token
              AND row.status = com.onyx.kotlin.documentset.DocumentSetSyncStatus.IN_PROGRESS
        """,
    )
    fun renewOwned(
        @Param("id") id: Long,
        @Param("token") token: UUID,
        @Param("now") now: Instant,
    ): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            UPDATE DocumentSetSyncOutboxEntity row
            SET row.status = com.onyx.kotlin.documentset.DocumentSetSyncStatus.DONE,
                row.claimToken = NULL,
                row.lockedAt = NULL,
                row.lastError = NULL
            WHERE row.id = :id
              AND row.claimToken = :token
              AND row.status = com.onyx.kotlin.documentset.DocumentSetSyncStatus.IN_PROGRESS
        """,
    )
    fun completeOwned(@Param("id") id: Long, @Param("token") token: UUID): Int

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
            UPDATE DocumentSetSyncOutboxEntity row
            SET row.status = com.onyx.kotlin.documentset.DocumentSetSyncStatus.PENDING,
                row.claimToken = NULL,
                row.lockedAt = NULL,
                row.lastError = :message
            WHERE row.id = :id
              AND row.claimToken = :token
              AND row.status = com.onyx.kotlin.documentset.DocumentSetSyncStatus.IN_PROGRESS
        """,
    )
    fun retryOwned(
        @Param("id") id: Long,
        @Param("token") token: UUID,
        @Param("message") message: String,
    ): Int
}

interface DocumentSetSyncClaimLockRepository : JpaRepository<DocumentSetSyncClaimLockEntity, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT lock FROM DocumentSetSyncClaimLockEntity lock WHERE lock.id = 1")
    fun lock(): DocumentSetSyncClaimLockEntity
}
