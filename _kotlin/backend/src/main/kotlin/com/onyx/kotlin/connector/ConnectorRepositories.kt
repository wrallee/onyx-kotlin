package com.onyx.kotlin.connector

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

import com.onyx.kotlin.ingestion.IngestionJobEntity
import com.onyx.kotlin.ingestion.JobState

interface CredentialRepository : JpaRepository<CredentialEntity, Long> {
    fun findAllBySource(source: ConnectorSource): List<CredentialEntity>
}

interface ConnectorRepository : JpaRepository<ConnectorEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT connector FROM ConnectorEntity connector WHERE connector.id = :id")
    fun lockById(@Param("id") id: Long): ConnectorEntity?
}

interface ConnectorCredentialPairRepository : JpaRepository<ConnectorCredentialPairEntity, Long> {
    fun findAllByConnectorId(connectorId: Long): List<ConnectorCredentialPairEntity>
    fun findAllByCredentialId(credentialId: Long): List<ConnectorCredentialPairEntity>
    fun findByConnectorIdAndCredentialId(connectorId: Long, credentialId: Long): ConnectorCredentialPairEntity?

    @Query("SELECT pair.connectorId FROM ConnectorCredentialPairEntity pair WHERE pair.id = :id")
    fun findConnectorIdById(@Param("id") id: Long): Long?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pair FROM ConnectorCredentialPairEntity pair WHERE pair.id = :id")
    fun lockById(@Param("id") id: Long): ConnectorCredentialPairEntity?

    @Query(
        """
            SELECT pair FROM ConnectorCredentialPairEntity pair
            WHERE pair.status IN :statuses
              AND NOT EXISTS (
                  SELECT job.id FROM IngestionJobEntity job
                  WHERE job.ccPairId = pair.id AND job.state IN :activeStates
              )
        """,
    )
    fun findSchedulable(
        @Param("statuses") statuses: Collection<PairStatus>,
        @Param("activeStates") activeStates: Collection<JobState>,
    ): List<ConnectorCredentialPairEntity>
}

interface FileAssetRepository : JpaRepository<FileAssetEntity, String>
