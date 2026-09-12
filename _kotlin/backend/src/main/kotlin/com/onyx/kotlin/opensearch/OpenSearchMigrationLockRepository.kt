package com.onyx.kotlin.opensearch

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

interface OpenSearchIndexMigrationLockRepository : JpaRepository<OpenSearchIndexMigrationLockEntity, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT lock FROM OpenSearchIndexMigrationLockEntity lock WHERE lock.id = 1")
    fun lock(): OpenSearchIndexMigrationLockEntity
}
